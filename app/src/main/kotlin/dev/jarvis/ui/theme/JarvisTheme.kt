package dev.jarvis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(primary = Color(0xFFBCE6C8), onPrimary = Color(0xFF193B27),
    primaryContainer = Color(0xFF244B33), onPrimaryContainer = Color(0xFFD2F5DC),
    background = Color(0xFF101411), surface = Color(0xFF101411), surfaceVariant = Color(0xFF242C26),
    onBackground = Color(0xFFE4E9E3), onSurface = Color(0xFFE4E9E3), onSurfaceVariant = Color(0xFFABB9AE))
private val Light = lightColorScheme(primary = Color(0xFF315F40), onPrimary = Color.White,
    primaryContainer = Color(0xFFD0EDD7), onPrimaryContainer = Color(0xFF173823),
    background = Color(0xFFF7F9F4), surface = Color(0xFFF7F9F4), surfaceVariant = Color(0xFFE7EEE5),
    onBackground = Color(0xFF19211B), onSurface = Color(0xFF19211B))
@Composable fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Typography(), content = content)
}

