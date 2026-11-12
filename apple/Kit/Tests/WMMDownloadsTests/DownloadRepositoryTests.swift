import Foundation
import Testing
@testable import WMMDownloads
import WMMModel

@MainActor
struct DownloadRepositoryTests {
    @Test func offersDownloadsOnlyFromLevel10AndWhenAllowed() async {
        let h = Harness()
        var repo = h.make()
        await settle()
        #expect(repo.canDownload)
        #expect(repo.enabled)

        h.gateway.mayDownload = false
        repo = h.make()
        await settle()
        #expect(!repo.canDownload)
        #expect(!repo.enabled)

        h.gateway.mayDownload = true
        h.gateway.current = OfflineOwner(serverID: "srv", userID: 1, baseURL: server, apiLevel: 9)
        repo = h.make()
        await settle()
        #expect(!repo.canDownload)
    }

    @Test func downloadQueuesTheMediaAndItsTextSubtitles() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42)]
        let repo = h.make()
        _ = try await repo.plan(fileID: 42)
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        let request = try #require(h.engine.added.first)
        #expect(!request.hls)
        #expect(request.fileExtension == "mp4")
        // The image subtitle comes only burned in; the text one beside the file.
        #expect(Array(request.parts.keys) == ["s3"])
        #expect(repo.downloads.first?.status == .waiting(.turn))
        #expect(h.notices.said == ["ask"])
    }

    @Test func aPlanReturningAfterSignOutIsDiscarded() async {
        let h = Harness()
        let repo = h.make()
        h.gateway.planHook = { await h.gateway.signOut() }
        do {
            _ = try await repo.plan(fileID: 42)
            Issue.record("a signed-out account's plan was accepted")
        } catch is CancellationError {
        } catch {
            Issue.record("unexpected error: \(error)")
        }
        #expect(repo.downloads.isEmpty)
    }

    @Test func aCreateReturningAfterSignOutCannotRestoreTheDeletedAccount() async {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42)]
        let repo = h.make()
        h.gateway.createHook = { await h.gateway.signOut() }
        do {
            try await repo.download(DownloadChoice(fileID: 42, quality: .original))
            Issue.record("the late create started downloading for a signed-out account")
        } catch is SignedOutError {
        } catch {
            Issue.record("unexpected error: \(error)")
        }
        #expect(h.engine.added.isEmpty)
        #expect(h.store.state.records.isEmpty)
        #expect(h.notices.said.isEmpty)
    }

    @Test func refusesADownloadThatWouldLeaveUnder1GBFree() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42)]
        h.gateway.planSize = 49_500_000_000
        let repo = h.make()
        _ = try await repo.plan(fileID: 42)
        do {
            try await repo.download(DownloadChoice(fileID: 42, quality: .original))
            Issue.record("a download that does not fit was made")
        } catch let error as APIError {
            #expect(error.message == "Needs 47 GB free. Pick a lower quality or free up space.")
        }
        #expect(h.engine.added.isEmpty)
    }

    @Test func anotherSignedInServersPendingBytesReserveSpaceWithoutExposingItsDownloads() async throws {
        let h = Harness()
        let other = OfflineOwner(serverID: "other", userID: 2, baseURL: URL(string: "http://127.0.0.1:27601")!, apiLevel: offlineAPILevel)
        h.gateway.current = other
        h.gateway.signedInKeys.insert(other.key)
        h.gateway.made = [record("other-transfer", fileID: 42), record("current-transfer", fileID: 43)]
        h.engine.freeBytes = DownloadStorage.reserveBytes + 6_000_000_000
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        h.engine.move("other-transfer", .downloading, fraction: 0.25)
        h.gateway.select(sam)
        _ = try await repo.plan(fileID: 43)

        #expect(repo.downloads.isEmpty)
        #expect(repo.storage().freeBytes == DownloadStorage.reserveBytes + 6_000_000_000)
        #expect(repo.availableStorage().freeBytes == DownloadStorage.reserveBytes + 3_000_000_000)
        do {
            try await repo.download(DownloadChoice(fileID: 43, quality: .original))
            Issue.record("another server's pending transfer spent the same free space")
        } catch is APIError {}
        #expect(h.gateway.createCalls == [42])
        #expect(h.gateway.planCalls == [42, 43], "cached plans are reused for admission")
        #expect(repo.downloads.isEmpty)

        h.engine.move("other-transfer", .failed)
        #expect(repo.availableStorage().freeBytes == repo.storage().freeBytes)
    }

    @Test func concurrentCreatesCannotBothSpendTheSameReserve() async throws {
        let h = Harness()
        h.gateway.made = [record("first-transfer", fileID: 42), record("second-transfer", fileID: 43)]
        h.engine.freeBytes = DownloadStorage.reserveBytes + 6_000_000_000
        let repo = h.make()
        _ = try await repo.plan(fileID: 42)
        _ = try await repo.plan(fileID: 43)
        let entered = AsyncStream<Void>.makeStream()
        var release: CheckedContinuation<Void, Never>?
        h.gateway.createHook = {
            guard h.gateway.createCalls.count == 1 else { return }
            entered.continuation.yield(())
            await withCheckedContinuation { release = $0 }
        }
        let first = Task { try await repo.download(DownloadChoice(fileID: 42, quality: .original)) }
        defer { release?.resume() }
        var arrivals = entered.stream.makeAsyncIterator()
        await arrivals.next()
        #expect(repo.downloads.isEmpty, "the first create has not returned a stored record yet")
        #expect(repo.availableStorage().freeBytes == DownloadStorage.reserveBytes + 2_000_000_000)
        do {
            try await repo.download(DownloadChoice(fileID: 43, quality: .original))
            Issue.record("both overlapping requests were admitted")
        } catch is APIError {}
        #expect(h.gateway.createCalls == [42])
        #expect(h.gateway.planCalls == [42, 43])
        release?.resume()
        release = nil
        try await first.value
        #expect(repo.downloads.map(\.fileID) == [42])
        #expect(repo.availableStorage().freeBytes == DownloadStorage.reserveBytes + 2_000_000_000,
                "the accepted transfer replaces its temporary reservation once")
    }

    @Test(arguments: [EngineDownload.State.paused, .completed])
    func repeatedCreateRepliesKeepOneRecordAndPreserveItsTransferState(_ state: EngineDownload.State) async throws {
        let h = Harness()
        h.gateway.made = [record("same-record", fileID: 42)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        if state == .completed {
            h.engine.finish("same-record")
            await settle()
        } else {
            h.engine.move("same-record", .paused, fraction: 0.3)
        }
        let accepted = try #require(repo.downloads.first)
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))

        #expect(h.store.state.records.count == 1)
        #expect(repo.downloads == [accepted])
        #expect(h.engine.added.map(\.id) == ["same-record"])
        #expect(h.engine.states["same-record"]?.state == state)
        #expect(h.notices.said.filter { $0 == "ask" }.count == 1)
        #expect(h.gateway.createCalls == [42, 42])
        #expect(h.gateway.planCalls == [42])
    }

    @Test(arguments: [false, true])
    func simultaneousSameChoicesShareOneCreateAndWaitForTheSameResult(fails: Bool) async throws {
        let h = Harness()
        h.gateway.made = [record("first-reply", fileID: 42)]
        if fails { h.gateway.createError = APIError(status: 503, message: "Try again later.") }
        let repo = h.make()
        let entered = AsyncStream<Void>.makeStream()
        var release: CheckedContinuation<Void, Never>?
        h.gateway.createHook = {
            guard h.gateway.createCalls.count == 1 else { return }
            entered.continuation.yield(())
            await withCheckedContinuation { release = $0 }
        }
        let choice = DownloadChoice(fileID: 42, quality: .original)
        let first = Task { () -> String in
            do { try await repo.download(choice); return "queued" }
            catch { return error.localizedDescription }
        }
        defer { release?.resume() }
        var arrivals = entered.stream.makeAsyncIterator()
        await arrivals.next()
        // Without coalescing this request would return first, replace the
        // server's earlier ID, then be displaced by the older delayed reply.
        h.gateway.made = [record("would-replace-first", fileID: 42)]
        var secondFinished = false
        let second = Task { () -> String in
            let result: String
            do { try await repo.download(choice); result = "queued" }
            catch { result = error.localizedDescription }
            secondFinished = true
            return result
        }
        await settle()
        #expect(!secondFinished, "joining callers wait for the server result")
        #expect(h.gateway.createCalls == [42])
        #expect(h.gateway.planCalls == [42])
        release?.resume()
        release = nil
        let expected = fails ? "Try again later." : "queued"
        #expect(await first.value == expected)
        #expect(await second.value == expected)
        #expect(repo.downloads.map(\.id) == (fails ? [] : ["first-reply"]))
        #expect(h.engine.added.map(\.id) == (fails ? [] : ["first-reply"]))
        #expect(h.store.state.records.count == (fails ? 0 : 1))
    }

    @Test func aDifferentChoiceWhileCreatingReportsBusyWithoutReplacingTheTransfer() async throws {
        let h = Harness()
        h.gateway.made = [record("first-choice", fileID: 42)]
        let repo = h.make()
        let entered = AsyncStream<Void>.makeStream()
        var release: CheckedContinuation<Void, Never>?
        h.gateway.createHook = {
            guard h.gateway.createCalls.count == 1 else { return }
            entered.continuation.yield(())
            await withCheckedContinuation { release = $0 }
        }
        let first = Task { try await repo.download(DownloadChoice(fileID: 42, quality: .original)) }
        defer { release?.resume() }
        var arrivals = entered.stream.makeAsyncIterator()
        await arrivals.next()
        do {
            try await repo.download(DownloadChoice(fileID: 42, quality: .p720))
            Issue.record("different choices were allowed to race")
        } catch let error as APIError {
            #expect(error.message.contains("already being prepared"))
        }
        #expect(h.gateway.createCalls == [42])
        release?.resume()
        release = nil
        try await first.value
        #expect(repo.downloads.map(\.id) == ["first-choice"])
    }

    @Test func switchingAccountsDuringAQueuedPlanNeverCreatesForTheNewAccount() async {
        let h = Harness()
        let repo = h.make()
        h.gateway.planHook = {
            h.gateway.select(OfflineOwner(serverID: "other", userID: 2, baseURL: server, apiLevel: offlineAPILevel))
        }
        do {
            try await repo.download(DownloadChoice(fileID: 42, quality: .original))
            Issue.record("the queued choice survived an account switch")
        } catch is CancellationError {
        } catch {
            Issue.record("unexpected error: \(error)")
        }
        #expect(h.gateway.planCalls == [42])
        #expect(h.gateway.createCalls.isEmpty)
        #expect(h.engine.added.isEmpty)
    }

    @Test func theSameFileIDOnAnotherAccountCreatesIndependentlyAndKeepsItsOwnerAfterTheAwait() async throws {
        let h = Harness()
        h.gateway.made = [record("first-owner", fileID: 42)]
        let repo = h.make()
        let entered = AsyncStream<Void>.makeStream()
        var release: CheckedContinuation<Void, Never>?
        h.gateway.createHook = {
            guard h.gateway.createCalls.count == 1 else { return }
            entered.continuation.yield(())
            await withCheckedContinuation { release = $0 }
        }
        let first = Task { try await repo.download(DownloadChoice(fileID: 42, quality: .original)) }
        defer { release?.resume() }
        var arrivals = entered.stream.makeAsyncIterator()
        await arrivals.next()
        h.gateway.select(OfflineOwner(serverID: "other", userID: 2, baseURL: URL(string: "http://127.0.0.1:27601")!, apiLevel: offlineAPILevel))
        h.gateway.made = [record("second-owner", fileID: 42)]
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        release?.resume()
        release = nil
        try await first.value

        #expect(h.store.state.records.count == 2)
        #expect(repo.downloads.map(\.id) == ["second-owner"])
        #expect(h.engine.added.map(\.id) == ["second-owner", "first-owner"])
        #expect(h.store.state.record("first-owner")?.ownerKey == sam.key)
        #expect(h.store.state.record("second-owner")?.ownerKey == "other/2")
        #expect(h.gateway.createCalls == [42, 42])
    }

    @Test func aLargerCreateReplyKeepsTheOldCopyAndReportsTheRejectedNewCopyRemoved() async throws {
        let h = Harness()
        h.gateway.made = [record("old-copy", fileID: 42)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        h.engine.finish("old-copy")
        await settle()
        h.engine.freeBytes = DownloadStorage.reserveBytes + 2_000_000_000
        h.gateway.planSize = 1_000_000_000
        h.gateway.made = [record("larger-copy", fileID: 99)]
        _ = try await repo.plan(fileID: 99)
        do {
            try await repo.download(DownloadChoice(fileID: 99, quality: .original))
            Issue.record("the returned size exceeded the reserved free space")
        } catch is APIError {}
        #expect(repo.downloads.map(\.id) == ["old-copy"])
        #expect(repo.downloads.first?.downloaded == true)
        #expect(h.engine.added.map(\.id) == ["old-copy"])
        #expect(h.gateway.syncs.last?.downloads.contains(SyncedDownload(id: "larger-copy", state: .removed)) == true)
        #expect(repo.availableStorage().freeBytes == repo.storage().freeBytes)
    }

    @Test func showsTheEngineAsThePersonSeesIt() {
        let e = EngineDownload(id: "a", state: .downloading, fraction: 0.42, bytes: 0, location: nil, parts: [:])
        #expect(RealDownloadRepository.status(e, notMet: nil, sizeBytes: 4_000_000_000) == .downloading(percent: 42, bytes: 1_680_000_000))
        #expect(RealDownloadRepository.status(e, notMet: .wifi, sizeBytes: 4_000_000_000) == .waiting(.wifi))
        var queued = e
        queued.state = .queued
        #expect(RealDownloadRepository.status(queued, notMet: nil, sizeBytes: 1) == .waiting(.turn))
        queued.state = .preparing
        #expect(RealDownloadRepository.status(queued, notMet: nil, sizeBytes: 1) == .preparing)
        queued.state = .paused
        #expect(RealDownloadRepository.status(queued, notMet: nil, sizeBytes: 4_000_000_000) == .paused(percent: 42, bytes: 1_680_000_000))
    }

    @Test func aFinishedDownloadIsToldAndSynced() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        h.engine.finish("aaaaaaaaaaaaaaaa")
        await settle()
        let d = try #require(repo.downloads.first)
        #expect(d.downloaded)
        #expect(d.sizeBytes == 3_900_000_000)
        #expect(h.notices.said.contains("downloaded S03 E05 · Keel"))
        let sent = try #require(h.gateway.syncs.last)
        #expect(sent.downloads == [SyncedDownload(id: "aaaaaaaaaaaaaaaa", state: .downloaded, sizeBytes: 3_900_000_000)])
    }

    @Test func aRevokedDownloadIsDeletedAndReportedGone() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        h.engine.finish("aaaaaaaaaaaaaaaa")
        await settle()
        h.gateway.syncs = []
        h.gateway.reply = { downloads in
            let held = downloads.filter { $0.state != .removed }.map { record($0.id, fileID: 42, state: .revoked) }
            return SyncReply(offline: true, downloads: held, results: [])
        }
        await repo.sync()
        #expect(repo.downloads.isEmpty)
        #expect(h.engine.removed == ["aaaaaaaaaaaaaaaa"])
        #expect(h.notices.said.last == "removed S03 E05 · Keel")
        // Said at once, so the server forgets the record.
        #expect(h.gateway.syncs.count >= 2)
        #expect(h.gateway.syncs[1].downloads == [SyncedDownload(id: "aaaaaaaaaaaaaaaa", state: .removed)])
    }

    @Test func accessWithdrawnDeletesEveryDownload() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42), record("bbbbbbbbbbbbbbbb", fileID: 43)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        try await repo.download(DownloadChoice(fileID: 43, quality: .original))
        h.gateway.mayDownload = false
        await repo.sync()
        #expect(repo.downloads.isEmpty)
        #expect(!repo.canDownload)
    }

    @Test func aDeletedOrReplacedFileOnlyMarksTheCopy() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42), record("bbbbbbbbbbbbbbbb", fileID: 43)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        try await repo.download(DownloadChoice(fileID: 43, quality: .original))
        h.engine.finish("aaaaaaaaaaaaaaaa")
        h.engine.finish("bbbbbbbbbbbbbbbb")
        await settle()
        h.gateway.reply = { _ in
            SyncReply(offline: true, downloads: [
                record("aaaaaaaaaaaaaaaa", fileID: 42, state: .gone),
                record("bbbbbbbbbbbbbbbb", fileID: 43, state: .replaced, replacedBy: 99),
            ], results: [])
        }
        await repo.sync()
        let gone = try #require(repo.downloads.forFile(42))
        let replaced = try #require(repo.downloads.forFile(43))
        #expect(gone.mark == .removedFromServer && gone.downloaded)
        #expect(replaced.mark == .newVersion && replaced.replacedBy == 99)
        #expect(downloadRowState(gone) == "Removed from the server")

        // A gone download's progress is not sent.
        repo.mark(fileID: 42, watched: true)
        repo.mark(fileID: 43, watched: true)
        h.gateway.syncs = []
        await repo.sync()
        #expect(h.gateway.syncs.first?.progress.map(\.fileID) == [43])
    }

    @Test func aRestartWhileDownloadingStartsOver() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42, protocol: .hls)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .p720))
        h.engine.move("aaaaaaaaaaaaaaaa", .downloading, fraction: 0.3)
        h.gateway.reply = { _ in SyncReply(offline: true, downloads: [record("aaaaaaaaaaaaaaaa", fileID: 42, protocol: .hls, state: .restart)], results: []) }
        await repo.sync()
        #expect(h.engine.removed == ["aaaaaaaaaaaaaaaa"])
        #expect(h.engine.added.count == 2)
        #expect(h.engine.added.last?.hls == true)
    }

    @Test func offlineProgressIsQueuedAndSentByWhenItHappened() async throws {
        var h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        h.engine.finish("aaaaaaaaaaaaaaaa")
        await settle()
        h.gateway.syncError = APIError(status: nil, message: "offline")
        let play = try #require(repo.offlinePlay(42))
        repo.progress(play, ProgressReport(fileID: 42, positionMs: 600_000, durationMs: 3_000_000, playID: "offline", state: .playing, bufferedMs: 0))
        repo.progress(play, ProgressReport(fileID: 42, positionMs: 900_000, durationMs: 3_000_000, playID: "offline", state: .stopped, bufferedMs: 0))
        await settle()
        // Offline: kept, and where the copy resumes.
        #expect(h.store.state.progress.count == 1)
        #expect(repo.downloads.first?.watch?.positionMs == 900_000)

        h.gateway.syncError = nil
        h.gateway.syncs = []
        h.clock = h.clock.addingTimeInterval(60)
        await repo.sync()
        let sent = try #require(h.gateway.syncs.first)
        #expect(sent.progress.count == 1)
        #expect(sent.progress[0].positionMs == 900_000)
        #expect(h.store.state.progress.isEmpty)
    }

    @Test func signingOutReportsEveryDownloadRemovedThenDeletesThem() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        h.engine.finish("aaaaaaaaaaaaaaaa")
        await settle()
        h.gateway.syncs = []
        await h.gateway.signOut()
        #expect(h.gateway.syncs.first?.downloads == [SyncedDownload(id: "aaaaaaaaaaaaaaaa", state: .removed)])
        #expect(h.engine.removed == ["aaaaaaaaaaaaaaaa"])
        #expect(h.store.state.records.isEmpty)
        #expect(repo.downloads.isEmpty)
    }

    @Test func aRefusedSyncDeletesTheAccountsDownloads() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        // The device was signed out from Signed-in devices: the sync's 401 signs it out here.
        h.gateway.syncError = SignedOutError()
        await repo.sync()
        #expect(h.store.state.records.isEmpty)
    }

    @Test func downloadingAgainKeepsTheOldCopyUntilTheNewOneIsDone() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42), record("bbbbbbbbbbbbbbbb", fileID: 99)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        h.engine.finish("aaaaaaaaaaaaaaaa")
        await settle()
        h.gateway.reply = { downloads in
            SyncReply(offline: true, downloads: downloads.filter { $0.state != .removed }.map {
                $0.id == "aaaaaaaaaaaaaaaa" ? record($0.id, fileID: 42, state: .replaced, replacedBy: 99) : record($0.id, fileID: 99)
            }, results: [])
        }
        await repo.sync()
        try await repo.download(DownloadChoice(fileID: 99, quality: .original))
        #expect(repo.downloads.count == 2)
        h.engine.finish("bbbbbbbbbbbbbbbb")
        await settle()
        #expect(repo.downloads.map(\.fileID) == [99])
    }

    @Test func theQueueKeepsTheNewestPositionAndTheMarkAfterIt() {
        let t = Date(timeIntervalSince1970: 1_800_000_000)
        func p(_ seq: Int, _ s: TimeInterval, position: Int? = nil, watched: Bool? = nil) -> StoredProgress {
            StoredProgress(seq: seq, ownerKey: "k", fileID: 1, downloadID: "a", at: t.addingTimeInterval(s), positionMs: position, watched: watched)
        }
        var q: [StoredProgress] = []
        q = q.adding(p(1, 0, position: 10))
        q = q.adding(p(2, 10, watched: true))
        q = q.adding(p(3, 20, position: 30))
        #expect(q.map(\.seq) == [3])
        q = q.adding(p(4, 30, watched: true))
        q = q.adding(p(5, 40, watched: false))
        #expect(q.map(\.seq) == [3, 5])
    }

    @Test func reconcileFollowsTheOneRule() {
        let reply = SyncReply(offline: true, downloads: [
            record("ready", fileID: 1), record("gone", fileID: 2, state: .gone), record("new", fileID: 3, state: .replaced, replacedBy: 30),
            record("revoked", fileID: 4, state: .revoked), record("restart", fileID: 5, state: .restart), record("done", fileID: 6, state: .restart),
        ], results: [])
        let held = [("ready", false), ("gone", true), ("new", true), ("revoked", true), ("restart", false), ("done", true), ("unknown", true)].map { (id: $0.0, done: $0.1) }
        #expect(reconcile(held, reply) == [
            .mark("ready", .none, replacedBy: nil), .mark("gone", .removedFromServer, replacedBy: nil), .mark("new", .newVersion, replacedBy: 30),
            .delete("revoked", notice: true), .restart("restart"), .mark("done", .none, replacedBy: nil), .delete("unknown", notice: false),
        ])
        let withdrawn = SyncReply(offline: false, downloads: reply.downloads, results: [])
        #expect(reconcile([(id: "ready", done: true)], withdrawn) == [.delete("ready", notice: true)])
    }

    @Test func anOfflinePlayTakesWhatCameWithTheDownload() {
        var r = record("aaaaaaaaaaaaaaaa", fileID: 42, protocol: .hls, next: 43)
        r.decision?.selectedSubtitleID = "s3"
        let d = OfflineDownload(id: r.id, fileID: 42, item: r.item, quality: .p720, mode: .transcode, protocol: .hls, sizeBytes: 1, sizeEstimated: false, status: .downloaded, watch: WatchProgress(positionMs: 120_000, durationMs: 3_000_000, completed: false))
        let media = URL(fileURLWithPath: "/Library/x.movpkg")
        let sub = URL(fileURLWithPath: "/Downloads/a/sub-s3.vtt")
        let play = OfflinePlaybackGateway.offlineDecision(d, r.decision!, media: media, parts: ["s3": sub], nextDownloaded: false)
        #expect(play.url == media)
        #expect(play.mimeType == nil)
        #expect(play.playID == "offline")
        #expect(play.resumeMs == 120_000)
        #expect(play.next == nil && play.upNextMs == 0)
        #expect(play.audio.map(\.index) == [2])
        #expect(play.subtitles.map(\.option.id) == ["s3"])
        #expect(play.subtitles.first?.url == sub)
        #expect(play.selectedSubtitleID == "s3")

        let withNext = OfflinePlaybackGateway.offlineDecision(d, r.decision!, media: media, parts: [:], nextDownloaded: true)
        #expect(withNext.next?.fileID == 43)
        #expect(withNext.subtitles.isEmpty)
        #expect(withNext.selectedSubtitleID == nil)
    }

    @Test func theGatewayPlaysADownloadFromTheDeviceAndQueuesItsHeartbeats() async throws {
        let h = Harness()
        h.gateway.made = [record("aaaaaaaaaaaaaaaa", fileID: 42)]
        let repo = h.make()
        try await repo.download(DownloadChoice(fileID: 42, quality: .original))
        h.engine.finish("aaaaaaaaaaaaaaaa")
        await settle()
        let online = OnlineStub()
        let gateway = OfflinePlaybackGateway(online: online, downloads: repo)
        let decision = try await gateway.decide(DecisionRequest(fileID: 42, profile: profile, client: client))
        #expect(decision.url.isFileURL)
        #expect(online.decided == 0)
        let reply = try await gateway.reportProgress(ProgressReport(fileID: 42, positionMs: 2_850_000, durationMs: 3_000_000, playID: "offline", state: .playing, bufferedMs: 0))
        #expect(reply.completed)
        #expect(online.reported == 0)
        #expect(h.store.state.progress.count == 1)

        _ = try await gateway.decide(DecisionRequest(fileID: 7, profile: profile, client: client))
        #expect(online.decided == 1)
    }
}

@Suite struct OfflineAudioTests {
    private let tracks = [
        AudioOption(index: 1, label: "English", language: "eng", isDefault: true),
        AudioOption(index: 2, label: "Spanish", language: "spa", isDefault: false),
    ]

    @Test func aWholeFileCopyPlaysTheTrackAskedFor() {
        let file = PlaybackDecision(mode: .direct, protocol: .file, vod: true, url: URL(filePath: "/tmp/fictional.mp4"), playID: "offline", durationMs: 1, audio: tracks, selectedAudio: 1)
        #expect(OfflinePlaybackGateway.asked(file, audioIndex: 2).selectedAudio == 2)
        #expect(OfflinePlaybackGateway.asked(file, audioIndex: nil).selectedAudio == 1)
        #expect(OfflinePlaybackGateway.asked(file, audioIndex: 9).selectedAudio == 1, "a track the copy doesn't have")
        var hls = file
        hls.protocol = .hls
        #expect(OfflinePlaybackGateway.asked(hls, audioIndex: 2).selectedAudio == 1, "an HLS copy carries one track")
    }
}

/// The online gateway behind an offline one: counts what reaches it.
final class OnlineStub: PlaybackGateway, @unchecked Sendable {
    var decided = 0
    var reported = 0

    func decide(_ request: DecisionRequest) async throws -> PlaybackDecision {
        decided += 1
        return PlaybackDecision(mode: .direct, protocol: .file, vod: true, url: server.appending(path: "api/files/\(request.fileID)/stream"), playID: "0123456789abcdef", durationMs: 1)
    }

    func reportProgress(_ report: ProgressReport) async throws -> ProgressReply {
        reported += 1
        return ProgressReply(completed: false)
    }

    func mediaToken() async throws -> MediaToken { MediaToken(token: "m1.x", expiresAt: .distantFuture) }
    func stopSession(_ sessionID: String) async throws {}
    func plans(profile: PlaybackProfile, quality: StreamQuality, fileIDs: [Int]) async throws -> [PlaybackPlan] { [] }
    func searchSubtitles(fileID: Int) async throws -> OnlineSubtitles? { nil }
    func downloadSubtitle(fileID: Int, resultID: String) async throws -> String { "" }
    func subtitleText(_ url: URL) async throws -> String { "" }
}
