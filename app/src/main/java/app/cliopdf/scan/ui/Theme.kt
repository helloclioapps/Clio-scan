package app.cliopdf.scan.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Clio {
    val Blue = Color(0xFF0A72F0)
    val BlueDark = Color(0xFF0861CF)
    val BlueSoft = Color(0xFFE8F1FE)
    val Navy = Color(0xFF14386B)
    val Bg = Color(0xFFF4F6FA)
    val Panel = Color(0xFFFFFFFF)
    val Panel2 = Color(0xFFF8FAFC)
    val Line = Color(0xFFE5E9F0)
    val Text = Color(0xFF0F172A)
    val Text2 = Color(0xFF334155)
    val Muted = Color(0xFF64748B)
    val Faint = Color(0xFF94A3B8)
    val Green = Color(0xFF15A34A)
    val GreenSoft = Color(0xFFE7F8EE)
    val GreenText = Color(0xFF11743A)
    val Red = Color(0xFFDC2626)
    val RedSoft = Color(0xFFFDECEC)
    val Amber = Color(0xFFB45309)

    // tool tile colours, same family as CLIO PDF Desktop
    val TOcr = Color(0xFF0EA5C6)
    val TMerge = Color(0xFF6366F1)
    val TCompress = Color(0xFF16A34A)
    val TText = Color(0xFF64748B)
    val TImages = Color(0xFFDB2777)
    val TPdf = Color(0xFFDC2626)
}

private val scheme = lightColorScheme(
    primary = Clio.Blue,
    onPrimary = Color.White,
    primaryContainer = Clio.BlueSoft,
    onPrimaryContainer = Clio.Navy,
    secondary = Clio.Navy,
    onSecondary = Color.White,
    background = Clio.Bg,
    onBackground = Clio.Text,
    surface = Clio.Panel,
    onSurface = Clio.Text,
    surfaceVariant = Color(0xFFF1F4F8),
    onSurfaceVariant = Clio.Text2,
    surfaceContainer = Clio.Panel,
    surfaceContainerHigh = Clio.Panel,
    surfaceContainerHighest = Color(0xFFF1F4F8),
    surfaceContainerLow = Clio.Panel2,
    outline = Color(0xFFD7DEE8),
    outlineVariant = Clio.Line,
    error = Clio.Red,
)

private val type = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

@Composable
fun ClioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
