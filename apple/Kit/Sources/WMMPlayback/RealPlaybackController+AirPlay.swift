import Foundation
import os
import WMMModel

private let log = Logger(subsystem: "media.watchmy.client", category: "Playback")

// AirPlay: the play moves to the server's LAN address when the video goes
// to another screen that is unlikely to reach the address in use.

extension RealPlaybackController {
    /// Follows the video onto another screen (PLAN-IOS.md §5). The Apple TV
    /// fetches the stream itself, so when the server's address in use is one
    /// it is unlikely to reach (a tailnet name, a public name, localhost), the
    /// play restarts at its position on the same server's LAN address, as a
    /// quality change restarts it. Heartbeats still go from here to the
    /// address in use. With no LAN address it plays on as it is.
    func followRoute() {
        let now = media?.externalPlaybackActive == true
        guard now != external else { return }
        external = now
        guard now, !playingLocally else { return }
        if localRoute != nil {
            restartLocally()
            return
        }
        guard !lookingForLocal, let servers, let base = servers.current?.server.baseURL, !isLANHost(base.host() ?? "") else { return }
        lookingForLocal = true
        launch { [self] in
            let found = await servers.localAddress()
            lookingForLocal = false
            guard let found else {
                log.info("AirPlay: no LAN address for the server, so the stream stays where it is")
                return
            }
            localRoute = LocalRoute(from: base, to: found)
            if external { restartLocally() }
        }
    }

    /// Restarts the play on the LAN address. A play still loading picks it up by itself.
    func restartLocally() {
        guard let v = active(), let d = v.decision, !v.loading, !playingLocally, localRoute?.apply(to: d.url) != nil else { return }
        if let pending = v.pendingSwitch {
            // AirPlay needs an address its receiver can reach. Preserve the
            // earlier stream if that route changes before the new frame.
            restorePrevious(v, pending)
            return
        }
        reload(v, at: position(v))
    }
}
