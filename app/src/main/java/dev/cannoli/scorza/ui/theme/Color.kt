package dev.cannoli.scorza.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Black = Color(0xFF000000)
val White = Color(0xFFFFFFFF)
val GrayText = Color(0xFF999999)
val DarkGray = Color(0xFF1A1A1A)

data class CannoliColors(
    val highlight: Color = Color.White,
    val text: Color = Color.White,
    val highlightText: Color = Color.Black,
    val accent: Color = Color.White
)

val LocalCannoliColors = staticCompositionLocalOf { CannoliColors() }

data class ColorPreset(val name: String, val color: Long)

val COLOR_PRESETS = listOf(
    // Row 1: Neutrals
    ColorPreset("Black", 0xFF000000),
    ColorPreset("Dark Gray", 0xFF424242),
    ColorPreset("Gray", 0xFF9E9E9E),
    ColorPreset("White", 0xFFFFFFFF),
    // Row 2: Cool colors
    ColorPreset("Red", 0xFFE57373),
    ColorPreset("Pink", 0xFFF06292),
    ColorPreset("Purple", 0xFFBA68C8),
    ColorPreset("Indigo", 0xFF7986CB),
    // Row 3: Blues & greens
    ColorPreset("Cannoli", 0xFF6FB8DE),
    ColorPreset("Cyan", 0xFF4DD0E1),
    ColorPreset("Teal", 0xFF4DB6AC),
    ColorPreset("Green", 0xFF81C784),
    // Row 4: Warm colors
    ColorPreset("Lime", 0xFFAED581),
    ColorPreset("Yellow", 0xFFFFD54F),
    ColorPreset("Amber", 0xFFFFB74D),
    ColorPreset("Orange", 0xFFFF8A65)
)

fun colorToHex(color: Color): String {
    val argb = color.value.shr(32).toLong()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "#%02X%02X%02X".format(r, g, b)
}

fun hexToColor(hex: String): Color? {
    val clean = hex.removePrefix("#")
    if (clean.length != 6) return null
    return try {
        Color(0xFF000000 or clean.toLong(16))
    } catch (_: NumberFormatException) {
        null
    }
}

fun colorToArgbLong(color: Color): Long {
    val argb = color.value.shr(32).toLong()
    return argb or (0xFFL shl 24)
}
