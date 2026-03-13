package dev.cannoli.scorza.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

data class CreditEntry(val name: String, val detail: String)

val CREDITS: List<CreditEntry> = listOf(
    CreditEntry("Shaun Inman", "MinUI — Inspiration"),
    CreditEntry("M+ Fonts Project", "M PLUS 1 Code — OFL"),
    CreditEntry("Atari800", "GPLv2"),
    CreditEntry("Beetle NeoPop", "GPLv2"),
    CreditEntry("Beetle PCE FAST", "GPLv2"),
    CreditEntry("Beetle PC-FX", "GPLv2"),
    CreditEntry("Beetle VB", "GPLv2"),
    CreditEntry("Beetle Wonderswan", "GPLv2"),
    CreditEntry("blueMSX", "GPLv2"),
    CreditEntry("DOSBox-Pure", "GPLv2"),
    CreditEntry("FCEUmm", "GPLv2"),
    CreditEntry("FreeIntv", "GPLv3"),
    CreditEntry("Gambatte", "GPLv2"),
    CreditEntry("Genesis Plus GX", "Non-commercial"),
    CreditEntry("Handy", "Zlib"),
    CreditEntry("mGBA", "MPLv2.0"),
    CreditEntry("Mupen64Plus-Next", "GPLv2"),
    CreditEntry("Nestopia", "GPLv2"),
    CreditEntry("PCSX ReARMed", "GPLv2"),
    CreditEntry("PicoDrive", "MAME"),
    CreditEntry("PokeMini", "GPLv3"),
    CreditEntry("ProSystem", "GPLv2"),
    CreditEntry("Snes9x", "Non-commercial"),
    CreditEntry("Stella", "GPLv2"),
    CreditEntry("SwanStation", "GPLv3"),
    CreditEntry("vecx", "GPLv3"),
    CreditEntry("Virtual Jaguar", "GPLv3"),
)

@Composable
fun CreditsOverlay(
    selectedIndex: Int,
    scrollTarget: Int,
    backgroundImagePath: String?,
    backgroundTint: Int,
    listFontSize: TextUnit,
    listLineHeight: TextUnit,
    listVerticalPadding: Dp
) {
    val itemHeight = pillItemHeight(listLineHeight, listVerticalPadding)
    ListDialogScreen(
        backgroundImagePath = backgroundImagePath,
        backgroundTint = backgroundTint,
        title = "Credits and Licenses",
        listFontSize = listFontSize,
        listLineHeight = listLineHeight,
        fullWidth = true,
        rightBottomItems = emptyList()
    ) {
        List(
            items = CREDITS,
            selectedIndex = selectedIndex,
            scrollTarget = scrollTarget,
            itemHeight = itemHeight
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
