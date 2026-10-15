package com.dji.dronecontrol.adapter.dji

import dji.v5.manager.KeyManager

/**
 * Gates KeyManager listen/get calls until the aircraft product is connected (MSDK sample pattern).
 */
object DjiMsdkSession {

    @Volatile
    var isProductConnected: Boolean = false
        private set

    @Volatile
    var isFlightControllerLinked: Boolean = false
        private set

    private val lock = Any()
    private val onConnectListeners = mutableListOf<() -> Unit>()
    private val onDisconnectListeners = mutableListOf<() -> Unit>()
    private val onFcLinkedListeners = mutableListOf<(Boolean) -> Unit>()

    fun isBenignKeyError(message: String?): Boolean {
        if (message == null) return false
        return message.contains("REQUEST_HANDLER_NOT_FOUND", ignoreCase = true) ||
            message.contains("handler not found", ignoreCase = true)
    }

    fun whenProductConnected(listener: () -> Unit) {
        synchronized(lock) {
            onConnectListeners.add(listener)
        }
        if (isProductConnected) {
            listener()
        }
    }

    fun onProductDisconnect(listener: () -> Unit) {
        synchronized(lock) {
            onDisconnectListeners.add(listener)
        }
    }

    fun whenFlightControllerLinkChanged(listener: (Boolean) -> Unit) {
        synchronized(lock) {
            onFcLinkedListeners.add(listener)
        }
        listener(isFlightControllerLinked)
    }

    fun notifyProductConnected() {
        isProductConnected = true
        synchronized(lock) {
            onConnectListeners.toList()
        }.forEach { listener ->
            try {
                listener()
            } catch (_: Throwable) {
            }
        }
    }

    fun notifyProductDisconnected() {
        isProductConnected = false
        setFlightControllerLinked(false)
        synchronized(lock) {
            onDisconnectListeners.toList()
        }.forEach { listener ->
            try {
                listener()
            } catch (_: Throwable) {
            }
        }
    }

    fun setFlightControllerLinked(linked: Boolean) {
        if (isFlightControllerLinked == linked) return
        isFlightControllerLinked = linked
        synchronized(lock) {
            onFcLinkedListeners.toList()
        }.forEach { listener ->
            try {
                listener(linked)
            } catch (_: Throwable) {
            }
        }
    }

    fun cancelKeyListeners(owner: Any) {
        try {
            KeyManager.getInstance().cancelListen(owner)
        } catch (_: Throwable) {
        }
    }
}
