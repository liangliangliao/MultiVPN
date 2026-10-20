package com.multivpn.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.multivpn.app.model.Protocol
import com.multivpn.app.model.RoutePolicy
import com.multivpn.app.model.VpnProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted local profile store. No secrets in plaintext SharedPreferences.
 * Auto Backup is disabled to avoid restoring ciphertext without Keystore key.
 */
class ProfileVault(context: Context) {
    private val prefs = context.getSharedPreferences("multivpn_vault", Context.MODE_PRIVATE)

    fun loadProfiles(): List<VpnProfile> {
        val ciphertext = prefs.getString("profiles_v1", null) ?: return emptyList()
        val value = decrypt(ciphertext)
        val data = JSONArray(value)
        return List(data.length()) { index ->
            val obj = data.getJSONObject(index)
            VpnProfile(
                id = obj.getString("id"),
                name = obj.getString("name"),
                protocol = Protocol.valueOf(obj.getString("protocol")),
                endpoint = obj.getString("endpoint"),
                originalConfig = obj.getString("config"),
                importedAt = obj.getLong("at")
            )
        }
    }

    fun saveProfiles(profiles: List<VpnProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(JSONObject()
                .put("id", profile.id)
                .put("name", profile.name)
                .put("protocol", profile.protocol.name)
                .put("endpoint", profile.endpoint)
                .put("config", profile.originalConfig)
                .put("at", profile.importedAt))
        }
        check(prefs.edit().putString("profiles_v1", encrypt(array.toString())).commit()) {
            "无法保存加密配置"
        }
    }

    fun loadPolicy(): RoutePolicy = RoutePolicy(
        chinaDirect = prefs.getBoolean("policy_china_direct", true),
        primaryId = prefs.getString("policy_primary", null),
        backupId = prefs.getString("policy_backup", null)
    )

    fun savePolicy(policy: RoutePolicy) {
        check(prefs.edit()
            .putBoolean("policy_china_direct", policy.chinaDirect)
            .putString("policy_primary", policy.primaryId)
            .putString("policy_backup", policy.backupId)
            .commit()) { "无法保存路由偏好" }
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val blob = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val blob = Base64.decode(encoded, Base64.NO_WRAP)
        require(blob.size > 28) { "加密数据不完整，无法解密" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return String(cipher.doFinal(blob.copyOfRange(12, blob.size)), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "multivpn_profiles_aes_v1"
    }
}
