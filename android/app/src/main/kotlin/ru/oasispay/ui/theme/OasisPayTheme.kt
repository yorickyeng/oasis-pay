package ru.oasispay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF006B54), onPrimary = Color.White,
    primaryContainer = Color(0xFFADF2D7), onPrimaryContainer = Color(0xFF00382B),
    secondary = Color(0xFF4D6359), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8EBE0), onSecondaryContainer = Color(0xFF263C32),
    background = Color(0xFFF5F8F4), onBackground = Color(0xFF14201A),
    surface = Color(0xFFF5F8F4), onSurface = Color(0xFF14201A),
    surfaceVariant = Color(0xFFDCE8DF), onSurfaceVariant = Color(0xFF40524A),
    surfaceContainerLow = Color(0xFFEDF3EE), surfaceContainer = Color(0xFFE7EFE9),
    surfaceContainerHigh = Color(0xFFDEEAE2), surfaceContainerLowest = Color.White,
    outline = Color(0xFF6E8176), outlineVariant = Color(0xFFBECFC4),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF86D6B8), onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF00513F), onPrimaryContainer = Color(0xFFADF2D7),
    secondary = Color(0xFFB7CBC0), onSecondary = Color(0xFF23382E),
    secondaryContainer = Color(0xFF354B40), onSecondaryContainer = Color(0xFFD8EBE0),
    background = Color(0xFF101712), onBackground = Color(0xFFE2EBE3),
    surface = Color(0xFF101712), onSurface = Color(0xFFE2EBE3),
    surfaceVariant = Color(0xFF3B4B42), onSurfaceVariant = Color(0xFFBFCFC5),
    surfaceContainerLow = Color(0xFF171F19), surfaceContainer = Color(0xFF1C2720),
    surfaceContainerHigh = Color(0xFF28342C), surfaceContainerLowest = Color(0xFF0C110E),
    outline = Color(0xFF8A9D91), outlineVariant = Color(0xFF43564B),
)
private val Base = Typography()
private val Type = Base.copy(
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.9).sp),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)
private val Corners = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(30.dp),
)

@Composable
internal fun OasisPayTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light,
        typography = Type, shapes = Corners, content = content)
}
