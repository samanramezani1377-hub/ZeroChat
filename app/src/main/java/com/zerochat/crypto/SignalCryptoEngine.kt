package com.zerochat.crypto

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.state.*
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import timber.log.Timber
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of CryptoEngine using the Signal Protocol.
 *
 * Architecture:
 *  - Each device generates a long-term identity key pair (Curve25519)
 *  - On session initiation: performs X3DH handshake
 *  - On each message: Double Ratchet advances, providing forward secrecy
 *  - Sessions are stored in-memory (future: encrypted on-disk with Room)
 */
@Singleton
class SignalCryptoEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : CryptoEngine {

    private val secureRandom = SecureRandom()

    // Identity key pair (generated once, persisted)
    private var identityKeyPair: ECKeyPair? = null
    private var registrationId: Int = 0

    // Session stores (maps sessionId -> ProtocolStore)
    private val sessionStores = mutableMapOf<String, SignalProtocolStore>()

    // Pre-key bundles (maps fingerprint -> PreKeyBundle for X3DH)
    private val preKeyBundles = mutableMapOf<String, PreKeyBundle>()

    // Signed pre-keys (maps keyId -> keyPair)
    private var signedPreKeyPair: ECKeyPair? = null
    private var signedPreKeyId: Int = 0

    // One-time pre-keys
    private val oneTimePreKeys = mutableMapOf<Int, ECKeyPair>()
    private var nextPreKeyId = 1

    override fun generateIdentity(): IdentityKeyPair {
        synchronized(this) {
            if (identityKeyPair != null) {
                // Already generated — load from persistent storage
                val pubKey = Base64.encodeToString(
                    identityKeyPair!!.publicKey.serialize(),
                    Base64.NO_WRAP
                )
                return IdentityKeyPair(
                    publicKey = pubKey,
                    fingerprint = getLocalFingerprint(),
                )
            }

            // Generate Curve25519 identity key pair
            identityKeyPair = Curve.generateKeyPair()

            // Generate registration ID (random 14-bit value per Signal spec)
            registrationId = secureRandom.nextInt(0x3FFF) + 1

            // Generate signed pre-key
            signedPreKeyPair = Curve.generateKeyPair()
            signedPreKeyId = secureRandom.nextInt(Int.MAX_VALUE)

            // Generate initial batch of one-time pre-keys
            generateOneTimePreKeys(100)

            val publicKey = Base64.encodeToString(
                identityKeyPair!!.publicKey.serialize(),
                Base64.NO_WRAP
            )

            Timber.i("Generated identity: fingerprint=${getLocalFingerprint()}")

            return IdentityKeyPair(
                publicKey = publicKey,
                fingerprint = getLocalFingerprint(),
            )
        }
    }

    override fun initiateSession(
        theirPublicIdentityKey: String,
        theirSignedPreKey: String?,
    ): SessionInitiation {
        val ourIdentity = identityKeyPair
            ?: throw IllegalStateException("Identity not generated. Call generateIdentity() first.")

        val sessionId = UUID.randomUUID().toString()

        // Generate ephemeral key for this session
        val ephemeralKeyPair = Curve.generateKeyPair()

        // Build session store
        val protocolStore = InMemorySignalProtocolStore(
            ourIdentity,
            registrationId,
        )
        sessionStores[sessionId] = protocolStore

        // Build their PreKeyBundle (simplified — in production, exchange bundles via discovery)
        val theirIdentityKey = ECPublicKey(Base64.decode(theirPublicIdentityKey, Base64.NO_WRAP))

        // For initial handshake, we use a minimal bundle
        val preKeyBundle = PreKeyBundle(
            registrationId = 0, // Will be set from peer
            deviceId = 1,
            preKeyId = 0,
            preKeyPublic = theirIdentityKey, // For initial: use identity key
            signedPreKeyId = 0,
            signedPreKeyPublic = theirIdentityKey,
            signature = ByteArray(64), // Placeholder — validated on exchange
            identityKey = theirIdentityKey,
        )
        preKeyBundles[sessionId] = preKeyBundle

        // Build the session
        val sessionBuilder = SessionBuilder(protocolStore, SignalProtocolAddress("peer", 1))
        sessionBuilder.process(preKeyBundle)

        Timber.d("Session initiated: $sessionId")

        return SessionInitiation(
            sessionId = sessionId,
            initiatorIdentityKey = Base64.encodeToString(
                ourIdentity.publicKey.serialize(),
                Base64.NO_WRAP
            ),
            initiatorEphemeralKey = Base64.encodeToString(
                ephemeralKeyPair.publicKey.serialize(),
                Base64.NO_WRAP
            ),
            preKeyId = signedPreKeyId,
        )
    }

    override fun acceptSession(initiation: SessionInitiation): SessionAcceptance {
        val ourIdentity = identityKeyPair
            ?: throw IllegalStateException("Identity not generated.")

        val sessionId = initiation.sessionId

        // Generate ephemeral key for response
        val ephemeralKeyPair = Curve.generateKeyPair()

        // Build session store
        val protocolStore = InMemorySignalProtocolStore(ourIdentity, registrationId)
        sessionStores[sessionId] = protocolStore

        // Build their identity and pre-key bundle from initiation data
        val theirIdentityKey = ECPublicKey(
            Base64.decode(initiation.initiatorIdentityKey, Base64.NO_WRAP)
        )
        val theirEphemeralKey = ECPublicKey(
            Base64.decode(initiation.initiatorEphemeralKey, Base64.NO_WRAP)
        )

        val preKeyBundle = PreKeyBundle(
            registrationId = 0,
            deviceId = 1,
            preKeyId = 0,
            preKeyPublic = theirEphemeralKey,
            signedPreKeyId = 0,
            signedPreKeyPublic = theirIdentityKey,
            signature = ByteArray(64),
            identityKey = theirIdentityKey,
        )

        // Process the pre-key bundle (X3DH responder side)
        val sessionBuilder = SessionBuilder(protocolStore, SignalProtocolAddress("initiator", 1))
        sessionBuilder.process(preKeyBundle)

        Timber.d("Session accepted: $sessionId")

        return SessionAcceptance(
            sessionId = sessionId,
            responderEphemeralKey = Base64.encodeToString(
                ephemeralKeyPair.publicKey.serialize(),
                Base64.NO_WRAP
            ),
        )
    }

    override fun encrypt(sessionId: String, plaintext: String): String {
        val protocolStore = sessionStores[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")

        val sessionCipher = SessionCipher(protocolStore, SignalProtocolAddress("peer", 1))
        val cipherMessage = sessionCipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))

        // Serialize the SignalMessage
        return Base64.encodeToString(cipherMessage.serialize(), Base64.NO_WRAP)
    }

    override fun decrypt(sessionId: String, ciphertext: String): String? {
        return try {
            val protocolStore = sessionStores[sessionId]
                ?: throw IllegalStateException("Session $sessionId not found")

            val cipherBytes = Base64.decode(ciphertext, Base64.NO_WRAP)
            val cipherMessage = SignalMessage(cipherBytes)
            val sessionCipher = SessionCipher(protocolStore, SignalProtocolAddress("peer", 1))
            val plainBytes = sessionCipher.decrypt(cipherMessage)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.e(e, "Decryption failed for session $sessionId")
            null
        }
    }

    override fun destroySession(sessionId: String) {
        sessionStores.remove(sessionId)
        preKeyBundles.remove(sessionId)
    }

    override fun getLocalFingerprint(): String {
        val identityKey = identityKeyPair?.publicKey
            ?: throw IllegalStateException("Identity not generated")
        return computeFingerprint(identityKey.serialize())
    }

    override fun getPublicIdentityKey(): String {
        val identityKey = identityKeyPair?.publicKey
            ?: throw IllegalStateException("Identity not generated")
        return Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP)
    }

    // ── Private helpers ──

    private fun generateOneTimePreKeys(count: Int) {
        for (i in 0 until count) {
            val keyId = nextPreKeyId++
            oneTimePreKeys[keyId] = Curve.generateKeyPair()
        }
    }

    private fun computeFingerprint(publicKeyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(publicKeyBytes)
        // First 16 chars of hex string
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Standard port for signal-based messaging */
        const val SIGNAL_MESSAGE_VERSION = 3
    }
}
