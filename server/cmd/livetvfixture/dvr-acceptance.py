# Runs only against the identified disposable DVR fixture, never a real server.
import datetime as dt
import http.cookiejar
import json
import time
import sys
import urllib.error
import urllib.parse
import urllib.request

base = (sys.argv[1] if len(sys.argv) > 1 else 'http://127.0.0.1:28298').rstrip('/')
location = urllib.parse.urlparse(base)
if location.scheme != 'http' or location.hostname not in ('127.0.0.1', '::1') or location.path or location.username:
    raise SystemExit('Use a loopback DVR fixture URL; the fixture marker is checked before signing in.')
http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
def call(method, path, body=None):
    req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None, method=method, headers={'Content-Type': 'application/json'})
    try:
        with http.open(req, timeout=30) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'{method} {path}: {error.code} {error.read().decode()}') from error

def until(fn, description, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = fn()
        if result:
            return result
        time.sleep(.25)
    raise AssertionError(description)

assert call('GET', '/_fixture/status')['fixture'] == 'dvr-fixture-v1'
call('POST', '/api/auth/login', {'name': 'Fixture Admin', 'password': 'fixture-password'})
channel = call('GET', '/api/live-tv/channels')['items'][0]
now = dt.datetime.now(dt.timezone.utc)
body = {'channelId': channel['id'], 'name': 'Orchard recording acceptance', 'start': (now + dt.timedelta(seconds=1)).isoformat(), 'end': (now + dt.timedelta(seconds=34)).isoformat(), 'repeat': 'once', 'zone': 'America/New_York'}
recording = call('POST', '/api/live-tv/recordings/manual', body)
recording_id = recording['id']
assert call('POST', '/api/live-tv/recordings/manual', body)['id'] == recording_id
print('Manual request replay returned the same recording', flush=True)
def get():
    return call('GET', '/api/live-tv/recordings/' + recording_id)
until(lambda: get()['state'] == 'recording' and get()['bytes'] > 0, 'Recording did not write bytes')
profile = {'videoCodecs': ['h264'], 'audioCodecs': ['aac'], 'containers': ['mp4'], 'maxHeight': 480, 'maxWidth': 854, 'maxAudioChannels': 2}
decision = call('POST', f'/api/live-tv/recordings/{recording_id}/playback', {'profile': profile, 'playlist': 'vod'})
assert decision['playlist'] == 'event' and decision.get('growing') and decision['mode'] == 'transcode'
with http.open(base + decision['url']) as response:
    assert b'#EXTM3U' in response.read()
def report_five_seconds():
    # A bounded event run can make the first playlist before five seconds
    # have been captured. The server correctly clamps progress to what exists.
    progress = call('PUT', f'/api/live-tv/recordings/{recording_id}/progress', {
        'positionMs': 5000, 'durationMs': max(5000, decision['durationMs']),
        'playId': decision['playId'], 'state': 'playing'})
    return progress if progress['positionMs'] == 5000 else None
until(report_five_seconds, 'Growing recording did not reach five seconds')
seek = call('POST', f'/api/live-tv/recordings/{recording_id}/playback', {'profile': profile, 'startMs': 999999999, 'playId': decision['playId']})
assert 0 <= seek['startMs'] < seek['durationMs']
print('Growing event HLS, progress and bounded Go to now passed', flush=True)
assert len(call('GET', '/api/live-tv/activity')['items']) == 0
assert len(call('GET', '/api/live-tv/activity')['recordings']) == 1
until(lambda: get()['state'] == 'done' and get().get('mediaFileId'), 'Natural finish and scan/link did not complete')
finished = get()
assert finished['bytes'] > 0 and finished['canDelete']
local = call('GET', '/api/playback/local')
assert any(session['id'] == seek['sessionId'] for session in local['sessions'])
print('Natural completion, library link and local keep-awake session passed', flush=True)
call('DELETE', '/api/playback/' + seek['sessionId'])
ordinary = call('POST', f'/api/files/{finished["mediaFileId"]}/playback', {'profile': profile})
assert not ordinary.get('growing')
if ordinary.get('sessionId'):
    call('DELETE', '/api/playback/' + ordinary['sessionId'])
call('DELETE', f'/api/live-tv/recordings/{recording_id}/file?recordAgain=false')
assert call('GET', f'/api/live-tv/recordings/{recording_id}')['state'] == 'deleted'
assert all(row['id'] != recording_id for row in call('GET', '/api/live-tv/recordings?section=recorded')['items'])
print('Ordinary file playback and owned deletion/list removal passed', flush=True)
