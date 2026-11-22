package app.androidwebcam.security

import android.content.Context
import androidx.core.content.edit
import app.androidwebcam.core.auth.AiKeys

/**
 * Persists AI access keys across streaming sessions: only their SHA-256 hashes, the computer's
 * address and timestamps (app-private storage, excluded from backups and device transfer).
 */
class AiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai_keys", Context.MODE_PRIVATE)

    fun load(): List<AiKeys.Record> =
        prefs.getString(KEY, null).orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 4) return@mapNotNull null
            val created = parts[2].toLongOrNull() ?: return@mapNotNull null
            val lastUsed = parts[3].toLongOrNull() ?: return@mapNotNull null
            AiKeys.Record(parts[0], parts[1], created, lastUsed)
        }.toList()

    fun save(records: List<AiKeys.Record>) {
        prefs.edit { putString(KEY, records.joinToString("\n") { "${it.hash}|${it.address}|${it.createdAt}|${it.lastUsedAt}" }) }
    }

    private companion object {
        const val KEY = "records"
    }
}
