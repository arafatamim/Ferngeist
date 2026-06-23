package com.tamimarafat.ferngeist.acp.bridge.paseo

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box

/**
 * NaCl `box` primitives (Curve25519 + XSalsa20-Poly1305) for Paseo's relay E2EE,
 * backed by libsodium via lazysodium.
 *
 * The relay is a blind byte forwarder; confidentiality and daemon identity come from
 * a precomputed shared key derived from the daemon's public key (in the pairing offer)
 * and an ephemeral client keypair, exactly mirroring `nacl.box.before` / `box.after`.
 */
internal class PaseoCrypto {
    private val sodium = LazySodiumAndroid(SodiumAndroid())
    private val box: Box.Native = sodium

    data class KeyPair(val publicKey: ByteArray, val secretKey: ByteArray)

    fun generateKeyPair(): KeyPair {
        val pub = ByteArray(Box.PUBLICKEYBYTES)
        val sec = ByteArray(Box.SECRETKEYBYTES)
        check(box.cryptoBoxKeypair(pub, sec)) { "Failed to generate NaCl box keypair" }
        return KeyPair(pub, sec)
    }

    /** Precomputes the shared key: `nacl.box.before(peerPublicKey, mySecretKey)`. */
    fun sharedKey(
        peerPublicKey: ByteArray,
        mySecretKey: ByteArray,
    ): ByteArray {
        val k = ByteArray(Box.BEFORENMBYTES)
        check(box.cryptoBoxBeforeNm(k, peerPublicKey, mySecretKey)) {
            "Failed to derive NaCl box shared key"
        }
        return k
    }

    /** Encrypts [plaintext] and returns a frame: `nonce(24) ‖ (MAC ‖ ciphertext)`. */
    fun seal(
        plaintext: ByteArray,
        sharedKey: ByteArray,
    ): ByteArray {
        val nonce = sodium.randomBytesBuf(Box.NONCEBYTES)
        val cipher = ByteArray(plaintext.size + Box.MACBYTES)
        check(
            box.cryptoBoxEasyAfterNm(cipher, plaintext, plaintext.size.toLong(), nonce, sharedKey),
        ) { "Failed to encrypt relay frame" }
        return nonce + cipher
    }

    /** Decrypts a `nonce(24) ‖ (MAC ‖ ciphertext)` frame; returns null on auth failure. */
    fun open(
        frame: ByteArray,
        sharedKey: ByteArray,
    ): ByteArray? {
        if (frame.size < Box.NONCEBYTES + Box.MACBYTES) return null
        val nonce = frame.copyOfRange(0, Box.NONCEBYTES)
        val cipher = frame.copyOfRange(Box.NONCEBYTES, frame.size)
        val message = ByteArray(cipher.size - Box.MACBYTES)
        val ok = box.cryptoBoxOpenEasyAfterNm(message, cipher, cipher.size.toLong(), nonce, sharedKey)
        return if (ok) message else null
    }

    companion object {
        fun decodeKey(base64: String): ByteArray = Base64.decode(base64, Base64.DEFAULT)

        fun encodeKey(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        fun encodeFrame(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        fun decodeFrame(text: String): ByteArray = Base64.decode(text, Base64.DEFAULT)
    }
}
