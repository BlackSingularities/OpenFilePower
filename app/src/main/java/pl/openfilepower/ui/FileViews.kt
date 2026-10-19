package pl.openfilepower.ui

import android.content.ClipData
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** Ikona pliku albo miniatura zdjęcia/filmu; przytrzymanie rozpoczyna przeciąganie. */
@Composable fun FileGlyph(item: Item, vm: ExplorerModel, size: androidx.compose.ui.unit.Dp = 44.dp, corner: androidx.compose.ui.unit.Dp = 12.dp, draggable: Boolean = true) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(if (item.directory) Color.White.copy(alpha = 0.1f) else Palette.PanelHigh)
            .then(if (draggable) Modifier.dragAndDropSource(transferData = { _ -> vm.drag = item; DragAndDropTransferData(ClipData.newPlainText("OpenFilePower", item.name)) }) else Modifier)
            .semantics { contentDescription = "Przytrzymaj, aby przeciągnąć ${item.name}" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(iconFor(item), null, Modifier.size(size * 0.52f), tint = if (item.directory) Color.White else Palette.TextDim)
        if (vm.thumbnails && item.localUri != null && (FileRules.isImage(item) || FileRules.isVideo(item))) {
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

/** Wiersz listy: dotknięcie otwiera (w trybie zaznaczania – zaznacza), przytrzymanie zaznacza. */
@OptIn(ExperimentalFoundationApi::class)
@Composable fun FileRow(item: Item, vm: ExplorerModel, wide: Boolean, showPath: Boolean, inspect: (Item) -> Unit) {
    val selected = item.place in vm.selected
    val haptic = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (item.directory) Modifier.dropFolder(vm, item.place) else Modifier)
            .combinedClickable(onClick = { if (vm.selecting) vm.select(item) else vm.open(item) }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.select(item) })
            .padding(horizontal = 8.dp, vertical = if (vm.compact) 3.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileGlyph(item, vm, size = if (vm.compact) 32.dp else 44.dp, corner = if (vm.compact) 8.dp else 12.dp); Spacer(Modifier.width(if (vm.compact) 10.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = if (vm.compact) 13.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = if (item.hidden) Palette.TextDim else Color.White)
            Text(if (showPath) vm.pathOf(item.place.parent() ?: item.place) else describe(item), color = Palette.TextDim, fontSize = if (vm.compact) 10.sp else 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (wide && !showPath) Text(formatDate(item.modified), color = Palette.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        if (vm.selecting) SelectionMark(selected, Modifier.padding(horizontal = 8.dp))
        else RoundIcon(Icons.Rounded.Info, "Właściwości ${item.name}", size = if (vm.compact) 30.dp else 36.dp, flat = true, tint = Palette.TextFaint) { inspect(item) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun FileTile(item: Item, vm: ExplorerModel, inspect: (Item) -> Unit) {
    val selected = item.place in vm.selected
    val haptic = LocalHapticFeedback.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(if (selected) Color.White.copy(alpha = 0.14f) else Palette.Card)
            .then(if (item.directory) Modifier.dropFolder(vm, item.place) else Modifier)
            .combinedClickable(onClick = { if (vm.selecting) vm.select(item) else vm.open(item) }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.select(item) })
            .padding(8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            FileGlyph(item, vm, size = maxWidth, corner = 14.dp)
            if (vm.selecting || selected) SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (item.directory) (if (item.children >= 0) "${item.children} elem." else "Folder") else formatSize(item.size), color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(start = 2.dp))
            RoundIcon(Icons.Rounded.Info, "Właściwości ${item.name}", size = 30.dp, flat = true, tint = Palette.TextFaint) { inspect(item) }
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
