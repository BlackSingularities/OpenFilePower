package pl.openfilepower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import pl.openfilepower.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Galeria całej pamięci: oś czasu dzień po dniu albo albumy (foldery z mediami). */
@Composable fun GalleryScreen(vm: ExplorerModel, medium: Boolean, actions: BrowserActions) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (medium) 18.dp else 8.dp)) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (vm.selecting) {
                    RoundIcon(Icons.Rounded.Close, "Zakończ zaznaczanie") { vm.clearSelection() }
                    Text("Zaznaczono ${vm.selected.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    RoundIcon(Icons.Rounded.SelectAll, "Zaznacz wszystko") { vm.selectAll() }
                } else {
                    if (!medium) RoundIcon(Icons.Rounded.Menu, "Źródła i foldery", onClick = actions.menu)
                    RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Wstecz") { vm.back() }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(vm.galleryAlbum?.let(vm::title) ?: "Galeria", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val items = vm.visible
                        Text("${items.count { FileRules.isImage(it) }} zdjęć · ${items.count { FileRules.isVideo(it) }} filmów · ${formatSize(items.sumOf { it.size })}", fontSize = 11.sp, color = Palette.TextDim, maxLines = 1)
                    }
                    vm.galleryAlbum?.let { album -> RoundIcon(Icons.Rounded.FolderOpen, "Otwórz folder albumu", size = 40.dp) { vm.navigate(album, vm.tabs.isNotEmpty()) } }
                    if (vm.galleryAlbum == null) {
                        // Oś czasu: ponowne dotknięcie przełącza grupowanie dni ↔ miesiące.
                        RoundIcon(if (vm.galleryByMonth) Icons.Rounded.CalendarViewMonth else Icons.Rounded.CalendarMonth, if (vm.galleryAlbums) "Oś czasu" else if (vm.galleryByMonth) "Oś czasu – grupuj po dniach" else "Oś czasu – grupuj po miesiącach", active = !vm.galleryAlbums, size = 40.dp) {
                            if (vm.galleryAlbums) vm.galleryAlbums = false else vm.updateGalleryByMonth(!vm.galleryByMonth)
                        }
                        RoundIcon(Icons.Rounded.PhotoAlbum, "Albumy", active = vm.galleryAlbums, size = 40.dp) { vm.galleryAlbums = true }
                    }
                    RoundIcon(Icons.Rounded.PhotoSizeSelectLarge, "Rozmiar miniatur: ${vm.gridSize.label}", size = 40.dp) { vm.updateGridSize(GridSize.entries[(vm.gridSize.ordinal + 1) % GridSize.entries.size]) }
                }
            }
            if (vm.showFilters || vm.galleryFilter != MediaFilter.ALL) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                MediaFilter.entries.forEach { f -> Chip(f.label, vm.galleryFilter == f) { vm.galleryFilter = f } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !vm.access -> Empty(Icons.Rounded.PhotoLibrary, "Galeria potrzebuje dostępu", "Nadaj pełny dostęp do plików na ekranie startowym.", Modifier.align(Alignment.Center))
                    vm.loading && vm.entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.White)
                    vm.galleryAlbums && vm.galleryAlbum == null -> Albums(vm, medium)
                    vm.visible.isEmpty() -> Empty(Icons.Rounded.PhotoLibrary, "Brak zdjęć i filmów", "Zmień filtr albo zrób zdjęcie – galeria odświeża się przy każdym otwarciu.", Modifier.align(Alignment.Center))
                    else -> Timeline(vm, medium)
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = if (medium) 18.dp else 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.progress?.let { ProgressCard(vm, it) }
            AnimatedVisibility(vm.selecting, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) { SelectionDock(vm, actions, false) }
        }
    }
}

private val monthFormat = SimpleDateFormat("LLLL yyyy", Locale.getDefault())
private fun monthLabel(time: Long): String = monthFormat.format(java.util.Date(time)).replaceFirstChar { it.uppercase() }

private fun dayLabel(time: Long): String {
    val now = Calendar.getInstance(); val then = Calendar.getInstance().apply { timeInMillis = time }
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val diff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && diff == 0 -> "Dzisiaj"
        sameYear && diff == 1 -> "Wczoraj"
        sameYear -> SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(then.time).replaceFirstChar { it.uppercase() }
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(then.time)
    }
}

@Composable private fun Timeline(vm: ExplorerModel, medium: Boolean) {
    // Grupowanie liczone raz na zmianę danych, nie przy każdym przerysowaniu (szybsze przewijanie dużych galerii).
    val source = vm.visible
    val byMonth = vm.galleryByMonth
    val groups = remember(source.size, source.firstOrNull(), source.lastOrNull(), byMonth, vm.galleryAlbum, vm.galleryFilter) {
        source.sortedByDescending { it.modified }.groupBy { if (byMonth) monthLabel(it.modified) else dayLabel(it.modified) }
    }
    val cell = when (vm.gridSize) { GridSize.SMALL -> if (medium) 96 else 72; GridSize.MEDIUM -> if (medium) 130 else 100; GridSize.LARGE -> if (medium) 190 else 150 }
    // Szczypanie zmienia rozmiar miniatur jak w aplikacji Zdjęcia.
    var pinch by remember { mutableFloatStateOf(1f) }
    val zoom = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            pinch = 1f
            do {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) {
                    pinch *= event.calculateZoom()
                    val sizes = GridSize.entries
                    when {
                        pinch > 1.35f -> { vm.updateGridSize(sizes[(vm.gridSize.ordinal + 1).coerceAtMost(sizes.lastIndex)]); pinch = 1f }
                        pinch < 0.75f -> { vm.updateGridSize(sizes[(vm.gridSize.ordinal - 1).coerceAtLeast(0)]); pinch = 1f }
                    }
                    event.changes.forEach { it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val ordered = remember(groups) { groups.values.flatten() }
    LazyVerticalGrid(GridCells.Adaptive(cell.dp), state = grid, modifier = zoom.dragSelect(vm, grid, { ordered }), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
        groups.forEach { (label, dayItems) ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "h:$label") {
                Row(Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${dayItems.size}", fontSize = 11.sp, color = Palette.TextDim, modifier = Modifier.padding(end = 6.dp))
                    val all = dayItems.all { it.place in vm.selected }
                    if (vm.selecting) SelectionMark(all, Modifier.combinedClickableCompat { dayItems.forEach { if ((it.place in vm.selected) == all) vm.select(it) } })
                }
            }
            items(dayItems, key = { it.place.toString() }) { MediaTile(vm, it) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@OptIn(ExperimentalFoundationApi::class)
@Composable fun MediaTile(vm: ExplorerModel, item: Item, corner: androidx.compose.ui.unit.Dp = 6.dp) {
    val selected = item.place in vm.selected
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(corner)).background(Palette.PanelHigh)
            .clickable { if (vm.selecting) vm.select(item) else vm.open(item) },
    ) {
        Icon(iconFor(item), null, Modifier.align(Alignment.Center).size(28.dp), tint = Palette.TextFaint)
        AsyncImage(item.imageModel(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (FileRules.isVideo(item)) Icon(Icons.Rounded.PlayCircle, null, Modifier.align(Alignment.Center).size(30.dp), tint = Color.White)
        if (FileRules.isRaw(item)) Text("RAW", fontSize = 8.sp, fontWeight = FontWeight.Black, color = Palette.Ink, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(Pill).background(Palette.White).padding(horizontal = 5.dp, vertical = 1.dp))
        if (selected) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.25f)))
        if (vm.selecting) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(5.dp))
    }
}

@Composable private fun Albums(vm: ExplorerModel, medium: Boolean) {
    val albums = remember(vm.entries) { vm.albums }
    LazyVerticalGrid(GridCells.Adaptive(if (medium) 180.dp else 150.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
        items(albums, key = { it.first.toString() }) { (place, items, latest) ->
            Column(Modifier.pressable(vm.title(place)) { vm.showAlbum(place) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Palette.PanelHigh)) {
                    val cover = items.maxBy { it.modified }
                    AsyncImage(cover.imageModel(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Text("${items.size}", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(Pill).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                Text(vm.title(place), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                Text(formatShortDate(latest), fontSize = 11.sp, color = Palette.TextDim, modifier = Modifier.padding(start = 2.dp))
            }
        }
    }
}
