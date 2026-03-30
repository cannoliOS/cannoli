package dev.cannoli.scorza.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.cannoli.scorza.R

data class CreditEntry(val name: String, val detail: String)

val CREDITS: List<CreditEntry> = listOf(
    CreditEntry("Shaun Inman", "MinUI — Inspiration"),
    CreditEntry("M+ Fonts Project", "M PLUS 1 Code — OFL"),
    CreditEntry("Nerd Fonts", "NerdSymbols — OFL"),
    CreditEntry("Apache Commons Compress", "Archive Extraction — Apache 2.0"),
    CreditEntry("PdfiumAndroid (io.legere)", "PDF Renderer — Apache 2.0"),
    CreditEntry("XZ for Java", "7z Decompression — Public domain"),
    CreditEntry("ZXing", "QR Code Library — Apache 2.0"),
    CreditEntry("FBNeo", "Non-commercial"),
    CreditEntry("Gambatte", "GPLv2"),
    CreditEntry("Genesis Plus GX", "Non-commercial"),
    CreditEntry("Handy", "Zlib"),
    CreditEntry("MAME 2003-Plus", "MAME"),
    CreditEntry("Mednafen NGP", "GPLv2"),
    CreditEntry("Mednafen PCE FAST", "GPLv2"),
    CreditEntry("Mednafen VB", "GPLv2"),
    CreditEntry("Mednafen WonderSwan", "GPLv2"),
    CreditEntry("mGBA", "MPLv2.0"),
    CreditEntry("Mupen64Plus-Next", "GPLv2"),
    CreditEntry("Nestopia", "GPLv2"),
    CreditEntry("PCSX ReARMed", "GPLv2"),
    CreditEntry("PokeMini", "GPLv3"),
    CreditEntry("ProSystem", "GPLv2"),
    CreditEntry("Snes9x", "Non-commercial"),
    CreditEntry("Stella", "GPLv2"),
    CreditEntry("SwanStation", "GPLv3"),
    CreditEntry("crt-easymode by EasyMode", "Shader — GPL"),
    CreditEntry("sharp-bilinear by Themaister", "Shader — Public domain"),
    CreditEntry("scanline-fract by hunterk", "Shader — Public domain"),
    CreditEntry("zfast-crt by SoltanGris42 / metallic77", "Shader — GPLv2"),
    CreditEntry("zfast-lcd by SoltanGris42", "Shader — GPLv2"),
    CreditEntry("lcd3x by Gigaherz", "Shader — Public domain"),
)

@Composable
fun CreditsOverlay(
    selectedIndex: Int,
    scrollTarget: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp,
    onVisibleRangeChanged: ((Int, Int, Boolean) -> Unit)? = null
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    ListDialogScreen(
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        title = stringResource(R.string.credits_title),
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        fullWidth = true,
        rightBottomItems = emptyList()
    ) {
        List(
            items = CREDITS,
            selectedIndex = selectedIndex,
            scrollTarget = scrollTarget,
            itemHeight = itemHeight,
            onVisibleRangeChanged = onVisibleRangeChanged
        ) { index, entry ->
            PillRowKeyValue(
                label = entry.name,
                value = entry.detail,
                isSelected = selectedIndex == index,
                fontSize = listFontSize,
                lineHeight = listLineHeight,
                verticalPadding = listVerticalPadding
            )
        }
    }
}
