package com.itcast.ds_balance_viewer.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 敏感数据加密存储。
 *
 * 原理：在 Android Keystore 里生成一把 AES-256 主密钥，这把密钥由系统安全硬件
 * （TEE / StrongBox）托管，应用只能"使用"它，无法读出明文。用它把 API 密钥加密后，
 * 密文才写入普通 SharedPreferences。即使有人拿到手机里的 prefs 文件，
 * 没有这台设备的 Keystore 也解不开。
 *
 * 加密算法：AES/GCM/NoPadding，每条记录使用独立随机 IV（12 字节前置存储）。
 */
object SecureStore {

    private const val PREF_NAME = "ds_balance_secure"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val MASTER_ALIAS = "ds_balance_master_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun requirePrefs(): SharedPreferences =
        prefs ?: error("SecureStore 未初始化，请先调用 init(context)")

    /** 取出主密钥，不存在则创建。首次调用会触发 Keystore 生成，耗时约几十毫秒。 */
    private fun masterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户认证，保证后台/冷启动也能解密；如需更高安全性可改为 setUserAuthenticationRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    /** 加密并返回 Base64 字符串（格式：IV + 密文） */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** 解密，失败（如换了设备、Keystore 被清除）返回 null */
    fun decrypt(encoded: String): String? = runCatching {
        val raw = Base64.decode(encoded, Base64.NO_WRAP)
        if (raw.size <= IV_LENGTH) return@runCatching null
        val iv = raw.copyOfRange(0, IV_LENGTH)
        val cipherText = raw.copyOfRange(IV_LENGTH, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    fun putString(key: String, value: String) {
        requirePrefs().edit().putString(key, value).apply()
    }

    fun getString(key: String): String? = requirePrefs().getString(key, null)

    fun remove(key: String) {
        requirePrefs().edit().remove(key).apply()
    }
}
