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
    ColorPreset("Black", 0xFF1A1A1E),
    ColorPreset("Dark Grey", 0xFF3A3A3C),
    ColorPreset("Light Grey", 0xFFC0BFBE),
    ColorPreset("White", 0xFFF5F4F0),
    ColorPreset("Flame Red", 0xFFCC1A1A),
    ColorPreset("Crimson", 0xFFB8002A),
    ColorPreset("Berry", 0xFFC0336B),
    ColorPreset("Coral", 0xFFE8604A),
    ColorPreset("Spice", 0xFFE86A10),
    ColorPreset("Dandelion", 0xFFF5C400),
    ColorPreset("Kiwi", 0xFF5AB820),
    ColorPreset("Teal", 0xFF00897B),
    ColorPreset("Neon Blue", 0xFF0AB9E6),
    ColorPreset("Indigo", 0xFF3D4DB5),
    ColorPreset("Grape", 0xFF7B3FA0),
    ColorPreset("Midnight Purple", 0xFF4A1A6E)
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
