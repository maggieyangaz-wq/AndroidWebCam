package app.androidwebcam.core.auth

import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Security-relevant events surfaced to the phone owner. Never contain secrets. */
sealed class SecurityEvent {
    data class ViewerAuthorized(val address: String, val client: String, val viaPairing: Boolean) : SecurityEvent()
    data class LoginFailed(val address: String) : SecurityEvent()
    data class AddressLocked(val address: String, val seconds: Long) : SecurityEvent()
    /** Too many failures overall: the access code was replaced automatically. */
    object CodeRotatedAfterAttack : SecurityEvent()
}

sealed class LoginResult {
    class Success(val sessionToken: String) : LoginResult()
    class Invalid(val attemptsBeforeLock: Int) : LoginResult()
    class Locked(val retryAfterSeconds: Long) : LoginResult()
}

/**
 * Access control for viewers:
 *  - a random access code (Crockford base32, 8 chars = 40 bits) that changes every time
 *    streaming starts, and on demand;
 *  - single-use pairing tokens (144 bits, 5 minutes) embedded in the QR code;
 *  - per-address exponential lockout and a global failure budget that rotates the code;
 *  - in-memory sessions (256-bit tokens, stored only as SHA-256 hashes) bound to the
 *    viewer's IP address with idle and absolute expiry.
 */
class AccessControl(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: SecureRandom = SecureRandom(),
    private val onEvent: (SecurityEvent) -> Unit = {},
) {
    class Session internal constructor(
        val id: Int,
        val address: InetAddress,
        val client: String,
        val createdAt: Long,
        @Volatile var lastSeen: Long,
    )

    private class AddressState(var failures: Int = 0, var lockLevel: Int = 0, var lockedUntil: Long = 0)

    private class Pairing(val hash: ByteArray, val expiresAt: Long)

    private var accessCode: String = newCode()
    private var pairing: Pairing? = null
    private val sessions = LinkedHashMap<String, Session>()
    private val addressStates = HashMap<InetAddress, AddressState>()
    private val globalFailures = ArrayDeque<Long>()
    private var nextSessionId = 1

    @get:Synchronized
    val code: String get() = accessCode

    /** Code formatted for display, e.g. "K7QM-3XRP". */
    val displayCode: String get() = code.chunked(4).joinToString("-")

    @Synchronized
    fun rotateCode() {
        accessCode = newCode()
        pairing = null
    }

    /** Invalidates every session and pairing token and issues a new access code. */
    @Synchronized
    fun revokeAll() {
        sessions.clear()
        rotateCode()
    }

    @Synchronized
    fun sessionCount(): Int {
        purgeExpired()
        return sessions.size
    }

    /** Issues a fresh single-use pairing token; any previous token stops working. */
    @Synchronized
    fun issuePairingToken(): String {
        val bytes = ByteArray(18).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        pairing = Pairing(sha256(token), clock() + PAIRING_TTL_MS)
        return token
    }

    @Synchronized
    fun login(input: String, address: InetAddress, client: String): LoginResult {
        locked(address)?.let { return it }
        val candidate = normalizeCode(input)
        val ok = candidate.length == CODE_LENGTH &&
            MessageDigest.isEqual(candidate.toByteArray(Charsets.US_ASCII), accessCode.toByteArray(Charsets.US_ASCII))
        return if (ok) success(address, client, viaPairing = false) else failure(address)
    }

    @Synchronized
    fun pair(token: String, address: InetAddress, client: String): LoginResult {
        locked(address)?.let { return it }
        val current = pairing
        val ok = current != null && clock() < current.expiresAt && token.length in 16..64 &&
            MessageDigest.isEqual(sha256(token), current.hash)
        return if (ok) {
            pairing = null // single use
            success(address, client, viaPairing = true)
        } else {
            failure(address)
        }
    }

    /** Validates a session cookie for a request from [address]; refreshes idle expiry. */
    @Synchronized
    fun authenticate(token: String?, address: InetAddress): Session? {
        if (token == null || token.length != TOKEN_CHARS) return null
        purgeExpired()
        val session = sessions[hashHex(token)] ?: return null
        if (session.address != address) return null
        session.lastSeen = clock()
        return session
    }

    @Synchronized
    fun logout(token: String?) {
        if (token != null && token.length == TOKEN_CHARS) sessions.remove(hashHex(token))
    }

    private fun success(address: InetAddress, client: String, viaPairing: Boolean): LoginResult {
        addressStates.remove(address)
        purgeExpired()
        while (sessions.size >= MAX_SESSIONS) {
            val oldest = sessions.keys.first()
            sessions.remove(oldest)
        }
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = clock()
        sessions[hashHex(token)] = Session(nextSessionId++, address, client, now, now)
        onEvent(SecurityEvent.ViewerAuthorized(address.hostAddress ?: "?", client, viaPairing))
        return LoginResult.Success(token)
    }

    private fun locked(address: InetAddress): LoginResult.Locked? {
        val state = addressStates[address] ?: return null
        val remaining = state.lockedUntil - clock()
        return if (remaining > 0) LoginResult.Locked((remaining + 999) / 1000) else null
    }

    private fun failure(address: InetAddress): LoginResult {
        val now = clock()
        val host = address.hostAddress ?: "?"
        onEvent(SecurityEvent.LoginFailed(host))

        globalFailures.addLast(now)
        while (globalFailures.isNotEmpty() && now - globalFailures.first() > GLOBAL_WINDOW_MS) globalFailures.removeFirst()
        if (globalFailures.size >= GLOBAL_MAX_FAILURES) {
            globalFailures.clear()
            rotateCode()
            onEvent(SecurityEvent.CodeRotatedAfterAttack)
        }

        val state = addressStates.getOrPut(address) { AddressState() }
        state.failures++
        if (state.failures >= FREE_ATTEMPTS) {
            state.failures = 0
            state.lockLevel++
            val lockMs = (BASE_LOCK_MS shl (state.lockLevel - 1).coerceAtMost(10)).coerceAtMost(MAX_LOCK_MS)
            state.lockedUntil = now + lockMs
            onEvent(SecurityEvent.AddressLocked(host, lockMs / 1000))
            return LoginResult.Locked(lockMs / 1000)
        }
        if (addressStates.size > 1024) addressStates.entries.removeAll { it.value.lockedUntil < now && it.key != address }
        return LoginResult.Invalid(FREE_ATTEMPTS - state.failures)
    }

    private fun purgeExpired() {
        val now = clock()
        sessions.entries.removeAll { (_, s) ->
            now - s.createdAt > SESSION_ABSOLUTE_TTL_MS || now - s.lastSeen > SESSION_IDLE_TTL_MS
        }
    }

    private fun newCode(): String {
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))

    private fun hashHex(value: String): String = sha256(value).joinToString("") { String.format("%02x", it) }

    companion object {
        const val CODE_LENGTH = 8
        /** Crockford base32: no I, L, O, U to avoid confusion when typing. */
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        const val SESSION_COOKIE = "__Host-awc_sid"
        const val TOKEN_CHARS = 43 // 32 bytes, base64url without padding
        const val MAX_SESSIONS = 32
        const val FREE_ATTEMPTS = 5
        const val BASE_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 15 * 60_000L
        const val GLOBAL_WINDOW_MS = 10 * 60_000L
        const val GLOBAL_MAX_FAILURES = 20
        const val PAIRING_TTL_MS = 5 * 60_000L
        const val SESSION_IDLE_TTL_MS = 4 * 3600_000L
        const val SESSION_ABSOLUTE_TTL_MS = 24 * 3600_000L

        /** Accepts lower case, spaces, dashes and the usual look-alikes (O->0, I/L->1). */
        fun normalizeCode(input: String): String {
            val sb = StringBuilder(CODE_LENGTH)
            for (ch in input.take(64)) {
                val c = ch.uppercaseChar()
                when {
                    c == '-' || c == ' ' || c == '\t' -> Unit
                    c == 'O' -> sb.append('0')
                    c == 'I' || c == 'L' -> sb.append('1')
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
