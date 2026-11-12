#!/usr/bin/env python3
"""Visible local CAF qualification driver. Synthetic loopback fixture only.

Start receiver Vite separately, then open /qualification. No physical receiver,
production credentials, browser automation or production deployment is used.
"""
import argparse
import http.cookiejar
import http.server
import json
import threading
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from streaming import Fixture

HTML = '''<!doctype html><meta charset="utf-8"><title>Fictional adaptive CAF qualification</title>
<h1>Fictional CAF qualification</h1>
<p>Development receiver only. Samples report the actual CAF track and playback clock.</p>
<label>Start at seconds <input id="start" type="number" value="0" min="0"></label>
<button data-load="file">Play fictional file</button><button data-load="original">Play Original recovery file</button><button data-load="live">Play fictional Live TV</button>
<button id="seek">Seek to 120 seconds</button><button id="stop">Stop</button>
<button data-rate="40000000">40 Mbps</button><button data-rate="4000000">4 Mbps</button><button data-rate="2000000">2 Mbps</button>
<button id="corrupt">Corrupt this VOD family</button><button id="revoke">Revoke this sender</button>
<pre id="status">Ready</pre><pre id="samples"></pre>
<iframe src="/" allow="autoplay" style="width:100%;height:400px"></iframe>
<script>
const frame=document.querySelector('iframe'),status=document.querySelector('#status'),samples=document.querySelector('#samples');
let history=[];
const pm=()=>frame.contentWindow.cast.framework.CastReceiverContext.getInstance().getPlayerManager();
async function call(path,body={}){const r=await fetch('/qualification/'+path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});if(!r.ok)throw Error('Fixture control failed: '+r.status);return r.json()}
for(const b of document.querySelectorAll('[data-load]'))b.onclick=async()=>{try{
 const data=await call('load',{kind:b.dataset.load,startMs:Number(document.querySelector('#start').value)*1000});const w=frame.contentWindow;
 const r=new w.cast.framework.messages.LoadRequestData();r.media=new w.cast.framework.messages.MediaInformation();
 r.autoplay=true;r.media.contentId='fictional-a2';r.media.contentType='video/mp4';r.media.customData=data;
 history=[];await pm().load(r);status.textContent='Load accepted';
}catch(e){status.textContent=String(e)}};
document.querySelector('#seek').onclick=()=>pm().seek(120);
document.querySelector('#stop').onclick=()=>pm().stop();
for(const b of document.querySelectorAll('[data-rate]'))b.onclick=async()=>{await call('rate',{bps:Number(b.dataset.rate)});status.textContent=b.textContent};
for(const kind of ['corrupt','revoke'])document.querySelector('#'+kind).onclick=async()=>{status.textContent=JSON.stringify(await call(kind))};
setInterval(async()=>{try{const p=pm(),s=p.getStats();const sample={at:Date.now(),state:p.getPlayerState(),time:p.getCurrentTimeSec(),height:s.height,bufferingSeconds:s.bufferingTime};history.push(sample);samples.textContent=JSON.stringify(history.slice(-5),null,2);const evidence=await call('sample',sample);status.textContent=JSON.stringify(evidence,null,2)}catch{}},2000);
</script>'''


def loopback_url(value):
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or not parsed.port or parsed.path not in ('', '/') or parsed.query or parsed.fragment or parsed.username:
        raise argparse.ArgumentTypeError('Use an explicit http://127.0.0.1:PORT loopback origin')
    return value.rstrip('/')


def corrupt_fragment(data):
    """Keep fMP4 timing/box structure, replace encoded samples with invalid bytes.

    Arbitrary text is not a decoder test: Shaka can discard unknown boxes and
    drain its buffer without raising a fatal media error.
    """
    offset = 0
    while offset + 8 <= len(data):
        size = int.from_bytes(data[offset:offset + 4], 'big')
        if size < 8 or offset + size > len(data):
            raise ValueError('Incomplete fixture fMP4 box')
        if data[offset + 4:offset + 8] == b'mdat':
            return data[:offset + 8] + b'\xff' * (size - 8) + data[offset + size:]
        offset += size
    raise ValueError('Fixture segment lacks encoded samples')


class Driver:
    def __init__(self, fixture, receiver, origin, viewer, output):
        self.fixture, self.receiver, self.origin = fixture, receiver, origin
        self.viewer, self.output = viewer, output
        self.client = None
        self.lock = threading.Lock()
        self.reset()

    def reset(self):
        self.report = {'loads': [], 'samples': [], 'rates': [], 'renewals': 0, 'corruptedResponses': 0, 'codecs': []}
        self.corrupt = set()

    def control(self, path, body):
        if path == 'load':
            self.client = self.fixture.viewer(self.viewer)
            token = self.client.call('POST', '/api/auth/cast-token')
            self.reset()
            # Exercise the real renewal timer after its minimum 60 seconds;
            # this does not alter the server's signed token or its real TTL.
            data = {'v': 1, 'server': {'id': self.fixture.manifest['serverId'], 'urls': [self.origin]},
                    'token': token['token'], 'tokenExpiresAt': '2000-01-01T00:00:00Z', 'quality': 'auto', 'startMs': max(0, int(body.get('startMs', 0)))}
            if body['kind'] in ('file', 'original'):
                files = {f['kind']: f['id'] for f in self.fixture.manifest['files']}
                # -streaming-original-hd's title can reach a family and return.
                data['fileId'] = files['adaptive'] if body['kind'] == 'file' else files.get('original-hd', files['baseline'])
            else:
                channel = self.client.call('GET', '/api/live-tv/channels')['items'][0]['id']
                data['live'] = {'channelId': channel, 'quality': 'auto'}
            return data
        if path == 'rate':
            rate = int(body['bps'])
            if rate not in (2_000_000, 4_000_000, 40_000_000):
                raise ValueError('Unsupported qualification rate')
            self.fixture.profile(aggregate=rate)
            self.report['rates'].append(rate)
            return {'bps': rate}
        if path == 'corrupt':
            if not self.report['loads'] or not self.report['loads'][-1]['adaptive']:
                raise ValueError('No adaptive VOD family is active')
            self.corrupt.update(self.report['loads'][-1]['sessions'])
            return {'corruptionArmed': True}
        if path == 'revoke':
            if not self.client:
                raise ValueError('No fictional sender is signed in')
            self.client.call('POST', '/api/auth/logout')
            return {'senderRevoked': True}
        if path == 'sample':
            safe = {k: body[k] for k in ('at', 'state', 'time', 'height', 'bufferingSeconds') if k in body}
            self.report['samples'].append(safe)
            self.report['samples'] = self.report['samples'][-1800:]
            self.output.write_text(json.dumps(self.report, indent=2) + '\n')
            return {k: v for k, v in self.report.items() if k != 'samples'}
        raise ValueError('Unknown fixture action')

    def observe(self, method, path, status, data):
        if status != 200:
            return
        if method == 'POST' and path.startswith('/api/files/') and path.endswith('/playback'):
            d = json.loads(data)
            self.report['loads'].append({'mode': d.get('mode'), 'protocol': d.get('protocol'),
                'playId': d.get('playId'), 'video': d.get('video'), 'audio': d.get('audio'), 'startMs': d.get('startMs'), 'quality': (d.get('auto') or {}).get('step'),
                'adaptive': bool(d.get('adaptive')), 'sessions': [r.get('sessionId') for r in (d.get('adaptive') or {}).get('renditions', [])]})
        if method == 'POST' and path == '/api/live-tv/sessions':
            d = json.loads(data)
            self.report['loads'].append({'live': True, 'id': d.get('id'), 'adaptive': bool(d.get('adaptive')),
                'renditions': [r.get('height') for r in (d.get('adaptive') or {}).get('renditions', [])]})
        if method == 'PUT' and path.startswith('/api/live-tv/sessions/'):
            # What the server accepted as the rendition on screen.
            self.report['renditionHeight'] = json.loads(data).get('renditionHeight')
        if method == 'GET' and path.endswith('.m3u8'):
            import re
            for codec in re.findall(r'CODECS="([^"]+)"', data.decode()):
                if codec not in self.report['codecs']:
                    self.report['codecs'].append(codec)
        if method == 'POST' and path == '/api/auth/cast-token':
            self.report['renewals'] += 1


def serve(driver, port):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass  # Request URLs can contain bearer tokens.

        def do_GET(self):
            self.handle_request()

        def do_POST(self):
            self.handle_request()

        def do_PUT(self):
            self.handle_request()

        def do_DELETE(self):
            self.handle_request()

        def reply(self, status, data, content_type):
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def handle_request(self):
            try:
                path = urllib.parse.urlsplit(self.path).path
                length = int(self.headers.get('Content-Length', '0'))
                if length > 16384:
                    self.send_error(413)
                    return
                body = self.rfile.read(length) if length else None
                if self.command == 'GET' and path == '/qualification':
                    self.reply(200, HTML.encode(), 'text/html; charset=utf-8')
                    return
                if path.startswith('/qualification/'):
                    # Only our visible page can drive this fixture. Never expose
                    # controls through permissive CORS or bind on the LAN.
                    if self.command != 'POST' or self.headers.get('Origin') != driver.origin:
                        self.send_error(403)
                        return
                    with driver.lock:
                        answer = driver.control(path.removeprefix('/qualification/'), json.loads(body or '{}'))
                    self.reply(200, json.dumps(answer).encode(), 'application/json')
                    return
                api = path.startswith('/api/')
                target = (driver.fixture.base if api else driver.receiver) + self.path
                headers = {k: v for k, v in self.headers.items() if k.lower() in ('authorization', 'content-type', 'origin', 'range')}
                request = urllib.request.Request(target, data=body, headers=headers, method=self.command)
                try:
                    response = urllib.request.urlopen(request, timeout=90)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    with driver.lock:
                        corrupt = api and path.endswith('.m4s') and any('/'+s+'/' in path for s in driver.corrupt if s)
                    if api and self.command == 'GET' and not corrupt and not path.endswith('.m3u8'):
                        self.send_response(response.status)
                        for key in ('Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges'):
                            if response.headers.get(key):
                                self.send_header(key, response.headers[key])
                        self.end_headers()
                        while chunk := response.read1(16384):
                            self.wfile.write(chunk)
                            self.wfile.flush()
                        return
                    data = response.read()
                    with driver.lock:
                        driver.observe(self.command, path, response.status, data)
                        if corrupt:
                            data = corrupt_fragment(data)
                            driver.report['corruptedResponses'] += 1
                    self.reply(response.status, data, response.headers.get('Content-Type', 'application/octet-stream'))
            except (ValueError, KeyError, OSError, urllib.error.URLError):
                self.send_error(502, 'Fixture request failed')
    http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler).serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=loopback_url, required=True)
    parser.add_argument('--receiver', type=loopback_url, required=True)
    parser.add_argument('--port', type=int, default=58432)
    parser.add_argument('--viewer', type=int, default=2)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    fixture = Fixture(args.fixture)  # Verifies fixture identity before any login.
    origin = f'http://127.0.0.1:{args.port}'
    driver = Driver(fixture, args.receiver, origin, args.viewer, args.output)
    print(origin + '/qualification', flush=True)
    try:
        serve(driver, args.port)
    finally:
        fixture.profile()


if __name__ == '__main__':
    main()
