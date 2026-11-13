import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Full-screen choices preserve remote focus and keep scheduling in the shared
/// store. Only a programme of a series opens it; a film or a special records at once.
struct TVLiveRecordChoice: View {
    let programme: LiveProgramme
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(\.dismiss) private var dismiss
    @State private var options = LiveRecordingOptions()
    @State private var showingOptions = false
    /// Record found the tuners booked: the choice of which gives way takes the screen.
    @State private var clash: LiveRecordingClash?
    var body: some View {
        if let clash {
            TVLiveRecordingClashView(clash: clash) { kept in
                if kept { recordings.message = clash.kept }
                dismiss()
            }
        } else {
            choice
        }
    }
    private var choice: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                Text("Record \(programme.title)").font(.system(size: 46, weight: .bold)).accessibilityAddTraits(.isHeader)
                if showingOptions {
                    Toggle("Every new episode", isOn: $options.series)
                    if options.series {
                        Toggle("New episodes only", isOn: $options.newOnly)
                        Toggle("Any channel", isOn: $options.anyChannel)
                        Picker("Keep", selection: $options.keep) {
                            Text("All recordings").tag(0)
                            ForEach(1...10, id: \.self) { Text("The latest \($0)").tag($0) }
                        }
                    }
                    Toggle("Use server padding defaults", isOn: $options.useServerPadding)
                    if !options.useServerPadding {
                    Picker("Start early", selection: $options.padBefore) { ForEach(LiveRecordingOptions.paddingChoices, id: \.self) { Text("\($0) min").tag($0) } }
                    Picker("End late", selection: $options.padAfter) { ForEach(LiveRecordingOptions.paddingChoices, id: \.self) { Text("\($0) min").tag($0) } }
                    }
                    Button("Record with these options") { save(options) }.buttonStyle(TVButtonStyle(kind: .primary))
                } else {
                    Button("Just this episode") { save(.init()) }.buttonStyle(TVButtonStyle(kind: .primary))
                    Button("Every new episode") { save(.init(series: true)) }.buttonStyle(TVButtonStyle())
                    Button("Options…") { showingOptions = true }.buttonStyle(TVButtonStyle())
                }
                if let message = recordings.message { Text(message).font(.system(size: 24)).foregroundStyle(Palette.warning) }
                Text("Recordings may take a tuner from a live viewer.").font(.system(size: 23)).foregroundStyle(Palette.muted)
                Button("Cancel") { dismiss() }.buttonStyle(TVButtonStyle())
            }
            .frame(maxWidth: 1100, alignment: .leading).padding(70).frame(maxWidth: .infinity)
        }.groundBackground().foregroundStyle(Palette.text)
    }
    private func save(_ options: LiveRecordingOptions) {
        Task {
            guard let result = await recordings.record(programme, options: options) else { return }
            if let found = LiveRecordingClash(result) { clash = found } else { dismiss() }
        }
    }
}

/// The clash after Record, or a Won't record row's choice, full screen (the
/// web's `DvrTakeover` dialog; the Apple canvas has no board for it yet).
/// Keep holds the focus, so a stray press changes nothing; Back is Keep.
struct TVLiveRecordingClashView: View {
    @State private var clash: LiveRecordingClash
    /// After the new one took a tuner (false), or Keep (true).
    let done: (_ kept: Bool) -> Void
    @Environment(LiveRecordingsStore.self) private var recordings
    @State private var failure: String?
    @FocusState private var keepFocused: Bool
    init(clash: LiveRecordingClash, done: @escaping (_ kept: Bool) -> Void) { _clash = State(initialValue: clash); self.done = done }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                Text("\(Image(systemName: "circle")) \(clash.title)").font(.system(size: 42, weight: .bold)).foregroundStyle(Palette.warning)
                    .accessibilityAddTraits(.isHeader)
                Text(clash.message).font(.system(size: 24)).foregroundStyle(Palette.textSecondary)
                ForEach(clash.holders) { holder in
                    HStack(spacing: 24) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(holder.title).font(.system(size: 28, weight: .semibold))
                            Text(clash.line(holder)).font(.system(size: 21)).foregroundStyle(Palette.textSecondary)
                            if let status = clash.status(holder) {
                                Text(status).font(.system(size: 20)).foregroundStyle(holder.running ? Palette.liveText : Palette.muted)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        if clash.choices.contains(holder) {
                            Button(clash.recordInsteadOf(holder)) { Task { await giveWay(holder) } }
                                .buttonStyle(TVButtonStyle(kind: .primary))
                                .disabled(recordings.changing.contains(clash.id))
                                .accessibilityLabel(clash.recordInsteadLabel(holder))
                        }
                    }
                    .padding(20).background(Palette.surface, in: RoundedRectangle(cornerRadius: 16))
                    .frame(maxWidth: .infinity).focusSection()
                }
                if let failure { Text(failure).font(.system(size: 24)).foregroundStyle(Palette.warning) }
                Button(clash.keep) { done(!clash.choices.isEmpty) }.buttonStyle(TVButtonStyle()).focused($keepFocused)
            }
            .frame(maxWidth: 1300, alignment: .leading).padding(70).frame(maxWidth: .infinity)
        }
        .groundBackground().foregroundStyle(Palette.text)
        .defaultFocus($keepFocused, true)
        .onExitCommand { done(!clash.choices.isEmpty) }
        .accessibilityIdentifier("live-recording-clash")
    }
    private func giveWay(_ holder: LiveRecordingHolder) async {
        failure = nil
        guard let updated = await recordings.prefer(clash.recording, over: holder) else {
            failure = recordings.message ?? "Couldn’t change the recording. Try again."
            return
        }
        if let next = LiveRecordingClash(updated) { clash = next } else { done(false) }
    }
}

struct TVLiveRecordingsScreen: View {
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @State private var deleting: LiveRecording?
    @State private var editing: LiveRecordingRule?
    @State private var clash: LiveRecordingClash?
    var body: some View {
        TabPage {
            VStack(alignment: .leading, spacing: 34) {
                Text("Recordings").font(.system(size: 48, weight: .heavy)).accessibilityAddTraits(.isHeader)
                if recordings.available {
                    ForEach(LiveRecordingSection.allCases, id: \.self) { section in
                        sectionView(section)
                    }
                    if recordings.pages.values.allSatisfy({ $0.items.isEmpty && $0.rules.isEmpty }), recordings.loading.isEmpty {
                        TVEmptyState(systemImage: "record.circle", title: "No recordings", message: "Hold Select on a programme in the guide and choose Record. Make manual recordings on your phone or in the web app.")
                    }
                } else {
                    TVEmptyState(systemImage: "record.circle", title: "Recording isn’t available", message: "Ask the server’s admin to turn recording on and allow your account.")
                }
            }
            .padding(.horizontal, TVLayout.gutter).padding(.vertical, 40)
        }
        .task(id: services.liveTV.contextID) { for section in LiveRecordingSection.allCases { await recordings.load(section) } }
        // On screen until this list goes: events then stop asking for its sections (PLAN-APPLE-REVIEW.md §4.1.8).
        .task { await recordings.show(LiveRecordingSection.allCases) }
        .messagePill(Binding(get: { recordings.message }, set: { recordings.message = $0 }))
        .fullScreenCover(item: $deleting) { item in TVLiveRecordingDelete(recording: item) }
        .fullScreenCover(item: $clash) { found in TVLiveRecordingClashView(clash: found) { _ in clash = nil } }
        .sheet(item: $editing) { rule in TVLiveRecordingRuleOptions(rule: rule) }
        .accessibilityElement(children: .contain).accessibilityIdentifier("live-recordings")
    }
    @ViewBuilder private func sectionView(_ section: LiveRecordingSection) -> some View {
        let page = recordings.page(section)
        if let error = recordings.errors[section] {
            TVErrorState(title: "Couldn’t load \(section.title.lowercased())", message: error) { await recordings.load(section, force: true) }
        } else if recordings.loading.contains(section) && page == nil {
            ProgressView("Loading \(section.title.lowercased())")
        } else if let page, !page.items.isEmpty || !page.rules.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                Text(section.title).font(.system(size: 28, weight: .bold))
                ForEach(page.items) { item in row(item) }
                ForEach(page.rules) { rule in
                    HStack(spacing: 24) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(rule.title).font(.system(size: 28, weight: .semibold))
                            Text(rule.detail).font(.system(size: 20)).foregroundStyle(Palette.muted)
                        }.frame(maxWidth: .infinity, alignment: .leading)
                        if rule.canCancel {
                            Button("Edit") { editing = rule }.buttonStyle(TVButtonStyle())
                            Button("Stop series") { Task { await recordings.remove(rule) } }.buttonStyle(TVButtonStyle())
                        } else { Text("Set by \(rule.userName)").font(.system(size: 20)).foregroundStyle(Palette.muted) }
                    }.focusSection()
                }
                if page.nextCursor != nil { Button("Load more") { Task { await recordings.load(section, more: true) } }.buttonStyle(TVButtonStyle()) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    private func row(_ item: LiveRecording) -> some View {
        HStack(spacing: 26) {
            Artwork(url: item.pictureURL, seed: item.title, maxPixel: 480).frame(width: 224, height: 126).clipShape(RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 7) {
                Text(item.title).font(.system(size: 28, weight: .semibold))
                if let line = item.episodeLine { Text(line).font(.system(size: 21)).foregroundStyle(Palette.textSecondary) }
                Text("\(LiveClock.device.weekdayName(item.start)) \(LiveClock.device.range(item.start, item.end)) · \(item.number)").font(.system(size: 20)).foregroundStyle(Palette.muted)
                RecordingMark(item.mark, compact: false, title: item.statusTitle)
                if let reason = item.reasonMessage { Text(reason).font(.system(size: 20)).foregroundStyle(Palette.muted) }
                if item.state == .recording { TimelineView(.periodic(from: .now, by: 5)) { context in LiveProgressBar(progress: item.progress(at: context.date)) } }
            }.frame(maxWidth: .infinity, alignment: .leading)
            if item.playable {
                Button(item.state == .recording ? "Watch from the start" : "Watch") { navigator.play(item.playRequest(fromStart: item.state == .recording)) }
                    .buttonStyle(TVButtonStyle(kind: .primary))
            }
            if item.canDelete && [.done, .partial].contains(item.state) { Button("Delete") { deleting = item }.buttonStyle(TVButtonStyle()) }
            if item.canCancel && item.state.mayCancel {
                if item.state == .conflict {
                    // The same choice as when it was set: which holder gives way.
                    Button("Record this instead") {
                        if item.conflictsWith.isEmpty { Task { await recordings.prefer(item) } } else { clash = LiveRecordingClash(item) }
                    }.buttonStyle(TVButtonStyle())
                }
                Button(item.state == .recording ? "Stop recording" : "Don’t record") { Task { await recordings.cancel(item.mark, programmeID: item.programmeID) } }.buttonStyle(TVButtonStyle())
            }
        }
        .padding(20).background(Palette.surface, in: RoundedRectangle(cornerRadius: 16))
        .frame(maxWidth: .infinity).focusSection()
        .accessibilityElement(children: .contain).accessibilityIdentifier("recording-\(item.id)")
    }
}

private struct TVLiveRecordingRuleOptions: View {
    let rule: LiveRecordingRule
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(\.dismiss) private var dismiss
    @State private var options = LiveRecordingOptions(series: true)
    var body: some View {
        Form {
            Section(rule.title) {
                if rule.kind == "series" {
                    Toggle("New episodes only", isOn: $options.newOnly)
                    Toggle("Any channel", isOn: $options.anyChannel)
                    Picker("Keep", selection: $options.keep) {
                        Text("All recordings").tag(0)
                        ForEach(1...10, id: \.self) { Text("The latest \($0)").tag($0) }
                    }
                }
                Picker("Start early", selection: $options.padBefore) { ForEach(LiveRecordingOptions.paddingChoices, id: \.self) { Text("\($0) min").tag($0) } }
                Picker("End late", selection: $options.padAfter) { ForEach(LiveRecordingOptions.paddingChoices, id: \.self) { Text("\($0) min").tag($0) } }
                if let message = recordings.message { Text(message).foregroundStyle(Palette.warning) }
                Button("Save") { Task { await recordings.edit(rule, options: options); if recordings.message == nil { dismiss() } } }
                Button("Cancel") { dismiss() }
            }
        }.onAppear { options = .init(series: rule.kind == "series", newOnly: rule.newOnly, anyChannel: rule.anyChannel, keep: rule.keep, padBefore: rule.padBefore, padAfter: rule.padAfter, useServerPadding: false) }
    }
}

private struct TVLiveRecordingDelete: View {
    let recording: LiveRecording
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(\.dismiss) private var dismiss
    @State private var doNotRecordAgain = true
    var body: some View {
        Form {
            Section("Delete “\(recording.title)”?") {
                Text("This permanently deletes the file WatchMyMedia recorded. Your watch history and the series rule stay.")
                Toggle("Don’t record it again", isOn: $doNotRecordAgain)
                if let message = recordings.message { Text(message).foregroundStyle(Palette.warning) }
                Button("Cancel") { dismiss() }
                Button("Delete recording", role: .destructive) {
                    Task { if await recordings.deleteFile(recording, recordAgain: !doNotRecordAgain) { dismiss() } }
                }.disabled(recordings.changing.contains(recording.id))
            }
        }
    }
}
