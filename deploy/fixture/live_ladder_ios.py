#!/usr/bin/env python3
"""Qualify private A1 Live TV ABR on an owned iOS simulator and HD fixture."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
from types import SimpleNamespace
import urllib.parse
import uuid

import ladder_ios as native
import streaming


def wait_state(path, predicate, seconds, heartbeat):
    deadline = time.monotonic() + seconds
    state = None
    while time.monotonic() < deadline:
        heartbeat()
        state = native.read_state(path)
        if state and (predicate(state) or state.get('error')):
            return state
        time.sleep(1)
    return state


def launch_player(sim, address):
    try:
        launched = subprocess.run(['xcrun', 'simctl', 'launch', sim, native.BUNDLE, '--live-buffered', address],
                                  capture_output=True, text=True, timeout=30)
    except subprocess.TimeoutExpired:
        # TimeoutExpired includes argv, including the media token, in str(error).
        raise RuntimeError('Native fixture player launch timed out') from None
    if launched.returncode:
        raise RuntimeError('Native fixture player did not launch')


def validate_video_stages(stages):
    previous_frames = 0
    for stage in stages:
        frames = stage.get('sampledVideoFrames', 0)
        if (stage['status'] != 'ready' or stage['error'] or stage['stalls'] or
                frames - previous_frames < 3 or
                abs(stage.get('lastVideoSeconds', -100) - stage['positionSeconds']) > 3):
            raise RuntimeError('Native playback stalled, failed, or did not present new decoded video')
        previous_frames = frames


def run(args):
    runtime, device = native.simulator_choices()
    result = {'scenario': 'ios-live-hd-ladder-40-1.6-40', 'runtime': runtime['version'],
              'deviceType': device['name'], 'preferredBufferSeconds': 30, 'targetLiveDelaySeconds': 30, 'status': 'failed', 'stages': {}, 'errors': []}
    sim = None
    with tempfile.TemporaryDirectory(prefix='wmm-a1-ios-') as scratch:
        app = native.build_app(Path(scratch), runtime['version'])
        try:
            sim = native.command(['xcrun', 'simctl', 'create', 'WMM A1 ' + uuid.uuid4().hex[:8],
                                  device['identifier'], runtime['identifier']], output=True)
            uuid.UUID(sim)
            native.command(['xcrun', 'simctl', 'boot', sim])
            native.command(['xcrun', 'simctl', 'bootstatus', sim, '-b'], timeout=180)
            native.command(['xcrun', 'simctl', 'install', sim, str(app)])
            container = Path(native.command(['xcrun', 'simctl', 'get_app_container', sim, native.BUNDLE, 'data'], output=True))
            path = container / 'Documents' / 'ladder-report.json'
            fixture_args = SimpleNamespace(base_url=None, media_seconds=24, slots=2, ffmpeg=args.ffmpeg,
                                           ffprobe=args.ffprobe, extended=False, share_sources=True,
                                           live_hd=True, scenario='live-ladder')
            with streaming.owned_fixture(fixture_args) as fixture:
                viewer = fixture.viewer(1)
                viewer.headers['X-WMM-Fixture-Key'] = fixture.manifest['controlKey']
                channel = viewer.call('GET', '/api/live-tv/channels')['items'][0]['id']
                view = viewer.call('POST', '/_fixture/streaming/live-output', {
                    'channelId': channel, 'quality': '720', 'requestId': 'native-live', 'ladder': True})
                try:
                    token = viewer.call('POST', '/api/auth/media-token')['token']
                    address = fixture.base + view['url'] + '?access_token=' + urllib.parse.quote(token)
                    with viewer.open('GET', address) as response:
                        master = response.read(65536).decode()
                    rates = [int(x) for x in re.findall(r'#EXT-X-STREAM-INF:BANDWIDTH=(\d+)', master)]
                    if len(rates) != 2:
                        raise RuntimeError('Live master lacks two qualified rungs')
                    low, high = sorted(rates)
                    result['advertisedBitrates'] = [low, high]
                    fixture.profile(aggregate=40_000_000)
                    launch_player(sim, address)
                    def heartbeat():
                        viewer.call('PUT', '/api/live-tv/sessions/' + view['id'], {'paused': False})

                    fast_ready = lambda s: (high in s['indicatedBitrates'] and s['positionSeconds'] > 15 and
                                             s.get('bufferedAheadSeconds', 0) >= 20 and
                                             s.get('sampledVideoFrames', 0) >= 6 and s.get('liveStartFinished'))
                    fast = wait_state(path, fast_ready, 100, heartbeat)
                    result['stages']['fast'] = fast
                    if not fast or not fast_ready(fast):
                        raise RuntimeError('Native Live TV did not establish high quality with a 20-second live buffer')
                    fixture.profile(aggregate=1_600_000)
                    slow = wait_state(path, lambda s: native.has_sequence(s['indicatedBitrates'], [high, low]) and
                                      s['positionSeconds'] > fast['positionSeconds'] + 35, 120, heartbeat)
                    result['stages']['slow'] = slow
                    if not slow or not native.has_sequence(slow['indicatedBitrates'], [high, low]):
                        raise RuntimeError('Native Live TV did not lower quality')
                    fixture.profile(aggregate=40_000_000)
                    recovered = wait_state(path, lambda s: native.has_sequence(s['indicatedBitrates'], [high, low, high]) and
                                           s['positionSeconds'] > slow['positionSeconds'] + 35, 105, heartbeat)
                    result['stages']['recovered'] = recovered
                    if not recovered or not native.has_sequence(recovered['indicatedBitrates'], [high, low, high]):
                        raise RuntimeError('Native Live TV did not recover quality')
                    validate_video_stages((fast, slow, recovered))
                    if (slow['positionSeconds'] < fast['positionSeconds'] + 30 or
                            recovered['positionSeconds'] < slow['positionSeconds'] + 30):
                        raise RuntimeError('Live playback did not advance across bandwidth changes')
                    result['status'] = 'observed'
                finally:
                    subprocess.run(['xcrun', 'simctl', 'terminate', sim, native.BUNDLE], capture_output=True)
                    viewer.call('DELETE', '/api/live-tv/sessions/' + view['id'])
                    fixture.profile()
                    result['finalPlaybackClaims'] = fixture.metrics()['playbackClaims']
                    if result['finalPlaybackClaims']:
                        result['status'] = 'failed'
                        result['errors'].append('Stopped Live TV retained encoder claims')
        except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
            result['status'] = 'failed'
            result['errors'].append(str(error)[:300])
        finally:
            if sim:
                subprocess.run(['xcrun', 'simctl', 'shutdown', sim], capture_output=True)
                subprocess.run(['xcrun', 'simctl', 'delete', sim], capture_output=True)
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', default='ffmpeg')
    parser.add_argument('--ffprobe', default='ffprobe')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        result = run(args)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        result = {'status': 'failed', 'errors': [str(error)[:300]]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(f'Wrote native live report to {args.output}')
    return 0 if result['status'] == 'observed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
