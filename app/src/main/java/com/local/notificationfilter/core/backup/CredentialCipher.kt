package com.local.notificationfilter.core.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * WebDAV 密码的静态加密（V11）。AES/GCM，密钥存在 Android Keystore 里。
 *
 * 定位（不要高估）：这是「防止备份配置被同机其它应用在获得 root 后直接读走」的
 * 最低防线，**不是**硬件级安全方案。自用侧载场景下，把应用专用密码明文写进
 * DataStore 才是真正不该做的事；本类的成本约 60 行，收益是密码不以可读形式落盘。
 *
 * 关键设计：
 * - **不要求用户认证**（`setUserAuthenticationRequired(false)` 默认）。自动备份跑在
 *   后台广播里，若密钥要求指纹/密码，后台永远解不开 → 功能直接失效。
 * - IV 随机生成并**前置拼在密文里**（GCM 的 IV 不保密，但绝不可复用）。
 * - 所有异常一律返回 null，绝不抛：加密失败只应导致「请重新输入密码」，
 *   不应让设置页崩溃。
 * - 本类有 Keystore IO，**调用方必须在 IO 线程**（[com.local.notificationfilter.data.prefs.BackupStore]
 *   的 `getWebDavPassword` / `setWebDavPassword` 本身是 suspend，由调用方切线程即可）。
 */
object CredentialCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "nf_backup_webdav_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** 明文 → Base64(iv + cipherText)；失败返回 null。 */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(body, 0, out, iv.size, body.size)
        Base64.encodeToString(out, Base64.NO_WRAP)
    }.getOrNull()

    /** Base64(iv + cipherText) → 明文；失败返回 null。 */
    fun decrypt(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size <= IV_LEN) return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, raw, 0, IV_LEN),
        )
        String(cipher.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    }.getOrNull()

    /** 取（或首次生成）密钥。任何异常由调用方的 runCatching 兜住。 */
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // 后台自动备份必须能无交互解密，故显式声明不要求用户认证
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
