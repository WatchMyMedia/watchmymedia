import Foundation
import WMMModel

/// What Play/Pause does on a focused card on Apple TV, where Select opens
/// the card's page (PLAN-APPLE-REVIEW.md §6.2). Only what the card's own data
/// supports plays: a show's card knows no single episode to play, so it opens
/// the show as Select does, and the page has Play ready.
public enum CardPlayPause: Hashable, Sendable {
    /// Plays at once: a Continue watching item (resuming), an episode on
    /// disk, an unmatched film.
    case play(PlayRequest)
    /// A movie: loaded first, then its first version plays, resuming, as its page's Play does.
    case playMovie(id: Int)
    /// Tunes the channel.
    case tune(LiveChannel)
    /// Nothing single to play (a show, an episode not on disk, a folder of
    /// files, a channel that can't be watched): the card's Select.
    case select

    /// A movie, show or unmatched card.
    public static func card(_ item: BrowseItem) -> Self {
        switch item.kind {
        case .movie: .playMovie(id: item.id)
        case .show: .select
        case .unmatched: item.unmatchedPlayRequest.map(Self.play) ?? .select
        }
    }

    /// A Continue watching card: resumes, as Select does.
    public static func card(_ item: ContinueItem) -> Self {
        .play(item.playRequest)
    }

    /// An episode card (Recently aired): plays it when it is on disk.
    public static func card(_ episode: EpisodeCard) -> Self {
        episode.playRequest.map(Self.play) ?? .select
    }

    /// A channel card: tunes it, as Select does.
    public static func card(_ channel: LiveChannel) -> Self {
        channel.available ? .tune(channel) : .select
    }

    /// A guide cell: a programme on now tunes its channel; one that has
    /// ended or is still to come does the cell's Select.
    public static func cell(_ programme: LiveProgramme, on channel: LiveChannel, now: Date) -> Self {
        programme.isOn(at: now) ? card(channel) : .select
    }
}

extension Navigator {
    /// Does what Play/Pause does on a card: plays, tunes, or calls `select`
    /// (the card's own Select) when there is nothing single to play. A movie
    /// that can't be loaded, or has no file, opens its page instead, which
    /// says why.
    public func playPause(_ card: CardPlayPause, library: any LibraryRepository, select: @MainActor () -> Void) async {
        switch card {
        case .play(let request):
            play(request)
        case .tune(let channel):
            playLive(LivePlayRequest(channel: channel))
        case .select:
            select()
        case .playMovie(let id):
            if let movie = try? await library.movie(id: id), let request = movie.playRequest() {
                play(request)
            } else {
                open(.movie(id: id))
            }
        }
    }
}
