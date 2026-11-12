import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Programme action shared by guide details and search. The server owns
/// permission and conflicts; a request is never started by opening the sheet.
struct LiveRecordControls: View {
    let programme: LiveProgramme
    let now: Date
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(Navigator.self) private var navigator
    @State private var choosing = false
    @State private var options = false
    @State private var failure: (programmeID: String, message: String)?
    /// Record found the tuners booked: which one gives way.
    @State private var clash: LiveRecordingClash?
    /// After Keep: "Coastline Rescue stays under Won’t record in Recordings."
    @State private var kept: (programmeID: String, note: String)?
    var body: some View {
        if recordings.available, !programme.hasEnded(at: now) {
            VStack(spacing: 10) {
                controls
                if let kept, kept.programmeID == programme.id {
                    Text(kept.note).font(.footnote).foregroundStyle(Palette.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("live-clash-kept")
                }
            }
            .sheet(item: $clash) { found in
                LiveRecordingClashSheet(clash: found) { keeping in
                    kept = keeping ? (programme.id, found.kept) : nil
                }
            }
        }
    }
    @ViewBuilder private var controls: some View {
            let mark = recordings.mark(for: programme)
            if let mark, mark.state.marked {
                HStack(spacing: 12) {
                    RecordingMark(mark, compact: false).frame(maxWidth: .infinity, alignment: .leading)
                    if mark.state == .recording {
                        Button("Watch from the start") {
                            navigator.play(.init(fileID: 0, startMs: 0, title: programme.title, subtitle: programme.episodeLine, recordingID: mark.id))
                        }.buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                    }
                    if mark.canCancel && mark.state.mayCancel {
                        Button(mark.state == .recording ? "Stop" : "Don’t record") {
                            let id = programme.id
                            Task { await recordings.cancel(mark, programmeID: id); failure = recordings.message.map { (id, $0) } }
                        }
                            .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                            .disabled(recordings.changing.contains(mark.id))
                    }
                    if mark.state == .conflict { Button("Resolve") { navigator.open(.liveRecordings) } }
                }
                .frame(minHeight: 44)
                .accessibilityIdentifier("live-recording-status")
            } else {
                // "Already recorded", "Not a new episode": said, and Record is still offered.
                if let note = mark?.note {
                    Text(note).font(.subheadline).foregroundStyle(Palette.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("live-recording-note")
                }
                // A programme of a series asks which; a film or a special records at once.
                Button { if programme.seriesKey != nil { choosing = true } else { save(.init()) } } label: {
                    Label(programme.isOn(at: now) ? "Record the rest" : "Record", systemImage: "record.circle")
                }
                .buttonStyle(GhostButtonStyle(height: 48))
                .disabled(recordings.changing.contains(programme.id))
                .accessibilityIdentifier("live-record")
                .confirmationDialog("Record \(programme.title)", isPresented: $choosing, titleVisibility: .visible) {
                    Button("Just this episode") { save(.init()) }
                    Button("Every new episode") { save(.init(series: true)) }
                    Button("Options…") { options = true }
                    Button("Cancel", role: .cancel) {}
                } message: { Text("A recording takes a tuner from live viewing if needed.") }
                .sheet(isPresented: $options) {
                    LiveRecordingOptionsSheet(title: programme.title, allowsSeries: programme.seriesKey != nil) { save($0) }
                }
            }
            if let failure, failure.programmeID == programme.id {
                Text(failure.message).font(.footnote).foregroundStyle(Palette.warning).accessibilityIdentifier("live-record-error")
            }
    }
    private func save(_ options: LiveRecordingOptions) {
        failure = nil; kept = nil
        let programme = programme
        Task {
            if let result = await recordings.record(programme, options: options) { clash = LiveRecordingClash(result) }
            else if let message = recordings.message { failure = (programme.id, message) }
        }
    }
}

/// The clash after Record, or a Won't record row's choice (the web's
/// `DvrTakeover` dialog, here a sheet; a form sheet on iPad). The Apple
/// canvas has no board of its own for it yet. Each holder not yet recording
/// offers Record … instead, which makes that one give way; Keep changes nothing.
struct LiveRecordingClashSheet: View {
    @State private var clash: LiveRecordingClash
    /// After the new one took a tuner (false), or Keep (true).
    var done: (_ kept: Bool) -> Void = { _ in }
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(\.dismiss) private var dismiss
    @State private var failure: String?
    init(clash: LiveRecordingClash, done: @escaping (_ kept: Bool) -> Void = { _ in }) {
        _clash = State(initialValue: clash); self.done = done
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 8) {
                        Label(clash.title, systemImage: "circle").font(.headline).foregroundStyle(Palette.warning)
                        Text(clash.message).font(.subheadline).foregroundStyle(Palette.textSecondary)
                    }
                    .padding(.vertical, 4)
                    .accessibilityElement(children: .combine)
                }
                if !clash.holders.isEmpty {
                    Section(clash.choices.isEmpty ? "Recording then" : "Which one won’t record") {
                        ForEach(clash.holders) { holder in holderRow(holder) }
                    }
                }
                if let failure { Section { Text(failure).foregroundStyle(Palette.warning).accessibilityIdentifier("live-clash-error") } }
            }
            .scrollContentBackground(.hidden).groundBackground()
            // Keep stays in reach at the sheet's medium height, however many hold the tuners.
            .safeAreaInset(edge: .bottom) {
                Button(clash.keep) { finish(kept: !clash.choices.isEmpty) }
                    .buttonStyle(GhostButtonStyle(height: 48))
                    .padding(.horizontal, 20).padding(.vertical, 10)
                    .background(Palette.ground)
                    .accessibilityIdentifier("live-clash-keep")
            }
            .navigationTitle(clash.recording.title).navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .preferredColorScheme(.dark)
        .accessibilityIdentifier("live-recording-clash")
    }
    private func holderRow(_ holder: LiveRecordingHolder) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(holder.title).font(.headline)
            Text(clash.line(holder)).font(.subheadline).foregroundStyle(Palette.textSecondary)
            if let status = clash.status(holder) {
                Text(status).font(.caption).foregroundStyle(holder.running ? Palette.liveText : Palette.muted)
            }
            if clash.choices.contains(holder) {
                Button(clash.recordInstead) { Task { await giveWay(holder) } }
                    .buttonStyle(.borderless).foregroundStyle(Palette.gold).font(.subheadline.weight(.semibold))
                    .frame(minHeight: 44)
                    .disabled(recordings.changing.contains(clash.id))
                    .accessibilityLabel(clash.recordInsteadLabel(holder))
                    .accessibilityIdentifier("live-clash-instead-\(holder.id)")
            }
        }
        .padding(.vertical, 4)
    }
    private func giveWay(_ holder: LiveRecordingHolder) async {
        failure = nil
        guard let updated = await recordings.prefer(clash.recording, over: holder) else {
            failure = recordings.message ?? "Couldn’t change the recording. Try again."
            return
        }
        // The plan may have moved on meanwhile: show what holds the tuners now.
        if let next = LiveRecordingClash(updated) { clash = next } else { finish(kept: false) }
    }
    private func finish(kept: Bool) { dismiss(); done(kept) }
}

struct LiveRecordingOptionsSheet: View {
    let title: String
    var allowsSeries = true
    var allowsServerPadding = true
    var initial = LiveRecordingOptions()
    let save: (LiveRecordingOptions) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var options = LiveRecordingOptions()
    var body: some View {
        NavigationStack {
            Form {
                Section(title) {
                    if allowsSeries { Toggle("Record the series", isOn: $options.series) }
                    if options.series {
                        Toggle("New episodes only", isOn: $options.newOnly)
                        Toggle("Any channel", isOn: $options.anyChannel)
                        Picker("Keep", selection: $options.keep) {
                            Text("All recordings").tag(0)
                            ForEach(1...10, id: \.self) { Text("The latest \($0)").tag($0) }
                        }
                    }
                }
                Section("Padding") {
                    if allowsServerPadding { Toggle("Use server padding defaults", isOn: $options.useServerPadding) }
                    if !options.useServerPadding {
                        Picker("Start early", selection: $options.padBefore) {
                            ForEach(LiveRecordingOptions.paddingChoices, id: \.self) { Text("\($0) min").tag($0) }
                        }
                        Picker("End late", selection: $options.padAfter) {
                            ForEach(LiveRecordingOptions.paddingChoices, id: \.self) { Text("\($0) min").tag($0) }
                        }
                    }
                }
                if options.keep > 0 {
                    Section { Text("The oldest by air time are deleted, watched or not. Only recordings from this rule are removed.").foregroundStyle(Palette.muted) }
                }
            }
            .scrollContentBackground(.hidden).groundBackground()
            .navigationTitle("Recording options").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { save(options); dismiss() }.fontWeight(.semibold) }
            }
        }
        .onAppear { options = initial }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .preferredColorScheme(.dark)
    }
}

struct LiveRecordingsScreen: View {
    var compact = false
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @State private var manual = false
    @State private var editingRule: LiveRecordingRule?
    @State private var editing: LiveRecording?
    @State private var deleting: LiveRecording?
    @State private var removeRule: LiveRecordingRule?
    @State private var clash: LiveRecordingClash?
    /// A list, so a swipe left on a recording this person may change skips it,
    /// stops it or doesn't record it (DvrRecordings); the row's buttons do the same.
    var body: some View {
        List {
            if recordings.available {
                if compact { plain { Text("Recordings").font(.title2.bold()) } }
                ForEach(LiveRecordingSection.allCases, id: \.self) { section in
                    sectionView(section)
                }
                if recordings.pages.values.allSatisfy({ $0.items.isEmpty && $0.rules.isEmpty }), recordings.loading.isEmpty {
                    plain { ContentUnavailableView("No recordings", systemImage: "record.circle", description: Text("Choose Record in the guide or make a recording by channel and time.")) }
                }
                plain {
                    Text("Recordings go into your library. The server must be awake when they start. Set folders and access in the server’s web app, under Settings › Live TV › Recordings.")
                        .font(.footnote).foregroundStyle(Palette.muted)
                }
                if compact { plain { Button("New recording") { manual = true }.buttonStyle(PrimaryButtonStyle()) } }
            } else {
                plain { ContentUnavailableView("Recording isn’t available", systemImage: "record.circle", description: Text("Ask the server’s admin to enable recording and allow your account.")) }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .frame(maxWidth: compact ? .infinity : 1100).frame(maxWidth: .infinity)
        .navigationTitle(compact ? "" : "Recordings")
        .toolbar {
            if !compact && recordings.available { ToolbarItem(placement: .topBarTrailing) { Button { manual = true } label: { Label("New recording", systemImage: "plus") }.accessibilityIdentifier("live-new-recording") } }
        }
        .groundBackground()
        .refreshable { await recordings.refresh() }
        .task(id: services.liveTV.contextID) {
            for section in LiveRecordingSection.allCases { await recordings.load(section) }
        }
        .sheet(isPresented: $manual) { LiveManualRecordingSheet() }
        .sheet(item: $deleting) { item in LiveRecordingDeleteSheet(recording: item) }
        .sheet(item: $clash) { LiveRecordingClashSheet(clash: $0) }
        .sheet(item: $editingRule) { rule in
            LiveRecordingOptionsSheet(title: rule.title, allowsSeries: rule.kind == "series", allowsServerPadding: false,
                                      initial: .init(series: rule.kind == "series", newOnly: rule.newOnly, anyChannel: rule.anyChannel, keep: rule.keep, padBefore: rule.padBefore, padAfter: rule.padAfter, useServerPadding: false)) { value in
                Task { await recordings.edit(rule, options: value) }
            }
        }
        .sheet(item: $editing) { item in
            LiveRecordingOptionsSheet(title: item.title, allowsSeries: false, allowsServerPadding: false,
                                      initial: .init(padBefore: item.padBefore, padAfter: item.padAfter, useServerPadding: false)) { value in Task { await recordings.edit(item, options: value) } }
        }
        .alert("Stop recording this series?", isPresented: Binding(get: { removeRule != nil }, set: { if !$0 { removeRule = nil } }), presenting: removeRule) { rule in
            Button("Stop series", role: .destructive) { Task { await recordings.remove(rule) } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in Text("Upcoming episodes are cancelled. Recordings already made stay in the library.") }
        .messageToast(Binding(get: { recordings.message }, set: { recordings.message = $0 }))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-recordings")
    }
    /// A row of the list's own, without a card behind it.
    private func plain<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content().listRowBackground(Color.clear).listRowSeparator(.hidden).listRowInsets(.init(top: 6, leading: 0, bottom: 6, trailing: 0))
    }
    @ViewBuilder private func sectionView(_ section: LiveRecordingSection) -> some View {
        let page = recordings.page(section)
        if let error = recordings.errors[section] {
            plain { ErrorState(title: "Couldn’t load \(section.title.lowercased())", message: error) { await recordings.load(section, force: true) } }
        } else if recordings.loading.contains(section) && page == nil {
            plain { ProgressView("Loading \(section.title.lowercased())").frame(maxWidth: .infinity) }
        } else if let page, !page.items.isEmpty || !page.rules.isEmpty {
            Section {
                ForEach(page.items) { item in recordingRow(item).listRowBackground(Palette.surface) }
                ForEach(page.rules) { rule in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(rule.title).font(.headline)
                        Text(rule.detail).font(.subheadline).foregroundStyle(Palette.muted)
                        if rule.canCancel {
                            HStack {
                                Button("Edit") { editingRule = rule }
                                Button("Stop series", role: .destructive) { removeRule = rule }
                            }.buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                        } else { Text("Set by \(rule.userName)").font(.caption).foregroundStyle(Palette.muted) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 6)
                    .listRowBackground(Palette.surface)
                }
                if page.nextCursor != nil {
                    Button("Load more") { Task { await recordings.load(section, more: true) } }
                        .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                        .disabled(recordings.loading.contains(section))
                        .listRowBackground(Palette.surface)
                }
            } header: {
                Text(section.title).font(.title3.bold()).foregroundStyle(Palette.text).textCase(nil).accessibilityAddTraits(.isHeader)
            }
            .listRowSeparatorTint(Palette.separator)
        }
    }
    private func recordingRow(_ item: LiveRecording) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 12) {
                if !compact { Artwork(url: item.pictureURL, seed: item.title, maxPixel: 400).frame(width: 112, height: 63).clipShape(RoundedRectangle(cornerRadius: 8)) }
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.title).font(.headline)
                    if let line = item.episodeLine { Text(line).font(.subheadline).foregroundStyle(Palette.textSecondary) }
                    Text("\(LiveClock.device.weekdayName(item.start)) \(LiveClock.device.range(item.start, item.end)) · \(item.number)")
                        .font(.caption).foregroundStyle(Palette.muted)
                    if !item.canCancel && !item.userName.isEmpty { Text("Set by \(item.userName)").font(.caption).foregroundStyle(Palette.muted) }
                }
                Spacer(minLength: 0)
            }
            RecordingMark(item.mark, compact: false, title: item.statusTitle)
            if let reason = item.reasonMessage { Text(reason).font(.footnote).foregroundStyle(Palette.muted) }
            if item.state == .recording {
                TimelineView(.periodic(from: .now, by: 5)) { context in LiveProgressBar(progress: item.progress(at: context.date)) }
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 14) { actions(item) }
                VStack(alignment: .leading, spacing: 4) { actions(item) }
            }
        }
        .padding(.vertical, 6).frame(maxWidth: .infinity, alignment: .leading)
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if item.canCancel && item.state.mayCancel {
                Button(swipeTitle(item), role: .destructive) { Task { await recordings.cancel(item.mark, programmeID: item.programmeID) } }
            }
        }
        .accessibilityElement(children: .contain).accessibilityIdentifier("recording-\(item.id)")
    }
    /// The swipe's words: "Stop recording", "Skip this one" for a series' episode, else "Don’t record".
    private func swipeTitle(_ item: LiveRecording) -> String {
        item.state == .recording ? "Stop recording" : item.ruleID != nil ? "Skip this one" : "Don’t record"
    }
    @ViewBuilder private func actions(_ item: LiveRecording) -> some View {
        if item.playable {
            Button(item.state == .recording ? "Watch from the start" : "Watch") { navigator.play(item.playRequest(fromStart: item.state == .recording)) }
                .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
        }
        if item.canDelete && [.done, .partial].contains(item.state) {
            Button("Delete", role: .destructive) { deleting = item }.buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
        }
        if item.canCancel && item.state.mayCancel {
            if item.state == .conflict {
                // The same choice as when it was set: which holder gives way.
                Button("Record this instead") {
                    if item.conflictsWith.isEmpty { Task { await recordings.prefer(item) } } else { clash = LiveRecordingClash(item) }
                }.buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
            }
            if item.state == .scheduled { Button("Edit") { editing = item }.buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold))) }
            Button(item.state == .recording ? "Stop recording" : "Don’t record") { Task { await recordings.cancel(item.mark, programmeID: item.programmeID) } }
                .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                .disabled(recordings.changing.contains(item.id))
        }
    }
}

struct LiveManualRecordingSheet: View {
    @Environment(AppServices.self) private var services
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(\.dismiss) private var dismiss
    @State private var request = LiveManualRecording()
    @State private var clash: LiveRecordingClash?
    var body: some View {
        NavigationStack {
            Form {
                Section("Channel and name") {
                    Picker("Channel", selection: $request.channelID) {
                        Text("Choose a channel").tag("")
                        ForEach(services.liveTV.channels.filter(\.available)) { Text($0.title).tag($0.id) }
                    }
                    TextField("Recording name", text: $request.name).accessibilityIdentifier("manual-recording-name")
                }
                Section("When") {
                    DatePicker("Starts", selection: $request.start)
                    DatePicker("Ends", selection: $request.end)
                    Picker("Repeat", selection: $request.repeatKind) {
                        ForEach(LiveManualRepeat.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    Text("Time zone: \(request.zone). Repeating recordings keep this local time when daylight saving changes.").font(.footnote).foregroundStyle(Palette.muted)
                }
                if let validation = request.validationMessage { Section { Text(validation).foregroundStyle(Palette.warning) } }
                if let message = recordings.message { Section { Text(message).foregroundStyle(Palette.warning) } }
            }
            .scrollContentBackground(.hidden).groundBackground()
            .navigationTitle("New recording").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Record") {
                        Task {
                            guard let result = await recordings.manual(request) else { return }
                            if let found = LiveRecordingClash(result) { clash = found } else { dismiss() }
                        }
                    }
                        .disabled(request.validationMessage != nil || recordings.changing.contains("manual"))
                        .accessibilityIdentifier("manual-recording-save")
                }
            }
        }
        .preferredColorScheme(.dark)
        .presentationDragIndicator(.visible)
        // A clash shows before the form goes; either way the recording is set.
        .sheet(item: $clash, onDismiss: { dismiss() }) { LiveRecordingClashSheet(clash: $0) }
    }
}

struct LiveRecordingDeleteSheet: View {
    let recording: LiveRecording
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(\.dismiss) private var dismiss
    @State private var doNotRecordAgain = true
    var body: some View {
        NavigationStack {
            Form {
                Section(recording.title) {
                    Text("This permanently deletes the file WatchMyMedia recorded. Your watch history and the series rule stay.")
                    Toggle("Don’t record it again", isOn: $doNotRecordAgain)
                }
                if let message = recordings.message { Section { Text(message).foregroundStyle(Palette.warning) } }
                Section {
                    Button("Delete recording", role: .destructive) {
                        Task { if await recordings.deleteFile(recording, recordAgain: !doNotRecordAgain) { dismiss() } }
                    }.disabled(recordings.changing.contains(recording.id))
                }
            }
            .scrollContentBackground(.hidden).groundBackground()
            .navigationTitle("Delete recording?").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }.preferredColorScheme(.dark).presentationDetents([.medium])
    }
}
