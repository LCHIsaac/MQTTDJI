package com.dji.dronecontrol.adapter.dji

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.ImageReader
import android.util.Log
import com.dji.dronecontrol.core.log.AppLogger
import com.dji.dronecontrol.core.photo.PhotoErrorCodes
import com.dji.dronecontrol.core.port.SnapshotCaptureResult
import com.dji.dronecontrol.core.port.SnapshotPort
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.camera.CameraStreamManager
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Grabs one frame from [ICameraStreamManager] (FPV decode path) and encodes JPEG on the handset.
 */
class DjiCameraSnapshotAdapter(
    private val streamManager: ICameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager,
) : SnapshotPort {

    private val tag = "DjiSnapshot"
    private val decodeSession = SnapshotFpvDecodeSession(streamManager)

    override suspend fun captureVideoFrameJpeg(quality: Int, timeoutMs: Long): SnapshotCaptureResult =
        withContext(Dispatchers.IO) {
            if (!DjiMsdkSession.isProductConnected) {
                return@withContext SnapshotCaptureResult.Failure(
                    PhotoErrorCodes.NO_VIDEO_FRAME,
                    "Aircraft not connected — link the drone before snapshot",
                )
            }
            val clampedQuality = quality.coerceIn(1, 100)
            val cameraIndex = resolveCameraIndex()
            decodeSession.ensureDecoding(cameraIndex)

            val formats = listOf(
                ICameraStreamManager.FrameFormat.NV21,
                ICameraStreamManager.FrameFormat.RGBA_8888,
                ICameraStreamManager.FrameFormat.YUV420_888,
            )
            val perFormatMs = (timeoutMs / formats.size).coerceAtLeast(2_000L)
            var lastError: String? = null

            for (format in formats) {
                val frame = withTimeoutOrNull(perFormatMs) {
                    awaitVideoFrame(cameraIndex, format)
                }
                when {
                    frame == null -> {
                        lastError = "No $format frame within ${perFormatMs}ms (camera=$cameraIndex)"
                        logW(lastError)
                    }
                    else -> {
                        return@withContext encodeFrame(frame, clampedQuality)
                    }
                }
            }

            SnapshotCaptureResult.Failure(
                PhotoErrorCodes.SNAPSHOT_TIMEOUT,
                buildString {
                    append("No FPV video frame within ${timeoutMs}ms")
                    append(" (camera=$cameraIndex). ")
                    append("Ensure the aircraft video link is up — DJI FPV/preview on the phone is enough; ")
                    append("Agora/RTMP stream.start is not required.")
                    lastError?.let { append(" Last attempt: $it.") }
                },
            )
        }

    private suspend fun awaitVideoFrame(
        cameraIndex: ComponentIndexType,
        format: ICameraStreamManager.FrameFormat,
    ): FramePayload? = suspendCancellableCoroutine { cont ->
        val listener = object : ICameraStreamManager.CameraFrameListener {
            override fun onFrame(
                data: ByteArray,
                offset: Int,
                length: Int,
                width: Int,
                height: Int,
                frameFormat: ICameraStreamManager.FrameFormat,
            ) {
                if (!cont.isActive || width <= 0 || height <= 0) return
                val bytes = extractFrameBytes(data, offset, length) ?: return
                streamManager.removeFrameListener(this)
                cont.resume(
                    FramePayload(
                        bytes = bytes,
                        width = width,
                        height = height,
                        format = frameFormat,
                    ),
                )
            }
        }

        cont.invokeOnCancellation {
            streamManager.removeFrameListener(listener)
        }

        try {
            streamManager.setKeepAliveDecoding(true)
            streamManager.addFrameListener(cameraIndex, format, listener)
        } catch (e: Exception) {
            streamManager.removeFrameListener(listener)
            logW("addFrameListener failed: ${e.message}")
            if (cont.isActive) {
                cont.resume(null)
            }
        }
    }

    private fun encodeFrame(frame: FramePayload, quality: Int): SnapshotCaptureResult {
        return when (frame.format) {
            ICameraStreamManager.FrameFormat.NV21,
            ICameraStreamManager.FrameFormat.YUV420_888,
            -> encodeNv21ToJpeg(frame.bytes, frame.width, frame.height, quality)
            else -> encodeRgbaToJpeg(frame, quality)
        }
    }

    private fun encodeNv21ToJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
        quality: Int,
    ): SnapshotCaptureResult {
        return try {
            val yuv = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            ByteArrayOutputStream().use { out ->
                if (!yuv.compressToJpeg(Rect(0, 0, width, height), quality, out)) {
                    SnapshotCaptureResult.Failure(PhotoErrorCodes.NO_VIDEO_FRAME, "NV21 JPEG compression failed")
                } else {
                    SnapshotCaptureResult.Success(out.toByteArray(), width, height)
                }
            }
        } catch (e: Exception) {
            SnapshotCaptureResult.Failure(
                PhotoErrorCodes.NO_VIDEO_FRAME,
                "Failed to encode NV21 frame: ${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private fun encodeRgbaToJpeg(frame: FramePayload, quality: Int): SnapshotCaptureResult {
        val bitmap = try {
            rgbaToBitmap(frame.bytes, frame.width, frame.height)
        } catch (e: Exception) {
            return SnapshotCaptureResult.Failure(
                PhotoErrorCodes.NO_VIDEO_FRAME,
                "Failed to decode RGBA frame: ${e.message ?: e.javaClass.simpleName}",
            )
        }
        return try {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    SnapshotCaptureResult.Failure(PhotoErrorCodes.NO_VIDEO_FRAME, "JPEG compression failed")
                } else {
                    SnapshotCaptureResult.Success(out.toByteArray(), frame.width, frame.height)
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun rgbaToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val strideBytes = width * 4
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (data.size >= strideBytes * height) {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data))
        }
        return bitmap
    }

    private fun resolveCameraIndex(): ComponentIndexType {
        return try {
            val manager = streamManager as? CameraStreamManager ?: CameraStreamManager.getInstance()
            val sources = manager.getAvailableCameraSourceList() ?: emptyList()
            sources.firstOrNull { it.position == ComponentIndexType.LEFT_OR_MAIN }?.position
                ?: sources.firstOrNull()?.position
                ?: ComponentIndexType.LEFT_OR_MAIN
        } catch (e: Exception) {
            logW("resolveCameraIndex failed: ${e.message}")
            ComponentIndexType.LEFT_OR_MAIN
        }
    }

    private fun logW(msg: String) {
        Log.w(tag, msg)
        AppLogger.w(tag, msg)
    }

    private data class FramePayload(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val format: ICameraStreamManager.FrameFormat,
    )
}

/**
 * Holds a small off-screen [ImageReader] surface so the SDK decoder keeps producing frames
 * without Agora/RTMP or an on-screen FPV view.
 */
internal class SnapshotFpvDecodeSession(
    private val streamManager: ICameraStreamManager,
) {
    private val lock = Any()
    private var imageReader: ImageReader? = null
    private var attachedIndex: ComponentIndexType? = null

    fun ensureDecoding(cameraIndex: ComponentIndexType) {
        synchronized(lock) {
            streamManager.setKeepAliveDecoding(true)
            val reader = imageReader ?: ImageReader.newInstance(
                PREVIEW_WIDTH,
                PREVIEW_HEIGHT,
                android.graphics.PixelFormat.RGBA_8888,
                2,
            ).also { imageReader = it }

            if (attachedIndex != cameraIndex) {
                reader.surface?.let { surface ->
                    streamManager.putCameraStreamSurface(
                        cameraIndex,
                        surface,
                        PREVIEW_WIDTH,
                        PREVIEW_HEIGHT,
                        ICameraStreamManager.ScaleType.CENTER_INSIDE,
                    )
                    attachedIndex = cameraIndex
                }
            }
        }
    }

    companion object {
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
    }
}
