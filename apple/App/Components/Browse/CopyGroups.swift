import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// "Also on Cabin", under a title's facts, while it is on another server
/// too; "Also on Cabin · you got further there" where the place is further there.
struct AlsoOnLine: View {
    let text: String?

    var body: some View {
        if let text {
            Text("\(Image(systemName: "server.rack")) \(text)")
                .scaledFont(13)
                .foregroundStyle(Palette.textSecondary)
                .accessibilityIdentifier("title-also-on")
        }
    }
}

/// Versions on every server, grouped by server (one card): the server in
/// use first, a server away from home last with its note. Each copy shows
/// its picture and sound, the person's place there and Play this; tapping
/// one makes it the copy Play plays, kept for the title on this device.
/// Where the person got further on another server, the first line says
/// Resume starts there whichever is chosen. No copy here offers a
/// download: Download is for the server in use's copy, above.
struct CopyGroups: View {
    let copies: TitleCopiesModel
    var tiled = false
    let planLine: (MediaVersion) -> String
    let choose: (TitleCopy) -> Void
    let play: (TitleCopy) -> Void

    var body: some View {
        let chosen = copies.chosen
        if let note = copies.resumeNote {
            Text(note)
                .scaledFont(13)
                .foregroundStyle(Palette.text)
                .padding(.leading, 10)
                .overlay(alignment: .leading) { Rectangle().fill(Palette.gold).frame(width: 3) }
                .fixedSize(horizontal: false, vertical: true)
        }
        ForEach(copies.groups) { group in
            VStack(alignment: .leading, spacing: 8) {
                Text(group.heading.uppercased())
                    .scaledFont(12, weight: .semibold)
                    .tracking(0.6)
                    .foregroundStyle(Palette.muted)
                    .accessibilityAddTraits(.isHeader)
                if let note = group.note {
                    Text(note).scaledFont(12).foregroundStyle(Palette.muted).fixedSize(horizontal: false, vertical: true)
                }
                ForEach(group.copies) { copy in
                    CopyCard(copy: copy, planLine: group.server.id == copies.pageServer?.id ? planLine(copy.version) : nil,
                             chosen: copy.id == chosen?.id, tiled: tiled, choose: { choose(copy) }, play: { play(copy) })
                }
            }
            .padding(.top, 4)
        }
        if let asking = copies.stillAsking {
            HStack(spacing: 8) {
                ProgressView().controlSize(.mini)
                Text(asking).scaledFont(12).foregroundStyle(Palette.muted)
            }
            .accessibilityIdentifier("copies-still-asking")
        }
    }
}

/// One copy: "4K HDR" with its tags, the person's place there, and Play this.
private struct CopyCard: View {
    let copy: TitleCopy
    /// How it would play here, for the page's own server's copies.
    let planLine: String?
    let chosen: Bool
    let tiled: Bool
    let choose: () -> Void
    let play: () -> Void

    var body: some View {
        let layout = tiled ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12)) : AnyLayout(HStackLayout(spacing: 12))
        layout {
            Button(action: choose) {
                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 8) {
                        if chosen {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.gold).accessibilityHidden(true)
                        }
                        Text(copy.quality).scaledFont(15, weight: .bold).foregroundStyle(Palette.text)
                        Text(copy.placeLine).scaledFont(13).foregroundStyle(Palette.textSecondary)
                    }
                    FlowLayout(spacing: 4) { ForEach(copy.version.tags, id: \.self) { Tag($0) } }
                    if let planLine {
                        Text(planLine).scaledFont(11).foregroundStyle(Palette.faint).lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(copy.accessibilityLabel)
            .accessibilityHint("Makes it the one Play plays")
            .accessibilityAddTraits(chosen ? .isSelected : [])
            Button(OneCardWords.playThis, action: play)
                .buttonStyle(SmallGhostButtonStyle())
                .accessibilityLabel("Play this: \(copy.server.shortName), \(copy.quality), \(copy.placeLine)")
                .accessibilityIdentifier("copy-play-\(copy.id)")
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 14)
        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(chosen ? Palette.gold : Palette.separator))
    }
}
