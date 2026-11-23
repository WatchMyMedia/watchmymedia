import SwiftUI
import WMMFeatures
import WMMModel
import WMMUI

/// Record with options… from a programme's sheet: every new episode or
/// not, the series' settings and the padding, then Record. Full screen, so
/// remote focus stays in the form; scheduling is the shared store's.
struct TVLiveRecordChoice: View {
    let programme: LiveProgramme
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(\.dismiss) private var dismiss
    @State private var options = LiveRecordingOptions()
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
                Text(liveRecordHeading(programme.title)).font(.tv(.title3)).accessibilityAddTraits(.isHeader)
                // The options form's parts (LiveRecordingForm).
                let form = LiveRecordingForm(options: options, like: .programme(programme))
                if form.offersSeries { Toggle(liveRecordEveryNewEpisode, isOn: $options.series) }
                if form.showsSeriesSettings {
                    Toggle(liveNewEpisodesOnly, isOn: $options.newOnly)
                    Toggle(liveAnyChannel, isOn: $options.anyChannel)
                    Picker(liveKeepTitle, selection: $options.keep) {
                        ForEach(LiveRecordingForm.keepChoices, id: \.self) { Text(liveKeepChoice($0)).tag($0) }
                    }
                }
                if form.offersServerPadding { Toggle(liveUseServerPadding, isOn: $options.useServerPadding) }
                if form.showsPadding {
                    Picker(liveStartEarly, selection: $options.padBefore) { ForEach(LiveRecordingForm.paddingChoices, id: \.self) { Text(livePaddingChoice($0)).tag($0) } }
                    Picker(liveEndLate, selection: $options.padAfter) { ForEach(LiveRecordingForm.paddingChoices, id: \.self) { Text(livePaddingChoice($0)).tag($0) } }
                }
                HStack(spacing: 24) {
                    Button("Record") { save(options) }.buttonStyle(TVButtonStyle(kind: .primary))
                    Button("Cancel") { dismiss() }.buttonStyle(TVButtonStyle())
                }
                .padding(.top, 12)
                .focusSection()
                if let message = recordings.message { Text(message).font(.tv(.body)).foregroundStyle(Palette.warning) }
                Text("Recordings may take a tuner from a live viewer.").font(.tv(.caption2)).foregroundStyle(Palette.muted)
            }
            .font(.tv(.body))
            .frame(maxWidth: 1100, alignment: .leading)
            .padding(.vertical, TVLayout.safeTop)
            .frame(maxWidth: .infinity)
        }
        .groundBackground().foregroundStyle(Palette.text)
    }
    private func save(_ options: LiveRecordingOptions) {
        Task {
            guard let result = await recordings.record(programme, options: options) else { return }
            if let found = LiveRecordingClash(result) { clash = found } else { dismiss() }
        }
    }
}

/// Recordings that overlap, and one that won't record: one choice of which
/// to leave out. Each holder that can give way is a row; Select on it
/// leaves it out and records the new one. The one left out is last, with
/// focus, so a stray press changes nothing; Back keeps it so too.
struct TVLiveRecordingClashView: View {
    @State private var clash: LiveRecordingClash
    /// After the new one took a tuner (false), or Keep (true).
    let done: (_ kept: Bool) -> Void
    @Environment(LiveRecordingsStore.self) private var recordings
    @State private var failure: String?
    @FocusState private var keepFocused: Bool
    init(clash: LiveRecordingClash, done: @escaping (_ kept: Bool) -> Void) { _clash = State(initialValue: clash); self.done = done }
    var body: some View {
        TVSheet {
            Text(clash.title).font(.tv(.title3)).foregroundStyle(Palette.text)
                .accessibilityAddTraits(.isHeader)
            Text(clash.choices.isEmpty ? clash.message : liveClashChoose(clash, now: .now))
                .font(.tv(.body)).foregroundStyle(Palette.textSecondary)
            VStack(spacing: 12) {
                ForEach(clash.holders) { holder in
                    let choosable = clash.choices.contains(holder)
                    TVSheetRow(title: holder.title, value: holder.running ? "Recording now" : "Will record",
                               line: [clash.line(holder), clash.status(holder) == "Recording now" ? nil : clash.status(holder)].compactMap { $0 }.joined(separator: " · ")) {
                        if choosable { Task { await giveWay(holder) } }
                    }
                    .disabled(!choosable || recordings.changing.contains(clash.id))
                    .accessibilityLabel(choosable ? clash.recordInsteadLabel(holder) : holder.title)
                }
                TVSheetRow(title: clash.recording.title, value: clash.choices.isEmpty ? nil : "Left out",
                           line: "\(clash.recording.number) · \(LiveClock.device.range(clash.recording.start, clash.recording.end))",
                           warns: true) { done(!clash.choices.isEmpty) }
                    .focused($keepFocused)
                    .accessibilityLabel(clash.keep)
                    .accessibilityIdentifier("live-clash-keep")
            }
            .padding(.top, 8)
            .focusSection()
            if let failure { Text(failure).font(.tv(.body)).foregroundStyle(Palette.warning) }
        }
        .defaultFocus($keepFocused, true)
        .onExitCommand { done(!clash.choices.isEmpty) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("live-recording-clash")
    }
    private func giveWay(_ holder: LiveRecordingHolder) async {
        failure = nil
        guard let updated = await recordings.prefer(clash.recording, over: holder) else {
            failure = recordings.message ?? liveRecordingChangeFailed
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
                        TVEmptyState(systemImage: "record.circle", title: liveNoRecordings, message: "Hold Select on a programme in the guide and choose Record. Make manual recordings on your phone or in the web app.")
                    }
                } else {
                    TVEmptyState(systemImage: "record.circle", title: liveRecordingUnavailable, message: "Ask the server’s admin to turn recording on and allow your account.")
                }
            }
            .padding(.horizontal, TVLayout.gutter).padding(.vertical, 40)
        }
        .task(id: services.liveTV.contextID) { for section in LiveRecordingSection.allCases { await recordings.load(section) } }
        // On screen until this list goes: events then stop asking for its sections.
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
            TVErrorState(title: liveRecordingsFailed(section), message: error) { await recordings.load(section, force: true) }
        } else if recordings.loading.contains(section) && page == nil {
            ProgressView(liveRecordingsLoading(section))
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
                            Button(liveStopSeries) { Task { await recordings.remove(rule) } }.buttonStyle(TVButtonStyle())
                        } else { Text(liveSetBy(rule.userName)).font(.system(size: 20)).foregroundStyle(Palette.muted) }
                    }.focusSection()
                }
                if page.nextCursor != nil { Button(liveLoadMore) { Task { await recordings.load(section, more: true) } }.buttonStyle(TVButtonStyle()) }
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }
    private func row(_ item: LiveRecording) -> some View {
        HStack(spacing: 26) {
            Artwork(url: item.pictureURL, seed: item.title, maxPixel: 480).frame(width: 224, height: 126).clipShape(RoundedRectangle(cornerRadius: 12))
            VStack(alignment: .leading, spacing: 7) {
                Text(item.title).font(.system(size: 28, weight: .semibold))
                if let line = item.episodeLine { Text(line).font(.system(size: 21)).foregroundStyle(Palette.textSecondary) }
                Text(liveRecordingWhen(item)).font(.system(size: 20)).foregroundStyle(Palette.muted)
                RecordingMark(item.mark, compact: false, title: item.statusTitle)
                if let reason = item.reasonMessage { Text(reason).font(.system(size: 20)).foregroundStyle(Palette.muted) }
                if item.state == .recording { TimelineView(.periodic(from: .now, by: 5)) { context in LiveProgressBar(progress: item.progress(at: context.date)) } }
            }.frame(maxWidth: .infinity, alignment: .leading)
            // What a row offers is decided once, for both apps (LiveRecordingActions); this board has no Edit.
            let actions = item.actions
            if let watch = actions.watch {
                Button(watch == .fromStart ? liveWatchFromTheStart : "Watch") { navigator.play(item.playRequest(fromStart: watch == .fromStart)) }
                    .buttonStyle(TVButtonStyle(kind: .primary))
            }
            if actions.delete { Button("Delete") { deleting = item }.buttonStyle(TVButtonStyle()) }
            if actions.recordInstead {
                // The same choice as when it was set: which holder gives way.
                Button(liveRecordThisInstead) {
                    if item.conflictsWith.isEmpty { Task { await recordings.prefer(item) } } else { clash = LiveRecordingClash(item) }
                }.buttonStyle(TVButtonStyle())
            }
            if actions.cancel {
                Button(actions.cancelTitle) { Task { await recordings.cancel(item.mark, programmeID: item.programmeID) } }.buttonStyle(TVButtonStyle())
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
        // The options form's parts (LiveRecordingForm); this board has no Record the series switch.
        let form = LiveRecordingForm.rule(rule)
        Form {
            Section(rule.title) {
                if form.showsSeriesSettings {
                    Toggle(liveNewEpisodesOnly, isOn: $options.newOnly)
                    Toggle(liveAnyChannel, isOn: $options.anyChannel)
                    Picker(liveKeepTitle, selection: $options.keep) {
                        ForEach(LiveRecordingForm.keepChoices, id: \.self) { Text(liveKeepChoice($0)).tag($0) }
                    }
                }
                Picker(liveStartEarly, selection: $options.padBefore) { ForEach(LiveRecordingForm.paddingChoices, id: \.self) { Text(livePaddingChoice($0)).tag($0) } }
                Picker(liveEndLate, selection: $options.padAfter) { ForEach(LiveRecordingForm.paddingChoices, id: \.self) { Text(livePaddingChoice($0)).tag($0) } }
                if let message = recordings.message { Text(message).foregroundStyle(Palette.warning) }
                Button("Save") { Task { await recordings.edit(rule, options: options); if recordings.message == nil { dismiss() } } }
                Button("Cancel") { dismiss() }
            }
        }.onAppear { options = LiveRecordingForm.rule(rule).options }
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
                Text(liveDeleteRecordingNote)
                Toggle(liveDontRecordAgain, isOn: $doNotRecordAgain)
                if let message = recordings.message { Text(message).foregroundStyle(Palette.warning) }
                Button("Cancel") { dismiss() }
                Button(liveDeleteRecording, role: .destructive) {
                    Task { if await recordings.deleteFile(recording, recordAgain: !doNotRecordAgain) { dismiss() } }
                }.disabled(recordings.changing.contains(recording.id))
            }
        }
    }
}
