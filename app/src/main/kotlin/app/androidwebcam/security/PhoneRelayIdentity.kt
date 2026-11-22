package app.androidwebcam.security

import app.androidwebcam.core.cloud.Ecdsa
import app.androidwebcam.core.cloud.RelayClient
import app.androidwebcam.core.tls.ServerIdentity
import java.security.Signature

/**
 * The phone's identity towards the cloud relay: the same Android Keystore key as its TLS server
 * (so the relay's PHONE_PIN equals the pin shown in the app). The key never leaves the Keystore.
 */
class PhoneRelayIdentity(private val current: () -> ServerIdentity) : RelayClient.PhoneIdentity {
    override val spki: ByteArray get() = ServerIdentity.subjectPublicKeyInfo(current().certificate.encoded)

    override fun sign(message: ByteArray): ByteArray = Ecdsa.derToRaw(
        Signature.getInstance("SHA256withECDSA").apply {
            initSign(current().privateKey)
            update(message)
        }.sign(),
    )
}
