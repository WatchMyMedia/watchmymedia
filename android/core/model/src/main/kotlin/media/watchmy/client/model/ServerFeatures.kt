package media.watchmy.client.model

/** The level that brought cast tokens; senders offer casting only from it. */
const val CAST_API_LEVEL = 2

/** The level that brought opt-in Automatic recovery and acknowledged quality switches. */
const val AUTO_RECOVERY_API_LEVEL = 24

/**
 * The level that brought the media token with the decision, a Live TV status
 * call that waits while the channel tunes, and ETags on Live TV's
 * capabilities and channels (PLAN-ANDROID-REVIEW.md §4.2.2–3a).
 */
const val DECISION_TOKEN_API_LEVEL = 28

/**
 * What a server at API [level] offers, by name, so a check says what it gates
 * rather than a number (PLAN-ANDROID-REVIEW.md §5.7). The levels are the
 * server's (`APILevel` in server/internal/api/system.go, whose comment says
 * what each one brought); a feature is offered from its level and hidden
 * below it. The `*_API_LEVEL` constants stay for the code outside core:data
 * that still compares them.
 */
@JvmInline
value class ServerFeatures(val level: Int) {
    /** 2: cast tokens. */
    val castTokens: Boolean get() = level >= CAST_API_LEVEL

    /** 3: Automatic quality. */
    val automaticQuality: Boolean get() = level >= AUTO_API_LEVEL

    /** 5: requests, problem reports and missing episodes' state. */
    val requests: Boolean get() = level >= REQUESTS_API_LEVEL

    /** 6: deleting from the apps. */
    val delete: Boolean get() = level >= DELETE_API_LEVEL

    /** 8: Profile.hdrTypes and the Dolby Vision keys. */
    val hdrProfile: Boolean get() = level >= HDR_PROFILE_API_LEVEL

    /** 9: seek thumbnails. */
    val trickplay: Boolean get() = level >= TRICKPLAY_API_LEVEL

    /** 10: offline downloads. */
    val offline: Boolean get() = level >= OFFLINE_API_LEVEL

    /** 11: Live TV. */
    val liveTv: Boolean get() = level >= LIVE_TV_API_LEVEL

    /** 13: Live TV listings. */
    val liveGuide: Boolean get() = level >= LIVE_GUIDE_API_LEVEL

    /** 14: casting Live TV. */
    val liveCast: Boolean get() = level >= LIVE_CAST_API_LEVEL

    /** 15: Live TV favourites, listings search and reminders. */
    val livePersonal: Boolean get() = level >= LIVE_PERSONAL_API_LEVEL

    /** 18: per-person subtitle preferences. */
    val subtitlePreferences: Boolean get() = level >= SUBTITLE_PREFERENCES_API_LEVEL

    /** 19: recordings (DVR) and growing playback. */
    val liveRecordings: Boolean get() = level >= LIVE_RECORDING_API_LEVEL

    /** 20: movie trailers. */
    val trailers: Boolean get() = level >= TRAILERS_API_LEVEL

    /** 21: audio languages. */
    val audioLanguages: Boolean get() = level >= AUDIO_LANGUAGES_API_LEVEL

    /** 22: rows people arrange, and lists. */
    val rows: Boolean get() = level >= ROWS_API_LEVEL

    /** 24: opt-in Automatic recovery and acknowledged quality switches. */
    val autoRecovery: Boolean get() = level >= AUTO_RECOVERY_API_LEVEL

    /** 25: Live TV device profiles. */
    val liveProfile: Boolean get() = level >= LIVE_PROFILE_API_LEVEL

    /** 26: opt-in Live TV Automatic. */
    val liveAutomatic: Boolean get() = level >= LIVE_AUTOMATIC_API_LEVEL

    /** 28: a play's decision carries its media token (Decision.mediaToken), so the player needn't ask for one. */
    val tokenWithDecision: Boolean get() = level >= DECISION_TOKEN_API_LEVEL

    /** 28: the Live TV status call waits (waitMs) while the channel tunes, so the player needn't poll. */
    val liveStatusWaits: Boolean get() = level >= DECISION_TOKEN_API_LEVEL

    /** 29: following shows, the feed of their new episodes and Home's `followed` row (PLAN-NEW-EPISODES.md). */
    val follows: Boolean get() = level >= FOLLOWS_API_LEVEL

    /** 30: related titles at the end of movie and show pages (PLAN-RELATED.md). */
    val related: Boolean get() = level >= RELATED_API_LEVEL

    /**
     * 31: Profile.maxPcmChannels and pcm, copied only in direct play; a
     * converted track may be FLAC, E-AC-3 or AC-3 (PLAN-AUDIO.md).
     */
    val pcmProfile: Boolean get() = level >= PCM_PROFILE_API_LEVEL
}
