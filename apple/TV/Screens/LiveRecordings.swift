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
        // The one left out has focus, so a stray press changes nothing; each row says
        // whether it is a repeat that airs again.
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

/// Recordings, as rows: Ready to watch first (what is recording now leading
/// it, a title recorded several times as one card), then Coming up (Won't
/// record marked on its card in orange), then the series recorded. Room is
/// said in hours. A card that won't record opens the choice of which to
/// leave out; one coming up opens its choices.
struct TVLiveRecordingsScreen: View {
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(AppServices.self) private var services
    @Environment(Navigator.self) private var navigator
    @State private var deleting: LiveRecording?
    @State private var editing: LiveRecordingRule?
    @State private var clash: LiveRecordingClash?
    @State private var coming: LiveRecording?
    @State private var series: LiveRecordingRule?
    @State private var group: ReadyItem?

    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: 24) {
                    Text("Recordings").font(.tv(.title3)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
                    if let hours = liveRecordedHoursLine(recordings.page(.recorded)?.items ?? [], freeBytes: freeBytes) {
                        Text(hours).font(.tv(.body)).foregroundStyle(Palette.muted).accessibilityIdentifier("live-recordings-room")
                    }
                }
                .padding(.horizontal, TVLayout.gutter)
                .padding(.bottom, 20)
                if !recordings.available {
                    TVEmptyState(systemImage: "record.circle", title: liveRecordingUnavailable, message: "Ask the server’s admin to turn recording on and allow your account.")
                        .padding(.top, 140)
                } else if isEmpty {
                    TVRecordingsEmpty(openGuide: openGuide)
                } else {
                    rows
                }
            }
            .padding(.top, TVLayout.safeTop)
            .padding(.bottom, TVLayout.safeBottom)
        }
        .scrollClipDisabled()
        .ignoresSafeArea(edges: .horizontal)
        .groundBackground()
        .task(id: services.liveTV.contextID) { for section in LiveRecordingSection.allCases { await recordings.load(section) } }
        // On screen until this list goes: events then stop asking for its sections.
        .task { await recordings.show(LiveRecordingSection.allCases) }
        .messagePill(Binding(get: { recordings.message }, set: { recordings.message = $0 }))
        .fullScreenCover(item: $deleting) { item in TVLiveRecordingDelete(recording: item) }
        .tvLiveSheet(item: $clash) { found in TVLiveRecordingClashView(clash: found) { _ in clash = nil } }
        .tvLiveSheet(item: $coming) { item in TVComingChoices(recording: item, rule: rule(for: item), edit: { editing = $0 }) }
        .tvLiveSheet(item: $series) { rule in TVSeriesChoices(rule: rule, edit: { editing = $0 }) }
        .tvLiveSheet(item: $group) { item in TVRecordedEpisodes(item: item, delete: { deleting = $0 }) }
        .sheet(item: $editing) { rule in TVLiveRecordingRuleOptions(rule: rule) }
        .accessibilityElement(children: .contain).accessibilityIdentifier("live-recordings")
    }

    /// Every section has loaded and holds nothing.
    private var isEmpty: Bool {
        recordings.loading.isEmpty && recordings.errors.isEmpty
            && LiveRecordingSection.allCases.allSatisfy { recordings.page($0) != nil }
            && recordings.pages.values.allSatisfy { $0.items.isEmpty && $0.rules.isEmpty }
    }

    private var freeBytes: Int64? {
        LiveRecordingSection.allCases.lazy.compactMap { recordings.page($0)?.freeBytes }.first
    }

    private func rule(for recording: LiveRecording) -> LiveRecordingRule? {
        recording.ruleID.flatMap { id in recordings.page(.series)?.rules.first { $0.id == id } }
    }

    private func openGuide() {
        navigator.setPath([], for: .liveTV)
        if navigator.tab != .liveTV { navigator.select(.liveTV) }
    }

    @ViewBuilder private var rows: some View {
        VStack(alignment: .leading, spacing: 20) {
            let ready = ReadyItem.items(now: recordings.page(.now)?.items ?? [], recorded: recordings.page(.recorded)?.items ?? [])
            if !ready.isEmpty {
                ShelfRow(title: "Ready to watch", detail: formatCount(ready.count), items: ready) { item in readyCard(item) }
            }
            sectionNotes([.now, .recorded])
            let upcoming = ((recordings.page(.conflicts)?.items ?? []) + (recordings.page(.upcoming)?.items ?? [])).sorted { $0.start < $1.start }
            if !upcoming.isEmpty {
                ShelfRow(title: "Coming up", items: upcoming) { item in comingCard(item) }
            }
            sectionNotes([.conflicts, .upcoming])
            let rules = recordings.page(.series)?.rules ?? []
            if !rules.isEmpty {
                ShelfRow(title: "Series you record", detail: formatCount(rules.count), items: rules) { rule in seriesTile(rule) }
            }
            sectionNotes([.series])
            let more = LiveRecordingSection.allCases.filter { recordings.page($0)?.nextCursor != nil }
            if !more.isEmpty {
                Button(liveLoadMore) { Task { for section in more { await recordings.load(section, more: true) } } }
                    .buttonStyle(TVButtonStyle())
                    .padding(.horizontal, TVLayout.gutter)
            }
        }
    }

    /// A section that is still loading, or failed, says so in its place.
    @ViewBuilder private func sectionNotes(_ sections: [LiveRecordingSection]) -> some View {
        ForEach(sections, id: \.self) { section in
            if let error = recordings.errors[section] {
                HStack(spacing: 24) {
                    Text("\(liveRecordingsFailed(section)). \(error)").font(.tv(.body)).foregroundStyle(Palette.textSecondary)
                    Button("Try again") { Task { await recordings.load(section, force: true) } }.buttonStyle(TVButtonStyle())
                }
                .padding(.horizontal, TVLayout.gutter)
                .focusSection()
            } else if recordings.loading.contains(section) && recordings.page(section) == nil {
                Text(liveRecordingsLoading(section)).font(.tv(.caption1)).foregroundStyle(Palette.muted)
                    .padding(.horizontal, TVLayout.gutter)
            }
        }
    }

    private func readyCard(_ item: ReadyItem) -> some View {
        Button { open(item) } label: {
            TVRecordingCard(title: item.title, line: liveReadyLine(item.recordings), picture: item.first.pictureURL,
                            tag: item.first.state == .recording ? .recording : !item.playable ? .warning(item.first.statusTitle)
                                : item.newCount > 0 && item.recordings.count > 1 ? .new(item.newCount) : nil,
                            watched: item.watched, progress: item.recordings.count == 1 ? item.first.watchProgress : nil)
        }
        .buttonStyle(TVLiveCardButtonStyle())
        .contextMenu {
            if item.recordings.count == 1 {
                let actions = item.first.actions
                if actions.cancel {
                    Button(actions.cancelTitle) { Task { await recordings.cancel(item.first.mark, programmeID: item.first.programmeID) } }
                }
                // Delete last, and it asks first.
                if actions.delete { Button(liveDeleteRecording) { deleting = item.first } }
            }
        }
        .accessibilityIdentifier("recording-\(item.id)")
    }

    /// One recording plays (from the start while it records); several open their episodes.
    private func open(_ item: ReadyItem) {
        guard item.recordings.count == 1 else { group = item; return }
        let recording = item.first
        switch recording.actions.watch {
        case .fromStart?: navigator.play(recording.playRequest(fromStart: true))
        case .recorded?: navigator.play(recording.playRequest())
        case nil: group = item
        }
    }

    private func comingCard(_ item: LiveRecording) -> some View {
        Button {
            if item.state == .conflict, let found = LiveRecordingClash(item) { clash = found } else { coming = item }
        } label: {
            TVRecordingCard(title: item.title, line: liveComingLine(item, rule: rule(for: item), now: .now), picture: item.pictureURL,
                            tag: item.state == .conflict ? .warning(liveComingTag(item, now: .now)) : .when(liveComingTag(item, now: .now)), dimmed: true)
        }
        .buttonStyle(TVLiveCardButtonStyle())
        .accessibilityLabel("\(item.title), \(liveComingTag(item, now: .now)), \(liveComingLine(item, rule: rule(for: item), now: .now))")
        .accessibilityIdentifier("recording-\(item.id)")
    }

    private func seriesTile(_ rule: LiveRecordingRule) -> some View {
        Button { series = rule } label: { TVSeriesTile(rule: rule) }
            .buttonStyle(TVLiveCardButtonStyle())
            .accessibilityIdentifier("recording-rule-\(rule.id)")
    }
}

/// A card in Ready to watch: something recording now, one recording, or a title recorded several times.
struct ReadyItem: Identifiable {
    let recordings: [LiveRecording]
    var id: String { recordings.count == 1 ? first.id : "title:\(first.title)" }
    var first: LiveRecording { recordings[0] }
    var title: String { first.title }
    var playable: Bool { recordings.contains(where: \.playable) }
    /// Recorded and not watched yet, where the server says (API level 34): "2 new" on a title recorded several times.
    var newCount: Int { recordings.filter(\.unwatched).count }
    /// Every recording of it watched, where the server says: the check on its card.
    var watched: Bool { recordings.allSatisfy { $0.watched == true } }

    /// What is recording now first, then each title recorded, newest first,
    /// its recordings together.
    static func items(now: [LiveRecording], recorded: [LiveRecording]) -> [ReadyItem] {
        let recording = now.filter { $0.state == .recording }.map { ReadyItem(recordings: [$0]) }
        var order: [String] = []
        var byTitle: [String: [LiveRecording]] = [:]
        for item in recorded.sorted(by: { $0.start > $1.start }) {
            if byTitle[item.title] == nil { order.append(item.title) }
            byTitle[item.title, default: []].append(item)
        }
        return recording + order.compactMap { byTitle[$0].map(ReadyItem.init) }
    }
}

/// A recording's 16:9 card: its picture, a tag (Recording, its time, or
/// Won't record in orange), then its title and a line.
private struct TVRecordingCard: View {
    enum Tag { case recording, when(String), warning(String), new(Int) }
    let title: String
    let line: String
    let picture: URL?
    var tag: Tag?
    /// Not recorded yet: the picture a little dimmed.
    var dimmed = false
    /// Watched by the signed-in person: the check, as on every card.
    var watched = false
    /// How far they got, while partway.
    var progress: Double?
    @Environment(\.isFocused) private var focused
    @Environment(\.displayScale) private var scale

    var body: some View {
        let width = TVCardWidth.wideLarge, height = (TVCardWidth.wideLarge * 9 / 16).rounded()
        VStack(alignment: .leading, spacing: 0) {
            Artwork(url: picture, seed: title, maxPixel: cardPixels(width: width, height: height, scale: scale))
                .frame(width: width, height: height)
                .overlay { if picture == nil { PlaceholderTitle(title: title) } }
                .opacity(dimmed ? 0.8 : 1)
                .overlay(alignment: .topLeading) { tagView.padding(12) }
                .overlay {
                    if watched {
                        WatchedCheck(size: 40).pinned(.topTrailing)
                    } else if let progress, progress > 0 {
                        TVProgressBar(progress: progress).pinned(.bottom, padding: 14)
                    }
                }
                .overlay { FocusSheen(focused: focused) }
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .focusLift(focused)
            CardCaption(title: title, line: line, focused: focused)
        }
        .frame(width: width, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var tagView: some View {
        switch tag {
        case .recording?:
            HStack(spacing: 8) {
                Circle().fill(Color(hex: 0xFF4A3D)).frame(width: 14, height: 14)
                Text("Recording")
            }
            .font(.tv(.caption2, .bold)).foregroundStyle(.white)
            .padding(.horizontal, 12).frame(height: 38)
            .background(Color(hex: 0x0A0A0B).opacity(0.82), in: RoundedRectangle(cornerRadius: 8))
        case .when(let text)?:
            TVBadge(text)
        case .warning(let text)?:
            TVBadge(text, style: .warning)
        case .new(let count)?:
            TVBadge("\(formatCount(count)) new", style: .gold)
        case nil:
            EmptyView()
        }
    }
}

/// A series recorded, as a tile: its title and how it records.
private struct TVSeriesTile: View {
    let rule: LiveRecordingRule
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(rule.title).font(.tv(.callout)).foregroundStyle(focused ? Palette.ground : Palette.text).lineLimit(2)
            Text(rule.canCancel ? rule.detail : liveSetBy(rule.userName)).font(.tv(.caption2))
                .foregroundStyle(focused ? Color.tvOnWhiteMuted : Palette.muted).lineLimit(2)
        }
        .padding(.horizontal, 28)
        .frame(width: TVCardWidth.wideLarge, height: 225, alignment: .leading)
        .background(focused ? Palette.text : Palette.surfaceRaised, in: RoundedRectangle(cornerRadius: 14))
        .overlay { if !focused { RoundedRectangle(cornerRadius: 14).strokeBorder(Color(hex: 0x34343A), lineWidth: 2) } }
        .focusLift(focused, scale: 1.08)
        .accessibilityElement(children: .combine)
    }
}

/// Nothing recorded yet: how to record, and the way to the guide, focused.
private struct TVRecordingsEmpty: View {
    let openGuide: () -> Void
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Image(systemName: "record.circle").font(.system(size: 64, weight: .medium)).foregroundStyle(Palette.muted)
                .accessibilityHidden(true)
            Text("Nothing recorded yet").font(.tv(.title2)).foregroundStyle(Palette.text)
            Text("Choose a programme in the guide, then Record. It shows up here as soon as it starts.")
                .font(.tv(.body)).foregroundStyle(Palette.textSecondary).frame(maxWidth: 860, alignment: .leading)
            Button("Open the guide", action: openGuide)
                .buttonStyle(TVButtonStyle(kind: .primary))
                .focused($focused)
                .padding(.top, 16)
                .accessibilityIdentifier("live-recordings-open-guide")
        }
        .padding(.horizontal, TVLayout.gutter)
        .padding(.top, 160)
        .frame(maxWidth: .infinity, alignment: .leading)
        .defaultFocus($focused, true)
    }
}

/// A recording still to come: don't record this one, stop the series, or change its options.
private struct TVComingChoices: View {
    let recording: LiveRecording
    let rule: LiveRecordingRule?
    let edit: (LiveRecordingRule) -> Void
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        TVSheet {
            Text(recording.title).font(.tv(.title3)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
            Text([liveRecordingWhen(recording), recording.episodeLine].compactMap { $0 }.joined(separator: " · "))
                .font(.tv(.body)).foregroundStyle(Palette.textSecondary)
            Text(recording.statusTitle).font(.tv(.body)).foregroundStyle(Palette.muted)
            VStack(spacing: 12) {
                let actions = recording.actions
                if actions.cancel {
                    TVSheetRow(title: rule != nil || recording.ruleID != nil ? "Don’t record this episode" : liveDontRecord) {
                        Task { await recordings.cancel(recording.mark, programmeID: recording.programmeID) }
                        dismiss()
                    }
                }
                if let rule, rule.canCancel {
                    TVSheetRow(title: "Stop recording \(rule.newOnly ? "every new episode" : "every episode")", line: rule.detail) {
                        Task { await recordings.remove(rule) }
                        dismiss()
                    }
                    TVSheetRow(title: "Change the series’ options…") { dismiss(); edit(rule) }
                }
                if !actions.cancel && !(rule?.canCancel ?? false) {
                    TVSheetRow(title: "OK", line: liveSetBy(recording.userName)) { dismiss() }
                }
            }
            .padding(.top, 8)
            .focusSection()
        }
    }
}

/// A series recorded: change its options or stop it.
private struct TVSeriesChoices: View {
    let rule: LiveRecordingRule
    let edit: (LiveRecordingRule) -> Void
    @Environment(LiveRecordingsStore.self) private var recordings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        TVSheet {
            Text(rule.title).font(.tv(.title3)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
            Text(rule.detail).font(.tv(.body)).foregroundStyle(Palette.textSecondary)
            VStack(spacing: 12) {
                if rule.canCancel {
                    TVSheetRow(title: "Change options…") { dismiss(); edit(rule) }
                    TVSheetRow(title: liveStopSeries) {
                        Task { await recordings.remove(rule) }
                        dismiss()
                    }
                } else {
                    TVSheetRow(title: "OK", line: liveSetBy(rule.userName)) { dismiss() }
                }
            }
            .padding(.top, 8)
            .focusSection()
        }
    }
}

/// A title recorded several times: its recordings, newest first; Select
/// plays one, and its long press deletes it.
private struct TVRecordedEpisodes: View {
    let item: ReadyItem
    let delete: (LiveRecording) -> Void
    @Environment(Navigator.self) private var navigator
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        TVSheet(width: 1200) {
            Text(item.title).font(.tv(.title3)).foregroundStyle(Palette.text).accessibilityAddTraits(.isHeader)
            ScrollView(.vertical) {
                VStack(spacing: 12) {
                    ForEach(item.recordings) { recording in
                        TVSheetRow(title: recording.episodeLine ?? liveRecordingWhen(recording), value: liveRecordingLength(recording),
                                   line: recording.playable ? liveRecordingWhen(recording) : recording.statusTitle, warns: !recording.playable) {
                            guard recording.playable else { return }
                            dismiss()
                            navigator.play(recording.playRequest(fromStart: recording.state == .recording))
                        }
                        .contextMenu {
                            if recording.actions.delete { Button(liveDeleteRecording) { dismiss(); delete(recording) } }
                        }
                    }
                }
                .padding(12)
            }
            .frame(maxHeight: 640)
            .scrollClipDisabled()
            .focusSection()
        }
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
