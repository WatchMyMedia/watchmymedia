import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Report a problem on Apple TV, from a movie's or episode's long press: the
/// iPhone sheet's parts (ReportProblem.dc.html) at ten feet. The kind as a
/// row of chips, a note from the keyboard or dictation, what the server
/// attaches, then Send report.
struct ReportProblemDialog: View {
    @State var model: ReportProblemModel
    let onClose: () -> Void
    @FocusState private var focus: ProblemKind?

    var body: some View {
        ZStack {
            Color(hex: 0x0A0A0B).ignoresSafeArea()
            VStack(alignment: .leading, spacing: 22) {
                Text(model.target.kicker.uppercased())
                    .font(.system(size: 18, weight: .bold))
                    .tracking(2)
                    .foregroundStyle(Palette.gold)
                Text(reportProblemTitle)
                    .font(.system(size: 56, weight: .heavy))
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                if model.sent {
                    Text(model.sentMessage).font(.system(size: 28, weight: .semibold)).foregroundStyle(Palette.text)
                    Button("Done", action: onClose).buttonStyle(TVButtonStyle()).padding(.top, 10)
                } else {
                    HStack(spacing: 4) {
                        ForEach(ProblemKind.allCases, id: \.self) { kind in
                            Button(kind.label) { model.pick(kind) }
                                .buttonStyle(TVChipStyle(selected: model.kind == kind, height: 56, fontSize: 22))
                                .focused($focus, equals: kind)
                        }
                    }
                    .padding(6)
                    .background(.white.opacity(0.08), in: Capsule())
                    .focusSection()
                    TextField("Note", text: $model.note)
                        .font(.system(size: 22))
                        .frame(maxWidth: 900)
                    VStack(alignment: .leading, spacing: 6) {
                        Text(reportAttachNote).font(.system(size: 20)).foregroundStyle(Palette.textSecondary)
                        if let line = model.fileLine {
                            Text(line).font(.system(size: 17)).foregroundStyle(Palette.faint)
                        }
                    }
                    if let error = model.error {
                        Text(error).font(.system(size: 19)).foregroundStyle(Palette.warning)
                    }
                    HStack(spacing: 24) {
                        Button { Task { await model.send() } } label: {
                            if model.sending { ProgressView() } else { Text("Send report") }
                        }
                        .buttonStyle(TVButtonStyle(kind: .primary))
                        .disabled(!model.canSend)
                        Button("Cancel", action: onClose).buttonStyle(TVButtonStyle())
                    }
                    .padding(.top, 10)
                    .focusSection()
                }
            }
            .padding(.horizontal, 240)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        }
        .onExitCommand(perform: onClose)
        .defaultFocus($focus, ProblemKind.video)
    }
}
