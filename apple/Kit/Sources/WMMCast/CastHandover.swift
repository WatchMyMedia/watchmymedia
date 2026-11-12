import WMMModel

@MainActor
public enum CastHandover {
    /// Capture before any suspension; finish local playback only after the TV
    /// acknowledged this file. No API call or local heartbeat is made remotely.
    @discardableResult
    public static func move(_ controller: any PlaybackController, request: PlayRequest, to cast: CastService) async -> Bool {
        guard request.recordingID == nil else { return false }
        let state = controller.state
        guard !state.isOffline else { return false }
        var request = request
        request.startMs = state.positionMs; request.audioIndex = state.selectedAudio; request.subtitleID = state.selectedSubtitle
        request.qualityOverride = state.quality
        request.subtitleMode = state.selectedSubtitle == nil ? .off : .auto
        let succeeded = await cast.play(request, quality: state.quality, subtitleMode: state.selectedSubtitle == nil ? "off" : nil)
        guard succeeded, !Task.isCancelled else { return false }
        controller.stop()
        controller.release()
        return true
    }
}
