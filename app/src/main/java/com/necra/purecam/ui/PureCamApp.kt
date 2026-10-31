package com.necra.purecam.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.necra.purecam.R
import com.necra.purecam.presentation.CameraSignal
import com.necra.purecam.presentation.CameraViewModel

/**
 * Root of the UI tree. Shows the permission gate, the camera screen and the camera error
 * dialog. Keep this composable free of camera details: it only routes state.
 */
@Composable
fun PureCamApp(viewModel: CameraViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setPermission(granted)
        if (!granted) viewModel.onPermissionDenied()
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onAudioPermissionResult(granted)
    }

    var autoRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!state.hasPermission && !autoRequested) {
            autoRequested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(audioPermissionLauncher) {
        viewModel.signals.collect { signal ->
            when (signal) {
                // Status messages are intentionally not surfaced as toasts/snackbars.
                is CameraSignal.Message -> Unit
                CameraSignal.RequestAudioPermission ->
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                CameraSignal.CaptureFlash -> Unit
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize().preferredFrameRate(FrameRateCategory.High),
        color = Color.Black
    ) {
        if (state.hasPermission) {
            GoogleCameraScreen(
                state = state,
                viewModel = viewModel,
                onOpenPhoto = { uri -> openMedia(context, uri) }
            )
        } else {
            PermissionScreen(
                permanentlyDenied = state.permissionDenied &&
                    activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false,
                onRequest = {
                    val needsSettings = state.permissionDenied &&
                        activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
                    if (needsSettings) {
                        openAppSettings(context)
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            )
        }
    }

    state.cameraError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            title = { Text(stringResource(R.string.camera_error)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = hapticAction(viewModel::retryCamera)) {
                    Text(stringResource(R.string.retry))
                }
            },
            dismissButton = {
                TextButton(onClick = hapticAction(viewModel::dismissError)) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
private fun PermissionScreen(permanentlyDenied: Boolean, onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_camera),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(34.dp)
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.camera_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.camera_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = hapticAction(onRequest), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (permanentlyDenied) R.string.open_settings else R.string.grant_permission))
        }
    }
}
