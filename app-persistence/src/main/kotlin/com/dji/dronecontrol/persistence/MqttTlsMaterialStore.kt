package com.dji.dronecontrol.persistence

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dji.dronecontrol.core.model.MqttTlsConfig
import java.io.File

class MqttTlsMaterialStore(context: Context) {

    private val filesDir = File(context.filesDir, "mqtt_mtls").apply { mkdirs() }
    private val pkcs12File = File(filesDir, "client.p12")
    private val caFile = File(filesDir, "ca.pem")

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mqtt_mtls_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun hasClientCertificate(): Boolean = pkcs12File.exists() && pkcs12File.length() > 0L

    fun savePkcs12(bytes: ByteArray, password: String) {
        pkcs12File.writeBytes(bytes)
        encryptedPrefs.edit().putString(KEY_PKCS12_PASSWORD, password).apply()
    }

    fun saveCustomCa(pemBytes: ByteArray) {
        caFile.writeBytes(pemBytes)
    }

    fun clearAll() {
        if (pkcs12File.exists()) pkcs12File.delete()
        if (caFile.exists()) caFile.delete()
        encryptedPrefs.edit().remove(KEY_PKCS12_PASSWORD).apply()
    }

    fun loadTlsConfig(): MqttTlsConfig? {
        if (!hasClientCertificate()) return null
        val password = encryptedPrefs.getString(KEY_PKCS12_PASSWORD, "") ?: ""
        val caBytes = if (caFile.exists()) caFile.readBytes() else null
        return MqttTlsConfig(
            pkcs12Bytes = pkcs12File.readBytes(),
            pkcs12Password = password,
            customCaPemBytes = caBytes,
        )
    }

    companion object {
        private const val KEY_PKCS12_PASSWORD = "pkcs12_password"
    }
}
