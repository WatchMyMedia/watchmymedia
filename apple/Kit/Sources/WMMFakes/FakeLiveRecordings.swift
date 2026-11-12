import Foundation
import WMMModel

extension FakeLiveTVRepository {
    public func recordings(section: LiveRecordingSection, cursor: String?) async throws -> LiveRecordingPage {
        let context = contextID
        await FakeLatency.wait()
        try Task.checkCancellation()
        guard context == contextID else { throw CancellationError() }
        guard capabilities?.recordingsAvailable == true else { throw LiveTVError(reason: "unavailable") }
        if let recordingFailure { self.recordingFailure = nil; throw APIError(status: 500, message: recordingFailure) }
        let rows = recordingItems.filter { r in
            switch section {
            case .now: r.state == .recording
            case .conflicts: r.state == .conflict
            case .upcoming: [.scheduled, .gone, .skipped].contains(r.state)
            case .recorded: [.done, .partial, .failed].contains(r.state)
            case .series: false
            }
        }.sorted { ($0.start, $0.id) < ($1.start, $1.id) }
        let offset = max(Int(cursor ?? "") ?? 0, 0), end = min(offset + 20, rows.count)
        let items = offset < rows.count ? Array(rows[offset..<end]) : []
        return LiveRecordingPage(items: items, rules: section == .series ? recordingRules : [],
                                 nextCursor: end < rows.count ? String(end) : nil, tuners: 2, freeBytes: 625_000_000_000)
    }
    public func record(programmeID: String, options: LiveRecordingOptions) async throws -> LiveRecording {
        _ = try await recordings(section: .upcoming, cursor: nil)
        if let item = recordingItems.first(where: { $0.programmeID == programmeID && $0.state != .cancelled }) { return item }
        guard let hit = listings?.programmes(from: now().addingTimeInterval(-12 * 3600)).first(where: { $0.programme.id == programmeID }), hit.programme.end > now() else {
            throw APIError(status: 409, message: "That programme has ended or left the listings.")
        }
        let p = hit.programme
        var result = LiveRecording(id: UUID().uuidString, userName: "Sam", channelID: hit.channel.id, number: hit.channel.number,
                                   channelName: hit.channel.name, programmeID: p.id, title: p.title, episodeTitle: p.episodeTitle,
                                   season: p.season, episode: p.episode, start: p.start, end: p.end,
                                   state: p.isOn(at: now()) ? .recording : .scheduled, series: options.series,
                                   padBefore: options.useServerPadding ? 1 : options.padBefore, padAfter: options.useServerPadding ? 2 : options.padAfter, canCancel: true, pictureURL: p.pictureURL)
        if options.series {
            let id = UUID().uuidString
            result.ruleID = id
            recordingRules.append(.init(id: id, title: p.title, userName: "Sam", channelID: hit.channel.id,
                                        number: hit.channel.number, newOnly: options.newOnly, anyChannel: options.anyChannel,
                                        keep: options.keep, padBefore: options.useServerPadding ? 1 : options.padBefore, padAfter: options.useServerPadding ? 2 : options.padAfter, canCancel: true))
        }
        recordingItems.append(result)
        changedElsewhere { _ in }
        return result
    }
    public func recordManual(_ request: LiveManualRecording) async throws -> LiveRecording {
        _ = try await recordings(section: .upcoming, cursor: nil)
        if let error = request.validationMessage { throw APIError(status: 422, message: error) }
        guard let channel = channels.first(where: { $0.id == request.channelID }) else { throw APIError(status: 404, message: "Channel not found.") }
        var result = LiveRecording(id: UUID().uuidString, userName: "Sam", channelID: channel.id, number: channel.number,
                                   channelName: channel.name, title: request.name, start: request.start, end: request.end, canCancel: true)
        if request.repeatKind != .once {
            let id = UUID().uuidString
            result.ruleID = id
            recordingRules.append(.init(id: id, kind: "manual", title: request.name, userName: "Sam", channelID: channel.id,
                                        number: channel.number, repeatKind: request.repeatKind.rawValue, zone: request.zone, canCancel: true))
        }
        recordingItems.append(result)
        changedElsewhere { _ in }
        return result
    }
    public func editRecording(id: String, padBefore: Int?, padAfter: Int?, prefer: Bool) async throws -> LiveRecording {
        _ = try await recordings(section: .upcoming, cursor: nil)
        guard let index = recordingItems.firstIndex(where: { $0.id == id }), recordingItems[index].canCancel else { throw APIError(status: 403, message: "Only the person who set it or an admin can change it.") }
        if let padBefore { recordingItems[index].padBefore = padBefore }
        if let padAfter { recordingItems[index].padAfter = padAfter }
        if prefer { recordingItems[index].state = .scheduled; recordingItems[index].reason = nil }
        changedElsewhere { _ in }
        return recordingItems[index]
    }
    /// The chosen holder becomes the clash, and the clash takes its tuner.
    public func preferRecording(id: String, over: String?) async throws -> LiveRecording {
        _ = try await recordings(section: .conflicts, cursor: nil)
        guard let index = recordingItems.firstIndex(where: { $0.id == id }), recordingItems[index].canCancel,
              recordingItems[index].state == .conflict else { throw APIError(status: 403, message: "Only the person who set it or an admin can change it.") }
        let preferred = recordingItems[index]
        if let over, let other = recordingItems.firstIndex(where: { $0.id == over }) {
            guard recordingItems[other].state == .scheduled else { throw APIError(status: 422, message: "That recording has already started.") }
            recordingItems[other].state = .conflict
            recordingItems[other].reason = "tuners_busy"
            recordingItems[other].conflictsWith = [.init(id: preferred.id, title: preferred.title, number: preferred.number, channelName: preferred.channelName,
                                                         start: preferred.start, end: preferred.end, userName: preferred.userName)]
        }
        recordingItems[index].state = .scheduled; recordingItems[index].reason = nil; recordingItems[index].conflictsWith = []
        changedElsewhere { _ in }
        return recordingItems[index]
    }
    public func cancelRecording(id: String) async throws {
        _ = try await recordings(section: .upcoming, cursor: nil)
        guard let index = recordingItems.firstIndex(where: { $0.id == id }), recordingItems[index].canCancel else { throw APIError(status: 403, message: "Only the person who set it or an admin can cancel it.") }
        recordingItems[index].state = recordingItems[index].state == .recording ? .partial : .cancelled
        changedElsewhere { _ in }
    }
    public func editRecordingRule(id: String, options: LiveRecordingOptions) async throws -> LiveRecordingRule {
        _ = try await recordings(section: .series, cursor: nil)
        guard let index = recordingRules.firstIndex(where: { $0.id == id }), recordingRules[index].canCancel else { throw APIError(status: 403, message: "Only the person who set it or an admin can change it.") }
        recordingRules[index].newOnly = options.newOnly; recordingRules[index].anyChannel = options.anyChannel
        recordingRules[index].keep = options.keep; recordingRules[index].padBefore = options.padBefore; recordingRules[index].padAfter = options.padAfter
        changedElsewhere { _ in }
        return recordingRules[index]
    }
    public func deleteRecordingFile(id: String, recordAgain: Bool) async throws {
        _ = try await recordings(section: .recorded, cursor: nil)
        guard let index = recordingItems.firstIndex(where: { $0.id == id }), recordingItems[index].canDelete else { throw APIError(status: 403, message: "You can’t delete this recording.") }
        recordingItems[index].state = .deleted
        changedElsewhere { _ in }
    }
    public func removeRecordingRule(id: String) async throws {
        _ = try await recordings(section: .series, cursor: nil)
        guard recordingRules.contains(where: { $0.id == id && $0.canCancel }) else { throw APIError(status: 403, message: "Only the person who set it or an admin can remove it.") }
        recordingRules.removeAll { $0.id == id }
        recordingItems.removeAll { $0.ruleID == id && [.scheduled, .conflict].contains($0.state) }
        changedElsewhere { _ in }
    }
    /// Fictional, complete list of states from the approved DVR boards.
    public func offerRecordings(admin: Bool = true) {
        offered.recording = true; offered.canRecord = true
        let start = now().addingTimeInterval(-23 * 60)
        recordingItems = [
            .init(id: "dvr-now", userName: "Sam", channelID: "ch-4.2", number: "4.2", channelName: "Cedar Two", title: "Inspector Vane", episodeTitle: "The Low Tide", season: 2, episode: 4,
                  start: start, end: start.addingTimeInterval(3600), startedAt: start, state: .recording, bytes: 1_250_000_000, canCancel: admin),
            .init(id: "dvr-conflict", userName: "Alex", channelID: "ch-2.1", number: "2.1", title: "Coastline Rescue", start: now().addingTimeInterval(3600), end: now().addingTimeInterval(7200), state: .conflict,
                  reason: "tuners_busy", canCancel: true, conflictsWith: [
                      .init(id: "dvr-now", title: "Inspector Vane", number: "4.2", channelName: "Cedar Two", start: start, end: start.addingTimeInterval(3600), userName: "Sam", running: true),
                      .init(id: "dvr-upcoming", title: "Station 9", number: "7.1", start: now().addingTimeInterval(1800), end: now().addingTimeInterval(5400), userName: "Sam"),
                  ]),
            .init(id: "dvr-upcoming", ruleID: "dvr-rule", userName: "Sam", channelID: "ch-7.1", number: "7.1", title: "Station 9", episodeTitle: "The Harbor Fire", season: 4, episode: 12,
                  start: now().addingTimeInterval(1800), end: now().addingTimeInterval(5400), series: true, canCancel: admin),
            .init(id: "dvr-recorded", userName: "Sam", channelID: "ch-4.2", number: "4.2", title: "Inspector Vane", episodeTitle: "The Last Ferry", season: 2, episode: 3,
                  start: now().addingTimeInterval(-86400), end: now().addingTimeInterval(-82800), state: .done, bytes: 3_600_000_000, mediaFileID: 9101, canDelete: admin),
            .init(id: "dvr-partial", userName: "Sam", channelID: "ch-2.1", number: "2.1", title: "Coastline Rescue", start: now().addingTimeInterval(-172800), end: now().addingTimeInterval(-169200), state: .partial,
                  reason: "signal_lost", bytes: 2_100_000_000,
                  gaps: [.init(start: now().addingTimeInterval(-171_000), end: now().addingTimeInterval(-170_880), reason: "signal_lost")],
                  mediaFileID: 9102, canDelete: admin),
        ]
        recordingRules = [.init(id: "dvr-rule", title: "Station 9", userName: "Sam", number: "7.1", canCancel: admin)]
    }
}
