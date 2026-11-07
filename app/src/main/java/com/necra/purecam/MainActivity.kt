package com.necra.purecam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.HapticFeedbackConstants
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

    /**
     * Volume keys act as a hardware shutter, like the stock camera.
     *
     * Intercepting in [dispatchKeyEvent] (rather than `onKeyDown`) means a palm or finger
     * resting anywhere on the screen cannot swallow the key before it reaches the camera.
     * The volume UI is suppressed for both the press and the release.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (cameraViewModel.onShutter()) {
                    window.decorView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
}
