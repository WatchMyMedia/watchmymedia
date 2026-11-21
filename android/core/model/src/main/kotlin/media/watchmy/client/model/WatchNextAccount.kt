package media.watchmy.client.model

import java.security.MessageDigest

/** An opaque launcher namespace; never a credential or a server address. */
fun watchNextAccount(server: Server?, user: User?): String? {
    if (server == null || user == null) return null
    val identity = "${server.id}\u0000${server.baseUrl}\u0000${user.id}\u0000${user.name}"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * The account [watchNextAccount] names for a registered server: what a link
 * published for it carries. Null while no one is signed in there.
 */
fun SavedServer.linkAccount(): String? {
    val id = userId?.takeIf { signedIn } ?: return null
    return watchNextAccount(server, User(id, userName.orEmpty(), isAdmin = false))
}
