package com.necra.purecam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.necra.purecam.R

// Google Camera UI colors
private val CamBg = Color(0xFF000000)
private val CamScrim = Color(0x99202124)
private val CamBarBtn = Color(0xFF2B2B2D)
private val CamAccent = Color(0xFFA8C7FA)
private val CamOnAccent = Color(0xFF041E49)
private val CamWhite = Color(0xFFFFFFFF)

/**
 * Google Camera (Pixel) inspired UI with rounded viewfinder card,
 * segmented controls, and modern Material design.
 */
@Composable
fun GoogleCameraUI(
    modifier: Modifier = Modifier,
    onFlashClick: () -> Unit = {},
    onMotionClick: () -> Unit = {},
    onFlipClick: () -> Unit = {},
    onShutterClick: () -> Unit = {},
    onThumbnailClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onTuneClick: () -> Unit = {},
    onModeSelect: (String) -> Unit = {},
    onZoomSelect: (Float) -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CamBg)
    ) {
        // Viewfinder card with rounded corners
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 92.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(28.dp),
                color = CamBg
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Camera preview content
                    content()

                    // Top controls row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .align(Alignment.TopCenter),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Flash button
                        CircleButton(
                            icon = R.drawable.ic_flash_off,
                            contentDescription = "Flash",
                            onClick = onFlashClick
                        )

                        // Low light pill (conditionally shown)
                        LowLightPill(
                            onTryNightClick = { onModeSelect("Night") }
                        )

                        // Motion button
                        CircleButton(
                            icon = R.drawable.ic_motion_off,
                            contentDescription = "Motion",
                            onClick = onMotionClick
                        )
                    }

                    // Zoom pill
                    ZoomPill(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 136.dp),
                        onZoomSelect = onZoomSelect
                    )

                    // Mode row
                    ModeRow(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 80.dp),
                        onModeSelect = onModeSelect
                    )

                    // Shutter row
                    ShutterRow(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                        onShutterClick = onShutterClick,
                        onFlipClick = onFlipClick,
                        onThumbnailClick = onThumbnailClick
                    )
                }
            }
        }

        // Bottom bar (outside viewfinder card)
        BottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp),
            onSettingsClick = onSettingsClick,
            onTuneClick = onTuneClick
        )
    }
}

@Composable
private fun CircleButton(
    icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    backgroundColor: Color = CamScrim
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = CamWhite,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun LowLightPill(
    onTryNightClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // This would be conditionally shown based on ambient light
    var showPill by remember { mutableStateOf(false) }
    
    if (!showPill) return
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(19.dp),
        color = CamScrim
    ) {
        Row(
            modifier = Modifier
                .height(38.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_moon),
                contentDescription = null,
                tint = CamWhite,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = "Low light",
                color = CamWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 10.dp)
            )
            Surface(
                shape = RoundedCornerShape(15.dp),
                color = CamAccent,
                modifier = Modifier
                    .height(30.dp)
                    .clickable(onClick = onTryNightClick)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Try Night",
                        color = CamOnAccent,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomPill(
    modifier: Modifier = Modifier,
    onZoomSelect: (Float) -> Unit
) {
    var selectedZoom by remember { mutableStateOf(1f) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = CamScrim
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ZoomButton(
                text = "1×",
                isSelected = selectedZoom == 1f,
                onClick = {
                    selectedZoom = 1f
                    onZoomSelect(1f)
                }
            )
            ZoomButton(
                text = "2",
                isSelected = selectedZoom == 2f,
                onClick = {
                    selectedZoom = 2f
                    onZoomSelect(2f)
                }
            )
        }
    }
}

@Composable
private fun ZoomButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) CamAccent else Color.Transparent
    val textColor = if (isSelected) CamOnAccent else CamWhite

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        modifier = Modifier
            .height(32.dp)
            .widthIn(min = 48.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ModeRow(
    modifier: Modifier = Modifier,
    onModeSelect: (String) -> Unit
) {
    var selectedMode by remember { mutableStateOf("Photo") }

    Row(
        modifier = modifier.height(42.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        ModeButton(
            text = "Night",
            isSelected = selectedMode == "Night",
            onClick = {
                selectedMode = "Night"
                onModeSelect("Night")
            }
        )
        ModeButton(
            text = "Photo",
            isSelected = selectedMode == "Photo",
            onClick = {
                selectedMode = "Photo"
                onModeSelect("Photo")
            }
        )
    }
}

@Composable
private fun ModeButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) CamAccent else Color.Transparent
    val textColor = if (isSelected) CamOnAccent else CamWhite.copy(alpha = 0.8f)
    val fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal

    Surface(
        shape = RoundedCornerShape(21.dp),
        color = backgroundColor,
        modifier = Modifier
            .height(42.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 17.sp,
                fontWeight = fontWeight
            )
        }
    }
}

@Composable
private fun ShutterRow(
    modifier: Modifier = Modifier,
    onShutterClick: () -> Unit,
    onFlipClick: () -> Unit,
    onThumbnailClick: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Gallery thumbnail
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .border(2.dp, CamWhite.copy(alpha = 0.3f), CircleShape)
                .clickable(onClick = onThumbnailClick)
        ) {
            // Thumbnail image would go here
        }

        // Shutter button
        Box(
            modifier = Modifier
                .size(90.dp)
                .border(4.dp, CamWhite, CircleShape)
                .clickable(onClick = onShutterClick),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(CamWhite)
            )
        }

        // Flip camera button
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(CamScrim)
                .clickable(onClick = onFlipClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_flip_camera),
                contentDescription = "Switch camera",
                tint = CamWhite,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun BottomBar(
    modifier: Modifier = Modifier,
    onSettingsClick: () -> Unit,
    onTuneClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 44.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Settings button
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(CamBarBtn)
                .clickable(onClick = onSettingsClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "Settings",
                tint = CamAccent,
                modifier = Modifier.size(26.dp)
            )
        }

        // Camera/Screen segmented control
        SegmentedControl()

        // Tune button with notification dot
        Box(
            modifier = Modifier.size(54.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(CamBarBtn)
                    .clickable(onClick = onTuneClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_tune),
                    contentDescription = "Quick settings",
                    tint = CamAccent,
                    modifier = Modifier.size(26.dp)
                )
            }
            // Notification dot
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF5B9DFF))
                    .align(Alignment.TopEnd)
                    .offset(x = (-6).dp, y = 6.dp)
            )
        }
    }
}

@Composable
private fun SegmentedControl() {
    var selectedSegment by remember { mutableStateOf("camera") }

    Surface(
        shape = RoundedCornerShape(27.dp),
        color = CamBarBtn
    ) {
        Row(
            modifier = Modifier
                .height(54.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentButton(
                icon = R.drawable.ic_camera,
                isSelected = selectedSegment == "camera",
                onClick = { selectedSegment = "camera" }
            )
            SegmentButton(
                icon = R.drawable.ic_screen,
                isSelected = selectedSegment == "screen",
                onClick = { selectedSegment = "screen" }
            )
        }
    }
}

@Composable
private fun SegmentButton(
    icon: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) CamAccent else Color.Transparent
    val iconTint = if (isSelected) CamOnAccent else CamWhite

    Surface(
        shape = RoundedCornerShape(23.dp),
        color = backgroundColor,
        modifier = Modifier
            .width(64.dp)
            .height(46.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}
