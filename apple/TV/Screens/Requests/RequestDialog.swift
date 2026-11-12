import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The full-screen request dialog (TvSearchRequest.dc.html): the poster at
/// the left; at the right "Not in your library" (from search), the title,
/// facts and overview, a show's seasons to tick, the approval line, then
/// Request and Cancel, with focus on Request. Sent, it says where the
/// request went, with Done.
struct RequestDialog: View {
    @State var model: RequestSheetModel
    let onClose: () -> Void
    @Environment(AppServices.self) private var services
    @FocusState private var focus: Focus?

    private enum Focus: Hashable { case send, done, season(Int) }

    var body: some View {
        ZStack {
            Color(hex: 0x0A0A0B).ignoresSafeArea()
            RadialGradient(colors: [Color(hex: 0x37485A).opacity(0.35), .clear], center: UnitPoint(x: 0.25, y: 0.4), startRadius: 0, endRadius: 900)
                .ignoresSafeArea()
            HStack(alignment: .top, spacing: 72) {
                Artwork(url: services.library.imageURL(model.item?.posterPath, size: .w500), seed: model.title, maxPixel: 900)
                    .frame(width: 360, height: 540)
                    .clipShape(RoundedRectangle(cornerRadius: 22))
                    .shadow(color: .black.opacity(0.6), radius: 40, y: 30)
                    .accessibilityHidden(true)
                ScrollView {
                    details.frame(maxWidth: .infinity, alignment: .leading)
                }
                .scrollClipDisabled()
            }
            .padding(.horizontal, 240)
            .padding(.top, 150)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        }
        .task { await model.loadOnce() }
        .onExitCommand(perform: onClose)
        .defaultFocus($focus, model.sent == nil ? .send : .done)
        .onChange(of: model.sent != nil) { _, sent in if sent { focus = .done } }
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 14) {
            if model.item?.state != .available {
                Text("NOT IN YOUR LIBRARY")
                    .font(.system(size: 18, weight: .bold))
                    .tracking(2)
                    .foregroundStyle(Palette.gold)
            }
            Text(model.title)
                .font(.system(size: 56, weight: .heavy))
                .tracking(-1.4)
                .foregroundStyle(Palette.text)
                .lineLimit(2)
                .accessibilityAddTraits(.isHeader)
            if !model.meta.isEmpty {
                Text(model.meta).font(.system(size: 22)).foregroundStyle(Color.tvTitle)
            }
            if let overview = model.item?.overview, !overview.isEmpty {
                Text(overview)
                    .font(.system(size: 21))
                    .lineSpacing(6)
                    .foregroundStyle(Palette.textSecondary)
                    .lineLimit(3)
                    .frame(maxWidth: 900, alignment: .leading)
            }
            switch model.state {
            case .loading:
                ProgressView().padding(.top, 30)
            case .failed(let message):
                Text(message).font(.system(size: 21)).foregroundStyle(Palette.warning).padding(.top, 20)
                Button("Cancel", action: onClose).buttonStyle(TVButtonStyle()).padding(.top, 18)
            case .loaded:
                if model.sent != nil { sent } else { picking }
            }
        }
    }

    @ViewBuilder private var picking: some View {
        if !model.seasons.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                Text("SEASONS").font(.system(size: 18, weight: .bold)).tracking(1.6).foregroundStyle(Palette.muted)
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(model.seasons) { season in
                        Button { model.toggle(season) } label: { SeasonRow(season: season, ticked: model.isTicked(season)) }
                            .buttonStyle(.borderless)
                            .disabled(!season.askable)
                            .focused($focus, equals: .season(season.number))
                            .accessibilityAddTraits(model.isTicked(season) ? .isSelected : [])
                    }
                }
                .focusSection()
            }
            .frame(maxWidth: 900, alignment: .leading)
            .padding(.top, 14)
        }
        if model.inLibrary {
            Text("It’s in your library already.").font(.system(size: 21)).foregroundStyle(Palette.textSecondary).padding(.top, 10)
        } else if let note = model.approvalNote {
            Label(note, systemImage: "info.circle").font(.system(size: 19)).foregroundStyle(Palette.muted).padding(.top, 10)
        }
        if let error = model.error {
            Text(error).font(.system(size: 19)).foregroundStyle(Palette.warning)
        }
        HStack(spacing: 24) {
            if !model.inLibrary {
                Button { Task { await model.send() } } label: {
                    if model.sending { ProgressView() } else { Text(model.sendLabel) }
                }
                .buttonStyle(TVButtonStyle(kind: .primary))
                .disabled(!model.canSend)
                .focused($focus, equals: .send)
            }
            Button("Cancel", action: onClose).buttonStyle(TVButtonStyle())
        }
        .padding(.top, 18)
        .focusSection()
    }

    @ViewBuilder private var sent: some View {
        if let text = model.sentMessage {
            HStack(spacing: 20) {
                Image(systemName: "checkmark")
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(Palette.ground)
                    .frame(width: 56, height: 56)
                    .background(Palette.gold, in: Circle())
                    .accessibilityHidden(true)
                Text(text).font(.system(size: 28, weight: .semibold)).lineSpacing(6).foregroundStyle(Palette.text)
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 24)
            .frame(maxWidth: 900, alignment: .leading)
            .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 20))
            .padding(.top, 24)
        }
        Button("Done", action: onClose)
            .buttonStyle(TVButtonStyle())
            .focused($focus, equals: .done)
            .padding(.top, 18)
    }
}

/// A season in the dialog (the artboard's `.srow`): the box, the name, how
/// many episodes, and where it stands; greyed when it can't be asked for.
private struct SeasonRow: View {
    let season: RequestableSeason
    let ticked: Bool
    @Environment(\.isFocused) private var focused

    var body: some View {
        HStack(spacing: 18) {
            Image(systemName: "checkmark")
                .font(.system(size: 18, weight: .heavy))
                .foregroundStyle(season.askable ? Palette.ground : Palette.muted)
                .opacity(ticked ? 1 : 0)
                .frame(width: 34, height: 34)
                .background(season.askable && ticked ? Palette.gold : (season.askable ? Color.clear : Palette.outline), in: RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(season.askable && !ticked ? (focused ? Palette.ground : Palette.outlineStrong) : .clear, lineWidth: 2))
            Text(season.title)
                .font(.system(size: 22, weight: .semibold))
                .foregroundStyle(focused ? Palette.ground : season.askable ? Palette.text : Palette.muted)
            Text(season.episodesLine).font(.system(size: 18)).foregroundStyle(focused ? Color(hex: 0x525252) : Palette.muted)
            Spacer(minLength: 12)
            if let label = season.state.label {
                Text(label).font(.system(size: 18)).foregroundStyle(focused ? Color(hex: 0x525252) : Palette.muted)
            }
        }
        .padding(.horizontal, 20)
        .frame(height: 64)
        .background(focused ? Palette.text : .white.opacity(0.05), in: RoundedRectangle(cornerRadius: 14))
        .focusRim(focused, cornerRadius: 14, scale: 1.02)
    }
}
