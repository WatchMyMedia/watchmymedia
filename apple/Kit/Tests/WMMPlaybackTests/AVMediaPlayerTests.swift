#if os(macOS)
import AVFoundation
import CoreVideo
import Foundation
import Testing
@testable import WMMPlayback

/// The real AVPlayer path on this Mac, with a 3 second movie made for the test.
@MainActor
@Suite(.serialized) struct AVMediaPlayerTests {
    @Test func theItemsMetadataNamesWhatPlaysForTheTransportBar() {
        let items = AVMediaPlayer.metadata(title: "Night Shift Diner", subtitle: "S02 E01 · Open Late")
        #expect(items.map(\.identifier) == [.commonIdentifierTitle, .iTunesMetadataTrackSubTitle])
        #expect(items.map { $0.value as? String } == ["Night Shift Diner", "S02 E01 · Open Late"])
        // A bare play link has no title until the library names it.
        #expect(AVMediaPlayer.metadata(title: "", subtitle: nil).isEmpty)
    }

    @Test func theLinkIsMeasuredOverTheTransfersSinceTheLastRead() {
        let start = TransferCount(bytes: 10_000_000, seconds: 2)
        // 2.5 MB in 10 s of transfer: 2 Mbps, whatever came before.
        #expect(TransferCount(bytes: 12_500_000, seconds: 12).rate(since: start) == 2_000_000)
        // Under a second of transfer, or nothing new, says too little.
        #expect(TransferCount(bytes: 10_500_000, seconds: 2.5).rate(since: start) == nil)
        #expect(start.rate(since: start) == nil)
        #expect(TransferCount(bytes: 1_000, seconds: 5).rate(since: TransferCount()) == 1_600)
    }

    @Test func startsWhereAskedPlaysSeeksAndEnds() async throws {
        let movie = try await Self.movie()
        let p = AVMediaPlayer()
        var failures: [PlayerFailure] = []
        var times = 0
        var frames = 0
        p.setListener(MediaPlayerListener(changed: {}, failed: { failures.append($0) }, time: { times += 1 }, firstFrame: { frames += 1 }))
        #expect(p.avPlayer?.allowsExternalPlayback == true)

        p.load(PlayerSource(url: movie, mimeType: "video/mp4", hls: false, live: false, startPositionMs: 1_000, playWhenReady: false, title: "Test"))
        // Until the start seek is done the player reports where it will start.
        #expect(p.phase == .buffering)
        #expect(p.positionMs == 1_000)
        try await until { p.phase == .ready }
        #expect(abs(p.positionMs - 1_000) <= 100)
        #expect(!p.playWhenReady)
        #expect(p.bufferedPositionMs >= p.positionMs)

        p.play()
        try await until { p.positionMs >= 1_400 }
        try await until { frames > 0 }
        #expect(p.playWhenReady)
        #expect(times > 0)
        #expect(frames == 1)
        p.pause()
        p.seek(toMs: 500)
        try await until { abs(p.positionMs - 500) <= 50 }
        #expect(p.phase == .ready)

        p.seek(toMs: 2_700)
        p.play()
        try await until { p.phase == .ended }
        p.stop()
        #expect(p.phase == .idle)
        #expect(failures.isEmpty)
        p.release()
    }

    @Test func aFileThatIsNotThereFails() async throws {
        let p = AVMediaPlayer()
        var failures: [PlayerFailure] = []
        p.setListener(MediaPlayerListener(changed: {}, failed: { failures.append($0) }, time: {}))
        p.load(PlayerSource(url: FileManager.default.temporaryDirectory.appending(path: "missing-\(UUID().uuidString).mp4"), hls: false, live: false, startPositionMs: 0, playWhenReady: true, title: "Missing"))
        try await until { !failures.isEmpty }
        #expect(p.phase == .idle)
        p.release()
    }

    @Test func aDirectPlayStartsOnTheDecidedAudioTrack() async throws {
        let movie = try await Self.movie(audio: ["eng", "spa"])
        let p = AVMediaPlayer()
        var unmatched = 0
        p.setListener(MediaPlayerListener(changed: {}, failed: { _ in }, time: {}, audioUnmatched: { unmatched += 1 }))
        p.load(PlayerSource(url: movie, mimeType: "video/mp4", hls: false, live: false, startPositionMs: 0, playWhenReady: true, title: "Copper Valley",
                            audio: AudioStart(ordinal: 1, count: 2)))
        // Nothing plays until the track is selected.
        #expect(p.phase == .buffering)
        try await until { p.phase == .ready }
        #expect(p.selectedAudioOrdinal == 1)
        try await until { p.positionMs >= 300 }
        #expect(p.playWhenReady)
        #expect(unmatched == 0)
        p.release()
    }

    @Test func optionsThatDontLineUpPlayTheDefaultAndSaySo() async throws {
        let movie = try await Self.movie(audio: ["eng", "spa"])
        let p = AVMediaPlayer()
        var unmatched = 0
        p.setListener(MediaPlayerListener(changed: {}, failed: { _ in }, time: {}, audioUnmatched: { unmatched += 1 }))
        p.load(PlayerSource(url: movie, mimeType: "video/mp4", hls: false, live: false, startPositionMs: 1_000, playWhenReady: true, title: "Copper Valley",
                            audio: AudioStart(ordinal: 2, count: 3)))
        try await until { unmatched == 1 }
        try await until { p.positionMs >= 1_300 }
        #expect(p.selectedAudioOrdinal == 0)
        p.release()
    }

    private func until(_ condition: () -> Bool, timeout: Duration = .seconds(10)) async throws {
        let deadline = ContinuousClock.now + timeout
        while !condition() {
            guard ContinuousClock.now < deadline else {
                Issue.record("Timed out")
                return
            }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    /// A 3 s, 10 fps, 64×64 H.264 movie in the temporary folder, with a
    /// silent AAC track in each of `audio`'s languages, in one alternate
    /// group with the first as the default.
    private static func movie(audio: [String] = []) async throws -> URL {
        let url = FileManager.default.temporaryDirectory.appending(path: "wmm-player-test-\(UUID().uuidString).mp4")
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 64, AVVideoHeightKey: 64])
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA, kCVPixelBufferWidthKey as String: 64, kCVPixelBufferHeightKey as String: 64,
        ])
        writer.add(input)
        let rate = 44_100
        let tracks = audio.map { language in
            let track = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64_000,
            ])
            track.expectsMediaDataInRealTime = false
            track.languageCode = language
            writer.add(track)
            return track
        }
        if let first = tracks.first, tracks.count > 1 {
            writer.add(AVAssetWriterInputGroup(inputs: tracks, defaultInput: first))
        }
        #expect(writer.startWriting())
        writer.startSession(atSourceTime: .zero)
        for track in tracks {
            for second in 0..<3 {
                while !track.isReadyForMoreMediaData { try await Task.sleep(for: .milliseconds(5)) }
                track.append(try silence(frames: rate, at: second * rate, rate: rate))
            }
            track.markAsFinished()
        }
        for frame in 0..<30 {
            while !input.isReadyForMoreMediaData { try await Task.sleep(for: .milliseconds(5)) }
            var buffer: CVPixelBuffer?
            CVPixelBufferCreate(nil, 64, 64, kCVPixelFormatType_32BGRA, nil, &buffer)
            guard let buffer else { throw CocoaError(.fileWriteUnknown) }
            CVPixelBufferLockBaseAddress(buffer, [])
            memset(CVPixelBufferGetBaseAddress(buffer), Int32(frame * 8), CVPixelBufferGetDataSize(buffer))
            CVPixelBufferUnlockBaseAddress(buffer, [])
            adaptor.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(frame), timescale: 10))
        }
        input.markAsFinished()
        writer.endSession(atSourceTime: CMTime(value: 30, timescale: 10))
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            writer.finishWriting { done.resume() }
        }
        #expect(writer.status == .completed)
        return url
    }

    /// `frames` of 16-bit mono silence starting at frame `start`.
    private static func silence(frames: Int, at start: Int, rate: Int) throws -> CMSampleBuffer {
        var asbd = AudioStreamBasicDescription(
            mSampleRate: Float64(rate), mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
            mBytesPerPacket: 2, mFramesPerPacket: 1, mBytesPerFrame: 2, mChannelsPerFrame: 1, mBitsPerChannel: 16, mReserved: 0
        )
        var format: CMAudioFormatDescription?
        CMAudioFormatDescriptionCreate(allocator: nil, asbd: &asbd, layoutSize: 0, layout: nil, magicCookieSize: 0, magicCookie: nil, extensions: nil, formatDescriptionOut: &format)
        var block: CMBlockBuffer?
        CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: frames * 2, blockAllocator: nil, customBlockSource: nil,
                                           offsetToData: 0, dataLength: frames * 2, flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block)
        guard let format, let block else { throw CocoaError(.fileWriteUnknown) }
        CMBlockBufferFillDataBytes(with: 0, blockBuffer: block, offsetIntoDestination: 0, dataLength: frames * 2)
        var sample: CMSampleBuffer?
        CMAudioSampleBufferCreateReadyWithPacketDescriptions(allocator: nil, dataBuffer: block, formatDescription: format, sampleCount: frames,
                                                             presentationTimeStamp: CMTime(value: CMTimeValue(start), timescale: CMTimeScale(rate)),
                                                             packetDescriptions: nil, sampleBufferOut: &sample)
        guard let sample else { throw CocoaError(.fileWriteUnknown) }
        return sample
    }
}
#endif
