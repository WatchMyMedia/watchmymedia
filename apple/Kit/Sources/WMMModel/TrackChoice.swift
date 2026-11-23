import Foundation

/// The sound and subtitles chosen on a title's page before it plays (Apple
/// TV's More): nil keeps what the file and the person's languages would pick.
public struct TrackChoice: Hashable, Sendable {
    /// A subtitle choice: none at all, or one of the file's tracks.
    public enum Subtitle: Hashable, Sendable {
        case off
        case track(String)
    }

    /// The audio track (ffprobe index); nil is the file's default.
    public var audioIndex: Int?
    /// nil keeps the person's subtitle languages.
    public var subtitle: Subtitle?

    public init(audioIndex: Int? = nil, subtitle: Subtitle? = nil) {
        self.audioIndex = audioIndex
        self.subtitle = subtitle
    }

    /// Nothing chosen.
    public var isEmpty: Bool { audioIndex == nil && subtitle == nil }

    /// `request` starting with these tracks.
    public func applied(to request: PlayRequest) -> PlayRequest {
        var request = request
        if let audioIndex { request.audioIndex = audioIndex }
        switch subtitle {
        case .off?:
            request.subtitleID = nil
            request.subtitleMode = .off
        case .track(let id)?:
            request.subtitleID = id
        case nil:
            break
        }
        return request
    }
}
