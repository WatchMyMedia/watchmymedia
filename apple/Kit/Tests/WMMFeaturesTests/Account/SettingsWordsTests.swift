import Foundation
import Testing
@testable import WMMFeatures
import WMMModel

/// The words of the redesigned Settings (PLAN-APPLE-SETTINGS.md §6), as the
/// boards and the web word them.
@Suite struct SettingsWordsTests {
    private let en = Locale(identifier: "en")

    @Test func skipsModesAreTheWebsWordsInTheWebsOrder() {
        #expect(SkipMode.allCases == [.none, .ask, .autoDelay, .auto])
        #expect(SkipMode.allCases.map(\.label) == ["Do nothing", "Show a Skip button", "Skip after 3 seconds", "Skip right away"])
        #expect(SkipMode.allCases.map(\.line) == [
            "The part plays through", "For parts 3 seconds or longer", "Shows the button, then skips by itself", "Not for a part you seek into",
        ])
        // The raw values are the server's, unchanged by the order.
        #expect(SkipMode.allCases.map(\.rawValue) == ["none", "ask", "auto-delay", "auto"])
    }

    @Test func thePartsAndTheirLines() {
        #expect(SegmentKind.allCases.map(\.settingsLabel) == ["Intro", "Recap", "Credits", "Preview", "Ads"])
        #expect(SegmentKind.allCases.map(\.settingsHint) == [
            "Opening titles and theme", "“Previously on…”", "End credits; the Next Episode card takes over on the last block",
            "“Next time on…”", "Commercial breaks left in recordings",
        ])
        #expect(SegmentKind.credits.settingsAbout == "The end titles of an episode or a film.")
        #expect(SettingsWords.skipAbout(.credits, mode: .autoDelay) == [
            "The end titles of an episode or a film.",
            "Skip after 3 seconds shows the Skip button, then skips by itself. A part you seek into is never skipped for you.",
            "Kept on the server, so every device you sign in to follows it.",
        ])
    }

    @Test func savedLinesSayWhatTheChangeDid() {
        #expect(SettingsWords.skipSaved(.credits, mode: .autoDelay) == "Saved. Credits now skip after 3 seconds, on every device.")
        #expect(SettingsWords.skipSaved(.intro, mode: .none) == "Saved. Intros now play through, on every device.")
        #expect(SettingsWords.skipSaved(.commercial, mode: .ask) == "Saved. Ads now show a Skip button, on every device.")
        #expect(SettingsWords.nextEpisodeSaved(true) == "Saved. The next episode now plays by itself, on every device.")
        #expect(SettingsWords.nextEpisodeSaved(false) == "Saved. The Next Episode card now waits for you, on every device.")
        #expect(SettingsWords.reminderLeadSaved(5) == "Saved. Reminders now come 5 minutes before, on every device.")
        #expect(SettingsWords.reminderLeadSaved(0) == "Saved. Reminders now come when it starts, on every device.")
        #expect(SettingsWords.subtitleChoiceSaved(.foreign) == "Saved. Subtitles now start when the audio isn’t in your languages, on every device.")
    }

    @Test func aLanguageListsSavedLineNamesWhatMoved() {
        let audio = LanguageList.audio
        #expect(SettingsWords.listSaved(audio, before: ["en"], after: ["en", "ur"], locale: en) == "Saved. Urdu is your second audio language, on every device.")
        #expect(SettingsWords.listSaved(audio, before: [], after: ["en"], locale: en) == "Saved. English is your first audio language, on every device.")
        #expect(SettingsWords.listSaved(audio, before: ["en", "ur"], after: ["en"], locale: en) == "Saved. Urdu is no longer one of your audio languages, on every device.")
        #expect(SettingsWords.listSaved(.subtitles, before: ["en"], after: [], locale: en) == "Saved. You have no subtitle language now, on every device.")
        #expect(SettingsWords.listSaved(audio, before: ["en", "ur", "es"], after: ["en", "es", "ur"], locale: en) == "Saved. Spanish is now your second audio language, on every device.")
        #expect(SettingsWords.listSaved(.subtitles, before: ["en", "ur"], after: ["ur", "en"], locale: en) == "Saved. Urdu is now your first subtitle language, on every device.")
    }

    @Test func pageLedesSayHowThingsStand() {
        #expect(SettingsWords.playbackLede(device: "iPhone") == "Yours, on every device you sign in to. Quality is this iPhone’s alone.")
        #expect(SettingsWords.qualityHeading(device: "Apple TV") == "Quality · this Apple TV")
        #expect(SettingsWords.languagesLede(audio: ["en", "ur"], subtitles: ["en"], choice: .foreign, locale: en)
            == "Yours, on every device. Videos start in English, then Urdu. Subtitles start when the audio isn’t in your languages.")
        #expect(SettingsWords.languagesLede(audio: [], subtitles: [], choice: .forced, locale: en)
            == "Yours, on every device. Until you choose, videos play the file’s default audio and subtitles start only when the file marks them as forced.")
        #expect(SettingsWords.languagesLede(audio: [], subtitles: ["en"], choice: .on, locale: en)
            == "Yours, on every device. Videos play the file’s default audio. Subtitles always start, in your first language that has one.")
        #expect(SettingsWords.deviceUses("en", device: "iPhone", locale: en) == "This iPhone uses English.")
        #expect(SettingsWords.use("en", locale: en) == "Use English")
    }

    @Test func theAccountLedeCountsThisAccountsDevices() {
        let sam = User(id: 1, name: "Sam", isAdmin: true)
        let robin = User(id: 2, name: "Robin", isAdmin: false)
        #expect(SettingsWords.accountLede(user: sam, serverName: "Living room server", devices: 3, device: "iPhone")
            == "Sam, an admin on Living room server. 3 devices are signed in.")
        #expect(SettingsWords.accountLede(user: robin, serverName: "Living room server", devices: 1, device: "iPad")
            == "Robin on Living room server. Only this iPad is signed in.")
        #expect(SettingsWords.accountLede(user: sam, serverName: "Living room server", devices: nil, device: "iPhone")
            == "Sam, an admin on Living room server.")
        #expect(SettingsWords.thisDeviceLine(model: "iPhone 16", system: "iOS 27.1", appVersion: "1.8.6")
            == "iPhone 16 · iOS 27.1 · WatchMyMedia 1.8.6. The name shows in Signed-in devices and in the server’s Activity.")
        #expect(SettingsWords.signOutOfThis("iPhone") == "Sign out of this iPhone")
        #expect(SettingsWords.signOutOfThisLine(device: "iPhone", downloads: 7)
            == "Deletes this iPhone’s 7 downloads. Your account, watch history and settings stay on the server.")
        #expect(SettingsWords.signOutOfThisLine(device: "iPad", downloads: 0) == "Your account, watch history and settings stay on the server.")
    }

    @Test func signingAnotherDeviceOutNamesItAndWhoseItIs() {
        let mine = DeviceSession(id: "a", kind: .device, name: "Kitchen iPad", createdAt: .now, lastSeenAt: .now, current: false, userName: "Sam")
        let words = SettingsWords.signOutDevice(mine, me: "Sam")
        #expect(words.title == "Sign out Kitchen iPad?")
        #expect(words.lines == ["It will need a password or a code to sign in again. Anything it is playing stops."])
        #expect(words.confirm == "Sign out Kitchen iPad")
        #expect(words.keep == "Keep it signed in")

        let theirs = DeviceSession(id: "b", kind: .device, name: "Robin’s iPad", createdAt: .now, lastSeenAt: .now, current: false, userName: "Robin")
        let other = SettingsWords.signOutDevice(theirs, me: "Sam")
        #expect(other.title == "Sign out Robin’s iPad?")
        #expect(other.lines == ["Robin will need their password or a code to sign in on it again. Anything it is playing stops."])
        #expect(other.confirm == "Sign out Robin’s iPad")
    }

    @Test func signingThisDeviceOutCountsWhatItDeletes() {
        let gb: Int64 = 1_073_741_824
        let with = SettingsWords.signOutThisDevice(serverName: "Living room server", device: "iPhone", downloads: 7, bytes: Int64(66.1 * Double(gb)))
        #expect(with.title == "Sign out of Living room server?")
        #expect(with.lines == [
            "Stays on the server: your account, watch history and settings.",
            "Deleted from this iPhone: 7 downloads, 66.1 GB.",
            "To sign in again you’ll need your password, or a code from a signed-in device.",
        ])
        #expect(with.confirm == "Sign out and delete 7 downloads")
        #expect(with.keep == "Stay signed in")

        let without = SettingsWords.signOutThisDevice(serverName: "Living room server", device: "iPhone", downloads: 0, bytes: 0)
        #expect(without.lines == [
            "Your account, watch history and settings stay on the server. Nothing is deleted from this iPhone.",
            "To sign in again you’ll need your password, or a code from a signed-in device.",
        ])
        #expect(without.confirm == "Sign out of this iPhone")

        let one = SettingsWords.signOutThisDevice(serverName: nil, device: "iPad", downloads: 1, bytes: gb)
        #expect(one.title == "Sign out?")
        #expect(one.confirm == "Sign out and delete 1 download")
        #expect(one.lines[1] == "Deleted from this iPad: 1 download, 1.0 GB.")

        let tv = SettingsWords.signOutThisAppleTV(serverName: "Living room server")
        #expect(tv.lines == [
            "Your account, watch history and settings stay on the server.",
            "This Apple TV will need a new code, or your password, to sign in again.",
        ])
        #expect(tv.confirm == "Sign out of this Apple TV")
        #expect(tv.keep == "Stay signed in")
        #expect(tv.message == tv.lines.joined(separator: "\n\n"))
    }

    @Test func signOutOutcomesSayWhatHappened() {
        #expect(SettingsWords.deviceSignedOut("Kitchen iPad") == "Kitchen iPad was signed out. It needs a password or a code to sign in again.")
        #expect(SettingsWords.deviceSignOutFailed("Kitchen iPad", status: nil) == "Couldn’t sign Kitchen iPad out, so it’s still signed in. Check your connection and try again.")
        #expect(SettingsWords.deviceAlreadyGone("Kitchen iPad") == "Kitchen iPad was already signed out. The list is up to date.")
    }

    @Test func removeAllDownloadsAndCancelARequestAskFirst() {
        let gb: Int64 = 1_073_741_824
        let all = SettingsWords.removeAllDownloads(count: 7, bytes: Int64(66.1 * Double(gb)), device: "iPhone")
        #expect(all.title == "Remove all 7 downloads?")
        #expect(all.lines == ["Frees 66.1 GB on this iPhone. Your watched marks and resume points stay, and you can download them again while they are in the library."])
        #expect(all.confirm == "Remove 7 downloads")
        #expect(all.keep == "Keep downloads")
        let one = SettingsWords.removeAllDownloads(count: 1, bytes: gb, device: "iPad")
        #expect(one.title == "Remove 1 download?")
        #expect(one.confirm == "Remove 1 download")

        let request = SettingsWords.cancelRequest(title: "Coastline", viaSeerr: false)
        #expect(request.title == "Cancel your request for Coastline?")
        #expect(request.lines == ["It hasn’t been approved yet, so nothing has been downloaded. Admins stop seeing it, and you can ask again later."])
        #expect(request.confirm == "Cancel request")
        #expect(request.keep == "Keep request")
        #expect(SettingsWords.cancelRequest(title: "Coastline", viaSeerr: true).lines == ["It hasn’t been approved yet, so nothing has been downloaded. It’s deleted in Seerr too, and you can ask again later."])
    }

    @Test func renameWords() {
        #expect(SettingsWords.renameTitle(device: "iPhone") == "Rename this iPhone")
        #expect(SettingsWords.renameOff(keeping: "Sam’s iPhone") == "Rename is off until there is a name. Cancel keeps Sam’s iPhone.")
        #expect(SettingsWords.nameTitle(device: "Apple TV") == "Name this Apple TV")
        #expect(SettingsWords.roomNames(device: "Apple TV", current: "Living room Apple TV") == [
            "Living room Apple TV", "Bedroom Apple TV", "Kitchen Apple TV", "Office Apple TV", "Den Apple TV",
            "Basement Apple TV", "Kids’ room Apple TV", "Guest room Apple TV",
        ])
        #expect(SettingsWords.roomNames(device: "Apple TV", current: "Sam’s Apple TV").prefix(2) == ["Sam’s Apple TV", "Living room Apple TV"])
        #expect(SettingsWords.roomNamesHint(keeping: "Living room Apple TV")
            == "Select a name to save it · Menu goes back to Account & devices and keeps Living room Apple TV")
    }

    @Test func namesAndTheKeptOutPages() {
        #expect(SettingsWords.serverSettings == "Server settings")
        #expect(SettingsWords.aboutApp == "About WatchMyMedia")
        #expect(SettingsWords.nextEpisodeTitle == "Play the next episode by itself")
        let server = Server(id: "s", name: "Living room server", baseURL: URL(string: "https://home.example.net")!, version: "1.4.5", apiLevel: 28)
        #expect(SettingsWords.serverLede(server, user: User(id: 1, name: "Sam", isAdmin: true)) == "Living room server, v1.4.5. You’re signed in as Sam, an admin.")
        #expect(SettingsWords.serverLede(server, user: User(id: 2, name: "Sam", isAdmin: false)) == "Living room server, v1.4.5. You’re signed in as Sam.")
        #expect(SettingsWords.requestsOff.title == "Requests are off on this server")
        #expect(SettingsWords.downloadsOff.who == "An admin chooses who can download in the web app, under Server settings › Users.")
        let old = SettingsWords.serverTooOld(serverName: "Living room server", version: "0.9.3", doing: "Choosing audio and subtitle languages",
                                             needs: "1.1.0", meanwhile: "Until then, pick tracks in the player.")
        #expect(old.title == "This server needs an update first")
        #expect(old.body == "Living room server is on v0.9.3. Choosing audio and subtitle languages needs v1.1.0 or newer. Until then, pick tracks in the player.")
        #expect(old.who == "An admin can update the server. The steps are at watchmy.media/guide.")
        #expect(andList(["English"]) == "English")
        #expect(andList(["Living room TV", "Kitchen iPad", "Chrome on macOS"]) == "Living room TV, Kitchen iPad and Chrome on macOS")
    }
}
