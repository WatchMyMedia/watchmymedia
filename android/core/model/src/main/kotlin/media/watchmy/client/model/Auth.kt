package media.watchmy.client.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class User(val id: Long, val name: String, val isAdmin: Boolean)

/** How this device describes itself to the server (sign-in, pairing, playback). */
data class ClientInfo(
    /** Random, made once and kept. */
    val deviceId: String,
    /** The name people see in Signed-in devices and Activity; editable in Settings. */
    val name: String,
    /** "WatchMyMedia for Android TV" or "WatchMyMedia for Android". */
    val product: String,
    /** "androidtv" or "android". */
    val platform: String,
    val version: String,
    /** The device's model ("Pixel 9"), shown in Signed-in devices; blank when unknown. */
    val model: String = "",
)

/** A code shown on a TV for someone signed in elsewhere to approve. */
data class PairingCode(
    /** Six characters, A–Z and 2–9 without O or I. */
    val code: String,
    val expiresAtMs: Long,
    /** The approval page with the code filled in, for the QR code. */
    val linkUrl: String,
    val pollIntervalMs: Long,
)

sealed interface PairingState {
    data object Starting : PairingState
    data class Waiting(val code: PairingCode) : PairingState
    /** The TV is signed in; [AuthRepository.user] now holds [user]. */
    data class Approved(val user: User) : PairingState
    /** The code ran out; collect [AuthRepository.pair] again for a new one. */
    data object Expired : PairingState
    data class Failed(val message: String) : PairingState
}

/** A browser or app signed in to the account (Settings › Signed-in devices). */
data class DeviceSession(
    val id: String,
    val kind: Kind,
    val name: String,
    val product: String?,
    val platform: String?,
    val version: String?,
    val lastIp: String?,
    val createdAtMs: Long,
    val lastSeenAtMs: Long,
    val current: Boolean,
    val userName: String,
    /** The device's model ("Pixel 9"), when the app said at sign-in. */
    val model: String? = null,
) {
    enum class Kind { Browser, Device }
}

/** A TV waiting behind a pairing code, as the phone's Link a TV shows it. */
data class PendingDevice(
    val code: String,
    val name: String,
    val product: String?,
    val platform: String?,
    val version: String?,
    val ip: String?,
    val approved: Boolean,
    /** The TV's model ("Chromecast with Google TV"), when it said. */
    val model: String? = null,
)

/**
 * Signing in on the server in use. Methods throw [ApiException] on failure;
 * a wrong password is an ApiException with status 401, throttling 429.
 */
interface AuthRepository {
    /** The signed-in user on the current server, or null. */
    val user: StateFlow<User?>

    /** This device's name as people see it; editable in Settings. */
    val deviceName: StateFlow<String>

    suspend fun setDeviceName(name: String)

    /** Password sign-in as a device (bearer token, kept for a year of disuse). */
    suspend fun signIn(name: String, password: String): User

    /**
     * TV sign-in by code: starts a pairing, polls it, and emits each state
     * until it is approved, expires or fails. Cancelling the collection stops
     * polling.
     */
    fun pair(): Flow<PairingState>

    /** Signs this device out of the current server. */
    suspend fun signOut()

    /** Sessions of the signed-in user, or everyone's for an admin with [all]. */
    suspend fun sessions(all: Boolean = false): List<DeviceSession>

    suspend fun revoke(sessionId: String)

    /** Phone side of pairing: the device behind a code. */
    suspend fun lookUpPairing(code: String): PendingDevice

    /** Phone side of pairing: signs the device behind [code] in as the current user. */
    suspend fun approvePairing(code: String): PendingDevice
}
