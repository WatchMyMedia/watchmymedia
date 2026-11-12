import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// The request sheet (SearchRequest.dc.html; on iPad the form sheet of
/// PadShowMissing.dc.html): poster, title, year and overview; a show's
/// seasons to tick, those in the library or asked for already greyed and
/// ticked; the approval line when an admin approves first; the button; then
/// the confirmation, with a link to Your requests.
struct RequestSheet: View {
    /// The sheet to present: on iPad (`centred`) only once the lookup has
    /// answered, since a form sheet keeps the height it opened at.
    static func presented(_ sheet: RequestSheetModel?, centred: Bool) -> RequestSheetModel? {
        guard let sheet else { return nil }
        return centred && !sheet.settled ? nil : sheet
    }

    @State var model: RequestSheetModel
    var centred = false
    let onClose: () -> Void
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator

    var body: some View {
        FormSheet(centred: centred) {
            header
            switch model.state {
            case .loading:
                ProgressView().tint(Palette.muted).frame(maxWidth: .infinity).padding(.vertical, 24)
            case .failed(let message):
                ErrorState(message: message, retry: model.load)
            case .loaded:
                if model.sent != nil {
                    sent
                } else {
                    picking
                }
            }
        }
        .task { await model.loadOnce() }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: centred ? 16 : 14) {
            Artwork(url: services.library.imageURL(model.item?.posterPath, size: .w342), seed: model.title, maxPixel: 300)
                .frame(width: centred ? 80 : 72, height: centred ? 120 : 108)
                .clipShape(RoundedRectangle(cornerRadius: centred ? 10 : 8))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(model.title)
                    .scaledFont(centred ? 26 : 22, weight: .heavy)
                    .foregroundStyle(Palette.text)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                if !model.meta.isEmpty {
                    Text(model.meta).scaledFont(13).foregroundStyle(Palette.muted)
                }
                if let overview = model.item?.overview, !overview.isEmpty {
                    Text(overview)
                        .scaledFont(14)
                        .lineSpacing(2)
                        .foregroundStyle(Palette.textSecondary)
                        .lineLimit(3)
                        .padding(.top, 4)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            SheetCloseButton(action: onClose)
        }
    }

    @ViewBuilder private var picking: some View {
        if !model.seasons.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("SEASONS")
                    .scaledFont(12, weight: .bold)
                    .tracking(0.8)
                    .foregroundStyle(Palette.muted)
                    .accessibilityAddTraits(.isHeader)
                VStack(spacing: 0) {
                    ForEach(Array(model.seasons.enumerated()), id: \.element.id) { index, season in
                        seasonRow(season)
                            .overlay(alignment: .top) { if index > 0 { RowSeparator() } }
                    }
                }
                .background(Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.separator))
            }
        }
        if model.inLibrary {
            Text("It’s in your library already.").scaledFont(14).foregroundStyle(Palette.textSecondary)
        } else {
            if let note = model.approvalNote { InfoNote(text: note) }
            if let error = model.error {
                Text(error).scaledFont(13).foregroundStyle(Palette.warning).fixedSize(horizontal: false, vertical: true)
            }
            Button { Task { await model.send() } } label: {
                BusyLabel(title: model.sendLabel, busy: model.sending)
            }
            .buttonStyle(PrimaryButtonStyle())
            .disabled(!model.canSend)
        }
    }

    private func seasonRow(_ season: RequestableSeason) -> some View {
        let ticked = model.isTicked(season)
        return Button { model.toggle(season) } label: {
            HStack(spacing: 12) {
                Image(systemName: "checkmark")
                    .font(.system(size: 13, weight: .heavy))
                    .foregroundStyle(season.askable ? Palette.ground : Palette.muted)
                    .opacity(ticked ? 1 : 0)
                    .frame(width: 22, height: 22)
                    .background(
                        season.askable && ticked ? Palette.gold : (season.askable ? Color.clear : Palette.outline),
                        in: RoundedRectangle(cornerRadius: 6)
                    )
                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(season.askable && !ticked ? Palette.outlineStrong : .clear, lineWidth: 2))
                VStack(alignment: .leading, spacing: 1) {
                    Text(season.title).scaledFont(16, weight: .semibold).foregroundStyle(season.askable ? Palette.text : Palette.muted)
                    Text(season.episodesLine).scaledFont(12).foregroundStyle(Palette.muted)
                }
                Spacer(minLength: 8)
                if let label = season.state.label {
                    Text(label).scaledFont(13).foregroundStyle(Palette.muted).lineLimit(1)
                }
            }
            .padding(.horizontal, 12)
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!season.askable)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(ticked ? .isSelected : [])
    }

    @ViewBuilder private var sent: some View {
        if let text = model.sentMessage {
            let link: (() -> Void)? = model.sentToYourRequests ? { openYourRequests() } : nil
            SentCard(text: text, onYourRequests: link)
        }
        Button("Done", action: onClose)
            .buttonStyle(GhostButtonStyle(height: centred ? 50 : 46))
    }

    private func openYourRequests() {
        onClose()
        Task { await navigator.open(.myRequests, library: services.library) }
    }
}
