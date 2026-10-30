package com.necra.purecam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.necra.purecam.presentation.CameraViewModel
import com.necra.purecam.ui.CameraScreenView
import com.necra.purecam.ui.PermissionHost
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), PermissionHost {
    private lateinit var cameraViewModel: CameraViewModel
    private lateinit var orientationHelper: OrientationHelper
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraViewModel.setPermission(granted)
        if (!granted) cameraViewModel.onPermissionDenied()
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraViewModel.onAudioPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraViewModel = ViewModelProvider(this)[CameraViewModel::class.java]
        orientationHelper = OrientationHelper(this) { degrees -> cameraViewModel.setDeviceRotation(degrees) }
        cameraViewModel.setPermission(hasCameraPermission())
        setContentView(CameraScreenView(this, cameraViewModel, lifecycleScope) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { cameraViewModel.uiState.collect { state ->
                if (!state.hasPermission && !state.permissionDenied && !isFinishing) requestCameraPermission()
            } }
        }
    }

    override fun onResume() { super.onResume(); orientationHelper.enable(); cameraViewModel.setPermission(hasCameraPermission()); cameraViewModel.onResume() }
    override fun onPause() { orientationHelper.disable(); cameraViewModel.onPause(); super.onPause() }
    override fun requestCameraPermission() { cameraPermission.launch(Manifest.permission.CAMERA) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) cameraViewModel.onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    private fun hasCameraPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
