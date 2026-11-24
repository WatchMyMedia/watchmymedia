package media.watchmy.client.core.data

import media.watchmy.client.model.CopyChoices

/**
 * The copy Play uses for each title on several servers, in the device's
 * settings ([Stored.chosenCopies]): the last [MAX] titles chosen, so the
 * list can't grow without end. A server removed from the device leaves its
 * choices to be ignored, since its copies are no longer offered.
 */
internal class StoredCopyChoices(private val store: LocalStore) : CopyChoices {
    override fun chosen(titleKey: String): CopyChoices.Chosen? {
        val value = store.value.chosenCopies[titleKey] ?: return null
        val server = value.substringBeforeLast('/').takeIf { it.isNotEmpty() } ?: return null
        val file = value.substringAfterLast('/').toLongOrNull() ?: return null
        return CopyChoices.Chosen(server, file)
    }

    override fun choose(titleKey: String, chosen: CopyChoices.Chosen) {
        store.update { s ->
            val kept = LinkedHashMap(s.chosenCopies)
            kept.remove(titleKey)
            kept[titleKey] = "${chosen.serverId}/${chosen.fileId}"
            while (kept.size > MAX) kept.remove(kept.keys.first())
            s.copy(chosenCopies = kept)
        }
    }

    private companion object {
        const val MAX = 500
    }
}
