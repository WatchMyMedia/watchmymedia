import Foundation
import WMMData
#if DEBUG
import WMMFakes
#endif
import WMMModel
import WMMPlayback

/// Builds the services the Apple TV app runs on, as the iPhone app's
/// factory does: `-fakes` runs on the mockups' library with no server (any
/// name, password `password`; pairing signs in by itself after a few
/// seconds), `-fakesSignedOut` starts at sign-in and `-fakesNoServer` at
/// the server picker. `-fakesLiveTV` and `-fakesLiveGuide` (with its
/// `-fakesLiveGuideStale`, `-fakesLiveGuideTMDB` and
/// `-fakesLiveGuideNoPictures`) as on iPhone.
/// `-fakesAudioLanguages` makes the server take audio languages (API level
/// 21); followed by a list ("es,en") the person has saved those, with
/// subtitles for foreign audio, as the server sets them. `-fakesRows` puts
/// the server at API level 22, with arranged rows and lists (PLAN-ROWS.md).
@MainActor
enum ServicesFactory {
    static func make(arguments: [String]) -> AppServices {
        #if DEBUG
        if let services = fakes(arguments) { return services }
        #endif
        let data = DataServices.live()
        let profile = DeviceProfileProvider(tv: true)
        let playback = PlaybackEngine(
            gateway: APIPlaybackGateway(api: { try data.api() }, apiLevel: { data.apiLevel }),
            preferences: data.preferences,
            client: { data.client },
            // Asks for the output's audio channels at every request (PLAN-APPLE-REVIEW.md §3.1.1).
            profile: profile,
            library: data.library,
            // Now Playing's picture. No `servers`: the AirPlay restart is the sender's, never the TV's.
            images: data.images
        )
        return data.makeAppServices(playback: playback, livePlayback: LivePlaybackEngine(repository: data.liveTV, profile: profile, userAgent: APIPlaybackGateway.userAgent))
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
            client: .fakeAppleTV,
            admin: !member,
            seerr: arguments.contains("-fakesSeerr"),
            seerrLinked: !arguments.contains("-fakesSeerrUnlinked"),
            liveTV: arguments.contains("-fakesLiveTV") || arguments.contains("-fakesLiveAutomatic"),
            liveGuide: FakeLiveGuide.from(arguments),
            apiLevel: arguments.contains("-fakesLiveAutomatic") ? liveAutomaticAPILevel : arguments.contains("-fakesAudioLanguages") ? audioPreferencesAPILevel : arguments.contains("-fakesLiveRecordings") ? liveRecordingAPILevel : arguments.contains("-fakesSubtitlePreferences") ? subtitlePreferencesAPILevel : nil,
            recordings: arguments.contains("-fakesLiveRecordings"),
            liveAutomatic: arguments.contains("-fakesLiveAutomatic"),
            rows: arguments.contains("-fakesRows")
        )
        (services.library as? FakeLibraryRepository)?.firstRun = FakeFirstRunState.from(arguments)
        if let i = arguments.firstIndex(of: "-fakesAudioLanguages"), arguments.indices.contains(i + 1), !arguments[i + 1].hasPrefix("-") {
            (services.preferences as? FakePreferencesRepository)?.seedLanguages(arguments[i + 1].split(separator: ",").map(String.init))
        }
        return services
    }
    #endif
}
