import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Report a problem (ReportProblem.dc.html), from a movie's or show's More
/// menu: what it is about, the kind (Video, Audio, Subtitles, Other), a note,
/// and what the server attaches; then Send report.
struct ReportProblemSheet: View {
    @State var model: ReportProblemModel
    var centred = false
    let onClose: () -> Void
    @FocusState private var noteFocused: Bool

    var body: some View {
        FormSheet(centred: centred) {
            header
            if model.sent {
                SentCard(text: model.sentMessage)
                Button("Done", action: onClose).buttonStyle(GhostButtonStyle())
            } else {
                Picker("Kind of problem", selection: Binding(get: { model.kind }, set: { if let kind = $0 { model.pick(kind) } })) {
                    ForEach(ProblemKind.allCases, id: \.self) { Text($0.label).tag(Optional($0)) }
                }
                .pickerStyle(.segmented)
                VStack(alignment: .leading, spacing: 6) {
                    Text("NOTE").scaledFont(12, weight: .bold).tracking(0.8).foregroundStyle(Palette.muted)
                    TextField("", text: $model.note, axis: .vertical)
                        .lineLimit(3, reservesSpace: true)
                        .focused($noteFocused)
                        .scaledFont(15)
                        .foregroundStyle(Palette.text)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Palette.outlineStrong))
                        .accessibilityLabel("Note")
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(reportAttachNote).scaledFont(13).foregroundStyle(Palette.textSecondary)
                    if let line = model.fileLine {
                        Text(line).scaledFont(12).foregroundStyle(Palette.faint).fixedSize(horizontal: false, vertical: true)
                    }
                }
                if let error = model.error {
                    Text(error).scaledFont(13).foregroundStyle(Palette.warning)
                }
                Button { Task { await model.send() } } label: {
                    BusyLabel(title: "Send report", busy: model.sending)
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!model.canSend)
            }
        }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 3) {
                Text(model.target.kicker.uppercased())
                    .scaledFont(12, weight: .bold)
                    .tracking(1)
                    .foregroundStyle(Palette.gold)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Report a problem")
                    .scaledFont(24, weight: .heavy)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 0)
            SheetCloseButton(action: onClose)
        }
    }
}
