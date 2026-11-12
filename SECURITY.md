# Security

Report suspected vulnerabilities privately to
[eldwicksoftware@gmail.com](mailto:eldwicksoftware@gmail.com), the project's
published contact. Include affected server/app versions, a minimal reproduction
using synthetic data, expected behavior, and the impact you observed. Do not
send passwords, session tokens, private keys, personal library listings or
production databases. Avoid public exploit details until a fix can be prepared.

The server is designed for a trusted administrator sharing media with viewers.
An administrator can configure library paths and server integrations; do not
make an untrusted viewer an administrator. Keep the server and ffmpeg current.
Run a native server as a dedicated unprivileged account with read-only access to
its media libraries and write access only to its data and transcode directories.
A reverse proxy terminates HTTPS; it does not sandbox ffmpeg or the native process.

For a public server behind SWAG, trust only the proxy's actual connection address
and firewall the backend port against other sources. Do not trust an entire LAN
or Docker subnet merely because SWAG uses one address there. Follow the
[deployment instructions](README.md#install) and test remote sign-in after changes.

Media files and library directories must come from trusted sources. Direct file
streaming uses a confined filesystem root; scanners reject media symlinks and
parser paths are checked for library escapes. These checks are not an OS sandbox
for hostile media parsers or a local writer racing filesystem changes. Use OS or
container isolation to limit their access to the host and network.

CI uses disposable machines; persistent release machines must not accept
workflows from untrusted refs.

The code is licensed under the GNU Affero General Public License, version 3
([LICENSE](LICENSE)).
