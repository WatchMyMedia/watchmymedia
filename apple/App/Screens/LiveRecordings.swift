import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Programme action shared by guide details and search. The server owns
/// permission and conflicts; a request is never started by opening the sheet.
struct LiveRecordControls: View {
    let programme: LiveProgramme
    let channel: LiveChannel
    let now: Date
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(LiveExtrasStore.self) private var extras
    @Environment(Navigator.self) private var navigator
    @State private var choosing = false
    @State private var options = false
    @State private var failure: (programmeID: String, message: String)?
    /// Record found the tuners booked: which one gives way.
    @State private var clash: LiveRecordingClash?
    /// After Keep: "Coastline Rescue stays under Won’t record in Recordings."
    @State private var kept: (programmeID: String, note: String)?
    var body: some View {
        // What it offers is decided once, for every menu (LiveProgrammeActions).
        if let recording = actions.recording {
            VStack(spacing: 10) {
                controls(recording)
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
    private var actions: LiveProgrammeActions {
        LiveProgrammeActions(programme, on: channel, now: now, extras: extras, recordings: recordings)
    }
    @ViewBuilder private func controls(_ recording: LiveProgrammeActions.Recording) -> some View {
            switch recording {
            case .marked(let mark):
                HStack(spacing: 12) {
                    RecordingMark(mark, compact: false).frame(maxWidth: .infinity, alignment: .leading)
                    if let recordingNow = actions.recordingNow {
                        Button(liveWatchFromTheStart) {
                            navigator.play(.init(fileID: 0, startMs: 0, title: programme.title, subtitle: programme.episodeLine, recordingID: recordingNow.id))
                        }.buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                    }
                    if let cancel = actions.cancel {
                        Button(liveCancelRecordingTitle(cancel.state, short: true)) {
                            let id = programme.id
                            Task { await recordings.cancel(cancel, programmeID: id); failure = recordings.message.map { (id, $0) } }
                        }
                            .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                            .disabled(recordings.changing.contains(cancel.id))
                    }
                    if actions.clashes { Button("Resolve") { navigator.open(.liveRecordings) } }
                }
                .frame(minHeight: 44)
                .accessibilityIdentifier("live-recording-status")
            case .record(let rest, let asks, let note):
                // "Already recorded", "Not a new episode": said, and Record is still offered.
                if let note {
                    Text(note).font(.subheadline).foregroundStyle(Palette.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading).accessibilityIdentifier("live-recording-note")
                }
                // A programme of a series asks which; a film or a special records at once.
                Button { if asks { choosing = true } else { save(.init()) } } label: {
                    Label(liveRecordTitle(rest: rest), systemImage: "record.circle")
                }
                .buttonStyle(GhostButtonStyle(height: 48))
                .disabled(recordings.changing.contains(programme.id))
                .accessibilityIdentifier("live-record")
                .confirmationDialog(liveRecordHeading(programme.title), isPresented: $choosing, titleVisibility: .visible) {
                    Button(liveRecordJustThisEpisode) { save(.init()) }
                    Button(liveRecordEveryNewEpisode) { save(.init(series: true)) }
                    Button(liveRecordOptionsChoice) { options = true }
                    Button("Cancel", role: .cancel) {}
                } message: { Text("A recording takes a tuner from live viewing if needed.") }
                .sheet(isPresented: $options) {
                    LiveRecordingOptionsSheet(title: programme.title, form: .programme(programme)) { save($0) }
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
            failure = recordings.message ?? liveRecordingChangeFailed
            return
        }
        // The plan may have moved on meanwhile: show what holds the tuners now.
        if let next = LiveRecordingClash(updated) { clash = next } else { finish(kept: false) }
    }
    private func finish(kept: Bool) { dismiss(); done(kept) }
}

struct LiveRecordingOptionsSheet: View {
    let title: String
    /// Which parts the form shows, and what it starts with (LiveRecordingForm).
    let form: LiveRecordingForm
    let save: (LiveRecordingOptions) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var options = LiveRecordingOptions()
    var body: some View {
        let shown = LiveRecordingForm(options: options, like: form)
        NavigationStack {
            Form {
                Section(title) {
                    if form.offersSeries { Toggle("Record the series", isOn: $options.series) }
                    if shown.showsSeriesSettings {
                        Toggle(liveNewEpisodesOnly, isOn: $options.newOnly)
                        Toggle(liveAnyChannel, isOn: $options.anyChannel)
                        Picker(liveKeepTitle, selection: $options.keep) {
                            ForEach(LiveRecordingForm.keepChoices, id: \.self) { Text(liveKeepChoice($0)).tag($0) }
                        }
                    }
                }
                Section("Padding") {
                    if form.offersServerPadding { Toggle(liveUseServerPadding, isOn: $options.useServerPadding) }
                    if shown.showsPadding {
                        Picker(liveStartEarly, selection: $options.padBefore) {
                            ForEach(LiveRecordingForm.paddingChoices, id: \.self) { Text(livePaddingChoice($0)).tag($0) }
                        }
                        Picker(liveEndLate, selection: $options.padAfter) {
                            ForEach(LiveRecordingForm.paddingChoices, id: \.self) { Text(livePaddingChoice($0)).tag($0) }
                        }
                    }
                }
                if shown.showsKeepNote {
                    Section { Text(liveKeepNote).foregroundStyle(Palette.muted) }
                }
            }
            .scrollContentBackground(.hidden).groundBackground()
            .navigationTitle("Recording options").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Save") { save(options); dismiss() }.fontWeight(.semibold) }
            }
        }
        .onAppear { options = form.options }
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
                    plain { ContentUnavailableView(liveNoRecordings, systemImage: "record.circle", description: Text("Choose Record in the guide or make a recording by channel and time.")) }
                }
                plain {
                    Text("Recordings go into your library. The server must be awake when they start. Set folders and access in the server’s web app, under Settings › Live TV › Recordings.")
                        .font(.footnote).foregroundStyle(Palette.muted)
                }
                if compact { plain { Button("New recording") { manual = true }.buttonStyle(PrimaryButtonStyle()) } }
            } else {
                plain { ContentUnavailableView(liveRecordingUnavailable, systemImage: "record.circle", description: Text("Ask the server’s admin to enable recording and allow your account.")) }
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
        // On screen until this list goes: events then stop asking for its sections (PLAN-APPLE-REVIEW.md §4.1.8).
        .task { await recordings.show(LiveRecordingSection.allCases) }
        .sheet(isPresented: $manual) { LiveManualRecordingSheet() }
        .sheet(item: $deleting) { item in LiveRecordingDeleteSheet(recording: item) }
        .sheet(item: $clash) { LiveRecordingClashSheet(clash: $0) }
        .sheet(item: $editingRule) { rule in
            LiveRecordingOptionsSheet(title: rule.title, form: .rule(rule)) { value in
                Task { await recordings.edit(rule, options: value) }
            }
        }
        .sheet(item: $editing) { item in
            LiveRecordingOptionsSheet(title: item.title, form: .recording(item)) { value in Task { await recordings.edit(item, options: value) } }
        }
        .alert("Stop recording this series?", isPresented: Binding(get: { removeRule != nil }, set: { if !$0 { removeRule = nil } }), presenting: removeRule) { rule in
            Button(liveStopSeries, role: .destructive) { Task { await recordings.remove(rule) } }
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
            plain { ErrorState(title: liveRecordingsFailed(section), message: error) { await recordings.load(section, force: true) } }
        } else if recordings.loading.contains(section) && page == nil {
            plain { ProgressView(liveRecordingsLoading(section)).frame(maxWidth: .infinity) }
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
                                Button(liveStopSeries, role: .destructive) { removeRule = rule }
                            }.buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
                        } else { Text(liveSetBy(rule.userName)).font(.caption).foregroundStyle(Palette.muted) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 6)
                    .listRowBackground(Palette.surface)
                }
                if page.nextCursor != nil {
                    Button(liveLoadMore) { Task { await recordings.load(section, more: true) } }
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
                    Text(liveRecordingWhen(item))
                        .font(.caption).foregroundStyle(Palette.muted)
                    if !item.canCancel && !item.userName.isEmpty { Text(liveSetBy(item.userName)).font(.caption).foregroundStyle(Palette.muted) }
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
            let actions = item.actions
            if actions.cancel {
                Button(actions.swipeTitle, role: .destructive) { Task { await recordings.cancel(item.mark, programmeID: item.programmeID) } }
            }
        }
        .accessibilityElement(children: .contain).accessibilityIdentifier("recording-\(item.id)")
    }
    /// What a row offers is decided once, for both apps (LiveRecordingActions).
    @ViewBuilder private func actions(_ item: LiveRecording) -> some View {
        let actions = item.actions
        if let watch = actions.watch {
            Button(watch == .fromStart ? liveWatchFromTheStart : "Watch") { navigator.play(item.playRequest(fromStart: watch == .fromStart)) }
                .buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
        }
        if actions.delete {
            Button("Delete", role: .destructive) { deleting = item }.buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
        }
        if actions.recordInstead {
            // The same choice as when it was set: which holder gives way.
            Button(liveRecordThisInstead) {
                if item.conflictsWith.isEmpty { Task { await recordings.prefer(item) } } else { clash = LiveRecordingClash(item) }
            }.buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold)))
        }
        if actions.edit { Button("Edit") { editing = item }.buttonStyle(GoldTextButtonStyle(font: .subheadline.weight(.semibold))) }
        if actions.cancel {
            Button(actions.cancelTitle) { Task { await recordings.cancel(item.mark, programmeID: item.programmeID) } }
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
                    Text(liveDeleteRecordingNote)
                    Toggle(liveDontRecordAgain, isOn: $doNotRecordAgain)
                }
                if let message = recordings.message { Section { Text(message).foregroundStyle(Palette.warning) } }
                Section {
                    Button(liveDeleteRecording, role: .destructive) {
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
