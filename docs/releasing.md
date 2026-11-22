# Releasing

How a version is tagged, and what the release workflow builds, signs and uploads for each
platform. Only repository admins can push a release tag. Building and testing from source is
in [development.md](development.md).

## Cutting a release

The server and every app take their version from one git tag, `vMAJOR.MINOR.PATCH`, with
`-alpha.N`, `-beta.N` or `-rc.N` for a pre-release.
Add the release's section to [CHANGELOG.md](../CHANGELOG.md) on `main`, then run
`make release-tag VERSION=v1.2.3`. It tags `origin/main`, not this checkout, once it has
checked that the version is valid and newer than every tag so far and that the changelog
has its notes, and it asks before pushing. Only repository admins can push a `v*` tag.
The tag starts `.github/workflows/release.yml`, which creates the
GitHub release with those notes (as a pre-release for a suffixed tag); each job then adds
its files: the server archives, the Linux packages, the DMG, the APK and the Windows
installer. It also pushes
the Docker image and uploads the iPhone app to TestFlight. The Mac app's build number,
which Sparkle compares, is the number the iPhone and
Android builds use (`apple/scripts/version.sh`: 1.2.3 is 10200399).

The workflow runs on GitHub's runners and restores nothing from a cache. Its signing keys
and API keys are secrets of the `release` environment (`windows-signing` for the Windows
job), which admit only `v*` tags; a build without them still runs, unsigned, and says so.
The Linux packages are the exception: without their key the job fails and the release has
none.

A GitHub release on its own is not what people are offered. The website, the servers' Check
for updates and the Mac and Windows apps' updaters read <https://downloads.watchmy.media>,
which also serves the apt and dnf repositories; the maintainers publish to it from the
release's files once it is built.

## Releasing the Mac app

The `macos` job in `.github/workflows/release.yml` builds a universal DMG on every `v*`
tag. It signs, notarises and staples the app and the DMG when these `release` environment
secrets exist, and otherwise uploads an ad-hoc-signed DMG:

- `MACOS_CERTIFICATE_P12` and `MACOS_CERTIFICATE_PASSWORD`: a **Developer ID Application**
  certificate (developer.apple.com → Certificates; not Apple Distribution, which is for
  the App Store) exported from Keychain Access as a `.p12`, then `base64 -i cert.p12`.
- `NOTARY_KEY_ID`, `NOTARY_ISSUER_ID`, `NOTARY_KEY_P8`: an App Store Connect API key
  (Users and Access → Integrations → Team Keys, Developer role); the `.p8` file as
  base64. `xcrun notarytool` uses it; no Apple ID password is involved.
- The repository variables `SPARKLE_PUBLIC_KEY` and `SPARKLE_FEED_URL`
  (`https://downloads.watchmy.media/mac/appcast.xml`): the public half of the key from
  Sparkle's `generate_keys`, and where the app looks for updates. With them the app checks
  for updates itself; without them it has no update item. The private half signs the feed
  and is not kept in this repository.

Locally, `make macos-dmg` does the same: it signs with the first Developer ID in your
keychain (or `CODESIGN_IDENTITY`), and notarises when `NOTARY_KEY_ID`, `NOTARY_ISSUER_ID`
and `NOTARY_KEY_PATH` are set, or `NOTARY_KEYCHAIN_PROFILE` names a profile saved with
`xcrun notarytool store-credentials`.

## Releasing the Linux packages

The `packages` job in `.github/workflows/release.yml` builds the `.deb` and the `.rpm`, for
amd64 and arm64, on every `v*` tag. It runs [deploy/linux/release.sh](../deploy/linux/release.sh),
which packs the server and the FFmpeg from the release's own Linux archives, so a package
holds the same files as the archive beside it.

- **Signing.** The `.rpm` files are signed with the `release` environment secret
  `LINUX_PACKAGE_SIGNING_KEY`: the packages' signing subkey of the key whose public half is
  [deploy/linux/repository-key.asc](../deploy/linux/repository-key.asc), exported alone and
  armored, with no passphrase. The workflow names that subkey by its id, since the key has a
  second signing subkey for the repositories. The job checks each signature with rpm's own
  `rpmkeys` against the committed public key, and reads from the package which subkey made
  it, before it attaches anything. It fails without the secret: Fedora 45's `rpm -i` refuses
  an unsigned package. A `.deb` is not signed; apt trusts the repository's signed list.
- **The repositories' lists.** A release also gets
  `watchmymedia-linux-repositories-<version>.tar.gz`: the apt and dnf lists for that
  release's packages, written by `apt-ftparchive` and `createrepo_c`
  ([deploy/linux/repository.sh](../deploy/linux/repository.sh)) and laid out as
  downloads.watchmy.media serves them (`apt/dists/stable/…`, `rpm/<arch>/repodata/…`). They
  are not signed. The maintainers sign `Release` and `repomd.xml` when they publish, with
  the key's other subkey, which is never in this repository.
- **Pre-releases.** A `-rc.N` tag gets the four packages and no lists, and they stay on the
  GitHub release. Their version is `1.2.3~rc.1`, which sorts before `1.2.3`; the files are
  named `watchmymedia_1.2.3.rc.1_<arch>.deb` and `watchmymedia-1.2.3.rc.1-1.<arch>.rpm`,
  because GitHub replaces `~` in a file's name.

`deploy/linux/release.sh` runs on Linux only (it needs `rpmkeys`, `apt-ftparchive` and
`createrepo_c`); its test, [deploy/linux/release_test.py](../deploy/linux/release_test.py),
drives it from stand-in archives with a throwaway key, and CI runs it.

## Releasing the Android app

The `android` job in `.github/workflows/release.yml` builds the bundle and the APK on every
`v*` tag, attaches the APK to the GitHub release and keeps the bundle as the run's
`android-bundle` artifact. Without the signing secrets both are unsigned (the job summary
says so); with them they are signed, and with `PLAY_SERVICE_ACCOUNT_JSON` as well the bundle
goes to Play's internal testing track. Secrets of the `release` environment:

- `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`: the upload key. Play App Signing keeps the key that signs what
  Play delivers, so this one only proves uploads are yours, and Play Console can reset it if
  it is lost. Make it once, keep the file and password in a password manager, never commit
  it:

  ```bash
  keytool -genkeypair -keystore watchmymedia-upload.jks -storetype PKCS12 \
    -alias upload -keyalg RSA -keysize 4096 -validity 10000
  base64 -i watchmymedia-upload.jks | gh secret set --env release ANDROID_KEYSTORE_BASE64
  gh secret set --env release ANDROID_KEYSTORE_PASSWORD   # prompts
  gh secret set --env release ANDROID_KEY_ALIAS --body upload
  ```

  `ANDROID_KEY_PASSWORD` can stay unset: a PKCS12 keystore uses the store password for the
  key. Locally, the same variables with `ANDROID_KEYSTORE_FILE` (the `.jks` path) sign
  `./gradlew bundleRelease`. The upload key made for 0.1.0 lives in `android/secrets/`
  (gitignored): `watchmymedia-upload.jks` and `upload.env`, which holds its password and
  alias. A signed build from it:

  ```bash
  cd android
  set -a; . secrets/upload.env; set +a
  ANDROID_KEYSTORE_FILE="$PWD/secrets/watchmymedia-upload.jks" ./gradlew bundleRelease assembleRelease -PappVersion=v0.1.0
  ```

  Release builds are minified by R8 (shrunk, optimized and obfuscated), with the app's keep
  rules in `android/app/proguard-rules.pro`. The bundle carries the R8 mapping file, and Play
  uses it to deobfuscate crash reports, so nothing else needs uploading.
- `PLAY_SERVICE_ACCOUNT_JSON`: in Google Cloud, enable the Google Play Android Developer
  API in a project, create a service account there and download a JSON key for it
  (`gh secret set --env release PLAY_SERVICE_ACCOUNT_JSON < key.json`). In Play Console → Users and
  permissions, invite the service account's email and give it "Release to testing tracks"
  for WatchMyMedia.

Play's API cannot create an app or make its first upload: create the app in Play Console
(Play App Signing is on by default) and upload the first bundle by hand from the
`android-bundle` artifact. Until Play has published the app, it only accepts draft
releases from the API; set the repository variable `PLAY_RELEASE_STATUS` to `draft` until
then and roll each release out in Play Console.

## Releasing the iPhone app

The `ios` job in `.github/workflows/release.yml` archives the app on every `v*`
tag and uploads it to TestFlight. `apple/scripts/version.sh` turns the tag into
the version, and the build number follows the Android version code, so
pre-releases sort before their release (`v1.2.3-rc.1` is build 10200361, `v1.2.3`
is build 10200399). From `v2.0.0` the iPhone, iPad and Apple TV apps say the
tag's own version, as the server, the Mac app and Android always have. Before
that they ran ahead of it: the App Store's first release was 1.0, and App Store
Connect takes only a higher version than the last approved one, so
`CFBundleShortVersionString` stayed on 1.x (`v0.5.2` was 1.5.2, `v1.0.0` was
1.6.0, `v1.3.0` was 1.7.0 and `v1.8.0`, the last, was 1.11.0).

The job builds with the Xcode `apple/scripts/select-xcode.sh` names (27.2), on
GitHub's `xcode-27` image, the same Xcode CI's `apple` and `apple-tv` jobs test
with. From April 2027 App Store Connect takes iPhone and iPad builds only from
the iOS 27 SDK or later, and GitHub's `macos-26` image stops at Xcode 26.6.
To move to a newer Xcode, change the version in that script.

Once, by hand, in the Eldwick Software account (team `6R8GZD78G3`):

1. Register the bundle ID `media.watchmy.client` (Certificates, Identifiers &
   Profiles), and create the app in App Store Connect with it. The Apple TV
   app joins the same record (universal purchase). Its one capability is the
   App Group `group.media.watchmy.client`, which the Apple TV app shares with
   its Top Shelf extension, `media.watchmy.client.topshelf`.
2. Make an App Store Connect API key (Users and Access → Integrations → Team
   Keys) whose role may manage certificates and profiles (Admin, or App Manager
   with access to Certificates, Identifiers & Profiles), so `xcodebuild
   -allowProvisioningUpdates` can sign in the cloud. Set the `release` environment's secrets
   `APP_STORE_CONNECT_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID` and
   `APP_STORE_CONNECT_KEY_P8` (the `.p8` file as base64). The notarisation key
   from the Mac release works if its role allows this.

Without the secrets the job builds the app for the simulator and warns. Locally,
`make apple-upload VERSION=v1.2.3` does the same with the Xcode accounts signed in
on this Mac, or with `ASC_KEY_ID`, `ASC_ISSUER_ID` and `ASC_KEY_PATH` set. To archive
in Xcode instead, run `make apple-gen VERSION=v1.2.3` first: otherwise the archive is
the project's placeholder version, 1.0 (1). The Apple TV app uploads with
`make apple-tv-upload VERSION=v1.2.3`, since Xcode's own Archive needs an Apple TV
registered to the team.

Both apps are archived without a certificate and signed only when exported, for
the App Store. Signing the archive needs an Apple Development certificate in the
keychain, and a GitHub-hosted runner starts with none, so Xcode made a new one
through the API key ("Apple Development: Created via API") on every release
until v1.7.0's job failed at the team's limit ("Choose a certificate to
revoke"). The export makes none: with the key it signs in the cloud. An unsigned
archive carries no entitlements, though, and the export signs each app and
extension only with the entitlements in its archived signature, so
`make apple-tv-archive` signs the Apple TV app and its Top Shelf extension ad hoc
with their entitlements files. Without that, Apple TV 1.8.7 to 1.10.0 shipped
without the App Group the two share. Giving the iPhone app a capability means
adding the same step to `make apple-archive`. The certificates earlier releases
made are no use to anyone: their private keys went with the runners.
