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
/// the server at API level 22, with arranged rows and lists.
/// `-fakesFollows` puts it at API level 29: the bell on a show's page,
/// Settings › Notifications' list and Home's New from shows you follow.
/// `-fakesRedesign` puts it at API level 34: the cast as people who open
/// their titles, a card's counts, the library's totals and rating filter,
/// and Remove from Continue watching (`-fakesServers` does too).
/// `-fakesRelated` puts it at API level 30: movie
/// and show pages end in their collection and More like this.
/// `-fakesServers` signs Sam in to three servers: Living room server (in
/// use), Cabin server, with a small library of its own, and Den server,
/// which doesn't answer. `-fakesSlowServer` makes Cabin server take a few
/// seconds to answer a search from another server.
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
            // Asks for the output's audio channels at every request.
            profile: profile,
            library: data.library,
            // Now Playing's picture. No `servers`: the AirPlay restart is the sender's, never the TV's.
            images: data.images
        )
        let services = data.makeAppServices(playback: playback, livePlayback: LivePlaybackEngine(repository: data.liveTV, profile: profile, userAgent: APIPlaybackGateway.userAgent))
        // Another server signed in to, for one card: a title opened in place, a copy played from there.
        services.makeScoped = { serverID in
            let library = data.library(serverID: serverID)
            let playback = PlaybackEngine(
                gateway: APIPlaybackGateway(api: { try data.api(serverID: serverID) }, apiLevel: { data.apiLevel(serverID: serverID) }),
                preferences: data.preferences, client: { data.client }, profile: profile, library: library, images: data.images
            )
            return data.makeScopedServices(serverID: serverID, library: library, playback: playback)
        }
        return services
    }

    #if DEBUG
    /// The fakes, when the arguments ask for them. Debug builds only:
    /// a Release build ignores `-fakes…` and doesn't link WMMFakes.
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
            rows: arguments.contains("-fakesRows"),
            follows: arguments.contains("-fakesFollows"),
            related: arguments.contains("-fakesRelated"),
            watchlists: arguments.contains("-fakesWatchlists"),
            several: arguments.contains("-fakesServers"),
            redesign: arguments.contains("-fakesRedesign") || arguments.contains("-fakesServers")
        )
        (services.library as? FakeLibraryRepository)?.firstRun = FakeFirstRunState.from(arguments)
        // The server in use doesn't answer at all: Home's Can't reach.
        if arguments.contains("-fakesUnreachable") { (services.library as? FakeLibraryRepository)?.offline = true }
        if arguments.contains("-fakesSlowServer") {
            (services.library as? FakeLibraryRepository)?.otherServerDelay = .milliseconds(2500)
            // Den server doesn't answer within the time a server is given: screens say they are still asking, then that it didn't.
            (services.library as? FakeLibraryRepository)?.denWaits = .seconds(6)
        }
        if arguments.contains("-fakesOneWatchlist") { (services.watchlists as? FakeWatchlistsRepository)?.keepOnlyFirst() }
        if let i = arguments.firstIndex(of: "-fakesAudioLanguages"), arguments.indices.contains(i + 1), !arguments[i + 1].hasPrefix("-") {
            (services.preferences as? FakePreferencesRepository)?.seedLanguages(arguments[i + 1].split(separator: ",").map(String.init))
        }
        return services
    }
    #endif
}
