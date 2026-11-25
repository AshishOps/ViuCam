package com.personal.viucam

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages CameraX hardware lifecycle, preview surface, resolution selection,
 * torch/flashlight control, lens switching, and zero-allocation YUV->JPEG encoding.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val hasActiveClients: () -> Boolean,
    private val onResolutionChanged: ((Size) -> Unit)? = null,
    private val onFrameEncoded: (ByteArray) -> Unit
) {
    private val TAG = "CameraManager"
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var isTorchOn = false

    // Default to 720p HD for ultra-low latency over Wi-Fi (user can switch to 1080p anytime)
    var targetResolution: Size = Size(1280, 720)
        private set

    // Balanced JPEG quality (65%) cuts Wi-Fi bandwidth by ~60% with minimal visual loss
    var compressionQuality: Int = 65

    // Reusable buffers to eliminate per-frame GC pauses (sized for up to 4K UHD)
    private var nv21Buffer = ByteArray(0)
    private var rotatedNv21Buffer = ByteArray(0)
    private val jpegOutputStream = ByteArrayOutputStream(524288)
    private var lastIdleEncodeMs = 0L

    fun initialize(onReady: () -> Unit = {}) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
                onReady()
            } catch (e: Exception) {
                Log.e(TAG, "Use case binding failed: ${e.message}", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        // Keep local phone preview at 720p so hardware stream budget allows full 4K on ImageAnalysis
        val previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1280, 720),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val streamResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(
                    targetResolution,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        // 1. Preview Use Case (Local 16:9 Viewfinder on Phone)
        preview = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // 2. ImageAnalysis Use Case (Direct 16:9 YUV_420_888 up to 4K UHD for OBS)
        imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(streamResolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor) { imageProxy ->
                    processImageProxy(imageProxy)
                }
            }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            imageAnalysis?.resolutionInfo?.resolution?.let { actualSize ->
                onResolutionChanged?.invoke(actualSize)
            }
            if (isTorchOn) {
                camera?.cameraControl?.enableTorch(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Binding error: ${e.message}", e)
        }
    }

    private fun processImageProxy(imageProxy: ImageProxy) {
        try {
            // Skip heavy JPEG encoding when no clients are watching (keep 1fps for /snapshot)
            val now = System.currentTimeMillis()
            if (!hasActiveClients() && (now - lastIdleEncodeMs) < 1000L) {
                return
            }
            lastIdleEncodeMs = now

            val width = imageProxy.width
            val height = imageProxy.height
            val requiredSize = width * height * 3 / 2

            if (nv21Buffer.size != requiredSize) {
                nv21Buffer = ByteArray(requiredSize)
            }

            yuv420ToNv21(imageProxy, nv21Buffer)

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val yuvData: ByteArray
            val outWidth: Int
            val outHeight: Int

            if (rotationDegrees == 0) {
                yuvData = nv21Buffer
                outWidth = width
                outHeight = height
            } else {
                if (rotatedNv21Buffer.size != requiredSize) {
                    rotatedNv21Buffer = ByteArray(requiredSize)
                }
                rotateNv21(nv21Buffer, rotatedNv21Buffer, width, height, rotationDegrees)
                yuvData = rotatedNv21Buffer
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    outWidth = height
                    outHeight = width
                } else {
                    outWidth = width
                    outHeight = height
                }
            }

            jpegOutputStream.reset()
            val yuvImage = YuvImage(yuvData, ImageFormat.NV21, outWidth, outHeight, null)
            yuvImage.compressToJpeg(Rect(0, 0, outWidth, outHeight), compressionQuality, jpegOutputStream)

            onFrameEncoded(jpegOutputStream.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Converts CameraX YUV_420_888 planes into contiguous NV21 bytes without allocating.
     */
    private fun yuv420ToNv21(image: ImageProxy, out: ByteArray) {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        var pos = 0

        if (yRowStride == width) {
            yBuffer.get(out, 0, width * height)
            pos = width * height
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(out, pos, width)
                pos += width
            }
        }

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        if (vPixelStride == 2 && uPixelStride == 2 && vRowStride == width && uRowStride == width) {
            // Fast path: standard semi-planar NV21 layout
            val vRemaining = minOf(vBuffer.remaining(), width * chromaHeight)
            vBuffer.get(out, pos, vRemaining)
        } else {
            for (row in 0 until chromaHeight) {
                val vRowOffset = row * vRowStride
                val uRowOffset = row * uRowStride
                for (col in 0 until chromaWidth) {
                    out[pos++] = vBuffer.get(vRowOffset + col * vPixelStride)
                    out[pos++] = uBuffer.get(uRowOffset + col * uPixelStride)
                }
            }
        }
    }

    /**
     * Rotates NV21 image data in-place into [dst] without Bitmap or RGBA overhead.
     */
    private fun rotateNv21(src: ByteArray, dst: ByteArray, width: Int, height: Int, degrees: Int) {
        val frameSize = width * height
        when (degrees) {
            90 -> {
                var i = 0
                for (x in 0 until width) {
                    for (y in height - 1 downTo 0) {
                        dst[i++] = src[y * width + x]
                    }
                }
                i = frameSize
                for (x in 0 until width step 2) {
                    for (y in height / 2 - 1 downTo 0) {
                        val srcIdx = frameSize + y * width + x
                        dst[i++] = src[srcIdx]
                        dst[i++] = src[srcIdx + 1]
                    }
                }
            }
            270 -> {
                var i = 0
                for (x in width - 1 downTo 0) {
                    for (y in 0 until height) {
                        dst[i++] = src[y * width + x]
                    }
                }
                i = frameSize
                for (x in width - 2 downTo 0 step 2) {
                    for (y in 0 until height / 2) {
                        val srcIdx = frameSize + y * width + x
                        dst[i++] = src[srcIdx]
                        dst[i++] = src[srcIdx + 1]
                    }
                }
            }
            180 -> {
                for (i in 0 until frameSize) {
                    dst[i] = src[frameSize - 1 - i]
                }
                var i = frameSize
                var srcIdx = src.size - 2
                while (srcIdx >= frameSize) {
                    dst[i++] = src[srcIdx]
                    dst[i++] = src[srcIdx + 1]
                    srcIdx -= 2
                }
            }
            else -> System.arraycopy(src, 0, dst, 0, src.size)
        }
    }

    /**
     * Toggles the hardware flashlight/torch.
     */
    fun toggleTorch(): Boolean {
        camera?.let {
            if (it.cameraInfo.hasFlashUnit()) {
                isTorchOn = !isTorchOn
                it.cameraControl.enableTorch(isTorchOn)
                return isTorchOn
            }
        }
        return false
    }

    /**
     * Switches between front and back camera.
     */
    fun switchCamera(): String {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        isTorchOn = false
        bindCameraUseCases()
        return if (lensFacing == CameraSelector.LENS_FACING_BACK) "Back Camera" else "Front Camera"
    }

    /**
     * Updates target streaming resolution (1080p, 720p, 480p).
     */
    fun setResolution(width: Int, height: Int) {
        targetResolution = Size(width, height)
        bindCameraUseCases()
    }

    fun shutdown() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }
}
