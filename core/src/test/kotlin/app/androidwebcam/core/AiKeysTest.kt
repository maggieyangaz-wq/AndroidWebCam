package app.androidwebcam.core

import app.androidwebcam.core.auth.AiKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AiKeysTest {
    private val laptop = InetAddress.getByName("192.168.1.50")
    private val other = InetAddress.getByName("192.168.1.51")

    @Test
    fun keyWorksOnlyFromTheComputerItWasCreatedFor() {
        var now = 1_000L
        val keys = AiKeys(clock = { now })
        val key = keys.create(laptop)
        assertTrue(key.startsWith("awc_"))
        assertEquals(AiKeys.KEY_CHARS, key.length)
        assertTrue(keys.verify(key, laptop))
        assertFalse(keys.verify(key, other))
        assertFalse(keys.verify(key.dropLast(1) + if (key.last() == 'A') "B" else "A", laptop))
        assertFalse(keys.verify(null, laptop))
        assertFalse(keys.verify("Bearer $key", laptop))
        now = 5_000L
        keys.verify(key, laptop)
        assertEquals(5_000L, keys.list().single().lastUsedAt)
        // Only hashes are kept.
        assertFalse(keys.list().single().hash.contains(key.substring(4)))
    }

    @Test
    fun newKeyForTheSameComputerReplacesTheOldOne() {
        val keys = AiKeys()
        val first = keys.create(laptop)
        val second = keys.create(laptop)
        assertFalse(keys.verify(first, laptop))
        assertTrue(keys.verify(second, laptop))
        assertEquals(1, keys.list().size)
    }

    @Test
    fun keysSurviveRestartsUntilRevoked() {
        var saved = emptyList<AiKeys.Record>()
        val keys = AiKeys(onChange = { saved = it })
        val key = keys.create(laptop)
        // Next streaming session: the app restores the persisted records.
        val restored = AiKeys(saved, onChange = { saved = it })
        assertTrue(restored.verify(key, laptop))
        restored.revokeAll()
        assertTrue(saved.isEmpty())
        assertFalse(restored.verify(key, laptop))
    }

    @Test
    fun numberOfKeysIsLimited() {
        val keys = AiKeys()
        val first = keys.create(InetAddress.getByName("10.0.0.1"))
        for (i in 2..AiKeys.MAX_KEYS + 1) keys.create(InetAddress.getByName("10.0.0.$i"))
        assertEquals(AiKeys.MAX_KEYS, keys.list().size)
        assertFalse(keys.verify(first, InetAddress.getByName("10.0.0.1")))
    }

    @Test
    fun malformedPersistedRecordsAreIgnored() {
        val keys = AiKeys(listOf(AiKeys.Record("not-a-hash", "192.168.1.50", 1, 0)))
        assertTrue(keys.list().isEmpty())
    }
}
