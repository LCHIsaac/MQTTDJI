package com.dji.dronecontrol.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dji.dronecontrol.core.model.MqttBrokerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.mqttSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mqtt_settings"
)

data class MqttSettingsSnapshot(
    val config: MqttBrokerConfig,
    val autoConnectOnLaunch: Boolean,
    val settingsEverSaved: Boolean,
)

interface MqttPasswordStore {
    fun getPassword(): String
    fun setPassword(value: String)
    fun getStreamTokenSecret(): String
    fun setStreamTokenSecret(value: String)
}

class EncryptedMqttPasswordStore(context: Context) : MqttPasswordStore {
    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mqtt_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getPassword(): String = encryptedPrefs.getString(KEY_PASSWORD, "") ?: ""

    override fun setPassword(value: String) {
        encryptedPrefs.edit().putString(KEY_PASSWORD, value).apply()
    }

    override fun getStreamTokenSecret(): String =
        encryptedPrefs.getString(KEY_STREAM_TOKEN_SECRET, "") ?: ""

    override fun setStreamTokenSecret(value: String) {
        encryptedPrefs.edit().putString(KEY_STREAM_TOKEN_SECRET, value).apply()
    }

    companion object {
        private const val KEY_PASSWORD = "mqtt_password"
        private const val KEY_STREAM_TOKEN_SECRET = "stream_token_secret"
    }
}

class MqttSettingsRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences> = context.mqttSettingsDataStore,
    private val passwordStore: MqttPasswordStore = EncryptedMqttPasswordStore(context),
) {

    val settingsFlow: Flow<MqttSettingsSnapshot?> = dataStore.data.map { prefs ->
        if (prefs[KEY_SETTINGS_SAVED] != true) {
            return@map null
        }
        val password = passwordStore.getPassword()
        MqttSettingsSnapshot(
            config = MqttBrokerConfig(
                host = prefs[KEY_HOST] ?: "192.168.1.100",
                port = prefs[KEY_PORT] ?: 1883,
                useTls = prefs[KEY_USE_TLS] ?: false,
                username = prefs[KEY_USERNAME] ?: "",
                password = password,
                clientId = prefs[KEY_CLIENT_ID] ?: "dji-client",
                aircraftId = prefs[KEY_AIRCRAFT_ID] ?: "drone-01",
                commandTopicOverride = prefs[KEY_CMD_TOPIC]?.takeIf { it.isNotBlank() },
                statusTopicOverride = prefs[KEY_STATUS_TOPIC]?.takeIf { it.isNotBlank() },
                ackTopicOverride = prefs[KEY_ACK_TOPIC]?.takeIf { it.isNotBlank() },
                useMtls = prefs[KEY_USE_MTLS] ?: false,
                viewerBaseUrl = prefs[KEY_VIEWER_BASE_URL] ?: "https://tronico.kujuhk.com/live",
                streamTokenUrl = prefs[KEY_STREAM_TOKEN_URL] ?: "",
                photoUploadUrl = prefs[KEY_PHOTO_UPLOAD_URL] ?: "",
                videoUploadUrl = prefs[KEY_VIDEO_UPLOAD_URL] ?: "",
            ),
            autoConnectOnLaunch = prefs[KEY_AUTO_CONNECT] ?: true,
            settingsEverSaved = true,
        )
    }

    suspend fun load(): MqttSettingsSnapshot? = settingsFlow.first()

    suspend fun save(config: MqttBrokerConfig, autoConnectOnLaunch: Boolean, streamTokenSecret: String? = null) {
        passwordStore.setPassword(config.password)
        streamTokenSecret?.let { passwordStore.setStreamTokenSecret(it) }
        dataStore.edit { prefs ->
            prefs[KEY_SETTINGS_SAVED] = true
            prefs[KEY_HOST] = config.host
            prefs[KEY_PORT] = config.port
            prefs[KEY_USE_TLS] = config.useTls
            prefs[KEY_USERNAME] = config.username
            prefs[KEY_CLIENT_ID] = config.clientId
            prefs[KEY_AIRCRAFT_ID] = config.aircraftId
            prefs[KEY_CMD_TOPIC] = config.commandTopicOverride ?: ""
            prefs[KEY_STATUS_TOPIC] = config.statusTopicOverride ?: ""
            prefs[KEY_ACK_TOPIC] = config.ackTopicOverride ?: ""
            prefs[KEY_AUTO_CONNECT] = autoConnectOnLaunch
            prefs[KEY_USE_MTLS] = config.useMtls
            prefs[KEY_VIEWER_BASE_URL] = config.viewerBaseUrl
            prefs[KEY_STREAM_TOKEN_URL] = config.streamTokenUrl
            prefs[KEY_PHOTO_UPLOAD_URL] = config.photoUploadUrl
            prefs[KEY_VIDEO_UPLOAD_URL] = config.videoUploadUrl
        }
    }

    fun getStreamTokenSecret(): String = passwordStore.getStreamTokenSecret()

    suspend fun setAutoConnectOnLaunch(enabled: Boolean) {
        dataStore.edit { prefs ->
            if (prefs[KEY_SETTINGS_SAVED] == true) {
                prefs[KEY_AUTO_CONNECT] = enabled
            }
        }
    }

    companion object {
        private val KEY_SETTINGS_SAVED = booleanPreferencesKey("settings_saved")
        private val KEY_HOST = stringPreferencesKey("host")
        private val KEY_PORT = intPreferencesKey("port")
        private val KEY_USE_TLS = booleanPreferencesKey("use_tls")
        private val KEY_USERNAME = stringPreferencesKey("username")
        private val KEY_CLIENT_ID = stringPreferencesKey("client_id")
        private val KEY_AIRCRAFT_ID = stringPreferencesKey("aircraft_id")
        private val KEY_CMD_TOPIC = stringPreferencesKey("cmd_topic")
        private val KEY_STATUS_TOPIC = stringPreferencesKey("status_topic")
        private val KEY_ACK_TOPIC = stringPreferencesKey("ack_topic")
        private val KEY_AUTO_CONNECT = booleanPreferencesKey("auto_connect_on_launch")
        private val KEY_USE_MTLS = booleanPreferencesKey("use_mtls")
        private val KEY_VIEWER_BASE_URL = stringPreferencesKey("viewer_base_url")
        private val KEY_STREAM_TOKEN_URL = stringPreferencesKey("stream_token_url")
        private val KEY_PHOTO_UPLOAD_URL = stringPreferencesKey("photo_upload_url")
        private val KEY_VIDEO_UPLOAD_URL = stringPreferencesKey("video_upload_url")
    }
}
