package media.watchmy.client.model

/**
 * Everything the screens use, provided by the app (real implementations) or
 * by core:fake (previews, tests and work before the data layer is wired).
 */
interface AppServices {
    val servers: ServerRepository
    val auth: AuthRepository
    val library: LibraryRepository
    val preferences: PreferencesRepository
    val playback: PlaybackControllerFactory
    val client: ClientInfo

    /** Live channel browsing and playback use their own contracts, separate from files. */
    val live: LiveRepository get() = LiveRepository.Unavailable
    val livePlayback: LivePlaybackControllerFactory get() = LivePlaybackControllerFactory.Unavailable

    /** Arranging rows and reading lists (PLAN-ROWS.md); [RowsRepository.Unavailable] where there are none. */
    val rows: RowsRepository get() = RowsRepository.Unavailable

    /** Requests and problem reports; [RequestRepository.Unavailable] where there are none. */
    val requests: RequestRepository get() = RequestRepository.Unavailable

    /** Casting from this phone to a TV; [CastService.None] where there is none. */
    val cast: CastService get() = CastService.None

    /** Plays cast to this TV app; [CastReceiver.None] on phones. */
    val castReceiver: CastReceiver get() = CastReceiver.None

    /** Downloads to watch offline on phones and tablets; [DownloadRepository.Unavailable] on the TV. */
    val downloads: DownloadRepository get() = DownloadRepository.Unavailable

    /** The players' seek thumbnails' pictures; [SeekThumbnails.None] where there are none. */
    val seekThumbnails: SeekThumbnails get() = SeekThumbnails.None
}
