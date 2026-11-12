import subprocess
import unittest
from unittest import mock

import live_ladder_ios as runner


class NativeLiveEvidenceTest(unittest.TestCase):
    def test_launch_timeout_does_not_expose_media_token(self):
        address = 'http://127.0.0.1:1234/api/live-tv/sessions/fictional/live.m3u8?access_token=fictional-secret'
        error = subprocess.TimeoutExpired(['xcrun', 'simctl', 'launch', address], 30)
        with mock.patch.object(runner.subprocess, 'run', side_effect=error):
            with self.assertRaisesRegex(RuntimeError, '^Native fixture player launch timed out$'):
                runner.launch_player('fictional-simulator', address)

    @staticmethod
    def stages():
        return [{'status': 'ready', 'error': '', 'stalls': 0, 'positionSeconds': position,
                 'lastVideoSeconds': position - .1, 'sampledVideoFrames': count}
                for position, count in ((16, 8), (52, 26), (88, 44))]

    def test_new_frames_and_playback_advance_across_each_stage(self):
        runner.validate_video_stages(self.stages())

    def test_access_log_and_audio_clock_cannot_hide_frozen_video(self):
        for change in ('unchanged-frame-count', 'stale-video-time', 'stall'):
            with self.subTest(change=change):
                stages = self.stages()
                if change == 'unchanged-frame-count':
                    stages[1]['sampledVideoFrames'] = stages[0]['sampledVideoFrames']
                elif change == 'stale-video-time':
                    stages[1]['lastVideoSeconds'] = stages[0]['lastVideoSeconds']
                else:
                    stages[1]['stalls'] = 1
                with self.assertRaises(RuntimeError):
                    runner.validate_video_stages(stages)
