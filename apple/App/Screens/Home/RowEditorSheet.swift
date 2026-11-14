import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Home rows, or a library's rows (HomeEditRows.dc.html; on iPad the same
/// sheet as a form sheet, Pad2HomeEditRows.dc.html): every row in order with
/// Hide or Show and a handle to drag it by, Add a row, then Reset to default.
/// Hidden rows stay in their place, greyed. Done saves; Cancel leaves the
/// rows as they were. Opened from the end of Home, from Settings › Home rows,
/// and from Edit rows at the end of a Movies or TV Shows screen's rows.
struct RowEditorSheet: View {
    let screen: RowScreen
    let screenName: String
    @Environment(AppServices.self) private var services

    var body: some View {
        RowEditorView(model: RowEditorModel(rows: services.rows, screen: screen, screenName: screenName))
    }
}

/// `-editRows` (DEBUG) opens Home's sheet at launch, and `-addRow` its Add a row, for screenshots.
let rowEditorLaunch = ScreenshotLaunch.has("-editRows")
let addRowLaunch = ScreenshotLaunch.has("-addRow")

private struct RowEditorView: View {
    @State var model: RowEditorModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var adding: [String]?
    @State private var confirmingReset = false
    /// Counts rows dropped in a new place, for the tap felt as each lands.
    @State private var moves = 0

    var body: some View {
        NavigationStack {
            content
                .groundBackground()
                .navigationTitle(model.title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        if model.saving {
                            ProgressView()
                        } else {
                            Button("Done") { Task { if await model.save() { dismiss() } } }
                                .fontWeight(.semibold)
                                .disabled(model.state.isLoading)
                                .accessibilityIdentifier("rowsDone")
                        }
                    }
                }
        }
        .presentationBackground(Palette.sheet)
        .interactiveDismissDisabled(model.changed)
        .task {
            await model.load()
            if addRowLaunch && adding == nil { adding = model.offered.map(\.id) }
        }
        .messageToast($model.message)
        .sheet(isPresented: Binding(get: { adding != nil }, set: { if !$0 { adding = nil } })) {
            AddRowSheet(model: model, ids: adding ?? [])
        }
        .alert(model.resetTitle, isPresented: $confirmingReset) {
            Button("Reset", role: .destructive) { Task { await model.reset() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(model.resetMessage)
        }
    }

    @ViewBuilder private var content: some View {
        switch model.state {
        case .loading:
            ProgressView().tint(Palette.muted).frame(maxWidth: .infinity, maxHeight: .infinity)
        case .failed(let message):
            ErrorState(message: message) { await model.load() }
                .frame(maxHeight: .infinity)
        case .loaded:
            list
        }
    }

    private var list: some View {
        List {
            Section {
                ForEach(model.rows) { entry in
                    EditorRow(model: model, entry: entry, besideHero: sizeClass.isWide && model.screen == .home && model.isBesideHero(entry))
                }
                .onMove {
                    model.move(fromOffsets: $0, toOffset: $1)
                    moves += 1
                }
                Button { adding = model.offered.map(\.id) } label: {
                    Label { Text("Add a row").foregroundStyle(Palette.gold) } icon: {
                        Image(systemName: "plus").fontWeight(.bold).foregroundStyle(Palette.gold)
                    }
                    .frame(minHeight: 36)
                    .contentShape(Rectangle())
                }
                // In edit mode a row's plain button doesn't take taps.
                .buttonStyle(.borderless)
                .moveDisabled(true)
                .accessibilityIdentifier("addRow")
            } header: {
                VStack(alignment: .leading, spacing: 14) {
                    Text(model.intro(besideHero: sizeClass.isWide))
                        .scaledFont(13)
                        .foregroundStyle(Palette.muted)
                        .textCase(nil)
                        .fixedSize(horizontal: false, vertical: true)
                    GroupHeader(title: model.groupTitle)
                }
            } footer: {
                Text(model.footnote).scaledFont(12).foregroundStyle(Palette.faint)
            }
            .listRowBackground(Color(hex: 0x26262A))

            Section {
                Button { confirmingReset = true } label: {
                    Text("Reset to default").foregroundStyle(Color(hex: 0xFF8A80))
                        .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityIdentifier("resetRows")
            } footer: {
                Text(model.resetNote).scaledFont(12).foregroundStyle(Palette.faint)
            }
            .listRowBackground(Color(hex: 0x26262A))
        }
        .scrollContentBackground(.hidden)
        .environment(\.editMode, .constant(.active))
        .sensoryFeedback(.impact(weight: .light), trigger: moves)
    }
}

/// A group's small uppercase heading: "ROWS ON HOME", "FOR EVERYONE".
private struct GroupHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .scaledFont(12, weight: .semibold)
            .tracking(0.7)
            .textCase(.uppercase)
            .foregroundStyle(Palette.muted)
            .accessibilityAddTraits(.isHeader)
    }
}

/// A row in the list: its title, its line, why it isn't shown, then Hide or Show.
private struct EditorRow: View {
    let model: RowEditorModel
    let entry: RowLayoutEntry
    let besideHero: Bool

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 1) {
                Text(entry.title)
                    .scaledFont(16, weight: .medium)
                    .foregroundStyle(Palette.text)
                    .lineLimit(1)
                Text(model.line(entry))
                    .scaledFont(12)
                    .foregroundStyle(Palette.muted)
                    .lineLimit(1)
                if besideHero {
                    Text("\(Image(systemName: "rectangle.lefthalf.inset.filled")) Beside the featured title")
                        .scaledFont(12, weight: .medium)
                        .foregroundStyle(Palette.gold)
                        .padding(.top, 2)
                }
                if let note = entry.notShownLine {
                    Label { Text(note) } icon: { Image(systemName: "info.circle") }
                        .labelStyle(NoteLabelStyle())
                        .scaledFont(12)
                        .foregroundStyle(Palette.textSecondary)
                        .padding(.top, 2)
                }
            }
            .opacity(entry.hidden ? 0.45 : 1)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            Button(entry.hidden ? "Show" : "Hide") { model.setHidden(entry.id, !entry.hidden) }
                .buttonStyle(HideButtonStyle(show: entry.hidden))
                .accessibilityLabel("\(entry.hidden ? "Show" : "Hide") \(entry.title)")
                .accessibilityIdentifier("\(entry.hidden ? "show" : "hide")-\(entry.id)")
        }
        .padding(.vertical, 4)
        .accessibilityAction(named: "Move up") { model.moveUp(entry.id) }
        .accessibilityAction(named: "Move down") { model.moveDown(entry.id) }
        .accessibilityIdentifier("editor-\(entry.id)")
    }
}

/// Hide in gold text; Show on a faint gold ground.
private struct HideButtonStyle: ButtonStyle {
    let show: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Palette.gold)
            .padding(.horizontal, 10)
            .frame(minHeight: 32)
            .background(show ? Palette.gold.opacity(0.14) : .clear, in: Capsule())
            .opacity(configuration.isPressed ? 0.6 : 1)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
    }
}

/// A small icon at the top of a note that may wrap.
private struct NoteLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 5) {
            configuration.icon.font(.caption2)
            configuration.title.fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Add a row (AddRow.dc.html): the screen's hidden rows by kind, each with
/// Add, which shows it in its place in the list; then the line that lists are
/// made on the web.
private struct AddRowSheet: View {
    let model: RowEditorModel
    /// The rows hidden when this opened, so one just added stays, as Added.
    let ids: [String]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                } header: {
                    Text("Rows that aren’t on \(model.screenName). Adding one shows it in its place in your list; move it from there.")
                        .scaledFont(13)
                        .foregroundStyle(Palette.muted)
                        .textCase(nil)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if ids.isEmpty {
                    Section {
                        Text("Every row is on \(model.screenName) already.")
                            .foregroundStyle(Palette.muted)
                    }
                    .listRowBackground(Color(hex: 0x26262A))
                }
                ForEach(model.addGroups(ids), id: \.title) { group in
                    Section {
                        ForEach(group.rows) { entry in row(entry) }
                    } header: {
                        GroupHeader(title: group.title)
                    }
                    .listRowBackground(Color(hex: 0x26262A))
                }
                Section {
                    HStack(alignment: .top, spacing: 12) {
                        Image(systemName: "globe").foregroundStyle(Palette.gold).accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 3) {
                            Text(listsMadeOnTheWeb).scaledFont(15, weight: .semibold).foregroundStyle(Palette.text)
                            Text(listsMadeOnTheWebDetail).scaledFont(13).foregroundStyle(Palette.muted)
                        }
                    }
                    .padding(.vertical, 4)
                    .accessibilityElement(children: .combine)
                }
                .listRowBackground(Color(hex: 0x26262A))
            }
            .scrollContentBackground(.hidden)
            .groundBackground()
            .navigationTitle("Add a row")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.fontWeight(.semibold)
                }
            }
        }
        .presentationBackground(Palette.sheet)
    }

    private func row(_ entry: RowLayoutEntry) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 1) {
                Text(entry.title).scaledFont(16, weight: .medium).foregroundStyle(Palette.text).lineLimit(1)
                Text(model.addLine(entry)).scaledFont(12).foregroundStyle(Palette.muted).lineLimit(1)
                if let note = entry.note {
                    Text(note).scaledFont(12).foregroundStyle(Palette.textSecondary).padding(.top, 2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
            if entry.hidden {
                Button("Add") { model.add(entry.id) }
                    .buttonStyle(AddPillStyle())
                    .accessibilityLabel("Add \(entry.title)")
                    .accessibilityIdentifier("add-\(entry.id)")
            } else {
                Text("Added")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.textSecondary)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 32)
                    .background(Palette.outlineStrong, in: Capsule())
            }
        }
        .padding(.vertical, 4)
    }
}

/// Add: gold on a faint gold ground.
private struct AddPillStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(Palette.gold)
            .padding(.horizontal, 14)
            .frame(minHeight: 32)
            .background(Palette.gold.opacity(configuration.isPressed ? 0.24 : 0.14), in: Capsule())
            .frame(minHeight: 44)
            .contentShape(Rectangle())
    }
}
