package media.watchmy.client.core.data

import media.watchmy.client.model.GridChoices

/**
 * Each library's sort and filters, in the device's settings
 * ([Stored.gridChoices]): the last [MAX] libraries changed, so the list
 * can't grow without end. A server removed from the device leaves its
 * libraries' choices behind, unread.
 */
internal class StoredGridChoices(private val store: LocalStore) : GridChoices {
    override fun recalled(key: String): Map<String, String>? = store.value.gridChoices[key]

    override fun keep(key: String, choices: Map<String, String>) {
        store.update { s ->
            val kept = LinkedHashMap(s.gridChoices)
            kept.remove(key)
            kept[key] = choices
            while (kept.size > MAX) kept.remove(kept.keys.first())
            s.copy(gridChoices = kept)
        }
    }

    private companion object {
        const val MAX = 100
    }
}
