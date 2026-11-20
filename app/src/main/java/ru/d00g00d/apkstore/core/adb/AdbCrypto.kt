package ru.d00g00d.apkstore.core.adb

import android.content.Context
import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class AdbCrypto(context: Context) {
    private val privFile = File(context.filesDir, "adbkey.priv")
    private val pubFile = File(context.filesDir, "adbkey.pub")
    val keyPair: KeyPair by lazy { loadOrGenerateKeyPair() }

    private fun loadOrGenerateKeyPair(): KeyPair {
        if (privFile.exists() && pubFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            } catch (_: Exception) {
                privFile.delete()
                pubFile.delete()
            }
        }
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        return kp
    }

    /** Подпись 20-байтного SHA-1 токена от adbd в формате PKCS#1 v1.5 (без ASN.1 DigestInfo, сырой RSA) */
    fun signAdbToken(token: ByteArray): ByteArray {
        val signature = Signature.getInstance("NONEwithRSA")
        // Android adbd ожидает PKCS#1 v1.5 padding вокруг 35-байтного SHA1 DigestInfo + 20 байт токена
        val sha1Prefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        signature.initSign(keyPair.private)
        signature.update(sha1Prefix)
        signature.update(token)
        return signature.sign()
    }

    /** Сериализация публичного ключа в 524-байтную структуру libcrypto + Base64 + null-terminator */
    fun getAdbPublicKeyPayload(): ByteArray {
        val rsaPub = keyPair.public as RSAPublicKey
        val n = rsaPub.modulus
        val words = 64 // 2048 bit / 32 bit
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.modInverse(r32)).toInt()

        val r = BigInteger.ONE.shiftLeft(words * 32)
        val rr = r.multiply(r).mod(n)

        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv)

        val nInts = bigIntToLittleEndianInts(n, words)
        nInts.forEach { buf.putInt(it) }

        val rrInts = bigIntToLittleEndianInts(rr, words)
        rrInts.forEach { buf.putInt(it) }

        buf.putInt(rsaPub.publicExponent.toInt())

        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 carperms@flyme\u0000".toByteArray(Charsets.UTF_8)
    }

    private fun bigIntToLittleEndianInts(value: BigInteger, words: Int): IntArray {
        var v = value
        val mask = BigInteger.valueOf(0xFFFFFFFFL)
        return IntArray(words) {
            val w = v.and(mask).toInt()
            v = v.shiftRight(32)
            w
        }
    }
}
