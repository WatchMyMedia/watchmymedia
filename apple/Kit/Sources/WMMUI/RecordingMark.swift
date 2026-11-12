import SwiftUI
import WMMModel

/// Recording marks always have spoken words; the red dot is never the only cue
/// (PLAN-LIVE-TV-DVR.md §3.1). Compact, in a guide cell: a red dot for a
/// programme that will record, a ringed dot when a series records it, REC
/// while recording and a hollow orange dot for a clash; nothing in any other
/// state. Full, in details and lists: the words (or `title`), with the same
/// mark before them in those three states.
public struct RecordingMark: View {
    public let mark: LiveRecordingMark
    public var compact: Bool
    public var title: String?
    /// On the TVs' white focused cells, where the usual colours fade: darker red and orange.
    public var onLight: Bool
    public init(_ mark: LiveRecordingMark, compact: Bool = true, title: String? = nil, onLight: Bool = false) {
        self.mark = mark; self.compact = compact; self.title = title; self.onLight = onLight
    }
    public var body: some View {
        if mark.state.marked || !compact {
            HStack(spacing: 5) {
                if mark.state.marked {
                    Image(systemName: mark.state == .conflict ? "circle" : mark.series ? "record.circle" : "circle.fill")
                        .font(.system(size: compact ? 9 : 12, weight: .bold))
                }
                if !compact || mark.state == .recording { Text(compact ? "REC" : title ?? mark.title).font(.caption.weight(.bold)) }
            }
            .foregroundStyle(colour)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(title ?? mark.title)
        }
    }
    private var colour: Color {
        switch mark.state {
        case .conflict: onLight ? Color(hex: 0xF59E0B) : Palette.warning
        case .scheduled, .recording: onLight ? Color(hex: 0xE5332A) : Palette.liveText
        default: Palette.muted
        }
    }
}
