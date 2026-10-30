package com.necra.purecam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.necra.purecam.presentation.CameraViewModel
import com.necra.purecam.ui.PureCamApp
import com.necra.purecam.ui.theme.PureCamTheme

/**
 * Thin host: edge-to-edge window, orientation sensor and Compose content.
 * All camera and UI state lives in [CameraViewModel].
 */
class MainActivity : ComponentActivity() {

    private lateinit var cameraViewModel: CameraViewModel
    private lateinit var orientationHelper: OrientationHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraViewModel = ViewModelProvider(this)[CameraViewModel::class.java]
        orientationHelper = OrientationHelper(this) { degrees ->
            cameraViewModel.setDeviceRotation(degrees)
        }

        cameraViewModel.setPermission(hasCameraPermission())

        setContent {
            PureCamTheme {
                PureCamApp(cameraViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientationHelper.enable()
        cameraViewModel.setPermission(hasCameraPermission())
        cameraViewModel.onResume()
    }

    override fun onPause() {
        orientationHelper.disable()
        cameraViewModel.onPause()
        super.onPause()
    }

    /** Volume keys act as a hardware shutter, like the stock camera. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) cameraViewModel.onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
