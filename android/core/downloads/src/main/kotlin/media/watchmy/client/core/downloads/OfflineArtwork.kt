package media.watchmy.client.core.downloads

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import media.watchmy.client.model.ImageSize
import media.watchmy.client.model.OfflineItem
import media.watchmy.client.model.OfflineOwner

/** Small pinned files, outside Coil eviction and backups; removed with their download. */
internal class OfflineArtwork(private val root: File) {
    private val lock = Any()
    private val removed = mutableSetOf<String>()

    suspend fun keep(owner: OfflineOwner, id: String, item: OfflineItem, fetch: suspend (String, ImageSize) -> ByteArray?, alive: () -> Boolean) {
        val paths = listOfNotNull(item.posterPath?.let { it to ImageSize.W342 }, item.artPath?.let { it to ImageSize.W780 }).distinctBy { it.first }
        for ((path, size) in paths) {
            if (local(owner, id, path) != null) continue
            val bytes = fetch(path, size) ?: continue
            coroutineContext.ensureActive()
            synchronized(lock) {
                if (directory(owner, id).name in removed || !alive()) return
                val file = image(owner, id, path)
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                try {
                    tmp.writeBytes(bytes)
                    check(tmp.renameTo(file)) { "Couldn't retain download artwork" }
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    fun local(owner: OfflineOwner, id: String, path: String): String? = synchronized(lock) {
        image(owner, id, path).takeIf { directory(owner, id).name !in removed && it.isFile }?.toURI()?.toString()
    }

    fun remove(owner: OfflineOwner, id: String) = synchronized(lock) {
        removed += directory(owner, id).name
        directory(owner, id).deleteRecursively()
    }

    private fun image(owner: OfflineOwner, id: String, path: String) = File(directory(owner, id), hash(path))
    private fun directory(owner: OfflineOwner, id: String) = File(root, hash("${owner.key}\u0000${owner.baseUrl}\u0000$id"))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
