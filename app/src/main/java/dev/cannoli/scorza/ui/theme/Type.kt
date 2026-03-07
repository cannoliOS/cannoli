package dev.cannoli.scorza.ui.theme

import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import androidx.compose.ui.unit.sp

lateinit var MPlus1Code: FontFamily
    private set

lateinit var NerdSymbols: FontFamily
    private set

fun initFonts(assets: AssetManager) {
    val typeface = Typeface.createFromAsset(assets, "fonts/MPlus-1c-NerdFont-Bold.ttf")
    MPlus1Code = FontFamily(ComposeTypeface(typeface))

    val nerdTypeface = Typeface.createFromAsset(assets, "fonts/NerdSymbols.ttf")
    NerdSymbols = FontFamily(ComposeTypeface(nerdTypeface))
}

fun buildTypography(): Typography {
    return Typography(
        headlineLarge = TextStyle(
            fontFamily = MPlus1Code,
            fontWeight = FontWeight.Black,
            fontSize = 28.sp,
            color = Color.White
        ),
        titleLarge = TextStyle(
            fontFamily = MPlus1Code,
            fontWeight = FontWeight.Black,
            fontSize = 24.sp,
            color = Color.White
        ),
        bodyLarge = TextStyle(
            fontFamily = MPlus1Code,
            fontWeight = FontWeight.Black,
            fontSize = 22.sp,
            lineHeight = 32.sp,
            color = Color.White
        ),
        bodyMedium = TextStyle(
            fontFamily = MPlus1Code,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Color.White
        ),
        labelSmall = TextStyle(
            fontFamily = MPlus1Code,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            color = Color.White
        )
    )
}
