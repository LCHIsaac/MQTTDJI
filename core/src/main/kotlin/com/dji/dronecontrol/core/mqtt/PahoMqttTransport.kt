package com.dji.dronecontrol.core.mqtt

import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.model.MqttBrokerConfig
import com.dji.dronecontrol.core.model.MqttTlsConfig
import com.dji.dronecontrol.core.model.MqttLinkDiagnostics
import com.dji.dronecontrol.core.port.MqttConnectionState
import com.dji.dronecontrol.core.port.MqttTransportPort
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Robust MQTT transport implementation using Eclipse Paho Async Client.
 */
class PahoMqttTransport(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : MqttTransportPort {

    private val tag = "MQTT"

    private val _connectionState = MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _linkDiagnostics = MutableStateFlow(MqttLinkDiagnostics())
    val linkDiagnostics: StateFlow<MqttLinkDiagnostics> = _linkDiagnostics.asStateFlow()

    private var subscribedToCommandTopic = false
    private var lastMessageInEpochMs: Long? = null
    private var lastMessageOutEpochMs: Long? = null
    private var lastConnectError: String? = null

    private fun publishLinkDiagnostics(config: MqttBrokerConfig?) {
        val stateLabel = when (_connectionState.value) {
            is MqttConnectionState.Connected -> "connected"
            is MqttConnectionState.Connecting -> "connecting"
            is MqttConnectionState.Disconnected -> "disconnected"
            is MqttConnectionState.Error -> "error"
        }
        val err = when (val s = _connectionState.value) {
            is MqttConnectionState.Error -> s.message
            else -> lastConnectError
        }
        _linkDiagnostics.value = MqttLinkDiagnostics(
            brokerEndpoint = config?.let { "${it.host}:${it.port}" } ?: "—",
            connectionStateLabel = stateLabel,
            lastError = err,
            commandTopic = config?.commandTopic ?: "—",
            subscribedToCommandTopic = subscribedToCommandTopic,
            lastMessageInEpochMs = lastMessageInEpochMs,
            lastMessageOutEpochMs = lastMessageOutEpochMs,
        )
    }

    private val _incomingCommands = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val incomingCommands: SharedFlow<String> = _incomingCommands.asSharedFlow()

    private var mqttClient: MqttAsyncClient? = null
    private var currentConfig: MqttBrokerConfig? = null
    private var tlsConfig: MqttTlsConfig? = null

    fun setTlsConfig(config: MqttTlsConfig?) {
        tlsConfig = config
    }

    override suspend fun connect(config: MqttBrokerConfig) = withContext(dispatcher) {
        currentConfig = config
        subscribedToCommandTopic = false
        _connectionState.value = MqttConnectionState.Connecting
        publishLinkDiagnostics(config)
        AppLogger.i(tag, "Connecting to MQTT broker at ${config.brokerUrl} (client: ${config.clientId})...")

        try {
            // Disconnect old client if present
            try {
                mqttClient?.let { client ->
                    if (client.isConnected) {
                        client.disconnect(1000)
                    }
                    client.close()
                }
            } catch (_: Exception) {}

            val client = MqttAsyncClient(
                config.brokerUrl,
                config.clientId.ifBlank { "dji-client-${System.currentTimeMillis()}" },
                MemoryPersistence()
            )
            mqttClient = client

            val connectOptions = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 20
                if (config.username.isNotBlank()) {
                    userName = config.username
                }
                if (config.password.isNotBlank()) {
                    password = config.password.toCharArray()
                }
                if (config.useMtls && tlsConfig?.enabled == true) {
                    socketFactory = MqttTlsSocketFactory.createSocketFactory(tlsConfig!!)
                }
            }

            client.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    _connectionState.value = MqttConnectionState.Connected
                    lastConnectError = null
                    publishLinkDiagnostics(currentConfig)
                    AppLogger.i(tag, "MQTT connected to $serverURI (reconnect=$reconnect)")
                    scope.launch {
                        try {
                            subscribeToTopics(client, config)
                        } catch (e: Exception) {
                            val errMsg = "Subscribe failed: ${e.message}"
                            AppLogger.e(tag, errMsg)
                            _connectionState.value = MqttConnectionState.Error(errMsg, e)
                        }
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    val errMsg = "MQTT connection lost: ${cause?.message}"
                    AppLogger.w(tag, errMsg)
                    lastConnectError = errMsg
                    subscribedToCommandTopic = false
                    _connectionState.value = MqttConnectionState.Error(errMsg, cause)
                    publishLinkDiagnostics(currentConfig)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    message?.let {
                        lastMessageInEpochMs = System.currentTimeMillis()
                        publishLinkDiagnostics(currentConfig)
                        val payload = String(it.payload, Charsets.UTF_8)
                        val safe = com.dji.dronecontrol.core.security.SensitiveDataRedactor.redactJsonPayload(payload)
                        AppLogger.d(tag, "Incoming message on $topic: $safe")
                        _incomingCommands.tryEmit(payload)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            suspendCoroutine { cont ->
                client.connect(connectOptions, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        _connectionState.value = MqttConnectionState.Connected
                        AppLogger.i(tag, "MQTT connect handshake successful")
                        cont.resume(Unit)
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        val ex = exception ?: MqttException(MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt())
                        val errMsg = "MQTT connect failed: ${ex.message}"
                        AppLogger.e(tag, errMsg)
                        lastConnectError = errMsg
                        _connectionState.value = MqttConnectionState.Error(errMsg, ex)
                        publishLinkDiagnostics(config)
                        cont.resumeWithException(ex)
                    }
                })
            }

            subscribeToTopics(client, config)

        } catch (e: Exception) {
            val errMsg = "Failed to connect MQTT: ${e.message}"
            AppLogger.e(tag, errMsg)
            lastConnectError = errMsg
            _connectionState.value = MqttConnectionState.Error(errMsg, e)
            publishLinkDiagnostics(config)
            throw e
        }
    }

    private suspend fun subscribeToTopics(client: MqttAsyncClient, config: MqttBrokerConfig) = withContext(dispatcher) {
        val topic = config.commandTopic
        if (topic.isNotBlank() && client.isConnected) {
            suspendCoroutine { cont ->
                client.subscribe(topic, 1, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        subscribedToCommandTopic = true
                        publishLinkDiagnostics(config)
                        AppLogger.i(tag, "Subscribed to command topic: $topic")
                        cont.resume(Unit)
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        val ex = exception ?: Exception("Subscribe failed on $topic")
                        AppLogger.e(tag, "Failed to subscribe to $topic: ${ex.message}")
                        cont.resumeWithException(ex)
                    }
                })
            }
        }
    }

    override suspend fun disconnect(): Unit = withContext(dispatcher) {
        AppLogger.i(tag, "Disconnecting MQTT client...")
        try {
            mqttClient?.let { client ->
                if (client.isConnected) {
                    suspendCoroutine { cont ->
                        client.disconnect(null, object : IMqttActionListener {
                            override fun onSuccess(asyncActionToken: IMqttToken?) {
                                cont.resume(Unit)
                            }

                            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                                cont.resume(Unit)
                            }
                        })
                    }
                }
                client.close()
            }
        } catch (_: Exception) {
        } finally {
            mqttClient = null
            subscribedToCommandTopic = false
            _connectionState.value = MqttConnectionState.Disconnected
            publishLinkDiagnostics(currentConfig)
            AppLogger.i(tag, "MQTT disconnected")
        }
    }

    override suspend fun publish(topic: String, payload: String, qos: Int, retained: Boolean) = withContext(dispatcher) {
        val client = mqttClient
        if (client == null || !client.isConnected) {
            return@withContext
        }
        val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
            this.qos = qos
            isRetained = retained
        }
        try {
            suspendCoroutine { cont ->
                client.publish(topic, message, null, object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        lastMessageOutEpochMs = System.currentTimeMillis()
                        publishLinkDiagnostics(currentConfig)
                        cont.resume(Unit)
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                        val ex = exception ?: Exception("Publish failed to $topic")
                        AppLogger.e(tag, "Publish error on $topic: ${ex.message}")
                        cont.resumeWithException(ex)
                    }
                })
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "Publish exception on $topic: ${e.message}")
        }
    }
}
