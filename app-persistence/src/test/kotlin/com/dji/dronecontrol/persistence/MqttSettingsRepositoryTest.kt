package com.dji.dronecontrol.persistence

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.dji.dronecontrol.core.model.MqttBrokerConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

private class InMemoryPasswordStore : MqttPasswordStore {
    private var password: String = ""
    override fun getPassword(): String = password
    override fun setPassword(value: String) {
        password = value
    }

    private var streamSecret: String = ""

    override fun getStreamTokenSecret(): String = streamSecret

    override fun setStreamTokenSecret(value: String) {
        streamSecret = value
    }
}

@RunWith(RobolectricTestRunner::class)
class MqttSettingsRepositoryTest {

    private lateinit var repository: MqttSettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = PreferenceDataStoreFactory.create(
            produceFile = { File(context.filesDir, "test_mqtt_prefs.preferences_pb") }
        )
        repository = MqttSettingsRepository(context, store, InMemoryPasswordStore())
    }

    @Test
    fun saveAndLoad_roundTrip() = runTest {
        val config = MqttBrokerConfig(
            host = "10.0.0.5",
            port = 8883,
            useTls = true,
            username = "pilot",
            password = "secret-pass",
            clientId = "test-client",
            aircraftId = "mini4pro-99",
            commandTopicOverride = "custom/cmd",
        )
        repository.save(config, autoConnectOnLaunch = true)
        val loaded = repository.load()
        assertNotNull(loaded)
        assertEquals(config.host, loaded!!.config.host)
        assertEquals(config.port, loaded.config.port)
        assertEquals(config.useTls, loaded.config.useTls)
        assertEquals(config.username, loaded.config.username)
        assertEquals(config.password, loaded.config.password)
        assertEquals(config.clientId, loaded.config.clientId)
        assertEquals(config.aircraftId, loaded.config.aircraftId)
        assertEquals(config.commandTopicOverride, loaded.config.commandTopicOverride)
        assertTrue(loaded.autoConnectOnLaunch)
        assertTrue(loaded.settingsEverSaved)
    }
}
