package com.amusic.app.resolver

import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 与 yy-lx-hub (Node.js) 及 Amusic (Python) 字节级互通的零依赖认证解密器
 * 规范：PBKDF2-HMAC-SHA256 + SHA256-CTR + Encrypt-then-MAC HMAC-SHA256
 */
object ApiCrypto {
    private const val VERSION = 1
    private const val ALG = "PBKDF2-SHA256-CTR-HMAC"
    private val AAD_PREFIX = "yy-lx-hub:v1:".toByteArray(Charsets.UTF_8)
    const val BUILTIN_SYNC_KEY = "yy-lx-hub::amusic::auto-sync-v1"

    fun decrypt(envelopeJson: String, secret: String = BUILTIN_SYNC_KEY): String {
        val root = JSONObject(envelopeJson)
        if (root.optInt("v") != VERSION || root.optString("alg") != ALG) {
            throw IllegalArgumentException("不支持的密文格式或版本")
        }

        val iterations = root.getInt("iter")
        if (iterations < 10000 || iterations > 1000000) {
            throw IllegalArgumentException("非法的 PBKDF2 迭代次数: $iterations")
        }

        val salt = Base64.decode(root.getString("salt"), Base64.DEFAULT)
        val nonce = Base64.decode(root.getString("nonce"), Base64.DEFAULT)
        val ct = Base64.decode(root.getString("ct"), Base64.DEFAULT)
        val mac = Base64.decode(root.getString("mac"), Base64.DEFAULT)

        // 1. 派生密钥 (64 字节: 前 32 为 encKey，后 32 为 macKey)
        val keySpec = PBEKeySpec(secret.toCharArray(), salt, iterations, 64 * 8)
        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derived = keyFactory.generateSecret(keySpec).encoded

        val encKey = derived.copyOfRange(0, 32)
        val macKey = derived.copyOfRange(32, 64)

        // 2. 校验 MAC (Encrypt-then-MAC，杜绝填充与篡改攻击)
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(macKey, "HmacSHA256"))
        hmac.update(AAD_PREFIX)
        hmac.update(salt)
        hmac.update(nonce)
        hmac.update(ct)
        val computedMac = hmac.doFinal()

        if (!MessageDigest.isEqual(computedMac, mac)) {
            throw SecurityException("密文校验失败：HMAC-SHA256 签名不匹配或密钥错误")
        }

        // 3. SHA256-CTR 解密
        val sha256 = MessageDigest.getInstance("SHA-256")
        val plain = ByteArray(ct.size)
        var offset = 0
        var counter = 0

        while (offset < ct.size) {
            sha256.reset()
            sha256.update(encKey)
            sha256.update(nonce)
            val counterBytes = ByteBuffer.allocate(4).putInt(counter).array()
            sha256.update(counterBytes)
            val block = sha256.digest()

            val take = minOf(32, ct.size - offset)
            for (i in 0 until take) {
                plain[offset + i] = (ct[offset + i].toInt() xor block[i].toInt()).toByte()
            }
            offset += take
            counter++
        }

        return String(plain, Charsets.UTF_8)
    }
}
