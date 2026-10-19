package pl.openfilepower.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.openfilepower.*

/** Histogram (jasność i kanały RGB) oraz maska przepaleń/niedoświetleń. */
private class Analysis(val luma: IntArray, val red: IntArray, val green: IntArray, val blue: IntArray, val clipping: Bitmap, val highlights: Float, val shadows: Float)

private fun analyze(bitmap: Bitmap): Analysis {
    val w = bitmap.width; val h = bitmap.height
    val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
    val luma = IntArray(256); val r = IntArray(256); val g = IntArray(256); val b = IntArray(256)
    val mask = IntArray(w * h)
    var hi = 0; var lo = 0
    for (i in pixels.indices) {
        val p = pixels[i]
        val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
        r[pr]++; g[pg]++; b[pb]++
        luma[(pr * 299 + pg * 587 + pb * 114) / 1000]++
        val max = maxOf(pr, pg, pb)
        if (max >= 252) { mask[i] = 0xD0FF2A3A.toInt(); hi++ } else if (max <= 4) { mask[i] = 0xD02A6CFF.toInt(); lo++ }
    }
    return Analysis(luma, r, g, b, Bitmap.createBitmap(mask, w, h, Bitmap.Config.ARGB_8888), hi.toFloat() / pixels.size, lo.toFloat() / pixels.size)
}

/** Przeglądarka zdjęć (także RAW) i filmów: zoom, histogram, przepalenia, EXIF, pokaz slajdów, pasek miniatur. */
@Composable fun ImageViewer(vm: ExplorerModel, media: List<Item>, start: Int, close: () -> Unit, inspect: (Item) -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (media.size - 1).coerceAtLeast(0))) { media.size }
    var chrome by remember { mutableStateOf(true) }
    var showExif by remember { mutableStateOf(vm.prefs.flag("viewerExif", true)) }
    var showHistogram by remember { mutableStateOf(vm.prefs.flag("viewerHistogram", false)) }
    var showClipping by remember { mutableStateOf(false) }
    var slideshow by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(-1) }
    val context = LocalContext.current
    val current = media.getOrNull(pager.currentPage)
    BackHandler(onBack = close)

    // Pokaz slajdów: co 3 s następne zdjęcie (filmy są pomijane w odtwarzaniu automatycznym).
    LaunchedEffect(slideshow, pager.currentPage) {
        if (!slideshow) return@LaunchedEffect
        delay(3000)
        if (pager.currentPage < media.lastIndex) pager.animateScrollToPage(pager.currentPage + 1) else slideshow = false
    }
    LaunchedEffect(pager.currentPage) { if (playing != pager.currentPage) playing = -1 }

    var exif by remember { mutableStateOf<PhotoExif?>(null) }
    LaunchedEffect(current) {
        exif = null
        if (current != null && FileRules.isImage(current)) exif = vm.photoExif(current)
        vm.prefetchExif(media.subList((pager.currentPage - 2).coerceAtLeast(0), (pager.currentPage + 3).coerceAtMost(media.size)))
    }

    var analysis by remember { mutableStateOf<Analysis?>(null) }
    LaunchedEffect(current, showHistogram || showClipping) {
        analysis = null
        if (current == null || !FileRules.isImage(current) || !(showHistogram || showClipping)) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(current.imageModel()).size(900).allowHardware(false).build()
        val bitmap = (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        analysis = withContext(Dispatchers.Default) { analyze(bitmap) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { media[it].place.toString() }, beyondViewportPageCount = 1) { page ->
            val item = media[page]
            if (FileRules.isVideo(item)) VideoPage(item, playing == page, { playing = page; slideshow = false }) { chrome = !chrome }
            else {
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures(onTap = { chrome = !chrome }, onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f }) }
                        .pointerInput(Unit) {
                            // Dwa palce lub powiększenie: zoom i przesuwanie; jeden palec bez zoomu – przewijanie zdjęć.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size > 1 || scale > 1f) {
                                        scale = (scale * event.calculateZoom()).coerceIn(1f, 8f)
                                        offset = if (scale > 1f) offset + event.calculatePan() else Offset.Zero
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                ) {
                    AsyncImage(item.imageModel(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    val a = analysis
                    if (showClipping && a != null && page == pager.currentPage) androidx.compose.foundation.Image(a.clipping.asImageBitmap(), "Przepalenia i niedoświetlenia", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
        }

        if (chrome && current != null) {
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Zamknij podgląd", onClick = close)
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(current.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pager.currentPage + 1} z ${media.size} · ${formatSize(current.size)} · ${formatShortDate(current.modified)}", fontSize = 11.sp, color = Palette.TextDim, maxLines = 1)
                    }
                    RoundIcon(Icons.Rounded.Info, "Właściwości") { inspect(current) }
                }
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (FileRules.isImage(current)) {
                        Chip("EXIF", showExif, icon = Icons.Rounded.CameraAlt) { showExif = !showExif; vm.prefs.setFlag("viewerExif", showExif) }
                        Chip("Histogram", showHistogram, icon = Icons.Rounded.BarChart) { showHistogram = !showHistogram; vm.prefs.setFlag("viewerHistogram", showHistogram) }
                        Chip("Przepalenia", showClipping, icon = Icons.Rounded.Exposure) { showClipping = !showClipping }
                    }
                    Chip(if (slideshow) "Zatrzymaj" else "Pokaz slajdów", slideshow, icon = if (slideshow) Icons.Rounded.Pause else Icons.Rounded.Slideshow) { slideshow = !slideshow }
                    Chip("Udostępnij", false, icon = Icons.Rounded.Share) { vm.share(listOf(current)) }
                    Chip("Otwórz w…", false, icon = Icons.AutoMirrored.Rounded.OpenInNew) { vm.openExternal(current, true) }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val e = exif
            if (chrome && showExif && e != null && current != null && FileRules.isImage(current)) ExifStrip(e, current)
            val a = analysis
            if (chrome && showHistogram && a != null) HistogramCard(a, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            if (showClipping && a != null) Text("Przepalone ${"%.1f".format(a.highlights * 100)}% · niedoświetlone ${"%.1f".format(a.shadows * 100)}%", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).clip(Pill).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 4.dp))
            if (chrome && media.size > 1) {
                val strip = rememberLazyListState()
                LaunchedEffect(pager.currentPage) { strip.animateScrollToItem((pager.currentPage - 3).coerceAtLeast(0)) }
                val scope = rememberCoroutineScope()
                LazyRow(state = strip, modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(vertical = 8.dp), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(media, key = { _, it -> "s:" + it.place }) { index, item ->
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Palette.PanelHigh)
                            .then(if (index == pager.currentPage) Modifier.border(2.dp, Palette.White, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { scope.launch { pager.scrollToPage(index) } }) {
                            AsyncImage(item.imageModel(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (FileRules.isVideo(item)) Icon(Icons.Rounded.PlayArrow, null, Modifier.align(Alignment.Center).size(18.dp), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/** Plakietki ekspozycji jak w Fotopodglądzie: aparat, obiektyw, przysłona, czas, ISO, ogniskowa. */
@Composable private fun ExifStrip(e: PhotoExif, item: Item) {
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(listOfNotNull(e.camera, e.lens).joinToString(" · ").ifBlank { item.extension.uppercase() }, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 5.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (e.badges + listOfNotNull(e.megapixels, e.program, if (FileRules.isRaw(item)) "RAW" else null)).forEach { badge ->
                Text(badge, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(Pill).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable private fun HistogramCard(a: Analysis, modifier: Modifier) {
    Box(modifier.width(260.dp).height(96.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.7f)).padding(8.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val max = (a.red + a.green + a.blue).sortedDescending().getOrElse(3) { 1 }.coerceAtLeast(1).toFloat()
            fun draw(values: IntArray, color: Color) {
                val path = Path().apply {
                    moveTo(0f, size.height)
                    values.forEachIndexed { i, v -> lineTo(i / 255f * size.width, size.height - (v / max).coerceAtMost(1f) * size.height) }
                    lineTo(size.width, size.height); close()
                }
                drawPath(path, color)
            }
            draw(a.red, Color(0x66FF3B3B)); draw(a.green, Color(0x6633D17A)); draw(a.blue, Color(0x663B7BFF)); draw(a.luma, Color(0x55FFFFFF))
        }
    }
}

/** Strona filmu: miniatura z przyciskiem odtwarzania, po dotknięciu – odtwarzacz z kontrolkami systemu. */
@Composable private fun VideoPage(item: Item, playing: Boolean, play: () -> Unit, toggleChrome: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (playing && item.localUri != null) {
            AndroidView(factory = { ctx ->
                VideoView(ctx).apply {
                    val controller = MediaController(ctx).also { it.setAnchorView(this) }
                    setMediaController(controller)
                    setVideoURI(item.localUri)
                    setOnPreparedListener { start(); controller.show(2500) }
                }
            }, modifier = Modifier.fillMaxSize(), onRelease = { it.stopPlayback() })
        } else {
            AsyncImage(item.imageModel(), item.name, Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { toggleChrome() }) }, contentScale = ContentScale.Fit)
            Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable(onClick = play), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Odtwórz ${item.name}", tint = Palette.Ink, modifier = Modifier.size(44.dp))
            }
        }
    }
}
