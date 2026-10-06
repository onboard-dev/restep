package com.rocketglasses.soberyobratno

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean

class PhotoCapture(private val context: Context) {
    private val thread = HandlerThread("MemoryCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null
    private val busy = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun take(onResult: (Result<ByteArray>) -> Unit) {
        if (!busy.compareAndSet(false, true)) {
            onResult(Result.failure(IllegalStateException("Camera is busy")))
            return
        }
        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = manager.cameraIdList.first { candidate ->
                manager.getCameraCharacteristics(candidate).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            val characteristics = manager.getCameraCharacteristics(id)
            val outputs = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: error("Camera outputs unavailable")
            val sizes = outputs.getOutputSizes(ImageFormat.JPEG) ?: error("JPEG unavailable")
            // Use the native 1600x1200 JPEG to retain detail without 4K file sizes.
            // 実機グラスには 1600x1200 がある。エミュレータなど無い場合は、最大の 4:3、それも無ければ最大の出力を使う。
            val size = sizes.firstOrNull { it.width == 1600 && it.height == 1200 }
                ?: sizes.filter { it.width * 3 == it.height * 4 }.maxByOrNull { it.width * it.height }
                ?: sizes.maxByOrNull { it.width * it.height }
                ?: error("camera output unavailable")
            Log.d("MemoryCamera", "JPEG size ${size.width}x${size.height}")
            val orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            previewTexture = SurfaceTexture(0).apply { setDefaultBufferSize(640, 480) }
            previewSurface = Surface(previewTexture)
            val completed = AtomicBoolean(false)
            val delivered = AtomicBoolean(false)
            var pendingResult: Result<ByteArray>? = null
            fun deliver() {
                val result = pendingResult ?: return
                if (delivered.compareAndSet(false, true)) {
                    busy.set(false)
                    Log.i("MemoryCamera", if (result.isSuccess) "Photo ready: ${result.getOrNull()?.size} bytes"
                        else "Photo failed: ${result.exceptionOrNull()?.message}")
                    onResult(result)
                }
            }
            fun finish(result: Result<ByteArray>) {
                if (completed.compareAndSet(false, true)) {
                    pendingResult = result
                    val waitForClose = device != null
                    closeCurrent()
                    if (!waitForClose) deliver()
                    else handler.postDelayed({ deliver() }, 3000)
                }
            }
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ source ->
                    try {
                        val bytes = source.acquireLatestImage()?.use { image ->
                            val buffer = image.planes[0].buffer
                            ByteArray(buffer.remaining()).also { buffer.get(it) }
                        }
                        if (bytes != null) finish(Result.success(bytes))
                    } catch (e: Exception) { finish(Result.failure(e)) }
                }, handler)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (completed.get()) { camera.close(); return }
                    device = camera
                    try {
                        camera.createCaptureSession(listOf(reader!!.surface, previewSurface!!),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(captureSession: CameraCaptureSession) {
                                    if (completed.get()) { captureSession.close(); return }
                                    session = captureSession
                                    try {
                                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                            addTarget(reader!!.surface)
                                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                            set(CaptureRequest.JPEG_ORIENTATION, orientation)
                                        }.build()
                                        val preview = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(previewSurface!!)
                                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                        }.build()
                                        captureSession.setRepeatingRequest(preview, null, handler)
                                        handler.postDelayed({
                                            if (!completed.get()) {
                                                Log.d("MemoryCamera", "Capturing after AE preview warmup")
                                                try { captureSession.capture(request, null, handler) }
                                                catch (e: Exception) { finish(Result.failure(e)) }
                                            }
                                        }, 1800)
                                    } catch (e: Exception) { finish(Result.failure(e)) }
                                }
                                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                                    finish(Result.failure(IllegalStateException("Camera setup failed")))
                                }
                            }, handler)
                    } catch (e: Exception) { finish(Result.failure(e)) }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    finish(Result.failure(IllegalStateException("Camera disconnected")))
                }
                override fun onClosed(camera: CameraDevice) {
                    // Load speech only after the camera has released its native buffers.
                    deliver()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    finish(Result.failure(CameraAccessException(error)))
                }
            }, handler)
            handler.postDelayed({ finish(Result.failure(IllegalStateException("Camera timed out"))) }, 15000)
        } catch (e: Exception) {
            closeCurrent()
            busy.set(false)
            onResult(Result.failure(e))
        }
    }

    private fun closeCurrent() {
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
        previewSurface?.release(); previewSurface = null
        previewTexture?.release(); previewTexture = null
    }

    fun close() { closeCurrent(); thread.quitSafely() }
}
