package com.daya.velum.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.daya.velum.R

/** Velum tokens. Black is literal black for the AMOLED viewfinder; amber is the only accent. */
object V {
    val Black = Color(0xFF000000)
    val Graphite = Color(0xFF1B1C1E)
    val GraphiteHi = Color(0xFF2B2C2F)
    val Line = Color(0xFF3A3B3F)
    val Bone = Color(0xFFECE6DA)
    val Ash = Color(0xFF8E8980)
    val Dim = Color(0xFF5B5853)
    val Amber = Color(0xFFE8A33D)
    val Tally = Color(0xFFE5483A)
    val Ok = Color(0xFF7FB77E)
    val Scrim = Color(0xB3000000)
}

val Plex = FontFamily(
    Font(R.font.plex_cond_regular, FontWeight.Normal),
    Font(R.font.plex_cond_medium, FontWeight.Medium),
    Font(R.font.plex_cond_semibold, FontWeight.SemiBold),
)

/** Type scale (modular, ~1.2): 11 / 13 / 15 / 18 / 22 / 28. Readouts use tabular figures. */
object VType {
    private val base = TextStyle(fontFamily = Plex, color = V.Bone, fontFeatureSettings = "tnum")
    val micro = base.copy(fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp)
    val small = base.copy(fontSize = 13.sp, lineHeight = 17.sp)
    val body = base.copy(fontSize = 15.sp, lineHeight = 21.sp)
    val label = base.copy(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    val value = base.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val title = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val display = base.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun VelumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = V.Amber, onPrimary = V.Black, background = V.Black, onBackground = V.Bone,
            surface = V.Graphite, onSurface = V.Bone, surfaceVariant = V.GraphiteHi, onSurfaceVariant = V.Ash,
            outline = V.Line, error = V.Tally, secondary = V.Bone, onSecondary = V.Black,
        ),
        typography = Typography(
            bodyLarge = VType.body, bodyMedium = VType.small, bodySmall = VType.micro,
            labelLarge = VType.label, labelMedium = VType.small, labelSmall = VType.micro,
            titleLarge = VType.title, titleMedium = VType.value, titleSmall = VType.label,
            headlineSmall = VType.display,
        ),
        content = content,
    )
}
