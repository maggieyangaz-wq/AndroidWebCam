package app.androidwebcam.core.auth

import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * AI access keys: let AI agents on one computer take single pictures (GET /api/snapshot with
 * "Authorization: Bearer <key>") without the per-session login, e.g. with one curl command.
 *  - created by a logged-in viewer, for its own network address, and only usable from there;
 *  - only for single pictures (never the live stream);
 *  - kept until revoked, so agents keep working after streaming restarts; one key per address;
 *  - stored only as SHA-256 hashes ([Record]s are what the app persists).
 */
class AiKeys(
    initial: List<Record> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val onChange: (List<Record>) -> Unit = {},
) {
    /** [hash] is the hex SHA-256 of the key; times are epoch milliseconds (0 = never used). */
    data class Record(val hash: String, val address: String, val createdAt: Long, val lastUsedAt: Long)

    private val records = LinkedHashMap<String, Record>()

    init {
        for (r in initial.takeLast(MAX_KEYS)) {
            if (r.hash.matches(HASH) && r.address.isNotEmpty()) records[r.hash] = r
        }
    }

    /** A new key for [address]; an earlier key of that address stops working. */
    @Synchronized
    fun create(address: InetAddress): String {
        val host = address.hostAddress ?: throw IllegalArgumentException("address")
        val key = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        records.values.removeAll { it.address == host }
        while (records.size >= MAX_KEYS) records.remove(records.keys.first())
        val hash = hashHex(key)
        records[hash] = Record(hash, host, clock(), 0)
        changed()
        return key
    }

    /** True if [key] is valid for a request from [address]; records the use. */
    @Synchronized
    fun verify(key: String?, address: InetAddress): Boolean {
        if (key == null || key.length != KEY_CHARS || !key.startsWith(PREFIX)) return false
        val record = records[hashHex(key)] ?: return false
        if (record.address != address.hostAddress) return false
        val now = clock()
        records[record.hash] = record.copy(lastUsedAt = now)
        // The last-use time is informational: persist it at most once a minute.
        if (now - record.lastUsedAt >= 60_000) changed()
        return true
    }

    @Synchronized
    fun revokeAll() {
        if (records.isEmpty()) return
        records.clear()
        changed()
    }

    @Synchronized
    fun list(): List<Record> = records.values.toList()

    private fun changed() = onChange(records.values.toList())

    companion object {
        const val PREFIX = "awc_"
        const val KEY_CHARS = 47 // "awc_" + 32 bytes base64url without padding
        const val MAX_KEYS = 8
        private val HASH = Regex("[0-9a-f]{64}")

        fun hashHex(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.US_ASCII)).joinToString("") { String.format("%02x", it) }
    }
}
