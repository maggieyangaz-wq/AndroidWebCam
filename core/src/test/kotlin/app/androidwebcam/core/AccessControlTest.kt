package app.androidwebcam.core

import app.androidwebcam.core.auth.AccessControl
import app.androidwebcam.core.auth.LoginResult
import app.androidwebcam.core.auth.SecurityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AccessControlTest {
    private var now = 1_000_000L
    private val events = ArrayList<SecurityEvent>()
    private val access = AccessControl(clock = { now }, onEvent = { events.add(it) })
    private val viewer = InetAddress.getByName("192.168.1.50")
    private val other = InetAddress.getByName("192.168.1.51")

    private fun wrongCode(): String = if (access.code.startsWith("0")) "11111111" else "00000000"

    @Test
    fun codeUsesUnambiguousAlphabet() {
        repeat(50) {
            access.rotateCode()
            assertEquals(8, access.code.length)
            assertTrue(access.code.all { it in AccessControl.ALPHABET })
            assertTrue(access.displayCode.matches(Regex("[0-9A-Z]{4}-[0-9A-Z]{4}")))
        }
    }

    @Test
    fun loginAcceptsFormattingVariantsAndBindsSessionToAddress() {
        val typed = access.displayCode.lowercase().replace('0', 'o').replace('1', 'l')
        val result = access.login(" $typed ", viewer, "Chrome")
        assertTrue(result is LoginResult.Success)
        val token = (result as LoginResult.Success).sessionToken
        assertEquals(AccessControl.TOKEN_CHARS, token.length)
        assertNotNull(access.authenticate(token, viewer))
        assertNull("session must be bound to the viewer address", access.authenticate(token, other))
        access.logout(token)
        assertNull(access.authenticate(token, viewer))
    }

    @Test
    fun locksAddressAfterRepeatedFailuresWithBackoff() {
        repeat(AccessControl.FREE_ATTEMPTS - 1) {
            assertTrue(access.login(wrongCode(), viewer, "x") is LoginResult.Invalid)
        }
        val locked = access.login(wrongCode(), viewer, "x")
        assertTrue(locked is LoginResult.Locked)
        assertEquals(30, (locked as LoginResult.Locked).retryAfterSeconds)
        // Even the right code is refused while locked.
        assertTrue(access.login(access.code, viewer, "x") is LoginResult.Locked)
        // Another address is unaffected.
        assertTrue(access.login(access.code, other, "x") is LoginResult.Success)
        now += 31_000
        repeat(AccessControl.FREE_ATTEMPTS) { access.login(wrongCode(), viewer, "x") }
        val second = access.login(access.code, viewer, "x") as LoginResult.Locked
        assertEquals(60, second.retryAfterSeconds)
        now += 61_000
        assertTrue(access.login(access.code, viewer, "x") is LoginResult.Success)
    }

    @Test
    fun rotatesCodeWhenGlobalFailureBudgetIsExhausted() {
        val original = access.code
        for (i in 0 until AccessControl.GLOBAL_MAX_FAILURES) {
            val address = InetAddress.getByName("10.0.0.${i + 1}")
            access.login(if (original.startsWith("0")) "11111111" else "00000000", address, "x")
        }
        assertNotEquals(original, access.code)
        assertTrue(events.any { it is SecurityEvent.CodeRotatedAfterAttack })
    }

    @Test
    fun pairingTokensAreSingleUseAndExpire() {
        val token = access.issuePairingToken()
        assertTrue(access.pair(token, viewer, "Safari") is LoginResult.Success)
        assertTrue("token must be single use", access.pair(token, other, "Safari") is LoginResult.Invalid)

        val expiring = access.issuePairingToken()
        now += AccessControl.PAIRING_TTL_MS + 1
        assertTrue(access.pair(expiring, viewer, "Safari") is LoginResult.Invalid)

        val first = access.issuePairingToken()
        val second = access.issuePairingToken()
        assertTrue("older token is replaced", access.pair(first, viewer, "x") is LoginResult.Invalid)
        assertTrue(access.pair(second, viewer, "x") is LoginResult.Success)
    }

    @Test
    fun sessionsExpireAndCanBeRevoked() {
        val token = (access.login(access.code, viewer, "x") as LoginResult.Success).sessionToken
        now += AccessControl.SESSION_IDLE_TTL_MS - 1000
        assertNotNull(access.authenticate(token, viewer)) // refreshes idle timer
        now += AccessControl.SESSION_IDLE_TTL_MS + 1
        assertNull(access.authenticate(token, viewer))

        val token2 = (access.login(access.code, viewer, "x") as LoginResult.Success).sessionToken
        val codeBefore = access.code
        access.revokeAll()
        assertNull(access.authenticate(token2, viewer))
        assertNotEquals(codeBefore, access.code)
    }
}
