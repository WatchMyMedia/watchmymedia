import Foundation
import WMMModel

/// Live TV's controls on the iPhone Duo's folded layouts (`DuoTabletopLive`,
/// `DuoBookLive`; PLAN-IPHONE-DUO.md §5): Ch −, the button between, Ch +.
/// The flat player keeps its own play/pause and Go live over the picture.
public enum LiveFoldMiddle: Hashable, Sendable {
    /// Behind live: back to the broadcast.
    case goLive
    /// Paused: on from where it stopped.
    case play
    /// Playing live.
    case pause
    /// Stopped (the tuner given back, an admin's stop): tune again, as the stopped prompt's Watch live does.
    case watchLive
    /// It couldn't play: tune again, as the prompt's Try again does.
    case tryAgain

    public static func of(status: LivePlayerStatus, atLiveEdge: Bool) -> LiveFoldMiddle {
        switch status {
        case .stopped: return .watchLive
        case .error: return .tryAgain
        case .paused: return .play
        default: return atLiveEdge ? .pause : .goLive
        }
    }

    public var title: String {
        switch self {
        case .goLive: "Go live"
        case .play: "Play"
        case .pause: "Pause"
        case .watchLive: "Watch live"
        case .tryAgain: "Try again"
        }
    }
}

/// The list under the channel's controls when half open on a table.
public enum LiveFoldList: Hashable, Sendable, CaseIterable {
    case onNow, favourites

    public var title: String {
        switch self {
        case .onNow: "On now"
        case .favourites: "Favourites"
        }
    }

    /// Favourites is offered once the person has one among `channels`.
    public static func tabs(hasFavourite: Bool) -> [LiveFoldList] {
        hasFavourite ? [.onNow, .favourites] : [.onNow]
    }

    /// The tab shown: Favourites falls back to On now once the last one goes.
    public static func shown(_ chosen: LiveFoldList, hasFavourite: Bool) -> LiveFoldList {
        tabs(hasFavourite: hasFavourite).contains(chosen) ? chosen : .onNow
    }
}

/// The small capitals over what is on: "4.1 NFLD-HD · On now".
public func liveFoldKicker(_ channel: LiveChannel) -> String {
    "\(channel.number) \(channel.name) · On now"
}
