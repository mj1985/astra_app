package com.astraconnect.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Prüft die OAEP-Implementierung gegen die Java-Standardbibliothek (SHA-256 + MGF1-SHA-256,
 * identisch zu PyCryptodome PKCS1_OAEP mit hash_algo=SHA256, wie im Original verwendet).
 */
class OtpCryptoTest {
    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply {
        initialize(RSAKeyGenParameterSpec(1024, BigInteger.valueOf(0x11)))
    }.generateKeyPair()

    @Test
    fun oaepEncrypt_isDecryptableByJca() {
        val kp = keyPair()
        val modHex = (kp.public as RSAPublicKey).modulus.toString(16)
        repeat(20) { i ->
            val msg = ByteArray(16) { (it * 7 + i).toByte() }
            val enc = Otp.oaepEncrypt(msg, modHex)
            val dec = Cipher.getInstance("RSA/ECB/OAEPPadding").run {
                init(Cipher.DECRYPT_MODE, kp.private, oaep); doFinal(enc)
            }
            assertArrayEquals(msg, dec)
        }
    }

    @Test
    fun decodeOaep_readsServerSignedBlocks() {
        val kp = keyPair()
        val pub = kp.public as RSAPublicKey
        val priv = kp.private as RSAPrivateKey
        // 2 Blöcke wie beim echten Kiw (Modulus > ein OAEP-Block)
        val part1 = ByteArray(40) { (it + 1).toByte() }
        val part2 = ByteArray(24) { (200 - it).toByte() }
        fun serverBlock(msg: ByteArray): ByteArray {
            // Standard-OAEP-EM erzeugen und mit dem privaten Exponenten "verschlüsseln" (so macht es der Server)
            val c = Cipher.getInstance("RSA/ECB/OAEPPadding").run { init(Cipher.ENCRYPT_MODE, pub, oaep); doFinal(msg) }
            val em = Cipher.getInstance("RSA/ECB/NoPadding").run { init(Cipher.DECRYPT_MODE, priv); doFinal(c) }
            val emInt = BigInteger(1, em)
            val s = emInt.modPow(priv.privateExponent, pub.modulus).toByteArray()
            return when {
                s.size == 128 -> s
                s.size > 128 -> s.copyOfRange(s.size - 128, s.size)
                else -> ByteArray(128 - s.size) + s
            }
        }
        val enc = Otp.hex(serverBlock(part1) + serverBlock(part2))
        assertEquals(Otp.hex(part1 + part2), Otp.decodeOaep(enc, pub.modulus.toString(16)))
    }

    @Test
    fun base36_matchesPythonReference() {
        // Python: number_to_base36 liefert die Ziffern niederwertigste zuerst
        assertEquals("b", Otp.base36(1))
        assertEquals("ab", Otp.base36(36))     // 36 = "10" → umgekehrt "01" → a,b
        assertEquals("99", Otp.base36(36 * 36 - 1))
    }
}
