#!/usr/bin/env python3
"""Run streaming characterization only against the identified fictional fixture.

The HTTP player models a finite buffer; it does not decode frames. Its timings
are deliberately named model timings. Real player/device acceptance is separate.
Uses only the Python standard library.
"""
import argparse
import base64
import contextlib
import concurrent.futures
import copy
import datetime as dt
import http.cookiejar
import json
import os
from pathlib import Path
import queue
import re
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
MARKER = 'streaming-fixture-v1'
PASSWORD = 'fixture-password'
PROGRESSIVE_SAMPLE_BYTES = 2 << 20


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class HTTPFailure(RuntimeError):
    def __init__(self, status, reason=None):
        super().__init__(f'Fixture HTTP response {status}' + (f' ({reason})' if reason else ''))
        self.status, self.reason = status, reason


def loopback_url(value):
    p = urllib.parse.urlsplit(value)
    if (p.scheme != 'http' or p.hostname not in ('127.0.0.1', '::1') or
            not p.port or p.username or p.password or p.path not in ('', '/') or
            p.query or p.fragment):
        raise ValueError('Use a literal loopback fixture URL without credentials or a path')
    return value.rstrip('/')


class Client:
    def __init__(self, base, viewer=None, control=None):
        self.base = loopback_url(base)
        self.headers = {}
        if viewer:
            self.headers['X-WMM-Fixture-Viewer'] = viewer
        if control:
            self.headers['X-WMM-Fixture-Key'] = control
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect(),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def url(self, path):
        target = urllib.parse.urljoin(self.base + '/', path)
        p, origin = urllib.parse.urlsplit(target), urllib.parse.urlsplit(self.base)
        if (p.scheme, p.netloc) != (origin.scheme, origin.netloc) or p.username or p.fragment:
            raise ValueError('Fixture media escaped its verified origin')
        if not p.path.startswith(('/api/', '/_fixture/')):
            raise ValueError('Unexpected fixture route')
        return target

    def open(self, method, path, body=None, headers=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.url(path), data=data, method=method,
                                     headers={**self.headers, **(headers or {}),
                                              'Content-Type': 'application/json'})
        try:
            return self.opener.open(req, timeout=35)
        except urllib.error.HTTPError as error:
            reason = None
            try:
                detail = json.loads(error.read(32768)).get('detail', '')
                if detail in ('tuners_busy', 'server_busy', 'storage_busy', 'ladder_not_ready'):
                    reason = detail
                elif isinstance(detail, str) and detail.startswith('could not start playback: too many playback sessions'):
                    reason = 'playback_busy'
                elif detail == 'could not start playback: temporary storage unavailable':
                    reason = 'storage_busy'
            except (ValueError, OSError):
                pass
            finally:
                error.close()
            raise HTTPFailure(error.code, reason) from None

    def call(self, method, path, body=None):
        with self.open(method, path, body) as response:
            data = response.read(2 << 20)
            return json.loads(data) if data else None


class Fixture:
    def __init__(self, base):
        self.base = loopback_url(base)
        # No login, mutation or media request is allowed before this check.
        manifest = Client(base).call('GET', '/_fixture/status')
        if (not isinstance(manifest, dict) or manifest.get('fixture') != MARKER or manifest.get('baseUrl') != self.base or
                len(manifest.get('controlKey', '')) != 48 or
                not manifest.get('serverId') or not manifest.get('files') or
                len(manifest.get('accounts', [])) < 9):
            raise ValueError('Not the disposable streaming fixture; refusing credentials or mutations')
        self.manifest = manifest
        self.control = Client(base, control=manifest['controlKey'])

    def profile(self, aggregate=0, viewers=None):
        return self.control.call('POST', '/_fixture/streaming/profile', {
            'aggregate': {'bps': aggregate}, 'viewers': viewers or {}})

    def metrics(self):
        return self.control.call('GET', '/_fixture/streaming/metrics')

    def viewer(self, index, account_index=None):
        client = Client(self.base, viewer=f'viewer-{index}')
        client.call('POST', '/api/auth/login', {
            'name': self.manifest['accounts'][(account_index or index) - 1], 'password': PASSWORD})
        return client


def profile_for(mode, file):
    p = {'containers': ['mp4'], 'videoCodecs': ['h264'],
         'audioCodecs': ['aac'], 'maxAudioChannels': 2}
    if mode == 'direct' and file['kind'] == 'eac3':
        p['containers'] = ['mkv']
    if file['audio'] == 'eac3' and mode != 'audio':
        p['audioCodecs'].append('eac3')
    if mode == 'transcode':
        p.update(maxHeight=90, maxWidth=160, maxBitrate=1_000_000)
    return p


def media_playlist(text, source):
    """One supported muxed rendition, with all URLs checked by Client.open."""
    init, pending, segments, variant = None, None, [], False
    for line in text.splitlines():
        line = line.strip()
        if line.startswith('#EXT-X-STREAM-INF:'):
            variant = True
        elif line.startswith('#EXT-X-MAP:'):
            for part in line.split(':', 1)[1].split(','):
                if part.startswith('URI='):
                    init = urllib.parse.urljoin(source, part[4:].strip('"'))
        elif line.startswith('#EXTINF:'):
            pending = float(line.split(':', 1)[1].split(',')[0])
        elif line and not line.startswith('#'):
            path = urllib.parse.urljoin(source, line)
            if variant:
                return path, None, [], False
            if pending is not None:
                segments.append((path, pending))
                pending = None
    return None, init, segments, '#EXT-X-ENDLIST' in text


def next_segment(segments, seen, start, seek):
    """VOD lists the full timeline; a resumed model must not fetch from zero."""
    at = start
    for path, length in segments:
        end = at + length
        at = end
        if end <= seek:
            continue
        if path not in seen:
            return path, min(length, end - seek)
    return None


def abort_response(response):
    # Closing only HTTPResponse can wait behind a blocking read. Shutdown this
    # response's owned socket first so a shaped/outage transfer is interruptible.
    try:
        response.fp.raw._sock.shutdown(socket.SHUT_RDWR)
    except (AttributeError, OSError):
        pass


class Player:
    def __init__(self, fixture, index, file, mode='direct', auto=False, playlist='vod', same_account=False, recovery=False):
        self.fixture, self.index, self.file = fixture, index, file
        self.mode, self.auto, self.playlist = mode, auto, playlist
        self.recovery = recovery
        self.client = fixture.viewer(index, 1 if same_account else None)
        self.lock, self.stop_event = threading.Lock(), threading.Event()
        self.decision, self.generation, self.switching = None, 0, False
        self.buffer, self.position, self.throughput = 0.0, 0.0, 0
        self.throughput_at, self.pending_ack = 0.0, None
        self.started, self.waiting, self.last_tick = False, False, 0.0
        self.began, self.stall_ms, self.stalls = 0.0, 0.0, 0
        self.response, self.thread = None, None
        self.samples = {}
        self.result = {'viewer': f'viewer-{index}', 'fileKind': file['kind'],
                       'requestedMode': mode, 'automatic': auto, 'negotiatedRecovery': recovery, 'accepted': False,
                       'bytes': 0, 'modelStartupMs': None, 'firstMediaResponseMs': None,
                       'clientDecodedFrames': None, 'decodedSampleFrames': None,
                       'modes': [], 'switches': [], 'measurements': [], 'errors': [],
                       'modelPositionMs': 0, 'modelStalls': 0, 'modelStallMs': 0}

    def request_decision(self, switch_generation=None):
        with self.lock:
            old = self.decision
            position = self.position
            self.switching = True
        body = {'profile': profile_for(self.mode, self.file), 'quality': 'auto' if self.auto else 'original',
                'playlist': self.playlist, 'startMs': int(position * 1000),
                'client': {'deviceId': f'streaming-model-{self.index}',
                           'product': 'Fictional HTTP buffer model', 'platform': 'fixture'}}
        if self.recovery and self.auto:
            body['autoRecovery'] = True
            if switch_generation:
                body['switchGeneration'] = switch_generation
        if old:
            body['playId'] = old['playId']
        decision = self.client.call('POST', f'/api/files/{self.file["id"]}/playback', body)
        with self.lock:
            if not self.auto and decision['mode'] != self.mode:
                self.result['errors'].append(f'expected {self.mode}, got {decision["mode"]}')
            if old and decision['playId'] != old['playId']:
                self.result['errors'].append('logical play changed during quality reload')
            self.decision, self.generation, self.switching = decision, self.generation + 1, False
            self.pending_ack = switch_generation
            self.buffer, self.stalls, self.stall_ms = 0.0, 0, 0.0
            self.waiting, self.started = False, False
            self.result['accepted'] = True
            step = decision.get('auto', {}).get('step', 'original')
            self.result['modes'].append({'atMs': self.elapsed(), 'generation': self.generation, 'mode': decision['mode'],
                                         'step': step, 'playlist': decision.get('playlist'),
                                         'bitrate': decision.get('bitrate', 0)})
        return decision

    def elapsed(self):
        return int((time.monotonic() - self.began) * 1000)

    def launch(self, barrier):
        self.thread = threading.Thread(target=self.run, args=(barrier,), daemon=True)
        self.thread.start()

    def run(self, barrier):
        producer = None
        try:
            barrier.wait(timeout=40)
            self.began = self.last_tick = time.monotonic()
            self.request_decision()
            producer = threading.Thread(target=self.produce, daemon=True)
            producer.start()
            next_beat, previous_state = 0.0, ''
            while not self.stop_event.wait(.1):
                now = time.monotonic()
                with self.lock:
                    delta, self.last_tick = now - self.last_tick, now
                    if self.buffer >= 1 and not self.started:
                        self.started = True
                        if self.result['modelStartupMs'] is None:
                            self.result['modelStartupMs'] = self.elapsed()
                    if self.started and self.buffer > 0:
                        played = min(delta, self.buffer)
                        self.position += played
                        self.buffer -= played
                    state = 'playing' if self.started and self.buffer > .05 else 'buffering'
                    if self.started and state == 'buffering':
                        if not self.waiting:
                            self.stalls += 1
                            self.result['modelStalls'] += 1
                        self.stall_ms += delta * 1000
                        self.result['modelStallMs'] += int(delta * 1000)
                    self.waiting = state == 'buffering'
                    self.result['modelPositionMs'] = int(self.position * 1000)
                    d = self.decision
                    body = {'playId': d['playId'], 'positionMs': int(self.position * 1000),
                            'durationMs': d['durationMs'], 'bufferedMs': int(self.buffer * 1000),
                            'state': state, 'stalls': self.stalls, 'stallMs': int(self.stall_ms)}
                    if self.throughput and d['protocol'] == 'hls':
                        body['throughputBps'] = self.throughput
                        if self.recovery and self.throughput_at:
                            body['throughputAgeMs'] = max(0, int((now - self.throughput_at) * 1000))
                    if self.recovery and self.pending_ack and self.started:
                        body['switchGeneration'] = self.pending_ack
                if now >= next_beat or state != previous_state:
                    if self.recovery and len(self.result['measurements']) < 512:
                        self.result['measurements'].append({
                            'atMs': self.elapsed(), 'state': state,
                            'positionMs': body['positionMs'], 'bufferedMs': body['bufferedMs'],
                            'throughputBps': body.get('throughputBps'),
                            'throughputAgeMs': body.get('throughputAgeMs')})
                    beat = self.client.call('PUT', f'/api/files/{self.file["id"]}/progress', body)
                    if body.get('switchGeneration') == self.pending_ack:
                        self.pending_ack = None
                    previous_state, next_beat = state, now + (5 if self.auto else 10)
                    directive = beat.get('directive')
                    if directive and directive.get('action') == 'quality':
                        self.result['switches'].append({'atMs': self.elapsed(), 'to': directive.get('quality'),
                                                       'reason': directive.get('message', '')})
                        self.request_decision(directive.get('generation') if self.recovery else None)
                        previous_state = ''
        except HTTPFailure as error:
            if not self.result['accepted'] and error.status == 503 and error.reason == 'playback_busy':
                self.result['busy'] = True
            elif not self.stop_event.is_set():
                self.result['errors'].append(str(error))
        except Exception as error:
            if not self.stop_event.is_set():
                self.result['errors'].append(f'{type(error).__name__}: {error}')
        finally:
            self.stop_event.set()
            with self.lock:
                response = self.response
            if response:
                abort_response(response)
            if producer:
                producer.join(timeout=40)
                if producer.is_alive():
                    self.result['errors'].append('media worker did not join')
            self.cleanup()

    def fetch(self, path, generation, seconds=None, headers=None, progressive=False):
        asked = time.monotonic()
        response = self.client.open('GET', path, headers=headers)
        first_byte = time.monotonic()
        with self.lock:
            self.response = response
        data, count = bytearray(), 0
        try:
            while not self.stop_event.is_set():
                chunk = response.read(16 << 10)
                if not chunk:
                    break
                count += len(chunk)
                if progressive and len(data) < 1 << 20:
                    data.extend(chunk)
                if not progressive:
                    if len(data) + len(chunk) > 64 << 20:
                        raise ValueError('fixture segment/playlist exceeds 64 MiB')
                    data.extend(chunk)
                with self.lock:
                    self.result['bytes'] += len(chunk)
                    if self.result['firstMediaResponseMs'] is None:
                        self.result['firstMediaResponseMs'] = self.elapsed()
                    if generation != self.generation:
                        return b'', 0
                    if progressive:
                        self.buffer += len(chunk) * 8 / max(self.decision.get('bitrate', 0), 1)
            elapsed = time.monotonic() - first_byte
            with self.lock:
                if generation == self.generation:
                    if seconds is not None and not self.stop_event.is_set():
                        self.buffer += seconds
                    if not progressive and seconds is not None and elapsed >= (.001 if self.recovery else .02):
                        self.throughput = int(count * 8 / elapsed)
                        self.throughput_at = time.monotonic()
                    self.result['lastTransfer'] = {'firstByteMs': int((first_byte - asked) * 1000),
                                                   'transferMs': int(elapsed * 1000)}
            return bytes(data), count
        finally:
            response.close()
            with self.lock:
                if self.response is response:
                    self.response = None

    def produce(self):
        epoch, offset, seen, init_seen, initialization, seek = -1, 0, set(), set(), b'', 0
        while not self.stop_event.is_set():
            with self.lock:
                d, generation, buffered, switching = self.decision, self.generation, self.buffer, self.switching
                position = self.position
            if switching or buffered >= (30 if self.recovery else 12):
                self.stop_event.wait(.05)
                continue
            if generation != epoch:
                epoch, seen, init_seen, initialization = generation, set(), set(), b''
                seek = position
                offset = int(position * d.get('bitrate', 0) / 8)
            try:
                if d['protocol'] == 'file':
                    if offset > 0 and epoch not in self.samples:
                        # A resumed progressive range has no MP4 init. Verify
                        # this generation's authorized source prefix separately;
                        # it contributes no model buffer or recovery estimate.
                        prefix, _ = self.fetch(d['url'], epoch, headers={'Range': f'bytes=0-{PROGRESSIVE_SAMPLE_BYTES - 1}'})
                        if prefix:
                            self.samples[epoch] = prefix
                    raw, count = self.fetch(d['url'], epoch, headers={
                        'Range': f'bytes={offset}-{offset + 512 * 1024 - 1}'}, progressive=True)
                    prefix = self.samples.get(epoch, b'')
                    if raw and offset == len(prefix) and len(prefix) < PROGRESSIVE_SAMPLE_BYTES:
                        self.samples[epoch] = (prefix + raw)[:PROGRESSIVE_SAMPLE_BYTES]
                    offset += count
                else:
                    source = self.client.url(d['url'])
                    raw, _ = self.fetch(source, epoch)
                    variant, init, segments, ended = media_playlist(raw.decode(), source)
                    if variant:
                        source = variant
                        raw, _ = self.fetch(source, epoch)
                        _, init, segments, ended = media_playlist(raw.decode(), source)
                    if init and init not in init_seen:
                        initialization, _ = self.fetch(init, epoch)
                        init_seen.add(init)
                    origin = d.get('startMs', 0) / 1000 if d.get('playlist') != 'vod' else 0
                    segment = next_segment(segments, seen, origin, seek)
                    if segment:
                        raw, _ = self.fetch(segment[0], epoch, seconds=segment[1])
                        if raw and initialization and epoch not in self.samples and len(self.samples) < 8:
                            self.samples[epoch] = initialization + raw
                        seen.add(segment[0])
                    else:
                        self.stop_event.wait(.1)
                if self.stop_event.is_set():
                    return
            except Exception as error:
                with self.lock:
                    stale = self.generation != epoch or self.switching
                if not stale and not self.stop_event.is_set():
                    self.result['errors'].append(f'media {type(error).__name__}: {error}')
                    self.stop_event.set()

    def cleanup(self):
        with self.lock:
            d = self.decision
        if not d:
            return
        try:
            self.client.call('PUT', f'/api/files/{self.file["id"]}/progress', {
                'playId': d['playId'], 'state': 'stopped', 'positionMs': int(self.position * 1000)})
            if d.get('sessionId'):
                self.client.call('DELETE', '/api/playback/' + d['sessionId'])
        except HTTPFailure as error:
            if error.status not in (403, 404):
                self.result['errors'].append(f'cleanup {error}')

    def interrupt(self):
        self.stop_event.set()
        with self.lock:
            response = self.response
        if response:
            abort_response(response)

    def stop(self, deadline=None):
        self.interrupt()
        self.thread.join(timeout=max(0, (deadline or time.monotonic() + 45) - time.monotonic()))
        if self.thread.is_alive():
            self.result['errors'].append('player did not join')


def owned_process_counts(pid):
    """Read executable names only; discard unrelated processes and all paths."""
    if os.name == 'nt':
        return None
    try:
        result = subprocess.run(['ps', '-axo', 'pid=,ppid=,comm='],
                                capture_output=True, text=True, timeout=3, check=True)
        rows = [line.split(None, 2) for line in result.stdout.splitlines()]
        descendants, counts = {pid}, {'ffmpeg': 0, 'ffprobe': 0, 'other': 0}
        changed = True
        while changed:
            changed = False
            for row in rows:
                if len(row) == 3 and int(row[1]) in descendants and int(row[0]) not in descendants:
                    descendants.add(int(row[0]))
                    changed = True
        for row in rows:
            if len(row) == 3 and int(row[0]) in descendants and int(row[0]) != pid:
                name = Path(row[2]).name
                counts[name if name in ('ffmpeg', 'ffprobe') else 'other'] += 1
        return counts
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def shaping_errors(samples):
    """Check observed byte windows independently of the limiter's token state."""
    errors = []
    for before, after in zip(samples, samples[1:]):
        a, b = before['traffic'], after['traffic']
        if a['revision'] != b['revision']:
            continue
        seconds = (b['atNs'] - a['atNs']) / 1e9
        allowance = b['burstBytes'] + max(a['total']['active'], b['total']['active']) * (16 << 10)
        rate = b['profile']['aggregate']['bps']
        if rate and b['total']['bytes'] - a['total']['bytes'] > seconds * rate / 8 + allowance:
            errors.append('aggregate byte window exceeded configured rate plus bounded in-flight allowance')
        for viewer, cap in (b['profile'].get('viewers') or {}).items():
            previous = a['byViewer'].get(viewer, {'bytes': 0})
            current = b['byViewer'].get(viewer, {'bytes': 0})
            if cap['bps'] and current['bytes'] - previous['bytes'] > seconds * cap['bps'] / 8 + allowance:
                errors.append(f'{viewer} byte window exceeded its path rate')
    return errors


class LiveViewer:
    """Fetch the real live HLS without pretending it supports Automatic today."""
    def __init__(self, fixture, index, channel, copy_source=False, shared_output=False):
        self.fixture, self.index, self.channel = fixture, index, channel
        self.copy_source = copy_source
        self.shared_output = shared_output
        self.client = fixture.viewer(index)
        self.stop_event, self.response = threading.Event(), None
        self.lock, self.thread, self.session = threading.Lock(), None, None
        self.sample = b''
        self.result = {'viewer': f'viewer-{index}', 'accepted': False, 'bytes': 0,
                       'mediaDurationMs': 0, 'decodedSampleFrames': None,
                       'clientDecodedFrames': None, 'errors': []}

    def launch(self, barrier):
        self.thread = threading.Thread(target=self.run, args=(barrier,), daemon=True)
        self.thread.start()

    def fetch(self, path):
        response = self.client.open('GET', path)
        with self.lock:
            self.response = response
        try:
            data = bytearray()
            while not self.stop_event.is_set():
                part = response.read(16 << 10)
                if not part:
                    break
                data.extend(part)
                self.result['bytes'] += len(part)
                if len(data) > 16 << 20:
                    raise ValueError('live sample exceeded its byte bound')
            return bytes(data)
        finally:
            response.close()
            with self.lock:
                self.response = None

    def run(self, barrier):
        try:
            barrier.wait(timeout=40)
            request = {'channelId': self.channel, 'quality': '1080' if self.copy_source else '360',
                       'requestId': uuid.uuid4().hex, 'clientName': 'Fictional HTTP live reader'}
            if self.copy_source:
                request['profile'] = {'segmentFormats': ['mpegts'], 'videoCodecs': ['mpeg2video'],
                                      'audioCodecs': ['ac3'], 'interlaced': True,
                                      'maxHeight': 1080, 'maxBitrate': 8_000_000}
            if self.shared_output:
                self.client.headers['X-WMM-Fixture-Key'] = self.fixture.manifest['controlKey']
                self.session = self.client.call('POST', '/_fixture/streaming/live-output', {
                    'channelId': self.channel, 'quality': '360', 'requestId': request['requestId'], 'ladder': False})
            else:
                self.session = self.client.call('POST', '/api/live-tv/sessions', request)
            base = '/api/live-tv/sessions/' + self.session['id']
            ready_by = time.monotonic() + 30
            while self.session['state'] == 'starting' and not self.stop_event.wait(.1):
                self.session = self.client.call('GET', base)
                if time.monotonic() > ready_by:
                    raise ValueError('live startup exceeded its deadline')
            if self.session['state'] == 'failed':
                self.result['busy'] = self.session.get('reason') in ('tuners_busy', 'server_busy')
                if not self.result['busy']:
                    raise ValueError('live start failed: ' + self.session.get('reason', 'unknown'))
                return
            self.result['accepted'] = True
            self.result['mode'] = self.session.get('mode')
            seen, next_beat, init = set(), 0, b''
            while not self.stop_event.is_set():
                if time.monotonic() >= next_beat:
                    self.client.call('PUT', base, {'paused': False})
                    next_beat = time.monotonic() + 5
                source = self.client.url(self.session['url'])
                raw = self.fetch(source)
                _, initialization, segments, _ = media_playlist(raw.decode(), source)
                if initialization and not init:
                    init = self.fetch(initialization)
                for path, length in segments:
                    if path in seen or self.stop_event.is_set():
                        continue
                    raw = self.fetch(path)
                    if raw and not self.stop_event.is_set():
                        if not self.sample:
                            self.sample = init + raw
                        self.result['mediaDurationMs'] += int(length * 1000)
                    seen.add(path)
                self.stop_event.wait(.1)
        except HTTPFailure as error:
            if not self.result['accepted'] and error.reason in ('tuners_busy', 'server_busy'):
                self.result['busy'] = True
            elif not self.stop_event.is_set():
                self.result['errors'].append(str(error))
        except Exception as error:
            if not self.stop_event.is_set():
                self.result['errors'].append(f'{type(error).__name__}: {error}')
        finally:
            if self.session:
                try:
                    self.client.call('DELETE', '/api/live-tv/sessions/' + self.session['id'])
                except HTTPFailure as error:
                    self.result['errors'].append(f'live cleanup {error}')

    def interrupt(self):
        self.stop_event.set()
        with self.lock:
            response = self.response
        if response:
            abort_response(response)

    def stop(self, deadline=None):
        self.interrupt()
        self.thread.join(timeout=max(0, (deadline or time.monotonic() + 40) - time.monotonic()))
        if self.thread.is_alive():
            self.result['errors'].append('live worker did not join')


def stop_all(viewers):
    """Cancel everyone first; all joins share one bounded group deadline."""
    running = [viewer for viewer in viewers if viewer.thread]
    deadline = time.monotonic() + 45
    for viewer in running:
        viewer.interrupt()
    for viewer in running:
        viewer.stop(deadline)


def run_live(fixture, count, seconds, share_sources=False, live_profile=False):
    fixture.profile(40_000_000)
    channels = fixture.viewer(1).call('GET', '/api/live-tv/channels')['items']
    viewers = [LiveViewer(fixture, index + 1, channels[0]['id'],
                          copy_source=live_profile and index % 2 == 0) for index in range(count)]
    barrier, samples = threading.Barrier(count + 1), []
    try:
        for viewer in viewers:
            viewer.launch(barrier)
        barrier.wait(timeout=45)
        start = time.monotonic()
        while time.monotonic() - start < seconds:
            sample = fixture.metrics()
            sample['atMs'] = int((time.monotonic() - start) * 1000)
            sample['ownedChildProcesses'] = owned_process_counts(fixture.manifest['pid'])
            samples.append(sample)
            time.sleep(.5)
    finally:
        stop_all(viewers)
        fixture.profile()
    errors = [e for v in viewers for e in v.result['errors']] + shaping_errors(samples)
    accepted = [v for v in viewers if v.result['accepted']]
    if live_profile:
        for viewer in accepted:
            expected = 'original' if viewer.copy_source else 'video'
            if viewer.result.get('mode') != expected:
                errors.append(f'{viewer.result["viewer"]} selected {viewer.result.get("mode")}, expected {expected}')
    if share_sources:
        if len(accepted) != count or any(v.result.get('busy') for v in viewers):
            errors.append('same-channel group rejected a viewer despite available output slots')
        if any(sample['tuners']['peak'] > 1 for sample in samples):
            errors.append('same-channel group opened more than one physical tuner')
    elif not accepted or (count > 2 and not any(v.result.get('busy') for v in viewers)):
        errors.append('fictional two-tuner admission did not match the workload')
    for viewer in accepted:
        try:
            if not viewer.sample:
                raise ValueError('no complete live segment received')
            decoded = fixture.control.call('POST', '/_fixture/streaming/decode', {
                'media': base64.b64encode(viewer.sample).decode('ascii')})
            viewer.result['decodedSampleFrames'] = decoded['decodedSampleFrames']
        except (HTTPFailure, ValueError) as error:
            errors.append(f'{viewer.result["viewer"]} live sample validation: {error}')
    final = fixture.metrics()
    if final['tuners']['active'] or final['playbackReservations'] or final['traffic']['total']['active']:
        errors.append('live tuner/process/media requests did not release')
    scenario = f'live-profile-mixed-{count}' if live_profile else f'live-same-channel-{count}'
    return {'scenario': scenario, 'status': 'failed' if errors else 'observed',
            'viewers': [v.result for v in viewers], 'samples': samples,
            'afterCleanup': final, 'errors': errors,
            'qualification': 'Fixed quality and real received TS samples; no native live buffer/Automatic claim.'}


def process_resources(pid):
    """Sample only this fixture's RSS and descriptor count, without paths."""
    rss = subprocess.run(['ps', '-p', str(pid), '-o', 'rss='], capture_output=True,
                         text=True, timeout=3, check=True)
    if sys.platform == 'darwin':
        descriptors = subprocess.run(['lsof', '-a', '-p', str(pid), '-Ff'], capture_output=True,
                                     text=True, timeout=3, check=True)
        count = sum(line[1:].isdigit() for line in descriptors.stdout.splitlines() if line.startswith('f'))
    else:
        count = len(list(Path(f'/proc/{pid}/fd').iterdir()))
    return {'rssBytes': int(rss.stdout.strip()) * 1024, 'fileDescriptors': count}


def run_mixed(fixture, seconds=38, soak=False, shared_outputs=False):
    """C4: two tuner sources, shared viewers, a recording, files and a download."""
    fixture.profile(40_000_000, {'viewer-9': {'bps': 1_000_000}})
    files = {f['kind']: f for f in fixture.manifest['files']}
    channels = fixture.viewer(1).call('GET', '/api/live-tv/channels')['items']
    if len(channels) < 2:
        raise ValueError('mixed fixture needs two fictional channels')
    viewers = [LiveViewer(fixture, i, channels[0]['id'], copy_source=not shared_outputs and i % 2 == 0,
                          shared_output=shared_outputs) for i in range(1, 5)]
    viewers.append(LiveViewer(fixture, 5, channels[1]['id'], copy_source=False))
    files_playing = [Player(fixture, 6, files['baseline'], 'direct'),
                     Player(fixture, 7, files['eac3'], 'transcode')]
    all_playing = viewers + files_playing
    admin = Client(fixture.base, viewer='viewer-9')
    admin.call('POST', '/api/auth/login', {'name': 'Fixture Admin', 'password': PASSWORD})
    barrier, samples = threading.Barrier(len(all_playing) + 1), []
    recording, download = None, None
    download_bytes = 0
    download_error = None
    download_stop = threading.Event()
    download_response = None
    download_thread = None

    def read_download(path):
        nonlocal download_bytes, download_error, download_response
        try:
            response = admin.open('GET', path)
            download_response = response
            with response:
                while not download_stop.is_set():
                    chunk = response.read(16 << 10)
                    if not chunk:
                        break
                    download_bytes += len(chunk)
        except (HTTPFailure, OSError, ValueError) as error:
            if not download_stop.is_set():
                download_error = str(error)

    try:
        for viewer in all_playing:
            viewer.launch(barrier)
        barrier.wait(timeout=45)
        ready_by = time.monotonic() + 15
        while time.monotonic() < ready_by and not all(viewer.result['accepted'] for viewer in viewers):
            time.sleep(.1)
        now = dt.datetime.now(dt.timezone.utc)
        recording = admin.call('POST', '/api/live-tv/recordings/manual', {
            'channelId': channels[0]['id'], 'name': 'Fictional mixed workload',
            'start': (now + dt.timedelta(seconds=1)).isoformat(),
            'end': (now + dt.timedelta(seconds=max(28, seconds - 8) if soak else 28)).isoformat(),
            'repeat': 'once', 'zone': 'America/New_York'})
        download = admin.call('POST', f'/api/files/{files["baseline"]["id"]}/offline', {
            'profile': profile_for('direct', files['baseline']),
            'client': {'deviceId': 'fictional-mixed-download', 'name': 'Fictional offline client'}})
        token = admin.call('POST', f'/api/offline/downloads/{download["id"]}/token')
        path = download['url'] + ('&' if '?' in download['url'] else '?') + urllib.parse.urlencode({token['param']: token['token']})
        download_thread = threading.Thread(target=read_download, args=(path,), daemon=True)
        download_thread.start()
        started = time.monotonic()
        while time.monotonic() - started < seconds:
            sample = fixture.metrics()
            sample['atMs'] = int((time.monotonic() - started) * 1000)
            sample['ownedChildProcesses'] = owned_process_counts(fixture.manifest['pid'])
            if soak:
                sample['processResources'] = process_resources(fixture.manifest['pid'])
                sample['viewerProgress'] = [{'viewer': v.result['viewer'], 'bytes': v.result['bytes'],
                                             'positionMs': v.result.get('modelPositionMs'),
                                             'alive': v.thread.is_alive()} for v in all_playing]
            samples.append(sample)
            time.sleep(5 if soak else .5)
    finally:
        download_stop.set()
        if download_response:
            abort_response(download_response)
        if download_thread:
            download_thread.join(timeout=10)
        stop_all(all_playing)
        if download:
            try:
                admin.call('DELETE', f'/api/offline/downloads/{download["id"]}')
            except HTTPFailure:
                pass
        fixture.profile()

    errors = [error for viewer in all_playing for error in viewer.result['errors']]
    errors.extend(shaping_errors(samples))
    if download_error:
        errors.append('offline download: ' + download_error)
    if download_bytes == 0:
        errors.append('offline download received no media bytes')
    if not any(s['traffic']['byViewer'].get('viewer-9', {}).get('bytes', 0) for s in samples):
        errors.append('offline download bypassed fixture bandwidth accounting')
    if any(not viewer.result['accepted'] for viewer in all_playing):
        errors.append('mixed workload refused a viewer below the configured capacity')
    if any(sample['tuners']['peak'] > 2 for sample in samples):
        errors.append('mixed workload opened more than two physical tuners')
    if not samples or max(sample['tuners']['active'] for sample in samples) != 2:
        errors.append('mixed workload did not exercise both tuner sources together')
    if any(sample['playbackReservations'] > sample['playbackLimit'] for sample in samples):
        errors.append('mixed workload exceeded the playback process cap')
    if shared_outputs and (not samples or
            any(len([out for out in s['playbackCache']['sessions'] if out['kind'] == 'live']) != 2 for s in samples) or
            any(not 2 <= s['playbackClaims'] <= 3 for s in samples)):
        # File VOD may finish preparation before the Live TV startup window.
        errors.append('shared mixed workload did not retain exactly two live producers within three claims')
    if soak:
        for sample in samples:
            if any(not v['alive'] for v in sample['viewerProgress']):
                errors.append('soak reader exited before the end of its workload')
                break
        for player in files_playing:
            if player.result['modelPositionMs'] < seconds * 850:
                errors.append(player.result['viewer'] + ' failed to advance through the soak')
        for before, after in zip(samples, samples[12:]):
            if any(b['bytes'] >= a['bytes'] for b, a in zip(before['viewerProgress'], after['viewerProgress'])):
                errors.append('a soak viewer received no media for a full minute')
                break
    for viewer in viewers:
        expected = 'original' if viewer.copy_source else 'video'
        if viewer.result.get('mode') != expected:
            errors.append(viewer.result['viewer'] + ' selected the wrong live mode')
        if not viewer.sample:
            errors.append(viewer.result['viewer'] + ' received no complete live segment')
        else:
            try:
                decoded = fixture.control.call('POST', '/_fixture/streaming/decode', {
                    'media': base64.b64encode(viewer.sample).decode('ascii')})
                viewer.result['decodedSampleFrames'] = decoded['decodedSampleFrames']
            except HTTPFailure as error:
                errors.append(viewer.result['viewer'] + ' could not decode live sample: ' + str(error))
    for player in files_playing:
        expected = 'direct' if player.mode == 'direct' else 'transcode'
        if not player.result['modes'] or any(mode['mode'] != expected for mode in player.result['modes']):
            errors.append(player.result['viewer'] + ' selected the wrong file mode')
        if player.result['bytes'] <= 0:
            errors.append(player.result['viewer'] + ' received no file media bytes')
        for raw in player.samples.values():
            try:
                decoded = fixture.control.call('POST', '/_fixture/streaming/decode', {
                    'media': base64.b64encode(raw).decode('ascii')})
                player.result['decodedSampleFrames'] = decoded['decodedSampleFrames']
            except HTTPFailure as error:
                errors.append(player.result['viewer'] + ' could not decode file sample: ' + str(error))
    recording_state = None
    if recording:
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            recording_state = admin.call('GET', '/api/live-tv/recordings/' + recording['id'])
            if recording_state['state'] in ('done', 'failed', 'cancelled'):
                break
            time.sleep(.5)
        if not recording_state or recording_state['state'] != 'done' or recording_state['bytes'] <= 0:
            errors.append('shared-source recording did not finish with captured bytes')
    deadline = time.monotonic() + 10
    while True:
        final = fixture.metrics()
        if not (final['tuners']['active'] or final['playbackReservations'] or final['traffic']['total']['active']) or time.monotonic() >= deadline:
            break
        time.sleep(.2)
    if final['tuners']['active'] or final['playbackReservations'] or final['traffic']['total']['active']:
        errors.append('mixed workload left a tuner, playback reservation or media response')
    return {'scenario': 'shared-mixed' if shared_outputs else 'mixed-soak' if soak else 'mixed-live-file-recording-download', 'status': 'failed' if errors else 'observed',
            'liveViewers': [viewer.result for viewer in viewers],
            'fileViewers': [viewer.result for viewer in files_playing],
            'recordingState': recording_state['state'] if recording_state else None,
            'recordingBytes': recording_state['bytes'] if recording_state else 0,
            'downloadBytes': download_bytes, 'samples': samples, 'afterCleanup': final,
            'errors': errors, 'qualification': 'Fictional HTTP workload; no physical tuner or native player.'}


def run_group(fixture, name, count, seconds, modes=None, phases=None, distinct=False,
              auto=False, playlist='vod', same_account=False, detach_one=False, recovery=False):
    files = {f['kind']: f for f in fixture.manifest['files']}
    fixture.profile()
    players = []
    for index in range(count):
        mode = (modes or ['direct'])[index % len(modes or ['direct'])]
        if auto:
            kind = 'baseline'
        elif not distinct:
            kind = 'eac3'  # all four playback modes can use this one file
        else:
            kind = 'eac3' if mode in ('remux', 'audio') else ('alternate' if index % 2 else 'baseline')
        players.append(Player(fixture, index + 1, files[kind], mode, auto, playlist, same_account, recovery))
    barrier = threading.Barrier(count + 1)
    samples, transitions, probe_errors = [], [], []
    initial_bytes = fixture.metrics()['traffic']['total']['bytes']
    try:
        for player in players:
            player.launch(barrier)
        barrier.wait(timeout=45)
        start = time.monotonic()
        schedule = phases or [(0, 0, {})]
        next_phase, next_process, detached = 0, 0, False
        while time.monotonic() - start < seconds:
            elapsed = time.monotonic() - start
            if detach_one and not detached and elapsed >= seconds / 2:
                players[0].stop()
                detached = True
                # A buffered model could keep advancing after its server
                # session was wrongly killed. Check the surviving route too.
                for survivor in players[1:]:
                    with survivor.lock:
                        decision = survivor.decision
                    if decision and decision.get('sessionId'):
                        try:
                            with survivor.client.open('GET', decision['url']) as response:
                                if not response.read(65536).startswith(b'#EXTM3U'):
                                    probe_errors.append('surviving session lost its playlist')
                        except HTTPFailure as error:
                            probe_errors.append(f'stopping a peer affected the survivor: {error}')
            while next_phase < len(schedule) and elapsed >= schedule[next_phase][0]:
                _, aggregate, viewers = schedule[next_phase]
                update = fixture.profile(aggregate, viewers)
                transitions.append({'atMs': int(elapsed * 1000), 'revision': update['revision'],
                                    'profile': update['profile']})
                next_phase += 1
            sample = fixture.metrics()
            sample['atMs'] = int(elapsed * 1000)
            if elapsed >= next_process:
                sample['ownedChildProcesses'] = owned_process_counts(fixture.manifest['pid'])
                next_process = elapsed + 1
            samples.append(sample)
            time.sleep(.25)
    finally:
        stop_all(players)
        fixture.profile()
    results = [p.result for p in players]
    errors = probe_errors + [error for p in results for error in p['errors']]
    errors.extend(shaping_errors(samples))
    accepted = [p for p in players if p.result['accepted']]
    if not accepted:
        errors.append('no viewer was admitted')
    if len({p.decision['playId'] for p in accepted}) != len(accepted):
        errors.append('viewers shared a logical play ID')
    session_ids = [p.decision['sessionId'] for p in accepted if p.decision.get('sessionId')]
    if len(set(session_ids)) != len(session_ids):
        errors.append('viewers unexpectedly shared a playback session ID')
    if name == 'limit-plus-one' and not any(p.get('busy') for p in results):
        errors.append('saturated event sessions did not produce a busy response')
    if any(p['accepted'] and p['bytes'] == 0 for p in results):
        errors.append('an admitted viewer received no media bytes')
    if any(s['playbackReservations'] > s['playbackLimit'] for s in samples):
        errors.append('playback reservation cap exceeded')
    if name.startswith('preparation-'):
        errors.extend(preparation_errors(samples, playlist))
        # Session cleanup is asynchronous after ffmpeg joins; observe its
        # completion rather than treating a one-instant sample as a leak.
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            cache = fixture.metrics().get('playbackCache', {})
            if cache.get('scanComplete') and cache.get('temporaryBytes') == 0:
                break
            time.sleep(.2)
    final = fixture.metrics()
    if final['playbackReservations'] or final['traffic']['total']['active']:
        errors.append('playback/media requests did not release after cleanup')
    if name.startswith('preparation-') and final.get('playbackCache', {}).get('temporaryBytes') != 0:
        errors.append('owned playback temporary files remained after cleanup')
    delivered = final['traffic']['total']['bytes'] - initial_bytes
    received = sum(p['bytes'] for p in results)
    if received > delivered:
        errors.append('client received bytes missing from fixture media accounting')
    # Decode received bytes only after the measured workload has stopped, so
    # validation workers do not distort the simultaneous-stream sample.
    for player in accepted:
        try:
            verified = []
            for mode in player.result['modes']:
                generation = mode['generation']
                if generation not in player.samples:
                    raise ValueError(f'no complete received sample for generation {generation}')
                decoded = fixture.control.call('POST', '/_fixture/streaming/decode', {
                    'media': base64.b64encode(player.samples[generation]).decode('ascii')})
                verified.append({'generation': generation, 'frames': decoded['decodedSampleFrames']})
            player.result['decodedGenerations'] = verified
            player.result['decodedSampleFrames'] = min(v['frames'] for v in verified)
        except (HTTPFailure, ValueError) as error:
            errors.append(f'{player.result["viewer"]} received-sample validation: {error}')
    return {'scenario': name, 'status': 'failed' if errors else 'observed',
            'sameAccount': same_account, 'detachedOneViewer': detach_one,
            'trafficAccounting': {'serverWrittenBytes': delivered, 'clientReadBytes': received,
                                  'sameRevisionWindowsChecked': sum(a['traffic']['revision'] == b['traffic']['revision']
                                                                   for a, b in zip(samples, samples[1:]))},
            'viewers': results, 'transitions': transitions, 'samples': samples,
            'afterCleanup': final, 'errors': errors}


def recovery_assertions(case, phase_seconds):
    """Gate real-time negotiated N1/N2 runs after their full cooldown window."""
    if phase_seconds < 190 or case['scenario'] not in ('individual-50-5-50', 'shared-40-12-40'):
        return []
    rank = {step: index for index, step in enumerate(('original', '2160', '1080', '720', '480', '360'))}
    slow_start, recovered_start = int(phase_seconds * 1000), int(2 * phase_seconds * 1000)
    errors, upgrade_times = [], []
    for viewer in case['viewers']:
        modes = viewer['modes']
        if not modes or modes[0]['step'] != 'original':
            errors.append(f'{viewer["viewer"]} did not start at Original')
            continue
        previous, down, up = modes[0], None, None
        for mode in modes[1:]:
            if mode['step'] not in rank:
                errors.append(f'{viewer["viewer"]} received unknown quality {mode["step"]}')
                break
            if down is None and mode['atMs'] >= slow_start and rank[mode['step']] > rank[previous['step']]:
                down = mode
            elif down and mode['atMs'] >= recovered_start and rank[mode['step']] < rank[previous['step']]:
                up = mode
                break
            previous = mode
        if down is None:
            errors.append(f'{viewer["viewer"]} did not lower quality on the sustained slow link')
        if up is None:
            errors.append(f'{viewer["viewer"]} did not recover quality on the sustained fast link')
            continue
        upgrade_times.append(up['atMs'])
        if up['atMs'] < down['atMs'] + 240_000 or up['atMs'] < recovered_start + 60_000:
            errors.append(f'{viewer["viewer"]} recovered before cooldown and fresh-evidence windows')
        if not any(m['state'] == 'playing' and m['bufferedMs'] >= 20_000 and
                   m['throughputBps'] and m['throughputAgeMs'] is not None and m['throughputAgeMs'] <= 20_000 and
                   up['atMs'] - 10_000 <= m['atMs'] <= up['atMs']
                   for m in viewer.get('measurements', [])):
            errors.append(f'{viewer["viewer"]} recovered without observed fresh, buffered transfer evidence')
    if case['scenario'] == 'shared-40-12-40' and any(
            later - earlier < 10_000 for earlier, later in zip(sorted(upgrade_times), sorted(upgrade_times)[1:])):
        errors.append('shared viewers upgraded together instead of pacing their recovery')
    return errors


def preparation_errors(samples, playlist):
    """F1 assertions use complete server ranges, never ffmpeg progress alone."""
    errors, observed = [], []
    for sample in samples:
        cache = sample.get('playbackCache') or {}
        if not sample.get('playbackCacheMeasured') or not cache.get('scanComplete'):
            errors.append('playback cache or free-space measurement unavailable')
            break
        total, limit = cache['temporaryBytes'], cache['maxCacheBytes']
        if limit and total > limit:
            errors.append(f'owned playback cache exceeded {limit} bytes: {total}')
            break
        for session in cache['sessions']:
            limit = cache['maxSessionBytes']
            if limit and session['bytes'] > limit:
                errors.append(f'owned session cache exceeded {limit} bytes: {session["bytes"]}')
                break
        for session in sample['sessions']:
            prep = session.get('preparation') or {}
            if prep.get('playlist') != playlist:
                continue
            observed.append(prep)
            ranges = prep.get('ranges') or []
            if playlist == 'event' and ranges and prep.get('leadMs') is not None:
                # A completed HLS segment may cross the run target by one
                # segment; the source has four-second segments.
                bound = cache['eventAheadMs'] + 8000
                if prep['leadMs'] > bound:
                    errors.append(f'event prepared lead {prep["leadMs"]} ms exceeded {bound} ms')
                    break
    if not any(p.get('ranges') for p in observed):
        errors.append('no complete server-prepared range was observed')
    return errors


def run_live_ladder(fixture):
    """Explicit live rung switches; HTTP/decoder evidence, not native ABR."""
    errors, sessions, phases = [], [], []
    survivor = None
    last_segments = {}

    def read(client, url):
        started = time.monotonic()
        with client.open('GET', url) as response:
            data = response.read((16 << 20) + 1)
        if len(data) > 16 << 20:
            raise ValueError('live ladder response exceeded the fixture bound')
        return data, time.monotonic() - started

    def parts(client, url, fresh=False):
        deadline = time.monotonic() + 8
        while True:
            playlist, _ = read(client, url)
            text = playlist.decode()
            init = re.search(r'#EXT-X-MAP:URI="([^"]+)"', text)
            segments = [line for line in text.splitlines() if line and not line.startswith('#')]
            if not init or len(segments) < 3:
                raise ValueError('live family did not prepare its startup window')
            chosen = segments[-2]
            # Comparing the number across rungs also observes an advancing live
            # window instead of repeatedly decoding the same cached segment.
            number = re.search(r'segment-(\d+)\.m4s', chosen).group(1)
            key = client
            if not fresh or last_segments.get(key) != number:
                break
            if time.monotonic() >= deadline:
                raise ValueError('live family stopped advancing')
            time.sleep(.1)
        if fresh:
            last_segments[key] = number
        prefix, _ = read(client, urllib.parse.urljoin(url, init.group(1)))
        media, elapsed = read(client, urllib.parse.urljoin(url, chosen))
        return prefix + media, elapsed, number

    try:
        channel = fixture.viewer(1).call('GET', '/api/live-tv/channels')['items'][0]['id']
        for index in (1, 2):
            viewer = fixture.viewer(index)
            viewer.headers['X-WMM-Fixture-Key'] = fixture.manifest['controlKey']
            view = viewer.call('POST', '/_fixture/streaming/live-output', {
                'channelId': channel, 'quality': '720', 'requestId': f'live-family-{index}', 'ladder': True})
            sessions.append({'viewer': viewer, 'id': view['id']})
            token = viewer.call('POST', '/api/auth/media-token')['token']
            media_client = Client(fixture.base, viewer=f'viewer-{index}')
            master_url = fixture.base + view['url'] + '?access_token=' + urllib.parse.quote(token)
            master, _ = read(media_client, master_url)
            text = master.decode()
            variants = [urllib.parse.urljoin(master_url, line) for line in text.splitlines()
                        if line and not line.startswith('#')]
            audio = re.search(r'#EXT-X-MEDIA:TYPE=AUDIO[^\n]*URI="([^"]+)"', text)
            if len(variants) != 2 or not audio or any('access_token=' not in url for url in variants):
                raise ValueError('live master lost its variants, common audio or child authority')
            sessions[-1].update(client=media_client, variants=variants,
                                audio=urllib.parse.urljoin(master_url, audio.group(1)))
        metrics = fixture.metrics()
        if metrics['playbackClaims'] != 2 or metrics['tuners']['active'] != 1:
            errors.append('identical live families did not share two encoder claims and one capture')
        if len(metrics['playbackCache']['sessions']) != 1:
            errors.append('shared live family storage was counted more than once')
        for rate, rung in ((40_000_000, 1), (500_000, 0), (40_000_000, 1)):
            fixture.profile(aggregate=rate)

            def fetch(session):
                video, elapsed, number = parts(session['client'], session['variants'][rung], fresh=True)
                audio, audio_elapsed, _ = parts(session['client'], session['audio'])
                return video, {'segment': number, 'videoBytes': len(video), 'audioBytes': len(audio),
                               'videoTransferSeconds': round(elapsed, 3),
                               'audioTransferSeconds': round(audio_elapsed, 3)}

            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                fetched = list(pool.map(fetch, sessions))
            for video, result in fetched:
                decoded = fixture.control.call('POST', '/_fixture/streaming/decode', {
                    'media': base64.b64encode(video).decode()})
                if decoded.get('decodedSampleFrames', 0) < 3:
                    errors.append('live switched sample did not decode')
                result['decodedSampleFrames'] = decoded.get('decodedSampleFrames', 0)
            metrics = fixture.metrics()
            if metrics['playbackClaims'] != 2 or metrics['tuners']['opens'] != 1:
                errors.append('live rung switch restarted capture or changed encoder claims')
            phases.append({'aggregateBps': rate, 'selectedRung': 'high' if rung else 'low',
                           'viewers': [result for _, result in fetched],
                           'playbackClaims': metrics['playbackClaims']})
        first, second = sessions
        first['viewer'].call('DELETE', '/api/live-tv/sessions/' + first['id'])
        try:
            read(first['client'], first['variants'][1])
            errors.append('stopped live viewer retained variant access')
        except HTTPFailure as error:
            if error.status != 404:
                errors.append(f'stopped live variant returned {error.status}')
        video, _, _ = parts(second['client'], second['variants'][1], fresh=True)
        survivor = fixture.control.call('POST', '/_fixture/streaming/decode', {
            'media': base64.b64encode(video).decode()})
        if survivor.get('decodedSampleFrames', 0) < 3 or fixture.metrics()['playbackClaims'] != 2:
            errors.append('stopping a peer interrupted the surviving live family')
    except (HTTPFailure, OSError, ValueError, KeyError, TypeError, TimeoutError) as error:
        errors.append(f'live ladder playback failed: {str(error)[:180]}')
    finally:
        for session in sessions:
            try:
                session['viewer'].call('DELETE', '/api/live-tv/sessions/' + session['id'])
            except (HTTPFailure, OSError) as error:
                errors.append(f'live cleanup failed: {error}')
        fixture.profile()
        deadline = time.monotonic() + 5
        while True:
            metrics = fixture.metrics()
            if not metrics['playbackClaims'] and not metrics['tuners']['active']:
                break
            if time.monotonic() >= deadline:
                errors.append('live family left an encoder or tuner after stop')
                break
            time.sleep(.05)
        if metrics['temporaryBytes']:
            errors.append('live family left temporary output after stop')
    return {'scenario': 'private-live-ladder-2-viewers-40-0.5-40',
            'status': 'failed' if errors else 'observed', 'errors': errors,
            'phases': phases, 'survivor': survivor,
            'qualification': 'Explicit HTTP rung selection and decoded samples; not player-managed ABR or native A/V continuity.'}


def run_ladder(fixture):
    """Switch two private VOD ladders over a shared 40→3→40 Mbps link."""
    errors, plays, viewers, phases = [], [], [], []
    limit_rejected = False
    survivor = None

    def wait_claims(expected):
        deadline = time.monotonic() + 5
        while True:
            metrics = fixture.metrics()
            if metrics['playbackClaims'] <= expected or time.monotonic() >= deadline:
                return metrics
            # A stopped viewer may still own an encoder leased by a peer.
            # Wait for its bounded flight to release before checking cleanup.
            time.sleep(.05)

    def read(client, target):
        start = time.monotonic()
        with client.open('GET', target) as response:
            data = response.read((16 << 20) + 1)
        if len(data) > (16 << 20):
            raise ValueError('Fixture ladder media response exceeded 16 MiB')
        return data, time.monotonic() - start

    def media_parts(client, playlist_url, index):
        playlist, _ = read(client, playlist_url)
        text = playlist.decode()
        match = re.search(r'#EXT-X-MAP:URI="([^"]+)"', text)
        segments = [line.strip() for line in text.splitlines() if line and not line.startswith('#')]
        if not match or not segments or not -len(segments) <= index < len(segments):
            raise ValueError('Fixture ladder media playlist is incomplete')
        init, _ = read(client, urllib.parse.urljoin(playlist_url, match.group(1)))
        segment, elapsed = read(client, urllib.parse.urljoin(playlist_url, segments[index]))
        if b'ftyp' not in init or b'moof' not in segment:
            raise ValueError('Fixture ladder returned incomplete fMP4')
        return init + segment, elapsed

    try:
        for index in (1, 2):
            viewer = fixture.viewer(index)
            token = viewer.call('POST', '/api/auth/media-token')['token']
            name = fixture.manifest['accounts'][index - 1]
            start = fixture.control.call('POST', '/_fixture/streaming/ladder', {'viewer': name})
            plays.append((start['playId'], name))
            master_url = fixture.base + start['url'] + '?access_token=' + urllib.parse.quote(token)
            master, _ = read(viewer, master_url)
            text = master.decode()
            variants = [urllib.parse.urljoin(master_url, line.strip()) for line in text.splitlines()
                        if line.startswith('../')]
            audio = re.search(r'#EXT-X-MEDIA:TYPE=AUDIO[^\n]*URI="([^"]+)"', text)
            if len(variants) != 2 or not audio:
                raise ValueError('Fixture ladder master lacks two variants and common audio')
            viewers.append({'client': viewer, 'variants': variants,
                            'audio': urllib.parse.urljoin(master_url, audio.group(1))})
        if fixture.metrics()['playbackClaims'] != 4:
            errors.append('two viewers did not hold four cold-rung claims')
        try:
            extra = fixture.control.call('POST', '/_fixture/streaming/ladder', {
                'viewer': fixture.manifest['accounts'][2]})
            plays.append((extra['playId'], fixture.manifest['accounts'][2]))
            errors.append('limit-plus-one ladder was admitted')
        except HTTPFailure as error:
            if error.status != 503 or fixture.metrics()['playbackClaims'] != 4:
                errors.append(f'limit-plus-one ladder changed admitted claims: {error}')
            else:
                limit_rejected = True
        for segment, (rate, rung) in enumerate(((40_000_000, 1), (3_000_000, 0), (40_000_000, 1))):
            fixture.profile(aggregate=rate)

            def fetch_viewer(viewer):
                video, video_time = media_parts(viewer['client'], viewer['variants'][rung], segment)
                audio, audio_time = media_parts(viewer['client'], viewer['audio'], segment)
                return {'video': video, 'audio': audio, 'videoSeconds': video_time,
                        'audioSeconds': audio_time}

            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                fetched = list(pool.map(fetch_viewer, viewers))
            for result in fetched:
                decoded = fixture.control.call('POST', '/_fixture/streaming/decode', {
                    'media': base64.b64encode(result['video']).decode()})
                if decoded.get('decodedSampleFrames', 0) < 3:
                    errors.append('switched video segment did not decode')
            metrics = fixture.metrics()
            if metrics['playbackClaims'] != 4 or metrics['playbackReservations'] > 4:
                errors.append('ladder exceeded or lost its admitted playback slots')
            renditions = metrics['renditions']
            if (renditions['bytes'] > renditions['maxBytes'] or
                    renditions['flights'] > renditions['maxFlights'] or
                    renditions['followers'] + renditions['pins'] > renditions['maxFollowers']):
                errors.append('ladder exceeded its rendition-cache budget')
            phases.append({'aggregateBps': rate, 'selectedRung': 'high' if rung else 'low',
                           'segment': segment, 'viewers': [
                               {'videoBytes': len(result['video']), 'audioBytes': len(result['audio']),
                                'videoTransferSeconds': round(result['videoSeconds'], 3),
                                'audioTransferSeconds': round(result['audioSeconds'], 3)}
                               for result in fetched], 'playbackClaims': metrics['playbackClaims'],
                           'renditions': renditions})
        play_id, name = plays[0]
        fixture.control.call('POST', '/_fixture/streaming/ladder/stop', {'playId': play_id, 'viewer': name})
        try:
            read(viewers[0]['client'], viewers[0]['variants'][1])
            errors.append('stopped viewer retained access to a ladder variant')
        except HTTPFailure as error:
            if error.status != 404:
                errors.append(f'stopped variant returned {error.status}, expected 404')
        # Jump the surviving viewer to the final segment after its peer stops.
        # In-flight stop ordering is exercised separately with a slow encoder
        # and deterministic Go tests; this checks real HTTP routes and media.
        video, video_time = media_parts(viewers[1]['client'], viewers[1]['variants'][1], -1)
        audio, audio_time = media_parts(viewers[1]['client'], viewers[1]['audio'], -1)
        decoded = fixture.control.call('POST', '/_fixture/streaming/decode', {
            'media': base64.b64encode(video).decode()})
        metrics = wait_claims(2)
        if decoded.get('decodedSampleFrames', 0) < 3 or metrics['playbackClaims'] != 2:
            errors.append('surviving viewer lost decodable media or its two reserved slots')
        survivor = {'decodedSampleFrames': decoded.get('decodedSampleFrames', 0),
                    'videoBytes': len(video), 'audioBytes': len(audio),
                    'videoTransferSeconds': round(video_time, 3),
                    'audioTransferSeconds': round(audio_time, 3),
                    'playbackClaims': metrics['playbackClaims']}
    except (HTTPFailure, OSError, ValueError, KeyError, TypeError, TimeoutError) as error:
        errors.append(f'ladder playback failed: {str(error)[:180]}')
    finally:
        for play_id, name in plays:
            try:
                fixture.control.call('POST', '/_fixture/streaming/ladder/stop', {'playId': play_id, 'viewer': name})
            except (HTTPFailure, OSError) as error:
                errors.append(f'ladder cleanup failed: {error}')
        try:
            fixture.profile()
            final_claims = wait_claims(0)['playbackClaims']
            if final_claims != 0:
                errors.append('ladder left a cold-rung claim after stop')
        except (HTTPFailure, OSError, ValueError, KeyError) as error:
            final_claims = None
            errors.append(f'ladder final metrics failed: {error}')
    return {'scenario': 'private-ladder-2-viewers-40-3-40', 'status': 'failed' if errors else 'observed',
            'errors': errors, 'phases': phases, 'limitPlusOneRejected': limit_rejected,
            'survivorAfterPeerStop': survivor,
            'finalPlaybackClaims': final_claims,
            'qualification': 'Explicit synthetic rung selection and HTTP shaping; no native ABR or cold-switch latency guarantee.'}


def run_storage(fixture):
    """The fixture's free-byte hook forces the production admission path."""
    errors = []
    file = next(f for f in fixture.manifest['files'] if f['kind'] == 'baseline')
    viewer = fixture.viewer(1)
    try:
        viewer.call('POST', f'/api/files/{file["id"]}/playback', {
            'profile': profile_for('transcode', file), 'quality': 'original',
            'playlist': 'event', 'client': {'deviceId': 'fictional-low-disk',
                                           'product': 'Fictional storage probe', 'platform': 'fixture'}})
        errors.append('low temporary-volume reserve admitted a transcode')
    except HTTPFailure as error:
        if error.status != 503 or error.reason != 'storage_busy':
            errors.append(f'low temporary-volume reserve returned {error}')
    channel = viewer.call('GET', '/api/live-tv/channels')['items'][0]
    try:
        viewer.call('POST', '/api/live-tv/sessions', {
            'channelId': channel['id'], 'quality': '360',
            'requestId': uuid.uuid4().hex, 'clientName': 'Fictional storage probe'})
        errors.append('low temporary-volume reserve admitted Live TV')
    except HTTPFailure as error:
        if error.status != 503 or error.reason != 'storage_busy':
            errors.append(f'low temporary-volume reserve returned for Live TV: {error}')
    metrics = fixture.metrics()
    if metrics['playbackReservations'] or metrics['playbackCache']['temporaryBytes'] or metrics['tuners']['active']:
        errors.append('low-disk rejection left a playback reservation, tuner or cache file')
    if metrics['playbackCache']['freeBytes'] != 0:
        errors.append('low-disk override did not reach production resource accounting')
    return {'scenario': 'storage-free-space-admission', 'status': 'failed' if errors else 'observed',
            'errors': errors, 'metrics': metrics,
            'qualification': 'Fixture-only free-byte override; no physical volume was filled.'}


@contextlib.contextmanager
def owned_fixture(args):
    if args.base_url:
        yield Fixture(args.base_url)
        return
    with tempfile.TemporaryDirectory(prefix='wmm-streaming-run-') as scratch:
        binary = Path(scratch) / ('livetvfixture.exe' if os.name == 'nt' else 'livetvfixture')
        subprocess.run(['go', 'build', '-o', str(binary), './cmd/livetvfixture'],
                       cwd=ROOT / 'server', check=True)
        with (Path(scratch) / 'fixture.log').open('w+') as log:
            command = [str(binary), '-streaming', '-port', '0', '-streaming-seconds',
                       str(args.media_seconds), '-streaming-slots', str(args.slots),
                       '-ffmpeg', args.ffmpeg, '-ffprobe', args.ffprobe]
            if getattr(args, 'playback_ffmpeg', None):
                command.extend(['-streaming-playback-ffmpeg', args.playback_ffmpeg])
            if args.extended:
                command.append('-streaming-extended')
            if args.share_sources:
                command.append('-share-sources')
            if getattr(args, 'live_hd', False):
                command.append('-streaming-live-hd')
            if args.scenario == 'storage':
                command.extend(('-streaming-free-bytes', '0'))
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=log, text=True)
            ready = queue.Queue()
            reader = threading.Thread(target=lambda: ready.put(process.stdout.readline()), daemon=True)
            reader.start()
            try:
                line = ready.get(timeout=180)
                if not line:
                    # No account/token has been issued at startup. Keep the
                    # bounded setup error before deleting this owned log,
                    # redacting filesystem paths from machine-readable reports.
                    log.seek(0)
                    reason = re.sub(r'/[^\s"]+', '<path>', log.read()[-1200:])
                    raise RuntimeError('Fictional fixture failed to start: ' + ' '.join(reason.split())[-600:])
                manifest = json.loads(line)
                fixture = Fixture(manifest['baseUrl'])
                if fixture.manifest['serverId'] != manifest['serverId']:
                    raise ValueError('Fixture identity changed at startup')
                yield fixture
            finally:
                if process.poll() is None:
                    process.send_signal(signal.SIGTERM)
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
                    raise RuntimeError('Owned fixture exceeded its shutdown deadline') from None
                process.stdout.close()


def execute(args, counts, report):
    with owned_fixture(args) as fixture:
        report['environment'] = {k: copy.deepcopy(fixture.manifest[k]) for k in
                                 ('ffmpegVersion', 'ffprobeVersion', 'files', 'qualifications')}
        report['environment'].update({'encoder': 'libx264', 'hardwareAcceleration': 'software',
                                       'sourceRevision': subprocess.check_output(
                                           ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                                       'sourceDirty': bool(subprocess.check_output(
                                           ['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip()),
                                       'logicalCPUs': os.cpu_count(), 'otherHostWorkloads': 'not inspected',
                                       'gpuDriver': None, 'adapter': None, 'processRSS': None})
        if args.scenario == 'live-ladder':
            report['cases'].append(run_live_ladder(fixture))
            report['status'] = report['cases'][0]['status']
            return
        if args.scenario == 'ladder':
            report['cases'].append(run_ladder(fixture))
            report['status'] = report['cases'][0]['status']
            return
        jobs = []
        if args.scenario == 'original-recovery':
            jobs.append(('individual-return-original', {'count': 1, 'seconds': args.sample_seconds,
                'auto': True, 'recovery': True, 'phases': [(0, 0, {'viewer-1': {'bps': 50_000_000}}),
                    (30, 0, {'viewer-1': {'bps': 5_000_000}}), (150, 0, {'viewer-1': {'bps': 50_000_000}})]}))
        if args.scenario in ('all', 'concurrency'):
            for count in counts:
                for distinct in (False, True):
                    jobs.append((f'concurrent-{count}-{"mixed-files" if distinct else "same-file"}', {
                        'count': count, 'seconds': args.sample_seconds,
                        'modes': ['direct', 'remux', 'audio', 'transcode'], 'distinct': distinct}))
            jobs.append(('limit-plus-one', {'count': args.slots + 1, 'seconds': args.sample_seconds,
                                            'modes': ['transcode'], 'playlist': 'event'}))
            jobs.append(('same-account-two-plays', {'count': 2, 'seconds': args.sample_seconds,
                                                   'modes': ['remux'], 'same_account': True}))
            jobs.append(('stop-one-viewer', {'count': 2, 'seconds': args.sample_seconds,
                                             'modes': ['remux'], 'detach_one': True}))
        if args.scenario in ('all', 'bandwidth'):
            seconds = args.phase_seconds
            jobs.extend([
                ('individual-baseline', {'count': 1, 'seconds': args.sample_seconds, 'auto': True, 'recovery': args.auto_recovery}),
                ('individual-50-5-50', {'count': 1, 'seconds': 3 * seconds, 'auto': True, 'recovery': args.auto_recovery,
                                       'phases': [(0, 0, {'viewer-1': {'bps': 50_000_000}}),
                                                  (seconds, 0, {'viewer-1': {'bps': 5_000_000}}),
                                                  (2 * seconds, 0, {'viewer-1': {'bps': 50_000_000}})]}),
                ('shared-baseline', {'count': 4, 'seconds': args.sample_seconds, 'auto': True, 'recovery': args.auto_recovery}),
                ('shared-40-12-40', {'count': 4, 'seconds': 3 * seconds, 'auto': True, 'recovery': args.auto_recovery,
                                    'phases': [(0, 40_000_000, {}), (seconds, 12_000_000, {}),
                                               (2 * seconds, 40_000_000, {})]}),
                ('one-slow-peer', {'count': 4, 'seconds': 3 * seconds, 'auto': True, 'recovery': args.auto_recovery,
                                  'phases': [(0, 40_000_000, {}),
                                             (seconds, 40_000_000, {'viewer-1': {'bps': 5_000_000}}),
                                             (2 * seconds, 40_000_000, {})]})])
        if args.scenario in ('all', 'preparation', 'event'):
            for playlist in ('event', 'vod'):
                if args.scenario == 'event' and playlist != 'event':
                    continue
                jobs.append((f'preparation-{playlist}', {'count': 1, 'seconds': args.sample_seconds,
                                                        'modes': ['transcode'], 'playlist': playlist}))
        selected = set(args.only.split(',')) if args.only else set()
        if selected and selected - {name for name, _ in jobs}:
            raise ValueError('Unknown --only case for this scenario')
        for name, options in jobs:
            if selected and name not in selected:
                continue
            print(f'Running {name}', file=sys.stderr, flush=True)
            case = run_group(fixture, name, **options)
            if name == 'individual-return-original':
                modes = case['viewers'][0]['modes']
                if (len(modes) < 3 or modes[0]['step'] != 'original' or modes[-1]['step'] != 'original'
                        or modes[-1]['mode'] != 'direct' or not any(m['step'] != 'original' for m in modes)):
                    case['errors'].append('viewer did not lower quality and return to progressive Original')
                if case['viewers'][0]['modelPositionMs'] < args.sample_seconds * 800:
                    case['errors'].append('viewer did not advance through the full recovery workload')
                if case['errors']:
                    case['status'] = 'failed'
            if args.auto_recovery:
                case['errors'].extend(recovery_assertions(case, args.phase_seconds))
                if case['errors']:
                    case['status'] = 'failed'
            report['cases'].append(case)
        if args.scenario == 'storage':
            report['cases'].append(run_storage(fixture))
        if args.scenario in ('all', 'live'):
            counts = (2, 4, 8) if args.share_sources else (2, 3)
            for count in counts:
                if count > args.slots:
                    continue
                print(f'Running live-same-channel-{count}', file=sys.stderr, flush=True)
                report['cases'].append(run_live(fixture, count, max(8, args.sample_seconds), args.share_sources, args.live_profile))
        if args.scenario in ('mixed', 'shared-mixed', 'soak'):
            print('Running shared-mixed' if args.scenario == 'shared-mixed' else 'Running mixed-live-file-recording-download', file=sys.stderr, flush=True)
            report['cases'].append(run_mixed(fixture, max(38, args.sample_seconds), args.scenario == 'soak', args.scenario == 'shared-mixed'))
        report['status'] = 'failed' if any(case['status'] == 'failed' for case in report['cases']) else 'observed'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', help='Attach only to an already identified loopback streaming fixture')
    parser.add_argument('--scenario', choices=['all', 'concurrency', 'bandwidth', 'preparation', 'event', 'storage', 'live', 'mixed', 'shared-mixed', 'ladder', 'live-ladder', 'soak', 'original-recovery'], default='all')
    parser.add_argument('--counts', default='1,2,4,8,9')
    parser.add_argument('--sample-seconds', type=float, default=5)
    parser.add_argument('--phase-seconds', type=float, default=30)
    parser.add_argument('--media-seconds', type=int, default=180)
    parser.add_argument('--slots', type=int, default=8)
    parser.add_argument('--extended', action='store_true')
    parser.add_argument('--share-sources', action='store_true', help='Enable staged same-channel tuner sharing in the fictional fixture')
    parser.add_argument('--auto-recovery', action='store_true', help='Use the negotiated generation and fresh-transfer protocol for bandwidth cases')
    parser.add_argument('--only', help='Run named file/bandwidth cases only, comma-separated')
    parser.add_argument('--live-profile', action='store_true', help='Mix API-level-24 live copy with legacy conversion (requires --share-sources)')
    parser.add_argument('--ffmpeg', default='ffmpeg')
    parser.add_argument('--ffprobe', default='ffprobe')
    parser.add_argument('--output', type=Path, help='JSON report destination; default prints JSON to stdout')
    args = parser.parse_args(argv)
    if args.live_profile and (not args.share_sources or args.scenario not in ('all', 'live')):
        parser.error('--live-profile requires --share-sources and the live scenario')
    if args.scenario in ('mixed', 'shared-mixed', 'soak') and not args.share_sources:
        parser.error('the mixed scenario requires --share-sources')
    if args.auto_recovery and args.scenario not in ('all', 'bandwidth'):
        parser.error('--auto-recovery requires a bandwidth scenario')
    if args.scenario == 'live-ladder' and (not args.share_sources or args.slots < 2):
        parser.error('the private live ladder requires --share-sources and at least two slots')
    if args.scenario == 'ladder' and (args.slots < 4 or args.media_seconds < 18):
        parser.error('the private ladder needs four playback slots and at least 18 media seconds')
    if args.only and args.scenario not in ('all', 'bandwidth', 'concurrency', 'preparation', 'event'):
        parser.error('--only selects a file/bandwidth case')
    counts = [int(n) for n in args.counts.split(',')]
    if not counts or any(n < 1 or n > 17 for n in counts):
        parser.error('viewer counts must be between 1 and 17')
    if not (1 <= args.slots <= 16 and 1 <= args.sample_seconds <= 3600 and
            1 <= args.phase_seconds <= 600 and 10 <= args.media_seconds <= 2160):
        parser.error('invalid duration or playback slot limit')
    if args.media_seconds < 3 * args.phase_seconds + 20 and args.scenario in ('all', 'bandwidth'):
        parser.error('media must outlast the bandwidth phases plus a 20-second end-of-file margin')
    if args.scenario == 'soak' and args.media_seconds < args.sample_seconds + 30:
        parser.error('soak media must outlast the workload by 30 seconds')
    if args.scenario == 'original-recovery' and (args.sample_seconds < 900 or args.media_seconds < args.sample_seconds + 30):
        parser.error('return to Original needs at least 900 seconds and a 30-second media margin')
    report = {'schema': 'wmm-streaming-baseline-v1',
              'startedAt': dt.datetime.now(dt.timezone.utc).isoformat(),
              'measurement': 'HTTP transfer with simulated finite player buffer; no client frame decoding',
              'cases': [], 'laterPhaseAssertions': [
                  ('The short negotiated phases do not exercise a full recovery cooldown.'
                   if args.auto_recovery and args.phase_seconds < 190 else
                   'This run gates one upward step; return to direct Original after multiple cooldowns remains unverified.'
                   if args.auto_recovery else
                   'Legacy bandwidth cases do not negotiate or assert upward recovery.'),
                  'Product shared upload admission/enforcement is not asserted by F0 (B0).',
                  'Event preparation duration/byte bounds are observed, not asserted by F0 (F1).',
                  'Physical clients, GPU capacity, tuner reception and long soak are not verified.',
                  'OS process counts are sampled descendants, not an exact peak or a playback-only count.']}
    try:
        execute(args, counts, report)
    except (Exception, KeyboardInterrupt) as error:
        report['status'] = 'failed'
        report['runnerError'] = {'type': type(error).__name__, 'message': str(error)[:240]}
    encoded = json.dumps(report, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
        print(f'Wrote {len(report["cases"])} characterization cases to {args.output}', file=sys.stderr)
    else:
        print(encoded, end='')
    return 1 if report['status'] == 'failed' else 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (ValueError, RuntimeError, OSError, queue.Empty, subprocess.SubprocessError) as error:
        print(f'Streaming harness: {error}', file=sys.stderr)
        raise SystemExit(1) from None
