package pl.openfilepower.ui

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import pl.openfilepower.*

fun iconFor(item: Item): ImageVector = when {
    item.directory -> Icons.Rounded.Folder
    FileRules.isImage(item) -> Icons.Rounded.Image
    FileRules.isVideo(item) -> Icons.Rounded.Movie
    FileRules.isAudio(item) -> Icons.Rounded.MusicNote
    FileRules.isApk(item) -> Icons.Rounded.Android
    item.extension == "pdf" -> Icons.Rounded.PictureAsPdf
    item.extension in FileRules.archiveExtensions -> Icons.Rounded.FolderZip
    item.extension in setOf("xls", "xlsx", "ods", "csv", "tsv") -> Icons.Rounded.TableChart
    item.extension in setOf("ppt", "pptx", "odp") -> Icons.Rounded.Slideshow
    item.extension in setOf("doc", "docx", "odt", "rtf", "txt", "md", "epub") -> Icons.AutoMirrored.Rounded.Article
    FileRules.isText(item) -> Icons.Rounded.Code
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}
fun iconFor(category: Category): ImageVector = when (category) {
    Category.IMAGES -> Icons.Rounded.Photo
    Category.RAW -> Icons.Rounded.Camera
    Category.VIDEO -> Icons.Rounded.VideoLibrary
    Category.AUDIO -> Icons.Rounded.LibraryMusic
    Category.DOCUMENTS -> Icons.Rounded.Description
    Category.ARCHIVES -> Icons.Rounded.FolderZip
    Category.APK -> Icons.Rounded.Android
    Category.DOWNLOADS -> Icons.Rounded.Download
    Category.LARGE -> Icons.Rounded.DataUsage
}
fun describe(item: Item): String = if (item.directory) (if (item.children >= 0) "Folder · ${item.children} elem." else "Folder")
    else listOfNotNull(item.extension.uppercase().ifBlank { null }, formatSize(item.size), formatShortDate(item.modified).takeIf { item.modified > 0 }).joinToString(" · ")

/**
 * Ikona pliku albo miniatura zdjęcia/filmu; przytrzymanie rozpoczyna przeciąganie.
 * `onClick` jest obsługiwany wewnątrz źródła przeciągania – bez tego dotknięcie samej
 * ikony lub miniatury nie dochodziło do klikalnego wiersza/kafelka wyżej.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun FileGlyph(
    item: Item, vm: ExplorerModel, size: androidx.compose.ui.unit.Dp = 44.dp, corner: androidx.compose.ui.unit.Dp = 12.dp,
    draggable: Boolean = true, plain: Boolean = false, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null,
) {
    val thumb = vm.thumbnails && item.localUri != null && (FileRules.isImage(item) || FileRules.isVideo(item))
    // `plain` – bez kapsla pod ikoną: zamiast obrysowanego tła sama, znacznie większa ikona.
    val tile = plain && !thumb
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner))
            .background(if (tile) Color.Transparent else if (item.directory) Palette.Folder.copy(alpha = 0.16f) else Palette.PanelHigh)
            .then(if (draggable) Modifier.dragAndDropSource(transferData = { _ -> vm.drag = item; DragAndDropTransferData(ClipData.newPlainText("OpenFilePower", item.name)) }) else Modifier)
            .then(if (onClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick) else Modifier)
            .semantics { contentDescription = if (onClick != null) item.name else "Przytrzymaj, aby przeciągnąć ${item.name}" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(iconFor(item), null, Modifier.size(size * if (tile) 0.74f else 0.52f), tint = if (item.directory) Palette.Folder else Palette.TextDim)
        if (thumb) {
            AsyncImage(item.imageModel(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (FileRules.isVideo(item)) Icon(Icons.Rounded.PlayCircle, null, Modifier.size(size * 0.4f), tint = Color.White)
        }
    }
}

@Composable fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(24.dp).clip(CircleShape).background(if (selected) Palette.White else Color.Black.copy(alpha = 0.35f)).border(1.5.dp, if (selected) Palette.White else Palette.TextDim, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Icon(Icons.Rounded.Check, null, tint = Palette.Ink, modifier = Modifier.size(16.dp))
    }
}

/**
 * Wiersz listy: dotknięcie otwiera (w trybie zaznaczania – zaznacza), przytrzymanie zaznacza.
 * Wysokość, ikona i druga linia rosną płynnie z [density]; poniżej [BARE_CEILING] zostaje
 * najgęstsza, jednoliniowa lista bez ikon.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun FileRow(item: Item, vm: ExplorerModel, wide: Boolean, showPath: Boolean, density: Float, inspect: (Item) -> Unit) {
    val selected = item.place in vm.selected
    val haptic = LocalHapticFeedback.current
    val d = density.coerceIn(DENSITY_MIN, GRID_FLOOR)
    val roomy = ((d - BARE_CEILING) / (GRID_FLOOR - BARE_CEILING)).coerceIn(0f, 1f)
    val bare = d < BARE_CEILING
    val open = { if (vm.selecting) vm.select(item) else vm.open(item) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(lerp(8.dp, 16.dp, roomy))).background(if (selected) Palette.White.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (item.directory) Modifier.dropFolder(vm, item.place) else Modifier)
            .clickable { open() }
            .padding(horizontal = lerp(6.dp, 8.dp, roomy), vertical = lerp(1.dp, 8.dp, roomy)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bare) {
            // Bez ikony zostaje sam kolor: folder żółty, plik w kolorze tekstu.
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (item.directory) Palette.Folder else Palette.TextFaint))
            Spacer(Modifier.width(9.dp))
        } else {
            FileGlyph(item, vm, size = lerp(26.dp, 46.dp, roomy), corner = lerp(7.dp, 13.dp, roomy), onClick = open, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.select(item) })
            Spacer(Modifier.width(lerp(9.dp, 13.dp, roomy)))
        }
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = lerp(12.sp, 14.5.sp, roomy), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = if (item.hidden) Palette.TextDim else Palette.White)
            if (roomy > 0.25f) Text(if (showPath) vm.pathOf(item.place.parent() ?: item.place) else describe(item), color = Palette.TextDim, fontSize = lerp(10.sp, 11.5.sp, roomy), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (wide && !showPath) Text(formatDate(item.modified), color = Palette.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        if (vm.selecting) SelectionMark(selected, Modifier.padding(horizontal = 8.dp))
        else if (!bare) RoundIcon(Icons.Rounded.Info, "Właściwości ${item.name}", size = lerp(28.dp, 36.dp, roomy), flat = true, tint = Palette.TextFaint) { inspect(item) }
    }
}

/** Kafelek siatki: bez tła i obrysu – nośnikiem jest sama, duża ikona albo miniatura. */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun FileTile(item: Item, vm: ExplorerModel, cell: androidx.compose.ui.unit.Dp, inspect: (Item) -> Unit) {
    val selected = item.place in vm.selected
    val haptic = LocalHapticFeedback.current
    val big = cell >= 150.dp
    val open = { if (vm.selecting) vm.select(item) else vm.open(item) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (selected) Palette.White.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (item.directory) Modifier.dropFolder(vm, item.place) else Modifier)
            .clickable { open() }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            FileGlyph(item, vm, size = maxWidth, corner = (maxWidth.value * 0.1f).dp, plain = true, onClick = open, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.select(item) })
            // Rozmiar (albo liczba elementów) jako plakietka w prawym górnym rogu; folder bez znanej
            // liczby elementów nie dostaje nic – pusty napis „Folder” tylko zaśmiecał kafelek.
            val badge = if (item.directory) (if (item.children >= 0) "${item.children} elem." else null) else formatSize(item.size)
            if (vm.selecting || selected) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(5.dp))
            else if (badge != null && big) Text(badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(Pill).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 3.dp))
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = if (big) 13.sp else 11.5.sp, fontWeight = FontWeight.Medium, lineHeight = if (big) 16.sp else 14.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = !big))
            if (big) RoundIcon(Icons.Rounded.Info, "Właściwości ${item.name}", size = 28.dp, flat = true, tint = Palette.TextFaint) { inspect(item) }
        }
    }
}

/** Cel upuszczenia przeciąganego pliku: folder, karta, okruszek ścieżki lub pozycja drzewa. */
@Composable fun Modifier.dropFolder(vm: ExplorerModel, place: Place): Modifier {
    var hovered by remember(place) { mutableStateOf(false) }
    val target = remember(place) { object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) { hovered = true }
        override fun onExited(event: DragAndDropEvent) { hovered = false }
        override fun onEnded(event: DragAndDropEvent) { hovered = false; vm.drag = null }
        override fun onDrop(event: DragAndDropEvent): Boolean { hovered = false; val item = vm.drag ?: return false; vm.paste(place, item); vm.drag = null; return true }
    } }
    return this.then(if (hovered) Modifier.border(2.dp, Palette.White, RoundedCornerShape(12.dp)) else Modifier).dragAndDropTarget(shouldStartDragAndDrop = { vm.drag != null && !vm.busy }, target = target)
}
