package 后端

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥保管 —— 把「密钥」这类敏感字段**加密后再落盘**。
 *
 * 用 **Android 硬件密钥库（AndroidKeyStore）** 里的一把 AES-256-GCM 密钥来加解密：
 * 这把密钥**由系统生成、存在 TEE/StrongBox 里，永远不会以任何形式导出**。
 * 于是即便有人把 `接口配置.json` 拷走（root、备份、共享目录），
 * 看到的也只是 `enc1:xxxx` 密文，**没有这台设备的密钥就解不开**。
 *
 * 密文格式：`enc1:` + Base64(IV(12) || 密文)。
 * **不带头缀的值一律当作明文**（兼容从 PC 拷过来的 `接口配置.json`）。
 */
object 密钥保管 {

    private const val 别名 = "honglusi_api_key_v1"
    private const val 前缀 = "enc1:"
    private const val IV长 = 12

    private fun 取密钥(): SecretKey {
        val 库 = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (库.getEntry(别名, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val 生 = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        生.init(
            KeyGenParameterSpec.Builder(别名, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return 生.generateKey()
    }

    /** 明文 → `enc1:…`。空串保持空串；已是密文的原样返回（幂等）。失败就退回明文（宁可不加密也不能丢密钥）。 */
    fun 加密(明文: String): String {
        if (明文.isEmpty()) return ""
        if (明文.startsWith(前缀)) return 明文
        return try {
            val 密 = Cipher.getInstance("AES/GCM/NoPadding")
            密.init(Cipher.ENCRYPT_MODE, 取密钥())
            val iv = 密.iv
            val 身 = 密.doFinal(明文.toByteArray(Charsets.UTF_8))
            val 合 = ByteArray(iv.size + 身.size)
            System.arraycopy(iv, 0, 合, 0, iv.size)
            System.arraycopy(身, 0, 合, iv.size, 身.size)
            前缀 + Base64.encodeToString(合, Base64.NO_WRAP)
        } catch (e: Exception) { 明文 }
    }

    /** `enc1:…` → 明文。不是密文就原样返回（PC 拷来的明文）。解不开返回空串。 */
    fun 解密(值: String): String {
        if (!值.startsWith(前缀)) return 值
        return try {
            val 合 = Base64.decode(值.removePrefix(前缀), Base64.NO_WRAP)
            val iv = 合.copyOfRange(0, IV长)
            val 身 = 合.copyOfRange(IV长, 合.size)
            val 密 = Cipher.getInstance("AES/GCM/NoPadding")
            密.init(Cipher.DECRYPT_MODE, 取密钥(), GCMParameterSpec(128, iv))
            String(密.doFinal(身), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    /** 是不是密文（给界面判断用）。 */
    fun 是密文(值: String): Boolean = 值.startsWith(前缀)
}
