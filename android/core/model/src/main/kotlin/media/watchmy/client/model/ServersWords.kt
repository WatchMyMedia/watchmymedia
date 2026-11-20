package media.watchmy.client.model

// Several servers on one device: the words the phone, the tablet and the TV
// share for Servers (the switcher), Add a server, Settings' Servers pages and
// a search that looks in every server signed in. A server is called by its
// name wherever it is known, never "the server".

const val SERVERS = "Servers"
const val ADD_A_SERVER = "Add a server"
const val ADD_A_SERVER_LINE = "On this network, or by its address"
const val MANAGE_SERVERS = "Manage servers"
const val CURRENT_SERVER = "Current"
const val SWITCHING = "Switching…"
const val SIGNED_OUT_HERE = "Signed out here · sign in again"
const val TOO_OLD_FOR_APP = "Too old for this app"
const val SWITCH_TO_THIS_SERVER = "Switch to this server"
const val REMOVE_THIS_SERVER = "Remove this server…"

/** The note after a switch: "Now on Cabin server". It goes by itself after [NOW_ON_MS]. */
fun nowOn(serverName: String): String = "Now on $serverName"

/** How long the note after a switch stays. */
const val NOW_ON_MS = 4_000L

/** A server's line in Servers: who is signed in there and its address, "Sam · home.example.net". */
fun serverLine(userName: String?, baseUrl: String): String = listOfNotNull(userName, displayAddress(baseUrl)).joinToString(" · ")

/** A server's line in Settings' Servers page, with its version: "Sam · home.example.net · v1.4.5". */
fun serverLineWithVersion(userName: String?, baseUrl: String, version: String): String =
    listOfNotNull(userName, displayAddress(baseUrl), versionLabel(version)).joinToString(" · ")

/** The line over the list when a server didn't answer: "Couldn’t reach Cabin server". */
fun couldntReach(serverName: String): String = "Couldn’t reach $serverName"

/** Under [couldntReach]: why it may be, and which server the app is still on. */
fun couldntReachBody(stillOn: String?): String =
    "It may be off, or only reachable on its own network." + if (stillOn != null) " You’re still on $stillOn." else ""

/** [couldntReach] and where the app still is, in one line, for a link that couldn't switch. */
fun couldntReachLine(serverName: String, stillOn: String?): String =
    couldntReach(serverName) + "." + if (stillOn != null) " You’re still on $stillOn." else ""

/** The button that switches to a server that can't be reached, on a phone or tablet holding downloads from it: "Watch its 3 downloads". */
fun watchItsDownloads(count: Int): String = "Watch its ${plural(count, "download")}"

/** Under Servers' title while this device casts. */
fun switchingStopsCasting(deviceName: String): String = "Switching stops casting to $deviceName."

/**
 * The Servers row in Settings: the server in use and how many more,
 * "Living room and 2 more". A name ending in "server" loses that word
 * beside the count; with one server it is the name as it is.
 */
fun serversSummary(currentName: String?, registered: Int): String {
    val name = currentName ?: return if (registered == 0) "None yet" else plural(registered, "server")
    if (registered <= 1) return name
    val short = name.trim().let { n -> if (n.length > 7 && n.endsWith(" server", ignoreCase = true)) n.dropLast(7).trimEnd() else n }
    return "$short and ${registered - 1} more"
}

/** Over the list in Settings' Servers page ([device]: "phone", "tablet", "TV"). */
fun serversLede(device: String, registered: Int): String =
    if (registered > 1) "This $device is signed in to each of these. Search looks in all of them." else "This $device is signed in to this server. Add another and search looks in both."

/** The account button's label: "Sam on Living room server. Open Servers". */
fun accountButtonLabel(userName: String?, serverName: String?): String =
    listOfNotNull(userName, serverName).joinToString(" on ").let { who -> if (who.isEmpty()) "Open Servers" else "$who. Open Servers" }

/** The server's name under Home's title, as a button: "On Cabin server. Open Servers". */
fun onServerLabel(serverName: String): String = "On $serverName. Open Servers"

/** "3 · 12.4 GB": what a device holds from a server, on its page. */
fun downloadsFromIt(totals: DownloadTotals?): String = if (totals == null || totals.count == 0) "None" else "${formatCount(totals.count)} · ${formatBytes(totals.bytes)}"

/** The line under the Downloads list for another server's: "3 more from Cabin server". */
fun moreDownloadsFrom(count: Int, serverName: String): String = "${formatCount(count)} more from $serverName"

/** The question Remove this server… asks. */
fun removeServerTitle(serverName: String, device: String): String = "Remove $serverName from this $device?"

/** What removing does here: signed out, and the downloads from it deleted. */
fun removeServerDoes(device: String, downloads: Int): String =
    if (downloads > 0) "This $device is signed out of it, and its ${plural(downloads, "download")} here ${if (downloads == 1) "is" else "are"} deleted."
    else "This $device is signed out of it."

/** What removing leaves alone. */
const val REMOVE_SERVER_KEEPS = "The server, your account on it and what you’ve watched there aren’t changed. You can add it again."

// Add a server.

/** The steps of Add a server, as the bar over each says: "Step 1 of 3 · Choose a server · next: Sign in". */
enum class AddServerStep(val title: String) {
    Choose("Choose a server"),
    SignIn("Sign in"),
    Added("Added"),
    ;

    val line: String
        get() = "Step ${ordinal + 1} of ${entries.size} · $title" + (entries.getOrNull(ordinal + 1)?.let { " · next: ${it.title}" } ?: "")
}

/** Under the list of servers to add: "Already added: Living room server, Cabin server." */
fun alreadyAdded(names: List<String>): String? = names.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Already added: ", postfix = ".")

/** A typed address that reaches a server already registered. */
fun alreadyAddedMessage(serverName: String): String = "$serverName is already added. Choose it in Servers to switch to it."

/** With [MAX_SERVERS] registered: nothing more is added until one is removed. */
fun serversFull(device: String): String =
    "This $device keeps up to $MAX_SERVERS servers. Remove one in Settings › Account & devices › Servers, then add this one."

/** The last step's title: "Den server is added". */
fun serverAdded(serverName: String): String = "$serverName is added"

/** Under it: "You’re on it now, signed in as Sam." */
fun serverAddedLine(userName: String?): String = if (userName != null) "You’re on it now, signed in as $userName." else "You’re on it now."

/** What changed: "This phone is signed in to 3 servers. Search looks in all of them." */
fun serverAddedChanged(device: String, registered: Int): String =
    if (registered > 1) "This $device is signed in to ${plural(registered, "server")}. Search looks in all of them." else "This $device is signed in to 1 server."

/** What happens next. */
const val SERVER_ADDED_NEXT = "Tap your initial on Home to switch. It doesn’t ask for a password again."

// Search across servers.

/** Search's empty state with two or more servers searched. */
const val SEARCH_YOUR_SERVERS = "Search your servers"

fun searchYourServersLine(servers: Int): String = "Find a show, a movie or an episode on any of your ${plural(servers, "server")}."

/** At the end of the results while a server is still out: "Still asking Cabin server…". */
fun stillAsking(serverNames: List<String>): String = "Still asking ${serverNames.joinToString(" and ")}…"

/** A server that didn't answer in time, or failed. */
fun didntAnswer(serverName: String): String = "$serverName didn’t answer, so its titles aren’t listed."

/** A registered server no one is signed in to here. */
fun signedOutNotListed(serverName: String): String = "You’re signed out of $serverName here, so its titles aren’t listed."

/** A registered server older than this app works with. */
fun tooOldNotListed(serverName: String): String = "$serverName is too old for this app, so its titles aren’t listed."

/** Nothing found on any of them. */
fun noMatchesOnServers(servers: Int): String = "No matches on your ${plural(servers, "server")}"

fun noMatchesOnServersLine(query: String): String = "Nothing is called “$query” on any of them. Check the spelling, or try fewer words."
