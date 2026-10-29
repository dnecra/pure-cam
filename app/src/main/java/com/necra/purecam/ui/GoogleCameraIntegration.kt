package com.necra.purecam.ui

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.necra.purecam.presentation.CameraViewModel

/**
 * Example integration of GoogleCameraUI with the existing Pure Cam app.
 * This shows how to wire up the new UI to the existing ViewModel and state.
 * 
 * To use this, replace the CameraScreen call in PureCamApp.kt with this composable.
 */
@Composable
fun GoogleCameraScreen(
    viewModel: CameraViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenPhoto: (Uri) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    GoogleCameraUI(
        onFlashClick = { viewModel.toggleFlash() },
        onMotionClick = { /* Motion tracking feature - not yet implemented */ },
        onFlipClick = { viewModel.flipCamera() },
        onShutterClick = { viewModel.onShutter() },
        onThumbnailClick = {
            state.lastPhotoUri?.let { uri -> onOpenPhoto(uri) }
        },
        onSettingsClick = { /* Open settings bottom sheet */ },
        onTuneClick = { /* Open manual controls - viewModel has this */ },
        onModeSelect = { mode ->
            when (mode) {
                "Photo" -> viewModel.setCaptureMode(com.necra.purecam.presentation.CaptureMode.PHOTO)
                "Night" -> {
                    // Night mode would be a special exposure mode
                    // This could trigger long exposure or multi-frame capture
                }
                else -> {}
            }
        },
        onZoomSelect = { zoom ->
            viewModel.setZoom(zoom)
        }
    ) {
        // Insert the existing camera preview from CameraScreen.kt
        CameraPreview(state = state, viewModel = viewModel)
        
        // Add overlays if needed
        GridOverlay(visible = state.grid)
        CountdownOverlay(countdown = state.countdown)
        ReadoutPill(readout = state.readout)
    }
}

/**
 * Alternative: Hybrid approach that keeps existing controls but uses
 * the rounded viewfinder card style from Google Camera UI
 */
@Composable
fun HybridCameraScreen(
    state: com.necra.purecam.presentation.CameraUiState,
    viewModel: CameraViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenPhoto: (Uri) -> Unit
) {
    // Use the existing CameraScreen composable but apply rounded card styling
    // This is a less invasive change that just modernizes the look
    
    GoogleCameraUI(
        onFlashClick = { viewModel.toggleFlash() },
        onMotionClick = {},
        onFlipClick = { viewModel.flipCamera() },
        onShutterClick = { viewModel.onShutter() },
        onThumbnailClick = { state.lastPhotoUri?.let(onOpenPhoto) },
        onSettingsClick = {},
        onTuneClick = {},
        onModeSelect = {},
        onZoomSelect = { viewModel.setZoom(it) }
    ) {
        // Keep all existing functionality inside the rounded card
        CameraScreen(
            state = state,
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onOpenPhoto = onOpenPhoto
        )
    }
}
