package com.dji.dronecontrol.adapter.dji

import android.util.Log
import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.model.PhotoParams
import com.dji.dronecontrol.core.model.PhotoSize
import com.dji.dronecontrol.core.port.CameraPort
import com.dji.dronecontrol.core.port.CameraResult
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.PhotoSize as DjiPhotoSize
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * DJI Mobile SDK V5 Camera Adapter.
 *
 * Implements [CameraPort] using official DJI Mobile SDK V5 [KeyManager] and [CameraKey]:
 * - Automatically verifies and switches between [CameraMode.PHOTO_NORMAL] and [CameraMode.VIDEO_NORMAL].
 * - Executes photo capture via [CameraKey.KeyStartShootPhoto].
 * - Controls video recording via [CameraKey.KeyStartRecord] and [CameraKey.KeyStopRecord].
 * - Tracks camera mode, recording state, and duration via real-time KeyManager listeners.
 */
class DjiCameraAdapter : CameraPort {

    private val tag = "DjiCameraAdapter"

    companion object {
        private const val TAKE_PHOTO_TIMEOUT_MS = 45_000L
        private const val CAMERA_KEY_TIMEOUT_MS = 15_000L
    }

    private fun logI(msg: String) {
        Log.i(tag, msg)
        AppLogger.i(tag, msg)
    }

    private fun logW(msg: String) {
        Log.w(tag, msg)
        AppLogger.w(tag, msg)
    }

    private fun logE(msg: String) {
        Log.e(tag, msg)
        AppLogger.e(tag, msg)
    }

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0)
    override val recordingDurationSeconds: StateFlow<Int> = _recordingDurationSeconds.asStateFlow()

    private val _cameraMode = MutableStateFlow("UNKNOWN")
    override val cameraMode: StateFlow<String> = _cameraMode.asStateFlow()

    /** Mini 4 Pro / consumer aircraft: primary camera on index 0 (LEFT_OR_MAIN). */
    private val cameraIndex = ComponentIndexType.LEFT_OR_MAIN

    init {
        DjiMsdkSession.whenProductConnected { listenCameraState() }
        DjiMsdkSession.onProductDisconnect {
            DjiMsdkSession.cancelKeyListeners(this)
            _cameraMode.value = "UNKNOWN"
            _isRecording.value = false
        }
    }

    private fun listenCameraState() {
        try {
            val keyManager = KeyManager.getInstance()
            logI("Registering Camera KeyManager listeners (index=$cameraIndex)...")

            val modeKey = KeyTools.createKey(CameraKey.KeyCameraMode, cameraIndex)
            keyManager.listen(modeKey, this, object : CommonCallbacks.KeyListener<CameraMode> {
                override fun onValueChange(oldValue: CameraMode?, newValue: CameraMode?) {
                    val mode = newValue?.name ?: "UNKNOWN"
                    _cameraMode.value = mode
                    logI("Camera mode updated: $mode")
                }
            })

            val recordingKey = KeyTools.createKey(CameraKey.KeyIsRecording, cameraIndex)
            keyManager.listen(recordingKey, this, object : CommonCallbacks.KeyListener<Boolean> {
                override fun onValueChange(oldValue: Boolean?, newValue: Boolean?) {
                    val rec = newValue ?: false
                    _isRecording.value = rec
                    logI("Camera recording state updated: isRecording=$rec")
                }
            })

            val recordingTimeKey = KeyTools.createKey(CameraKey.KeyRecordingTime, cameraIndex)
            keyManager.listen(recordingTimeKey, this, object : CommonCallbacks.KeyListener<Int> {
                override fun onValueChange(oldValue: Int?, newValue: Int?) {
                    _recordingDurationSeconds.value = newValue ?: 0
                }
            })

        } catch (t: Throwable) {
            val msg = t.message ?: t.toString()
            if (DjiMsdkSession.isBenignKeyError(msg)) {
                Log.d(tag, "Camera listeners deferred: $msg")
            } else {
                logE("Failed to initialize Camera key listeners: $msg")
            }
        }
    }

    override suspend fun takePhoto(params: PhotoParams): CameraResult = withContext(Dispatchers.IO) {
        logI("Initiating take photo action (size=${params.size}, format=${params.format})...")
        val result = withTimeoutOrNull(TAKE_PHOTO_TIMEOUT_MS) {
            takePhotoInternal(params)
        }
        result ?: run {
            val msg = "Take photo timed out after ${TAKE_PHOTO_TIMEOUT_MS}ms"
            logE(msg)
            CameraResult.Failure(msg)
        }
    }

    private suspend fun takePhotoInternal(params: PhotoParams): CameraResult {
        val keyManager = KeyManager.getInstance()

        val currentMode = keyManager.getValue(KeyTools.createKey(CameraKey.KeyCameraMode, cameraIndex))
        if (currentMode != CameraMode.PHOTO_NORMAL) {
            logI("Switching camera mode to PHOTO_NORMAL (current: $currentMode)...")
            val modeSetSuccess = withTimeoutOrNull(CAMERA_KEY_TIMEOUT_MS) {
                suspendCoroutine { cont ->
                    val modeKey = KeyTools.createKey(CameraKey.KeyCameraMode, cameraIndex)
                    keyManager.setValue(modeKey, CameraMode.PHOTO_NORMAL, object : CommonCallbacks.CompletionCallback {
                        override fun onSuccess() {
                            logI("Camera mode switched to PHOTO_NORMAL")
                            cont.resume(true)
                        }

                        override fun onFailure(error: IDJIError) {
                            logW("Failed to set photo mode: ${error.description()} (${error.errorCode()})")
                            cont.resume(false)
                        }
                    })
                }
            } ?: false
            if (!modeSetSuccess) {
                logW("Proceeding with photo shoot despite mode change warning or timeout")
            }
        }

        applyPhotoSize(keyManager, params.size)

        return suspendCoroutine { cont ->
            try {
                val shootKey = KeyTools.createKey(CameraKey.KeyStartShootPhoto, cameraIndex)
                keyManager.performAction(shootKey, EmptyMsg(), object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        logI("Shoot photo triggered successfully")
                        cont.resume(CameraResult.PhotoSuccess(fileIndex = null))
                    }

                    override fun onFailure(error: IDJIError) {
                        val errMsg = "Take photo failed: ${error.description()} (code: ${error.errorCode()})"
                        logE(errMsg)
                        cont.resume(CameraResult.Failure(errMsg))
                    }
                })
            } catch (t: Throwable) {
                logE("Shoot photo exception: ${t.message}")
                cont.resume(CameraResult.Failure("Take photo exception: ${t.message}", t))
            }
        }
    }

    override suspend fun startRecord(): CameraResult = withContext(Dispatchers.IO) {
        logI("Initiating start video recording...")
        val keyManager = KeyManager.getInstance()

        // 1. Ensure Camera Mode is in VIDEO_NORMAL
        val currentMode = keyManager.getValue(KeyTools.createKey(CameraKey.KeyCameraMode, cameraIndex))
        if (currentMode != CameraMode.VIDEO_NORMAL) {
            logI("Switching camera mode to VIDEO_NORMAL (current: $currentMode)...")
            val modeSetSuccess = suspendCoroutine { cont ->
                val modeKey = KeyTools.createKey(CameraKey.KeyCameraMode, cameraIndex)
                keyManager.setValue(modeKey, CameraMode.VIDEO_NORMAL, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        logI("Camera mode switched to VIDEO_NORMAL")
                        cont.resume(true)
                    }

                    override fun onFailure(error: IDJIError) {
                        logW("Failed to set video mode: ${error.description()} (${error.errorCode()})")
                        cont.resume(false)
                    }
                })
            }
            if (!modeSetSuccess) {
                logW("Proceeding with record start despite mode change warning")
            }
        }

        // 2. Trigger Start Record
        suspendCoroutine { cont ->
            try {
                val startKey = KeyTools.createKey(CameraKey.KeyStartRecord, cameraIndex)
                keyManager.performAction(startKey, EmptyMsg(), object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        logI("Start record succeeded")
                        _isRecording.value = true
                        cont.resume(CameraResult.RecordStarted)
                    }

                    override fun onFailure(error: IDJIError) {
                        val errMsg = "Start record failed: ${error.description()} (code: ${error.errorCode()})"
                        logE(errMsg)
                        cont.resume(CameraResult.Failure(errMsg))
                    }
                })
            } catch (t: Throwable) {
                logE("Start record exception: ${t.message}")
                cont.resume(CameraResult.Failure("Start record exception: ${t.message}", t))
            }
        }
    }

    private suspend fun applyPhotoSize(
        keyManager: KeyManager,
        size: PhotoSize,
    ) {
        val djiSize = when (size) {
            PhotoSize.SIZE_48MP -> DjiPhotoSize.SIZE_LARGE
            PhotoSize.SIZE_12MP, PhotoSize.DEFAULT -> DjiPhotoSize.SIZE_MEDIUM
        }
        withTimeoutOrNull(CAMERA_KEY_TIMEOUT_MS) {
            suspendCoroutine { cont ->
                val sizeKey = KeyTools.createKey(CameraKey.KeyPhotoSize, cameraIndex)
                keyManager.setValue(sizeKey, djiSize, object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        logI("Photo size set to $djiSize")
                        cont.resume(Unit)
                    }

                    override fun onFailure(error: IDJIError) {
                        logW("Photo size set failed: ${error.description()}")
                        cont.resume(Unit)
                    }
                })
            }
        } ?: logW("Photo size set timed out; continuing with shoot")
    }

    override suspend fun stopRecord(): CameraResult = withContext(Dispatchers.IO) {
        logI("Initiating stop video recording...")
        val keyManager = KeyManager.getInstance()

        suspendCoroutine { cont ->
            try {
                val stopKey = KeyTools.createKey(CameraKey.KeyStopRecord, cameraIndex)
                keyManager.performAction(stopKey, EmptyMsg(), object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                    override fun onSuccess(result: EmptyMsg?) {
                        logI("Stop record succeeded")
                        _isRecording.value = false
                        cont.resume(CameraResult.RecordStopped)
                    }

                    override fun onFailure(error: IDJIError) {
                        val errMsg = "Stop record failed: ${error.description()} (code: ${error.errorCode()})"
                        logE(errMsg)
                        cont.resume(CameraResult.Failure(errMsg))
                    }
                })
            } catch (t: Throwable) {
                logE("Stop record exception: ${t.message}")
                cont.resume(CameraResult.Failure("Stop record exception: ${t.message}", t))
            }
        }
    }
}
