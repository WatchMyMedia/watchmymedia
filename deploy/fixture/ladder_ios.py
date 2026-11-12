#!/usr/bin/env python3
"""Run visible iOS AVPlayer ABR against an owned fictional A0 fixture.

This creates and deletes its own simulator. It never signs into a real server,
uses a physical device, or sends a media token to its report.
"""
import argparse
import json
import platform
from pathlib import Path
import shutil
import shlex
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import urllib.parse
import uuid

import streaming

HERE = Path(__file__).resolve().parent
BUNDLE = 'media.watchmy.a0fixture'
LOW, HIGH = 1_100_000, 2_300_000


def command(args, *, timeout=120, output=False):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        # No media URL is passed to this helper except simctl launch below.
        raise RuntimeError(f'{args[0]} failed: {result.stderr[-300:]}')
    return result.stdout.strip() if output else None


def simulator_choices():
    runtimes = json.loads(command(['xcrun', 'simctl', 'list', 'runtimes', '-j'], output=True))['runtimes']
    available = [r for r in runtimes if r['identifier'].startswith('com.apple.CoreSimulator.SimRuntime.iOS-')
                 and r.get('isAvailable', True)]
    if not available:
        raise RuntimeError('No available iOS simulator runtime')
    runtime = max(available, key=lambda r: tuple(int(x) for x in r['version'].split('.')))
    devices = json.loads(command(['xcrun', 'simctl', 'list', 'devicetypes', '-j'], output=True))['devicetypes']
    device = next((d for d in devices if d['name'] == 'iPhone 17'),
                  next((d for d in devices if d['name'].startswith('iPhone')), None))
    if device is None:
        raise RuntimeError('No iPhone simulator device type')
    return runtime, device


def build_app(folder, version):
    app = folder / 'A0Player.app'
    app.mkdir()
    shutil.copyfile(HERE / 'ladder_ios_info.plist', app / 'Info.plist')
    sdk = command(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-path'], output=True)
    target = f'{platform.machine()}-apple-ios{version}-simulator'
    command(['swiftc', '-parse-as-library', '-module-name', 'A0Player', '-sdk', sdk,
             '-target', target, '-framework', 'UIKit', '-framework', 'AVFoundation',
             '-o', str(app / 'A0Player'), str(HERE / 'ladder_ios_player.swift')])
    command(['codesign', '--force', '--sign', '-', str(app)])
    return app


def read_state(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


def has_sequence(values, expected):
    remaining = iter(expected)
    wanted = next(remaining, None)
    for value in values:
        if value == wanted:
            wanted = next(remaining, None)
            if wanted is None:
                return True
    return wanted is None


def run(args):
    runtime, device = simulator_choices()
    result = {'scenario': 'ios-visible-ladder-' + args.scenario, 'runtime': runtime['version'],
              'deviceType': device['name'], 'status': 'failed', 'stages': {}, 'errors': []}
    sim = None
    with tempfile.TemporaryDirectory(prefix='wmm-a0-ios-ladder-') as scratch:
        app = build_app(Path(scratch), runtime['version'])
        playback_ffmpeg = None
        if args.encoder_read_rate:
            wrapper = Path(scratch) / 'slow-ffmpeg'
            executable = shutil.which(args.ffmpeg) or args.ffmpeg
            wrapper.write_text(f'#!/bin/sh\nexec {shlex.quote(executable)} -readrate {args.encoder_read_rate} "$@"\n')
            wrapper.chmod(0o700)
            playback_ffmpeg = str(wrapper)
        try:
            name = 'WMM A0 ' + uuid.uuid4().hex[:8]
            sim = command(['xcrun', 'simctl', 'create', name, device['identifier'], runtime['identifier']], output=True)
            uuid.UUID(sim)
            command(['xcrun', 'simctl', 'boot', sim])
            command(['xcrun', 'simctl', 'bootstatus', sim, '-b'], timeout=180)
            command(['xcrun', 'simctl', 'install', sim, str(app)])
            container = Path(command(['xcrun', 'simctl', 'get_app_container', sim, BUNDLE, 'data'], output=True))
            report_path = container / 'Documents' / 'ladder-report.json'
            fixture_args = SimpleNamespace(base_url=None, media_seconds=240 if args.scenario == 'cold-seek' else 120, slots=2, ffmpeg=args.ffmpeg,
                                           ffprobe=args.ffprobe, extended=False, share_sources=False, scenario='ladder', playback_ffmpeg=playback_ffmpeg)
            with streaming.owned_fixture(fixture_args) as fixture:
                viewer = fixture.viewer(1)
                token = viewer.call('POST', '/api/auth/media-token')['token']
                try:
                    start = fixture.control.call('POST', '/_fixture/streaming/ladder', {'viewer': 'Stream Viewer 1'})
                except streaming.HTTPFailure as error:
                    if not args.encoder_read_rate or error.status != 503 or error.reason != 'ladder_not_ready':
                        raise
                    deadline = time.monotonic() + 5
                    claims = fixture.metrics()['playbackClaims']
                    while claims and time.monotonic() < deadline:
                        time.sleep(.05)
                        claims = fixture.metrics()['playbackClaims']
                    result['rejectedBeforeManifest'] = True
                    result['finalPlaybackClaims'] = claims
                    result['status'] = 'observed' if claims == 0 else 'failed'
                    if claims:
                        result['errors'].append('Slow encoder rejection retained capacity')
                    return result
                try:
                    if args.encoder_read_rate:
                        raise RuntimeError('Slow encoder was advertised instead of retaining single-rendition fallback')
                    fixture.profile(aggregate=40_000_000)
                    address = fixture.base + start['url'] + '?access_token=' + urllib.parse.quote(token)
                    # Do not include the URL in an exception or report: it has a media token.
                    launched = subprocess.run(['xcrun', 'simctl', 'launch', sim, BUNDLE, address],
                                              capture_output=True, text=True, timeout=30)
                    if launched.returncode:
                        raise RuntimeError('Visible simulator player did not launch')
                    deadline = time.monotonic() + 35
                    before = None
                    while time.monotonic() < deadline:
                        time.sleep(1)
                        before = read_state(report_path)
                        if before and HIGH in before['indicatedBitrates'] and before['positionSeconds'] > 12:
                            break
                    result['stages']['fast'] = before
                    if not before or not has_sequence(before['indicatedBitrates'], [LOW, HIGH]):
                        raise RuntimeError('Native player did not select the higher rung on the fast link')
                    if args.scenario == 'cold-seek':
                        command_path = container / 'Documents' / 'ladder-command.json'
                        for target in (180, 12, 228):
                            request_id = uuid.uuid4().hex
                            pending = command_path.with_suffix('.tmp')
                            pending.write_text(json.dumps({'id': request_id, 'seekSeconds': target}))
                            pending.replace(command_path)
                            started = time.monotonic()
                            state = None
                            while time.monotonic() - started < 8:
                                time.sleep(.1)
                                state = read_state(report_path)
                                if (state and state.get('commandID') == request_id and state.get('seekFinished')
                                        and state['positionSeconds'] >= target + 1.5):
                                    break
                            result['stages'][f'seek-{target}'] = state
                            if (not state or state.get('commandID') != request_id or not state.get('seekFinished')
                                    or state['positionSeconds'] < target + 1.5 or state['error']
                                    or state['stalls'] != 0 or state['seekElapsedSeconds'] > 6):
                                raise RuntimeError(f'Native cold seek to {target}s missed readiness or stalled')
                        result['status'] = 'observed'
                        return result
                    fixture.profile(aggregate=500_000)
                    time.sleep(40)
                    slow = read_state(report_path)
                    result['stages']['slow'] = slow
                    if not slow or not has_sequence(slow['indicatedBitrates'], [LOW, HIGH, LOW]):
                        raise RuntimeError('Native player did not lower quality on the slow link')
                    fixture.profile(aggregate=40_000_000)
                    time.sleep(30)
                    recovered = read_state(report_path)
                    result['stages']['recovered'] = recovered
                    if not recovered or not has_sequence(recovered['indicatedBitrates'], [LOW, HIGH, LOW, HIGH]):
                        raise RuntimeError('Native player did not recover quality on the fast link')
                    if (any(stage['status'] != 'ready' or stage['stalls'] != 0 or stage['error']
                            for stage in (before, slow, recovered)) or
                            slow['positionSeconds'] < before['positionSeconds'] + 25 or
                            recovered['positionSeconds'] < slow['positionSeconds'] + 20):
                        raise RuntimeError('Native playback stalled, failed, or stopped advancing')
                    result['status'] = 'observed'
                finally:
                    subprocess.run(['xcrun', 'simctl', 'terminate', sim, BUNDLE], capture_output=True)
                    fixture.control.call('POST', '/_fixture/streaming/ladder/stop', {
                        'playId': start['playId'], 'viewer': 'Stream Viewer 1'})
                    fixture.profile()
                    result['finalPlaybackClaims'] = fixture.metrics()['playbackClaims']
                    deadline = time.monotonic() + 5
                    while result['finalPlaybackClaims'] and time.monotonic() < deadline:
                        time.sleep(.05)
                        result['finalPlaybackClaims'] = fixture.metrics()['playbackClaims']
                    if result['finalPlaybackClaims'] != 0:
                        result['status'] = 'failed'
                        result['errors'].append('Playback claims remained after stop')
        except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
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
    parser.add_argument('--scenario', choices=['40-0.5-40', 'cold-seek'], default='40-0.5-40')
    parser.add_argument('--encoder-read-rate', type=float, choices=[0, 0.7], default=0)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    try:
        report = run(args)
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        report = {'scenario': 'ios-visible-ladder-40-0.5-40', 'status': 'failed',
                  'errors': [str(error)[:300]]}
    encoded = json.dumps(report, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
        print(f'Wrote native ladder report to {args.output}', file=sys.stderr)
    else:
        print(encoded, end='')
    return 0 if report['status'] == 'observed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
