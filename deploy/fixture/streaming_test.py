import contextlib
import importlib.util
import io
import json
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('streaming_harness', Path(__file__).with_name('streaming.py'))
streaming = importlib.util.module_from_spec(spec)
spec.loader.exec_module(streaming)


@contextlib.contextmanager
def stand_in(marker=streaming.MARKER):
    requests = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def do_GET(self):
            requests.append(('GET', self.path, None, dict(self.headers)))
            if self.path == '/_fixture/status':
                self.send_response(200)
                self.end_headers()
                self.wfile.write(json.dumps({
                    'fixture': marker, 'baseUrl': f'http://127.0.0.1:{self.server.server_port}',
                    'controlKey': 'f' * 48, 'serverId': 'fictional-run', 'pid': 123,
                    'accounts': [f'Fictional Viewer {n}' for n in range(1, 18)],
                    'files': [{'kind': 'baseline', 'id': 1}]}).encode())
            elif self.path == '/api/files/1/stream':
                self.send_response(302)
                self.send_header('Location', f'http://127.0.0.1:{self.server.server_port}/api/redirect-target')
                self.end_headers()
            else:
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{}')

        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))))
            requests.append(('POST', self.path, body, dict(self.headers)))
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{}')

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}', requests
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


class FixtureAuthorityTest(unittest.TestCase):
    def test_non_literal_loopback_or_credentialed_targets_are_rejected(self):
        for url in ['https://127.0.0.1:80', 'http://localhost:80', 'http://192.0.2.1:80',
                    'http://user@127.0.0.1:80', 'http://127.0.0.1:80/path',
                    'http://127.0.0.1:80?token=private', 'http://127.0.0.1:80/#x']:
            with self.subTest(url=url), self.assertRaises(ValueError):
                streaming.Fixture(url)

    def test_marker_is_checked_before_credentials_or_mutations(self):
        with stand_in('dvr-fixture-v1') as (base, requests):
            with self.assertRaises(ValueError):
                streaming.Fixture(base)
            self.assertEqual([(r[0], r[1]) for r in requests], [('GET', '/_fixture/status')])

    def test_verified_fixture_keeps_control_key_out_of_viewer_requests(self):
        with stand_in() as (base, requests):
            fixture = streaming.Fixture(base)
            fixture.profile(12_000_000, {'viewer-1': {'bps': 5_000_000}})
            fixture.viewer(2, account_index=1)
            control, login = requests[1:]
            self.assertEqual(control[2]['aggregate']['bps'], 12_000_000)
            self.assertEqual(control[3]['X-Wmm-Fixture-Key'], 'f' * 48)
            self.assertEqual(login[2]['name'], 'Fictional Viewer 1')
            self.assertNotIn('X-Wmm-Fixture-Key', login[3])
            self.assertEqual(login[3]['X-Wmm-Fixture-Viewer'], 'viewer-2')

    def test_redirect_is_not_followed_even_within_loopback(self):
        with stand_in() as (base, requests):
            with self.assertRaises(streaming.HTTPFailure) as failure:
                streaming.Client(base).call('GET', '/api/files/1/stream')
            self.assertEqual(failure.exception.status, 302)
            self.assertEqual(len(requests), 1)

    def test_manifest_media_cannot_escape_the_verified_origin(self):
        client = streaming.Client('http://127.0.0.1:1234')
        for target in ['http://127.0.0.1:1235/api/file', 'http://192.0.2.1/api/file',
                       '//example.invalid/api/file', '/etc/passwd', 'file:///tmp/example']:
            with self.subTest(target=target), self.assertRaises(ValueError):
                client.url(target)


class PlaybackModelTest(unittest.TestCase):
    def test_private_live_ladder_propagates_report_status(self):
        fixture = mock.Mock()
        fixture.manifest = {key: [] for key in ('ffmpegVersion', 'ffprobeVersion', 'files', 'qualifications')}
        report = {'cases': []}
        with mock.patch.object(streaming, 'owned_fixture', return_value=contextlib.nullcontext(fixture)), \
             mock.patch.object(streaming, 'run_live_ladder', return_value={'status': 'failed'}), \
             mock.patch.object(streaming.subprocess, 'check_output', return_value='fictional'):
            streaming.execute(mock.Mock(scenario='live-ladder'), [], report)
        self.assertEqual(report['status'], 'failed')


    def test_long_recovery_gate_requires_upshift_after_fresh_buffered_evidence(self):
        case = {'scenario': 'individual-50-5-50', 'viewers': [{
            'viewer': 'viewer-1',
            'modes': [{'step': 'original', 'atMs': 0}, {'step': '480', 'atMs': 227_000},
                      {'step': '720', 'atMs': 469_000}],
            'measurements': [{'atMs': 468_000, 'state': 'playing', 'bufferedMs': 30_000,
                              'throughputBps': 50_000_000, 'throughputAgeMs': 2000}],
        }]}
        self.assertEqual(streaming.recovery_assertions(case, 190), [])
        case['viewers'][0]['modes'].pop()
        self.assertTrue(any('did not recover' in error for error in streaming.recovery_assertions(case, 190)))
        case['viewers'][0]['modes'].append({'step': '720', 'atMs': 469_000})
        case['viewers'][0]['measurements'][0]['throughputAgeMs'] = 25_000
        self.assertTrue(any('fresh' in error for error in streaming.recovery_assertions(case, 190)))
        self.assertEqual(streaming.recovery_assertions(case, 30), [])

    def test_shared_recovery_gate_rejects_upgrade_stampede(self):
        first = {'viewer': 'viewer-1',
                 'modes': [{'step': 'original', 'atMs': 0}, {'step': '480', 'atMs': 220_000},
                           {'step': '720', 'atMs': 460_000}],
                 'measurements': [{'atMs': 459_000, 'state': 'playing', 'bufferedMs': 30_000,
                                   'throughputBps': 40_000_000, 'throughputAgeMs': 1000}]}
        second = {'viewer': 'viewer-2',
                  'modes': [{'step': 'original', 'atMs': 0}, {'step': '480', 'atMs': 220_000},
                            {'step': '720', 'atMs': 461_000}],
                  'measurements': [{'atMs': 460_000, 'state': 'playing', 'bufferedMs': 30_000,
                                    'throughputBps': 40_000_000, 'throughputAgeMs': 1000}]}
        case = {'scenario': 'shared-40-12-40', 'viewers': [first, second]}
        self.assertTrue(any('upgraded together' in error for error in streaming.recovery_assertions(case, 190)))
        second['modes'][-1]['atMs'] = 480_000
        second['measurements'][0]['atMs'] = 479_000
        self.assertEqual(streaming.recovery_assertions(case, 190), [])

    def test_recovery_reload_keeps_play_and_sends_directive_generation(self):
        fixture = mock.Mock()
        fixture.viewer.return_value.call.side_effect = [
            {'playId': 'fictional-play', 'mode': 'direct', 'protocol': 'file',
             'durationMs': 600_000, 'bitrate': 8_000_000},
            {'playId': 'fictional-play', 'mode': 'transcode', 'protocol': 'hls',
             'durationMs': 600_000, 'bitrate': 2_000_000, 'auto': {'step': '480'}},
        ]
        player = streaming.Player(fixture, 1, {'id': 1, 'kind': 'baseline', 'audio': 'aac'}, auto=True, recovery=True)
        player.began = streaming.time.monotonic()
        player.request_decision()
        player.position = 35
        player.request_decision(7)
        first, second = [call.args[2] for call in fixture.viewer.return_value.call.call_args_list]
        self.assertTrue(first['autoRecovery'])
        self.assertNotIn('switchGeneration', first)
        self.assertEqual(second['playId'], 'fictional-play')
        self.assertEqual(second['switchGeneration'], 7)
        self.assertEqual(second['startMs'], 35_000)
        self.assertEqual(player.pending_ack, 7)
        self.assertEqual([mode['step'] for mode in player.result['modes']], ['original', '480'])

    def test_preparation_assertions_detect_unbounded_lead_and_missing_ranges(self):
        cache = {'temporaryBytes': 1024, 'maxCacheBytes': 4096, 'maxSessionBytes': 2048,
                 'eventAheadMs': 32000, 'scanComplete': True,
                 'sessions': [{'bytes': 1024}]}
        def sample(lead, ranges):
            return {'playbackCacheMeasured': True, 'playbackCache': cache,
                    'sessions': [{'preparation': {'playlist': 'event', 'ranges': ranges,
                                                  'leadMs': lead}}]}
        ranges = [{'startMs': 0, 'endMs': 32000}]
        self.assertEqual(streaming.preparation_errors([sample(28000, ranges)], 'event'), [])
        self.assertTrue(streaming.preparation_errors([sample(44000, ranges)], 'event'))
        self.assertTrue(streaming.preparation_errors([sample(None, [])], 'event'))

    def test_vod_reload_seeks_to_the_current_segment_and_credits_only_its_remaining_time(self):
        segments = [(f'v{n}.m4s', 6) for n in range(10)]
        self.assertEqual(streaming.next_segment(segments, set(), 0, 35), ('v5.m4s', 1))
        self.assertEqual(streaming.next_segment(segments, {'v5.m4s'}, 0, 35), ('v6.m4s', 6))
        self.assertEqual(streaming.next_segment([('seg0.m4s', 6)], set(), 35, 35), ('seg0.m4s', 6))
        self.assertIsNone(streaming.next_segment(segments, set(), 0, 60))

    def test_group_cancels_every_reader_before_joining_and_uses_one_deadline(self):
        viewers = [mock.Mock(), mock.Mock(), mock.Mock()]
        deadlines = []
        def stop(deadline):
            self.assertTrue(all(v.interrupt.called for v in viewers))
            deadlines.append(deadline)
        for viewer in viewers:
            viewer.stop.side_effect = stop
        streaming.stop_all(viewers)
        self.assertEqual(len(deadlines), 3)
        self.assertEqual(len(set(deadlines)), 1)

    def test_live_busy_is_distinguished_from_other_conflicts_and_offline_errors(self):
        for status, reason, busy in [(409, 'tuners_busy', True), (503, 'server_busy', True),
                                     (409, None, False), (503, None, False)]:
            with self.subTest(status=status, reason=reason):
                fixture = mock.Mock()
                fixture.viewer.return_value.call.side_effect = streaming.HTTPFailure(status, reason)
                viewer = streaming.LiveViewer(fixture, 1, 'fictional-channel')
                viewer.run(mock.Mock())
                self.assertEqual(viewer.result.get('busy', False), busy)
                self.assertEqual(bool(viewer.result['errors']), not busy)

    def test_observed_byte_window_detects_an_aggregate_cap_bypass(self):
        def sample(at, delivered, revision=1):
            return {'traffic': {'atNs': at, 'revision': revision,
                                'profile': {'aggregate': {'bps': 1_000_000}},
                                'burstBytes': 32768, 'total': {'bytes': delivered, 'active': 1},
                                'byViewer': {}}}
        self.assertEqual(streaming.shaping_errors([sample(0, 0), sample(100_000_000, 10000)]), [])
        self.assertTrue(streaming.shaping_errors([sample(0, 0), sample(100_000_000, 100000)]))
        # A rate-transition window needs the transition timestamp; it must
        # not be judged using only the later rate.
        self.assertEqual(streaming.shaping_errors([sample(0, 0), sample(100_000_000, 100000, 2)]), [])

    def test_master_and_media_playlist_keep_init_and_segment_durations(self):
        source = 'http://127.0.0.1:1234/api/playback/fictional/master.m3u8'
        variant, _, _, _ = streaming.media_playlist('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=8000000\nmedia.m3u8\n', source)
        self.assertEqual(variant, source.replace('master', 'media'))
        _, init, segments, ended = streaming.media_playlist(
            '#EXTM3U\n#EXT-X-MAP:URI="init.mp4"\n#EXTINF:6.006,\nv00000.m4s\n#EXT-X-ENDLIST\n', variant)
        self.assertEqual(init, source.replace('master.m3u8', 'init.mp4'))
        self.assertEqual(segments, [(source.replace('master.m3u8', 'v00000.m4s'), 6.006)])
        self.assertTrue(ended)

    def test_same_file_can_exercise_four_playback_modes_without_forged_probe_data(self):
        file = {'kind': 'eac3', 'audio': 'eac3'}
        direct = streaming.profile_for('direct', file)
        remux = streaming.profile_for('remux', file)
        audio = streaming.profile_for('audio', file)
        transcode = streaming.profile_for('transcode', file)
        self.assertIn('mkv', direct['containers'])
        self.assertIn('eac3', direct['audioCodecs'])
        self.assertNotIn('mkv', remux['containers'])
        self.assertIn('eac3', remux['audioCodecs'])
        self.assertNotIn('eac3', audio['audioCodecs'])
        self.assertLess(transcode['maxHeight'], 180)

    def test_capacity_sampler_does_not_return_unrelated_processes_or_paths(self):
        completed = mock.Mock(stdout='10 1 /private/unrelated\n20 10 /bin/fixture\n21 20 /tools/ffmpeg\n22 21 /tools/helper\n30 1 /secret/ffprobe\n')
        with mock.patch.object(streaming.subprocess, 'run', return_value=completed):
            self.assertEqual(streaming.owned_process_counts(20), {'ffmpeg': 1, 'ffprobe': 0, 'other': 1})

    def test_invalid_duration_is_rejected_before_any_fixture_is_started(self):
        with mock.patch.object(streaming, 'owned_fixture') as fixture:
            with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
                streaming.main(['--scenario', 'bandwidth', '--phase-seconds', '100', '--media-seconds', '30'])
            fixture.assert_not_called()

    def test_startup_failure_still_writes_a_machine_readable_failed_report(self):
        with tempfile.TemporaryDirectory() as scratch:
            output = Path(scratch) / 'report.json'
            with mock.patch.object(streaming, 'owned_fixture', side_effect=RuntimeError('fictional startup failure')):
                with contextlib.redirect_stderr(io.StringIO()):
                    status = streaming.main(['--scenario', 'preparation', '--output', str(output)])
            report = json.loads(output.read_text())
            self.assertEqual(status, 1)
            self.assertEqual(report['status'], 'failed')
            self.assertEqual(report['cases'], [])
            self.assertEqual(report['runnerError']['type'], 'RuntimeError')


if __name__ == '__main__':
    unittest.main()
