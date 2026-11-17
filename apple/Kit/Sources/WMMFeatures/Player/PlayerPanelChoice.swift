import Foundation

/// A panel beside the picture on a wide window (`DuoOpenPlayer`): the
/// season's episodes, or the audio, subtitles and quality, scrolled to one.
public enum PlayerSidePanel: Hashable, Sendable {
    case episodes
    case options(PickerTab)

    public var title: String {
        switch self {
        case .episodes: "Episodes"
        case .options: "Audio & subtitles"
        }
    }
}

/// A tab on the half lying flat, under the controls (`DuoTabletopPlayer`).
public enum TabletopTab: Hashable, Sendable, CaseIterable {
    case upNext, tracks, quality

    public var title: String {
        switch self {
        case .upNext: "Up next"
        case .tracks: "Audio & subtitles"
        case .quality: "Quality"
        }
    }
}

/// What the film player shows beside the picture, or under the fold
/// (PLAN-IPHONE-DUO.md §4.4). It is one choice drawn two ways: the panel
/// chosen beside the picture is the tab shown under the fold, and the tab
/// chosen there is the panel beside the picture when the screen opens flat.
/// Nothing chosen shows no panel, and Up next under the fold (audio and
/// subtitles for a film).
public struct PlayerPanelChoice: Sendable, Equatable {
    /// The panel beside the picture; nil when it is closed.
    public private(set) var panel: PlayerSidePanel?

    public init(panel: PlayerSidePanel? = nil) {
        self.panel = panel
    }

    /// Opens `panel`, or closes it when it is the one open (its button is a toggle).
    public mutating func toggle(_ panel: PlayerSidePanel) {
        self.panel = isOpen(panel) ? nil : panel
    }

    public mutating func open(_ panel: PlayerSidePanel) {
        self.panel = panel
    }

    public mutating func close() {
        panel = nil
    }

    /// Whether `panel`'s button shows as on: any of the options counts for the options' button.
    public func isOpen(_ panel: PlayerSidePanel) -> Bool {
        switch (self.panel, panel) {
        case (.episodes?, .episodes), (.options?, .options): true
        default: false
        }
    }

    /// The tabs under the fold: Up next only with a season to show, Quality
    /// only when streaming (a download plays as it is).
    public static func tabs(episodes: Bool, offline: Bool) -> [TabletopTab] {
        TabletopTab.allCases.filter { tab in
            switch tab {
            case .upNext: episodes
            case .tracks: true
            case .quality: !offline
            }
        }
    }

    /// The tab shown under the fold.
    public func tab(episodes: Bool, offline: Bool) -> TabletopTab {
        let tabs = Self.tabs(episodes: episodes, offline: offline)
        let wanted: TabletopTab = switch panel {
        case nil, .episodes?: .upNext
        case .options(.quality)?: .quality
        case .options?: .tracks
        }
        return tabs.contains(wanted) ? wanted : (wanted == .quality ? .tracks : tabs[0])
    }

    /// A tab chosen under the fold, which becomes the panel beside the picture.
    public mutating func choose(_ tab: TabletopTab) {
        switch tab {
        case .upNext: panel = .episodes
        case .tracks: panel = .options(.audio)
        case .quality: panel = .options(.quality)
        }
    }
}

/// The two lines over the tabletop controls: the show and the episode's
/// code in small capitals ("Harbor Lights · S03 E07") over the episode's
/// name ("The Long Dark"); a film's title alone.
public func tabletopTitle(title: String, subtitle: String?) -> (kicker: String?, name: String) {
    guard let subtitle, !subtitle.isEmpty else { return (nil, title) }
    // "S03 E07 · The Long Dark"
    if let code = subtitle.range(of: #"^S\d+ E\d+ · "#, options: .regularExpression) {
        let name = String(subtitle[code.upperBound...])
        let kicker = [title, String(subtitle[code].dropLast(3))].filter { !$0.isEmpty }.joined(separator: " · ")
        return (kicker, name.isEmpty ? title : name)
    }
    return (title.isEmpty ? nil : title, subtitle)
}
