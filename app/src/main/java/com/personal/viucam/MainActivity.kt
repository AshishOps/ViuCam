package com.personal.viucam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.personal.viucam.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraManager: CameraManager? = null
    private var mjpegServer: MjpegServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isScreenDimmed = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            setupCameraAndServer()
        } else {
            Toast.makeText(this, "Camera permission is required to stream video", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep screen awake while streaming webcam
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupUI()
        checkPermissionsAndStart()
    }

    private fun setupUI() {
        val ipAddress = NetworkUtils.getLocalIpAddress()
        val videoUrl = "http://$ipAddress:8080/video"
        binding.tvWifiUrl.text = videoUrl

        // Copy Wi-Fi stream URL
        binding.btnCopyWifiUrl.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("ViuCam Stream URL", videoUrl)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Copied: $videoUrl", Toast.LENGTH_SHORT).show()
        }

        // USB ADB setup dialog
        binding.btnUsbGuide.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("USB Connection (OBS Studio)")
                .setMessage(
                    "Connect via USB with USB Debugging enabled, then run on your PC:\n\n" +
                    "adb forward tcp:8080 tcp:8080\n\n" +
                    "In OBS Browser or Media Source, use:\n" +
                    "http://localhost:8080/video"
                )
                .setPositiveButton("Copy USB URL") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("ViuCam USB URL", "http://localhost:8080/video"))
                    Toast.makeText(this, "Copied http://localhost:8080/video", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
        }

        // Switch camera (Front <-> Back)
        binding.btnSwitchCamera.setOnClickListener {
            val cameraName = cameraManager?.switchCamera() ?: ""
            Toast.makeText(this, cameraName, Toast.LENGTH_SHORT).show()
        }

        // Toggle Flashlight / Torch
        binding.btnToggleTorch.setOnClickListener {
            val isTorchOn = cameraManager?.toggleTorch() ?: false
            binding.btnToggleTorch.text = if (isTorchOn) "Flash ON" else "Flash"
            binding.btnToggleTorch.setBackgroundColor(
                ContextCompat.getColor(this, if (isTorchOn) R.color.accent_cobalt else R.color.surface_control)
            )
        }

        // Dim screen power saver mode
        binding.btnDimScreen.setOnClickListener {
            toggleDimScreen()
        }

        binding.dimOverlay.setOnClickListener {
            if (isScreenDimmed) {
                toggleDimScreen()
            }
        }

        // Resolution selectors (720p default for lowest Wi-Fi latency; 4K UHD supported)
        binding.toggleResolution.check(R.id.btnRes720p)
        binding.btnRes4k.setOnClickListener {
            cameraManager?.setResolution(3840, 2160)
            Toast.makeText(this, "Requesting 4K UHD (3840x2160)", Toast.LENGTH_SHORT).show()
        }
        binding.btnRes1080p.setOnClickListener {
            cameraManager?.setResolution(1920, 1080)
            Toast.makeText(this, "Resolution: 1080p", Toast.LENGTH_SHORT).show()
        }
        binding.btnRes720p.setOnClickListener {
            cameraManager?.setResolution(1280, 720)
            Toast.makeText(this, "Resolution: 720p", Toast.LENGTH_SHORT).show()
        }
        binding.btnRes480p.setOnClickListener {
            cameraManager?.setResolution(854, 480)
            Toast.makeText(this, "Resolution: 480p", Toast.LENGTH_SHORT).show()
        }

        // Wi-Fi compression presets
        binding.toggleQuality.check(R.id.btnQualBalanced)
        binding.btnQualFast.setOnClickListener {
            cameraManager?.compressionQuality = 50
            Toast.makeText(this, "Quality: Fast (Lowest Latency)", Toast.LENGTH_SHORT).show()
        }
        binding.btnQualBalanced.setOnClickListener {
            cameraManager?.compressionQuality = 65
            Toast.makeText(this, "Quality: Balanced", Toast.LENGTH_SHORT).show()
        }
        binding.btnQualSharp.setOnClickListener {
            cameraManager?.compressionQuality = 85
            Toast.makeText(this, "Quality: Sharp (High Bitrate)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleDimScreen() {
        isScreenDimmed = !isScreenDimmed
        val layoutParams = window.attributes
        if (isScreenDimmed) {
            layoutParams.screenBrightness = 0.01f
            window.attributes = layoutParams
            binding.dimOverlay.visibility = View.VISIBLE
            binding.tvDimNotice.visibility = View.VISIBLE
            binding.btnDimScreen.text = "Wake"
        } else {
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = layoutParams
            binding.dimOverlay.visibility = View.GONE
            binding.tvDimNotice.visibility = View.GONE
            binding.btnDimScreen.text = "Dim"
        }
    }

    private fun checkPermissionsAndStart() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                setupCameraAndServer()
            }
            else -> {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun setupCameraAndServer() {
        val serviceIntent = Intent(this, WebcamService::class.java).apply {
            action = WebcamService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(lockMode, "ViuCam:LowLatencyWifi").apply {
            setReferenceCounted(false)
            acquire()
        }

        mjpegServer = MjpegServer(
            port = 8080,
            onTorchToggle = {
                cameraManager?.toggleTorch() ?: false
            },
            onCameraSwitch = {
                cameraManager?.switchCamera() ?: ""
            }
        ).also { it.start() }

        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.viewFinder,
            hasActiveClients = { (mjpegServer?.getConnectedClientCount() ?: 0) > 0 },
            onResolutionChanged = { size ->
                runOnUiThread {
                    binding.tvStatusBadge.text = "${size.width}x${size.height}"
                }
            },
            onFrameEncoded = { jpegBytes ->
                mjpegServer?.broadcastFrame(jpegBytes)
            }
        ).also {
            it.initialize()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiLock?.let { if (it.isHeld) it.release() }
        cameraManager?.shutdown()
        mjpegServer?.stop()

        val serviceIntent = Intent(this, WebcamService::class.java).apply {
            action = WebcamService.ACTION_STOP
        }
        stopService(serviceIntent)
    }
}
