package com.dji.dronecontrol.adapter.dji

import dji.sdk.keyvalue.key.DJIKey
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal object DjiKeyValueHelper {

    suspend fun <T> getValueAsync(
        keyLabel: String,
        key: DJIKey<T>,
        log: (String) -> Unit,
    ): T? = suspendCoroutine { cont ->
        try {
            KeyManager.getInstance().getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
                override fun onSuccess(result: T?) {
                    log("Key GET [$keyLabel] OK -> $result")
                    cont.resume(result)
                }

                override fun onFailure(error: IDJIError) {
                    log("Key GET [$keyLabel] FAIL -> code=${error.errorCode()} desc=${error.description()}")
                    cont.resume(null)
                }
            })
        } catch (t: Throwable) {
            log("Key GET [$keyLabel] EXC -> ${t.message}")
            cont.resume(null)
        }
    }

    fun <T> getValueSync(
        keyLabel: String,
        key: DJIKey<T>,
        log: (String) -> Unit,
    ): T? {
        try {
            val value = KeyManager.getInstance().getValue(key)
            log("Key GET sync [$keyLabel] -> $value")
            return value
        } catch (t: Throwable) {
            log("Key GET sync [$keyLabel] EXC -> ${t.message}")
            return null
        }
    }
}
