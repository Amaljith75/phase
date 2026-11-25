package app.phase.stem.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object PhaseColors {
    val Void = Color(0xFF070708)
    val Carbon = Color(0xFF0E0E11)
    val Graphite = Color(0xFF16161B)
    val Slate = Color(0xFF202027)
    val Edge = Color(0xFF2C2C35)
    val Steel = Color(0xFF8C8C98)
    val Mist = Color(0xFFB9B9C4)
    val Chrome = Color(0xFFEDEDF2)
    val Danger = Color(0xFFFF4D5E)
    val Ok = Color(0xFF5BFF8F)
    val Accent = Color(0xFFE8E4D8)

    /** Liquid-metal gradient for marks, rims and headings. */
    val metal = Brush.linearGradient(
        listOf(Color(0xFFF4F4F8), Color(0xFF9A9AA6), Color(0xFFE2E2EA), Color(0xFF6E6E7A), Color(0xFFD5D5DE)),
    )
    val metalSoft = Brush.verticalGradient(listOf(Color(0xFF2A2A31), Color(0xFF141418)))
    val background = Brush.verticalGradient(listOf(Color(0xFF111115), Void, Void))
}

object PhaseType {
    /** Monospaced labels, in the spirit of hardware silkscreen printing. */
    val label = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
    val labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)
    val readout = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold)
    val display = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp)
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp)
    val body = TextStyle(fontSize = 14.sp, letterSpacing = 0.1.sp)
}

private val scheme = darkColorScheme(
    primary = PhaseColors.Chrome,
    onPrimary = PhaseColors.Void,
    secondary = PhaseColors.Mist,
    background = PhaseColors.Void,
    onBackground = PhaseColors.Chrome,
    surface = PhaseColors.Carbon,
    onSurface = PhaseColors.Chrome,
    surfaceVariant = PhaseColors.Graphite,
    onSurfaceVariant = PhaseColors.Mist,
    surfaceContainer = PhaseColors.Graphite,
    surfaceContainerHigh = PhaseColors.Slate,
    surfaceContainerHighest = PhaseColors.Edge,
    outline = PhaseColors.Edge,
    error = PhaseColors.Danger,
)

@Composable
fun PhaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
