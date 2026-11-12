package media.watchmy.client.presentation.live

import media.watchmy.client.model.LiveChannel
import media.watchmy.client.model.liveChannelAfter

/** Channel keys follow the server's lineup order and skip unavailable entries. */
fun nextLiveChannel(channels: List<LiveChannel>, currentId: String, direction: Int): LiveChannel? = liveChannelAfter(channels, currentId, direction)

/** Exact matches only: duplicate channel numbers across tuners require a choice from the list. */
fun exactLiveChannel(channels: List<LiveChannel>, number: String): LiveChannel? =
    channels.filter { it.available && it.number == number }.singleOrNull()

/** A bounded numeric entry, including one subchannel separator; delete never tunes anything. */
fun editLiveNumber(number: String, key: Char): String = when {
    key == '\b' -> number.dropLast(1)
    number.length >= 10 -> number
    key in '0'..'9' -> number + key
    key == '.' && number.isNotEmpty() && '.' !in number -> "$number."
    else -> number
}
