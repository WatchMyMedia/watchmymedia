# About the bundled ffmpeg

The `ffmpeg` and `ffprobe` shipped with WatchMyMedia (next to this file in
the release tarball or zip; in `Contents/Helpers` of the Mac app) are the
portable macOS or Windows build of
[jellyfin-ffmpeg](https://github.com/jellyfin/jellyfin-ffmpeg), unchanged;
`VERSION` (`FFMPEG-VERSION` in the Mac app) names the release.
WatchMyMedia runs them as separate programs and is not linked against them.

That build is licensed under the GNU General Public License, version 3 (it is
configured with `--enable-gpl` and includes x264 and x265). The complete
corresponding source, including the build scripts, is published with each
release at <https://github.com/jellyfin/jellyfin-ffmpeg/releases>; the tag
matching `VERSION` is the one used here. The GPL text is at
<https://www.gnu.org/licenses/gpl-3.0.txt>.

To use a different ffmpeg, set `ffmpeg.ffmpeg` and `ffmpeg.ffprobe` in
`config.yaml`, or `WATCHMYMEDIA_FFMPEG_PATH` and `WATCHMYMEDIA_FFPROBE_PATH`;
a bare name is looked for next to the server binary first, then on `PATH`.
