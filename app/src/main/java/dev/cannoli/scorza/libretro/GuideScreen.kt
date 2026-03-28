package dev.cannoli.scorza.libretro

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cannoli.scorza.R
import dev.cannoli.scorza.ui.components.ScreenBackground
import dev.cannoli.scorza.ui.theme.LocalCannoliColors
import dev.cannoli.scorza.ui.theme.MPlus1Code
import kotlinx.coroutines.delay
import java.io.File

private val ZOOM_SCALES = listOf(1, 2, 3)
private val TXT_FONT_SIZES = listOf(14, 18, 24)
private const val SCROLL_SPEED = 14f
private const val FRAME_MS = 16L

@Composable
fun GuideScreen(
    filePath: String,
    guideType: GuideType,
    page: Int,
    initialScrollY: Int,
    initialScrollX: Int,
    scrollDir: Int,
    scrollXDir: Int,
    pageJump: Int,
    pageJumpDir: Int,
    pageCount: Int,
    textZoom: Int,
    onScrollPosChanged: (y: Int, x: Int) -> Unit
) {
    val colors = LocalCannoliColors.current
    val zoomIndex = (textZoom - 1).coerceIn(0, ZOOM_SCALES.lastIndex)

    ScreenBackground(backgroundImagePath = null, backgroundAlpha = 0.92f) {
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            when (guideType) {
                GuideType.PDF -> PdfContent(
                    filePath, page, ZOOM_SCALES[zoomIndex],
                    initialScrollY, initialScrollX, scrollDir, scrollXDir, onScrollPosChanged
                )
                GuideType.TXT -> TxtContent(
                    filePath, initialScrollY, scrollDir, pageJump, pageJumpDir,
                    TXT_FONT_SIZES[zoomIndex], onScrollPosChanged
                )
                GuideType.IMAGE -> ImageContent(
                    filePath, initialScrollY, initialScrollX, scrollDir, scrollXDir,
                    pageJump, pageJumpDir, ZOOM_SCALES[zoomIndex], onScrollPosChanged
                )
            }

            if (guideType == GuideType.PDF && pageCount > 0) {
                Text(
                    text = stringResource(R.string.guide_page, page + 1, pageCount),
                    style = TextStyle(
                        fontFamily = MPlus1Code,
                        fontSize = 13.sp,
                        color = colors.text.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun PdfContent(
    filePath: String, page: Int, scale: Int,
    initialScrollY: Int, initialScrollX: Int,
    scrollDir: Int, scrollXDir: Int,
    onScrollPosChanged: (Int, Int) -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var renderer by remember { mutableStateOf<PdfRenderer?>(null) }
    var fd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    val scrollState = remember(initialScrollY) { ScrollState(initialScrollY) }
    val hScrollState = remember(initialScrollX) { ScrollState(initialScrollX) }

    LaunchedEffect(scrollDir) {
        while (scrollDir != 0) {
            scrollState.dispatchRawDelta(scrollDir * SCROLL_SPEED)
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(scrollXDir) {
        while (scrollXDir != 0) {
            hScrollState.dispatchRawDelta(scrollXDir * SCROLL_SPEED)
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.value to hScrollState.value }
            .collect { (y, x) -> onScrollPosChanged(y, x) }
    }

    DisposableEffect(filePath) {
        val file = File(filePath)
        if (file.exists()) {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            fd = pfd
            renderer = PdfRenderer(pfd)
        }
        onDispose {
            renderer?.close()
            fd?.close()
        }
    }

    LaunchedEffect(page, scale, renderer) {
        val r = renderer ?: return@LaunchedEffect
        if (page < 0 || page >= r.pageCount) return@LaunchedEffect
        val pdfPage = r.openPage(page)
        val renderScale = scale + 1
        val bmp = Bitmap.createBitmap(
            pdfPage.width * renderScale, pdfPage.height * renderScale, Bitmap.Config.ARGB_8888
        )
        bmp.eraseColor(android.graphics.Color.WHITE)
        pdfPage.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        pdfPage.close()
        bitmap = bmp
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        bitmap?.let { bmp ->
            if (scale == 1) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .horizontalScroll(hScrollState)
                        .verticalScroll(scrollState)
                        .requiredWidth(maxWidth * scale),
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }
}

@Composable
private fun TxtContent(
    filePath: String, initialScrollY: Int, scrollDir: Int,
    pageJump: Int, pageJumpDir: Int,
    fontSize: Int, onScrollPosChanged: (Int, Int) -> Unit
) {
    val colors = LocalCannoliColors.current
    var text by remember { mutableStateOf("") }
    val scrollState = remember(initialScrollY) { ScrollState(initialScrollY) }
    var viewportHeight by remember { mutableStateOf(0) }

    LaunchedEffect(scrollDir) {
        while (scrollDir != 0) {
            scrollState.dispatchRawDelta(scrollDir * SCROLL_SPEED)
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(pageJump) {
        if (pageJump > 0 && viewportHeight > 0) {
            scrollState.animateScrollTo(
                (scrollState.value + pageJumpDir * viewportHeight).coerceIn(0, scrollState.maxValue)
            )
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.value }.collect { onScrollPosChanged(it, 0) }
    }

    LaunchedEffect(filePath) {
        val file = File(filePath)
        if (file.exists()) text = file.readText()
    }

    if (text.isNotEmpty()) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize.sp,
                color = colors.text,
                lineHeight = (fontSize * 1.5).sp
            ),
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { viewportHeight = it.size.height }
                .verticalScroll(scrollState)
        )
    }
}

@Composable
private fun ImageContent(
    filePath: String, initialScrollY: Int, initialScrollX: Int,
    scrollDir: Int, scrollXDir: Int,
    pageJump: Int, pageJumpDir: Int,
    scale: Int, onScrollPosChanged: (Int, Int) -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scrollState = remember(initialScrollY) { ScrollState(initialScrollY) }
    val hScrollState = remember(initialScrollX) { ScrollState(initialScrollX) }
    var viewportHeight by remember { mutableStateOf(0) }

    LaunchedEffect(scrollDir) {
        while (scrollDir != 0) {
            scrollState.dispatchRawDelta(scrollDir * SCROLL_SPEED)
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(scrollXDir) {
        while (scrollXDir != 0) {
            hScrollState.dispatchRawDelta(scrollXDir * SCROLL_SPEED)
            delay(FRAME_MS)
        }
    }
    LaunchedEffect(pageJump) {
        if (pageJump > 0 && viewportHeight > 0) {
            scrollState.animateScrollTo(
                (scrollState.value + pageJumpDir * viewportHeight).coerceIn(0, scrollState.maxValue)
            )
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.value to hScrollState.value }
            .collect { (y, x) -> onScrollPosChanged(y, x) }
    }

    LaunchedEffect(filePath) {
        val file = File(filePath)
        if (file.exists()) bitmap = BitmapFactory.decodeFile(file.absolutePath)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        bitmap?.let { bmp ->
            if (scale == 1) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { viewportHeight = it.size.height }
                        .verticalScroll(scrollState),
                    contentScale = ContentScale.FillWidth
                )
            } else {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .horizontalScroll(hScrollState)
                        .verticalScroll(scrollState)
                        .requiredWidth(maxWidth * scale)
                        .onGloballyPositioned { viewportHeight = it.size.height },
                    contentScale = ContentScale.FillWidth
                )
            }
        }
    }
}
