package pl.openfilepower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (vm.selecting) {
                    RoundIcon(Icons.Rounded.Close, "Zakończ zaznaczanie") { vm.clearSelection() }
                    Text("Zaznaczono ${vm.selected.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    RoundIcon(Icons.Rounded.SelectAll, "Zaznacz wszystko") { vm.selectAll() }
                } else {
                    if (!medium) RoundIcon(Icons.Rounded.Menu, "Źródła i foldery", onClick = actions.menu)
                    RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Wstecz") { vm.back() }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(vm.galleryAlbum?.let(vm::title) ?: "Galeria", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val items = vm.visible
                        Text("${items.count { FileRules.isImage(it) }} zdjęć · ${items.count { FileRules.isVideo(it) }} filmów · ${formatSize(items.sumOf { it.size })}", fontSize = 11.sp, color = Palette.TextDim, maxLines = 1)
                    }
                    vm.galleryAlbum?.let { album -> RoundIcon(Icons.Rounded.FolderOpen, "Otwórz folder albumu") { vm.navigate(album, vm.tabs.isNotEmpty()) } }
                    RoundIcon(Icons.Rounded.Refresh, "Odśwież") { vm.refresh() }
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.galleryAlbum == null) {
                    Chip("Oś czasu", !vm.galleryAlbums, icon = Icons.Rounded.CalendarMonth) { vm.galleryAlbums = false }
                    Chip("Albumy", vm.galleryAlbums, icon = Icons.Rounded.PhotoAlbum) { vm.galleryAlbums = true }
                    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(20.dp).background(Palette.Line))
                }
                MediaFilter.entries.forEach { f -> Chip(f.label, vm.galleryFilter == f) { vm.galleryFilter = f } }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !vm.access -> Empty(Icons.Rounded.PhotoLibrary, "Galeria potrzebuje dostępu", "Nadaj pełny dostęp do plików na ekranie startowym.", Modifier.align(Alignment.Center))
                    vm.loading && vm.entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.White)
                    vm.galleryAlbums && vm.galleryAlbum == null -> Albums(vm, medium)
                    vm.visible.isEmpty() -> Empty(Icons.Rounded.PhotoLibrary, "Brak zdjęć i filmów", "Zmień filtr albo odśwież galerię.", Modifier.align(Alignment.Center))
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
    val items = vm.visible.sortedByDescending { it.modified }
    val groups = remember(items) { items.groupBy { dayLabel(it.modified) } }
    val cell = when (vm.gridSize) { GridSize.SMALL -> if (medium) 96 else 72; GridSize.MEDIUM -> if (medium) 130 else 100; GridSize.LARGE -> if (medium) 190 else 150 }
    LazyVerticalGrid(GridCells.Adaptive(cell.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(bottom = 120.dp)) {
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
            .combinedClickable(onClick = { if (vm.selecting) vm.select(item) else vm.open(item) }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.select(item) }),
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
    val albums = vm.albums
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
