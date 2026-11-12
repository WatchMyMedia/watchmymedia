import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Settings › Home rows on Apple TV (TvSettingsHomeRows.dc.html, PLAN-ROWS.md
/// §8.3), and Edit rows on Movies and TV Shows for that screen: every row
/// numbered, with Move up, Move down and Hide or Show, as the Audio &
/// subtitles sheet orders languages; hidden rows stay in place, greyed.
/// Beside the list: Add a row (the hidden rows by kind, in a menu) and Reset
/// to default. Each change saves at once.
struct TVRowEditor: View {
    let screen: RowScreen
    let screenName: String
    @Environment(AppServices.self) private var services

    var body: some View {
        TVRowEditorView(model: RowEditorModel(rows: services.rows, screen: screen, screenName: screenName, autosave: true))
    }
}

private struct TVRowEditorView: View {
    @State var model: RowEditorModel
    @Environment(\.dismiss) private var dismiss
    @State private var confirmingReset = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 40) {
                HStack {
                    Text(model.title)
                        .font(.system(size: 56, weight: .heavy))
                        .foregroundStyle(Palette.text)
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button("Done") { Task { await model.pendingSave?.value; dismiss() } }
                        .buttonStyle(TVButtonStyle(height: 64, fontSize: 23))
                        .accessibilityIdentifier("rowsDone")
                }
                .focusSection()
                switch model.state {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity, minHeight: 400)
                case .failed(let message):
                    TVErrorState(message: message) { await model.load() }
                case .loaded:
                    HStack(alignment: .top, spacing: 80) {
                        VStack(alignment: .leading, spacing: 8) {
                            ForEach(Array(model.rows.enumerated()), id: \.element.id) { index, entry in
                                rowCard(index, entry)
                            }
                        }
                        .frame(width: 1160, alignment: .leading)
                        .focusSection()
                        aside
                            .frame(width: 500, alignment: .leading)
                            .focusSection()
                    }
                }
            }
            .padding(.horizontal, TVLayout.gutter)
            .padding(.top, 64)
            .padding(.bottom, 60)
        }
        .background(Palette.ground.ignoresSafeArea())
        .task { await model.load() }
        .messagePill($model.message)
        .alert(model.resetTitle, isPresented: $confirmingReset) {
            Button("Reset", role: .destructive) { Task { await model.reset() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(model.resetMessage)
        }
    }

    /// A row: its number, title, line and why it isn't shown; then Move up, Move down and Hide or Show.
    private func rowCard(_ index: Int, _ entry: RowLayoutEntry) -> some View {
        HStack(spacing: 20) {
            Text("\(index + 1)")
                .font(.system(size: 21, weight: .bold).monospacedDigit())
                .foregroundStyle(Palette.faint)
                .frame(width: 36, alignment: .leading)
            VStack(alignment: .leading, spacing: 3) {
                Text(entry.title).font(.system(size: 24, weight: .semibold)).foregroundStyle(Palette.text).lineLimit(1)
                Text(model.line(entry)).font(.system(size: 18)).foregroundStyle(Palette.muted).lineLimit(1)
                if let note = entry.notShownLine {
                    Text("\(Image(systemName: "info.circle")) \(note)").font(.system(size: 17)).foregroundStyle(Palette.textSecondary)
                }
            }
            .opacity(entry.hidden ? 0.45 : 1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            HStack(spacing: 10) {
                Button("Move up") { model.moveUp(entry.id) }
                    .disabled(!model.canMoveUp(entry.id))
                    .opacity(model.canMoveUp(entry.id) ? 1 : 0.4)
                    .accessibilityLabel("Move \(entry.title) up")
                Button("Move down") { model.moveDown(entry.id) }
                    .disabled(!model.canMoveDown(entry.id))
                    .opacity(model.canMoveDown(entry.id) ? 1 : 0.4)
                    .accessibilityLabel("Move \(entry.title) down")
                Button(entry.hidden ? "Show" : "Hide") { model.setHidden(entry.id, !entry.hidden) }
                    .buttonStyle(TVChipStyle(selected: entry.hidden, height: 50, fontSize: 19))
                    .frame(minWidth: 96)
                    .accessibilityLabel("\(entry.hidden ? "Show" : "Hide") \(entry.title)")
                    .accessibilityIdentifier("\(entry.hidden ? "show" : "hide")-\(entry.id)")
            }
            .buttonStyle(TVChipStyle(selected: false, height: 50, fontSize: 19))
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.05), in: RoundedRectangle(cornerRadius: 16))
        // A container's identifier goes to every child unless it is an element.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("editor-\(entry.id)")
    }

    private var aside: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(model.tvIntro).font(.system(size: 23)).lineSpacing(5).foregroundStyle(Palette.textSecondary)
            Text("Your rows are the same on all your devices.").font(.system(size: 23)).foregroundStyle(Palette.textSecondary)
            VStack(alignment: .leading, spacing: 12) {
                addMenu
                Button { confirmingReset = true } label: {
                    Text("Reset to default").foregroundStyle(Color(hex: 0xFF8A80))
                        .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
                }
                .buttonStyle(TVButtonStyle(height: 76, fontSize: 23))
                .accessibilityIdentifier("resetRows")
            }
            .padding(.top, 8)
            Text("\(Image(systemName: "globe")) \(listsMadeOnTheWeb) A list made there shows up in Add a row.")
                .font(.system(size: 19))
                .lineSpacing(4)
                .foregroundStyle(Palette.faint)
                .padding(.top, 6)
            Text(model.footnote).font(.system(size: 19)).lineSpacing(4).foregroundStyle(Palette.faint)
        }
    }

    /// Add a row: the hidden rows by kind; choosing one shows it in its place.
    private var addMenu: some View {
        let groups = model.addGroups(model.offered.map(\.id))
        return Menu {
            if groups.isEmpty {
                Text("Every row is on \(model.screenName) already.")
            }
            ForEach(groups, id: \.title) { group in
                Section(group.title) {
                    ForEach(group.rows) { entry in
                        Button { model.add(entry.id) } label: {
                            Text(entry.title)
                            Text(model.addLine(entry))
                        }
                        .accessibilityIdentifier("add-\(entry.id)")
                    }
                }
            }
            Section {
                Text(listsMadeOnTheWeb)
            }
        } label: {
            Text("\(Image(systemName: "plus")) Add a row")
                .frame(maxWidth: .infinity, minHeight: 76, alignment: .leading)
        }
        .buttonStyle(TVButtonStyle(height: 76, fontSize: 23))
        .accessibilityIdentifier("addRow")
    }
}
