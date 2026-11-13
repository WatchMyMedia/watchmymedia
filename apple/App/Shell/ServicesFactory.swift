import Foundation
import UIKit
import WMMData
import WMMDownloads
#if DEBUG
import WMMFakes
#endif
import WMMModel
import WMMPlayback

/// Builds the services the app runs on. `-fakes` runs on the mockups'
/// library with no server (any name, password `password`), as Sam’s iPhone or,
/// on an iPad, Sam’s iPad; `-fakesSignedOut` starts at sign-in and
/// `-fakesNoServer` at the server picker. `-fakesMember` signs in as Alex, a
/// member whose requests wait for an admin. `-fakesLiveTV` adds Live TV's
/// channels without listings; `-fakesLiveGuide` adds them with the guide
/// boards' listings, and `-fakesLiveGuideStale`, `-fakesLiveGuideTMDB` and
/// `-fakesLiveGuideNoPictures` pick the boards' Tweaks. `-fakesTrailerKeys a,b`
/// gives the fake movies those YouTube trailers. The fakes' downloads
/// are `FakeDownloadRepository`'s: one movie downloaded, one episode coming
/// and one removed from the server.
/// `-fakesAudioLanguages` makes the server take audio languages (API level
/// 21); followed by a list ("es,en") the person has saved those, with
/// subtitles for foreign audio, as the server sets them. `-fakesRows` puts
/// the server at API level 22: Home and the library screens come arranged,
/// with list rows and list pages (PLAN-ROWS.md).
@MainActor
enum ServicesFactory {
    static func make(arguments: [String]) -> AppServices {
        #if DEBUG
        if let services = fakes(arguments) { return services }
        #endif
        let data = DataServices.live()
        let profile = DeviceProfileProvider()
        let device = UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        // Downloads (PLAN-DOWNLOADS.md D3): made at launch so the background sessions reconnect.
        let downloads = DownloadServices.live(
            gateway: data.offline, online: APIPlaybackGateway(api: { try data.api() }, apiLevel: { data.apiLevel }),
            client: { data.client }, profile: { profile.current() }, deviceKind: device
        )
        let playback = PlaybackEngine(
            gateway: downloads.playback,
            preferences: data.preferences,
            client: { data.client },
            profile: profile,
            library: data.library,
            images: data.images,
            servers: data.servers
        )
        return data.makeAppServices(
            playback: playback, livePlayback: LivePlaybackEngine(repository: data.liveTV, profile: profile, userAgent: APIPlaybackGateway.userAgent), downloads: downloads.repository
        )
    }

    #if DEBUG
    /// The fakes, when the arguments ask for them. Debug builds only
    /// (PLAN-APPLE-REVIEW.md §2.9): a Release build ignores `-fakes…` and doesn't link WMMFakes.
    private static func fakes(_ arguments: [String]) -> AppServices? {
        let member = arguments.contains("-fakesMember")
        guard arguments.contains("-fakes") || member || arguments.contains("-fakesSignedOut") || arguments.contains("-fakesNoServer") else { return nil }
        let services = AppServices.fake(
            signedIn: !arguments.contains("-fakesSignedOut") && !arguments.contains("-fakesNoServer"),
            chosen: !arguments.contains("-fakesNoServer"),
            client: UIDevice.current.userInterfaceIdiom == .pad ? .fakePad : .fakePhone,
            admin: !member,
            seerr: arguments.contains("-fakesSeerr"),
            seerrLinked: !arguments.contains("-fakesSeerrUnlinked"),
            liveTV: arguments.contains("-fakesLiveTV") || arguments.contains("-fakesLiveAutomatic"),
            liveGuide: FakeLiveGuide.from(arguments),
            downloads: true,
            apiLevel: arguments.contains("-fakesLiveAutomatic") ? liveAutomaticAPILevel : arguments.contains("-fakesAudioLanguages") ? audioPreferencesAPILevel : arguments.contains("-fakesLiveRecordings") ? liveRecordingAPILevel : arguments.contains("-fakesSubtitlePreferences") ? subtitlePreferencesAPILevel : nil,
            recordings: arguments.contains("-fakesLiveRecordings"),
            liveAutomatic: arguments.contains("-fakesLiveAutomatic"),
            rows: arguments.contains("-fakesRows")
        )
        (services.library as? FakeLibraryRepository)?.firstRun = FakeFirstRunState.from(arguments)
        if let i = arguments.firstIndex(of: "-fakesAudioLanguages"), arguments.indices.contains(i + 1), !arguments[i + 1].hasPrefix("-") {
            (services.preferences as? FakePreferencesRepository)?.seedLanguages(arguments[i + 1].split(separator: ",").map(String.init))
        }
        (services.library as? FakeLibraryRepository)?.offline = arguments.contains("-fakesOffline")
        if let i = arguments.firstIndex(of: "-fakesTrailerKeys"), arguments.indices.contains(i + 1) {
            (services.library as? FakeLibraryRepository)?.trailerKeys = arguments[i + 1].split(separator: ",").map(String.init)
        }
        return services
    }
    #endif
}
