package com.necra.purecam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.necra.purecam.R

private val MaterialSymbolsOutlined = FontFamily(
    Font(
        R.font.material_symbols_outlined,
        weight = FontWeight.Normal,
        loadingStrategy = FontLoadingStrategy.Async
    )
)

@Composable
internal fun MaterialSymbolIcon(
    symbol: String,
    description: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    size: TextUnit = 30.sp
) {
    Text(
        text = symbol,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        color = tint,
        style = TextStyle(
            fontFamily = MaterialSymbolsOutlined,
            fontWeight = FontWeight.Normal,
            fontSize = size,
            lineHeight = size,
            letterSpacing = 0.sp,
            fontFeatureSettings = "liga",
            textAlign = TextAlign.Center
        ),
        maxLines = 1
    )
}
