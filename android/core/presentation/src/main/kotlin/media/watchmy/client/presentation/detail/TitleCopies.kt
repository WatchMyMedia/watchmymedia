package media.watchmy.client.presentation.detail

import media.watchmy.client.model.CopyChoices
import media.watchmy.client.model.MediaVersion
import media.watchmy.client.model.PlaybackPlan
import media.watchmy.client.model.WatchProgress
import media.watchmy.client.model.serverHeading
import media.watchmy.client.model.shortServerName

/**
 * One server's copy of a title on a page that offers each server's: the
 * versions there, the person's place there and how each version would play.
 * [serverId] is null for the server in use; [registeredId] is the server's
 * own id whichever is in use, which a choice is kept by.
 */
data class ServerCopy(
    val serverId: String?,
    val registeredId: String,
    val serverName: String,
    val away: Boolean,
    val versions: List<MediaVersion>,
    val watch: WatchProgress?,
    /** The movie's or episode's id on that server. */
    val titleId: Long,
    val plans: Map<Long, PlaybackPlan> = emptyMap(),
) {
    /** "Home server", the group's heading. */
    val heading: String get() = serverHeading(serverName)

    /** "Home", as a button names the copy. */
    val shortName: String get() = shortServerName(serverName)

    /** Partway through here: the place a Resume would start from on this copy. */
    val place: WatchProgress? get() = watch?.takeIf { !it.completed && it.positionMs > 0 && it.durationMs > 0 }
}

/** A copy and one of its versions: what Play plays. */
data class CopyVersion(val copy: ServerCopy, val version: MediaVersion)

/**
 * Every copy of a title, the page's own and the other servers', with what
 * the person chose and where they got to. Groups are in Servers' order with
 * those away from home last.
 */
data class Copies(
    val all: List<ServerCopy> = emptyList(),
    /** The servers asked and not answered a moment in, by name ("Still asking Den server…"). */
    val asking: List<String> = emptyList(),
    val chosen: CopyChoices.Chosen? = null,
) {
    /** More than one server has it: the page offers the chooser. */
    val several: Boolean get() = all.size > 1

    /** The place to Resume from: the one played most recently on any copy, else the furthest. */
    val latest: ServerCopy?
        get() = all.filter { it.place != null }.maxWithOrNull(compareBy<ServerCopy> { it.place?.updatedAtMs ?: Long.MIN_VALUE }.thenBy { it.place?.positionMs ?: 0 })

    /**
     * What Play plays: the copy chosen for this title, else the one with the
     * latest place, else the first; its chosen version, else its first.
     */
    val play: CopyVersion?
        get() {
            val pick = chosen?.let { c -> all.firstOrNull { it.registeredId == c.serverId }?.let { copy -> copy to copy.versions.firstOrNull { it.fileId == c.fileId } } }
            val copy = pick?.first ?: latest ?: all.firstOrNull() ?: return null
            val version = pick?.second ?: copy.versions.firstOrNull() ?: return null
            return CopyVersion(copy, version)
        }

    /** Where Resume starts on [target]: the latest place when it is another copy's, else null, which the server resumes itself. */
    fun startFor(target: ServerCopy): Long? {
        val at = latest ?: return null
        return if (at.registeredId == target.registeredId) null else at.place?.positionMs
    }
}

/** The groups in the order the chooser lists them: Servers' order, those away from home last. */
fun List<ServerCopy>.inChooserOrder(): List<ServerCopy> = sortedBy { it.away }
