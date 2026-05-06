package com.example.untiswecker

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

object TotpUtils {
    fun generateTotp(secret: String): String? {
        return try {
            val key = decodeBase32(secret)
            val time = System.currentTimeMillis() / 1000 / 30
            val data = ByteBuffer.allocate(8).putLong(time).array()

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(data)

            val offset = hash[hash.size - 1].toInt() and 0xf
            val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                    ((hash[offset + 1].toInt() and 0xff) shl 16) or
                    ((hash[offset + 2].toInt() and 0xff) shl 8) or
                    (hash[offset + 3].toInt() and 0xff)

            val otp = binary % 10.0.pow(6.0).toInt()
            otp.toString().padStart(6, '0')
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeBase32(base32: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleanInput = base32.uppercase().replace(Regex("[^A-Z2-7]"), "")
        val out = mutableListOf<Byte>()
        var buffer = 0L
        var bitsLeft = 0
        for (c in cleanInput) {
            val val5 = alphabet.indexOf(c).toLong()
            buffer = (buffer shl 5) or val5
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return out.toByteArray()
    }
}
