package app.androidwebcam.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import app.androidwebcam.core.cloud.CloudCredential
import app.androidwebcam.core.cloud.RelayClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cloud agents that may take single pictures through the relay. Created only on the phone itself.
 * Stored: name, times, SHA-256 of the relay token, and the agent's end-to-end key encrypted with a
 * non-exportable AES key in the Android Keystore (app-private storage, no backups).
 */
class CloudAgentStore private constructor(context: Context) {
    data class Info(val id: String, val name: String, val createdAtMs: Long, val lastUsedAtMs: Long)

    private class Record(val id: String, val name: String, val authHash: String, val wrappedKey: ByteArray, val createdAtMs: Long, var lastUsedAtMs: Long)

    private val prefs = context.getSharedPreferences("cloud_agents", Context.MODE_PRIVATE)
    private val records = LinkedHashMap<String, Record>()
    private val lastAdmitted = HashMap<String, Long>()
    private var lastSaved = 0L
    private val mutableAgents = MutableStateFlow<List<Info>>(emptyList())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** For the UI. */
    val agents: StateFlow<List<Info>> = mutableAgents.asStateFlow()

    init {
        val b64 = Base64.getDecoder()
        for (line in prefs.getString(KEY, null).orEmpty().lineSequence()) {
            val p = line.split('|')
            if (p.size != 6) continue
            try {
                records[p[0]] = Record(p[0], String(b64.decode(p[1]), Charsets.UTF_8), p[2], b64.decode(p[3]), p[4].toLong(), p[5].toLong())
            } catch (_: Exception) {
                // skip damaged entries
            }
        }
        publish()
    }

    /** A new agent; the returned credential is shown once and never stored in full. */
    @Synchronized
    fun create(name: String): CloudCredential {
        while (records.size >= MAX_AGENTS) records.remove(records.keys.first())
        var credential = CloudCredential.create()
        while (records.containsKey(credential.agentId)) credential = CloudCredential.create()
        records[credential.agentId] = Record(
            credential.agentId, name.trim().take(40).ifEmpty { "Agent" }, credential.authHash, wrap(credential.e2eKey),
            System.currentTimeMillis(), 0,
        )
        changed()
        return credential
    }

    @Synchronized
    fun revoke(id: String) {
        if (records.remove(id) != null) changed()
    }

    @Synchronized
    fun revokeAll() {
        if (records.isEmpty()) return
        records.clear()
        changed()
    }

    @Synchronized
    fun nameOf(id: String): String? = records[id]?.name

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    /** What the relay client needs; at most one picture every [MIN_INTERVAL_MS] per agent. */
    val directory = object : RelayClient.AgentDirectory {
        override fun list(): List<RelayClient.Agent> = synchronized(this@CloudAgentStore) {
            records.values.map { RelayClient.Agent(it.id, it.authHash) }
        }

        override fun key(id: String): ByteArray? {
            val wrapped = synchronized(this@CloudAgentStore) { records[id]?.wrappedKey } ?: return null
            return try { unwrap(wrapped) } catch (_: Exception) { null }
        }

        override fun admit(id: String): Int? = synchronized(this@CloudAgentStore) {
            val record = records[id] ?: return@synchronized 401
            val now = System.currentTimeMillis()
            if (now - (lastAdmitted[id] ?: 0) < MIN_INTERVAL_MS) return@synchronized 429
            lastAdmitted[id] = now
            record.lastUsedAtMs = now
            if (now - lastSaved > 60_000) save() // the last-use time is informational
            publish()
            null
        }
    }

    private fun changed() {
        save()
        publish()
        for (listener in listeners) listener()
    }

    private fun publish() {
        mutableAgents.value = records.values.map { Info(it.id, it.name, it.createdAtMs, it.lastUsedAtMs) }
    }

    private fun save() {
        lastSaved = System.currentTimeMillis()
        val b64 = Base64.getEncoder()
        prefs.edit {
            putString(KEY, records.values.joinToString("\n") {
                listOf(it.id, b64.encodeToString(it.name.toByteArray(Charsets.UTF_8)), it.authHash, b64.encodeToString(it.wrappedKey), it.createdAtMs, it.lastUsedAtMs).joinToString("|")
            })
        }
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey()) // the Keystore chooses a random IV
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrap(wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, wrapped, 0, 12))
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }

    companion object {
        const val MIN_INTERVAL_MS = 5_000L
        private const val MAX_AGENTS = 16
        private const val KEY = "records"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAP_ALIAS = "awc_cloud_agents"

        @Volatile private var instance: CloudAgentStore? = null

        fun get(context: Context): CloudAgentStore =
            instance ?: synchronized(this) { instance ?: CloudAgentStore(context.applicationContext).also { instance = it } }
    }
}
