package pl.openfilepower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import pl.openfilepower.*

class HomeActions(val grantAccess: () -> Unit, val addFolder: () -> Unit, val addNetwork: () -> Unit, val settings: () -> Unit, val inspect: (Item) -> Unit)

/** Ekran startowy: zwarty pulpit z karuzelami zamiast długich list. */
@Composable fun HomeScreen(vm: ExplorerModel, medium: Boolean, actions: HomeActions) {
    val side = if (medium) 20.dp else 14.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.padding(horizontal = side), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Palette.White), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FolderOpen, null, tint = Palette.Ink, modifier = Modifier.size(28.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("OpenFilePower", fontSize = if (medium) 26.sp else 22.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    Text("Pliki · zdjęcia · RAW · wideo · NAS", fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundIcon(Icons.Rounded.PhotoLibrary, "Galeria") { vm.openGallery() }
                Spacer(Modifier.width(6.dp))
                RoundIcon(Icons.Rounded.Settings, "Ustawienia", badge = if (vm.release != null) "1" else null, onClick = actions.settings)
            }
        }
        item { Box(Modifier.padding(horizontal = side)) { UpdateBanner(vm) } }
        if (!vm.access) item { Box(Modifier.padding(horizontal = side)) { AccessCard(actions.grantAccess, actions.addFolder) } }

        if (vm.spaces.isNotEmpty()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.spaces, key = { it.first.id }) { (volume, space) ->
                    val fg = if (vm.access) Palette.Ink else Color.White
                    Column(Modifier.then(if (vm.spaces.size == 1) Modifier.fillParentMaxWidth() else Modifier.width(260.dp)).clip(RoundedCornerShape(20.dp)).background(if (vm.access) Palette.White else Palette.PanelHigh)
                        .clickable(enabled = vm.access) { vm.navigate(Place(volume.id), vm.tabs.isNotEmpty()) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (volume.removable) Icons.Rounded.SdCard else Icons.Rounded.Smartphone, null, tint = fg, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(volume.name, color = fg, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text("Wolne ${formatSize(space.free)} z ${formatSize(space.total)}", color = fg.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                            Text("${(space.fraction * 100).toInt()}%", color = fg, fontSize = 17.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().height(5.dp).clip(Pill).background(fg.copy(alpha = 0.12f))) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(space.fraction).clip(Pill).background(if (space.fraction > 0.9f) Palette.Record else fg))
                        }
                    }
                }
            }
        }

        item {
            LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { QuickAction(Icons.Rounded.PhotoLibrary, "Galeria") { vm.openGallery() } }
                item { QuickAction(Icons.Rounded.Camera, "RAW") { vm.galleryFilter = MediaFilter.RAW; vm.openGallery() } }
                item { QuickAction(Icons.Rounded.Dns, "NAS", onClick = actions.addNetwork) }
                item { QuickAction(Icons.Rounded.CreateNewFolder, "Folder", onClick = actions.addFolder) }
                item { QuickAction(Icons.Rounded.DeleteOutline, "Kosz") { vm.openTrash() } }
                if (vm.tabs.isNotEmpty()) item { QuickAction(Icons.Rounded.Tab, "Karty (${vm.tabs.size})") { vm.switch(vm.active) } }
            }
        }

        val photos = vm.recent.filter { FileRules.isMedia(it) }
        if (photos.isNotEmpty()) {
            item { Header("Najnowsze zdjęcia i filmy", side, "Galeria") { vm.openGallery() } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(photos, key = { "p:" + it.place }) { item ->
                        Box(Modifier.size(if (medium) 112.dp else 92.dp).clip(RoundedCornerShape(10.dp)).background(Palette.PanelHigh).clickable { vm.viewer = photos to photos.indexOf(item) }) {
                            AsyncImage(item.imageModel(), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (FileRules.isVideo(item)) Icon(Icons.Rounded.PlayCircle, null, Modifier.align(Alignment.Center).size(26.dp), tint = Color.White)
                            if (FileRules.isRaw(item)) Text("RAW", fontSize = 8.sp, fontWeight = FontWeight.Black, color = Palette.Ink, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(Pill).background(Palette.White).padding(horizontal = 4.dp))
                        }
                    }
                }
            }
        }

        val documents = vm.recent.filterNot { FileRules.isMedia(it) }
        if (documents.isNotEmpty()) {
            item { Header("Ostatnie pliki", side) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(documents, key = { "r:" + it.place }) { RecentCard(vm, it, actions.inspect) }
                }
            }
        }

        val main = vm.mainFolders
        if (main.isNotEmpty()) {
            item { Header("Foldery główne", side) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(main, key = { "m:" + it.first }) { (label, p) ->
                        Column(Modifier.width(84.dp).dropFolder(vm, p).pressable(label) { vm.navigate(p, vm.tabs.isNotEmpty()) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
                                Icon(folderIcon(p.parts.last()), null, Modifier.size(24.dp))
                            }
                            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
        }

        if (vm.access) {
            item { Header("Kategorie", side) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Category.entries, key = { "c:" + it.name }) { c ->
                        val stat = vm.stats.firstOrNull { it.category == c }
                        Column(Modifier.width(118.dp).pressable(c.label) { if (c == Category.RAW) { vm.galleryFilter = MediaFilter.RAW; vm.openGallery() } else vm.openCategory(c) }.clip(RoundedCornerShape(18.dp)).background(Palette.PanelHigh).padding(12.dp)) {
                            Icon(iconFor(c), null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(c.label, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Text(stat?.let { "${it.count} · ${formatSize(it.bytes)}" } ?: "…", fontSize = 10.sp, color = Palette.TextDim, maxLines = 1)
                        }
                    }
                }
            }
        }

        if (vm.recentPlaces.isNotEmpty()) {
            item { Header("Ostatnio odwiedzane", side, "Wyczyść") { vm.clearRecentPlaces() } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(vm.recentPlaces, key = { "v:$it" }) { p -> Chip(vm.title(p), false, icon = Icons.Rounded.History) { vm.navigate(p, vm.tabs.isNotEmpty()) } }
                }
            }
        }
        if (vm.favorites.isNotEmpty()) {
            item { Header("Ulubione", side) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(vm.favorites, key = { "f:$it" }) { p -> Chip(vm.title(p), false, icon = Icons.Rounded.Star) { vm.navigate(p, vm.tabs.isNotEmpty()) } }
                }
            }
        }
        val others = vm.roots.filterNot { it.direct }
        if (others.isNotEmpty()) {
            item { Header("Foldery i serwery", side) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(others, key = { "s:" + it.id }) { root ->
                        Row(Modifier.pressable(root.name) { vm.navigate(Place(root.id), vm.tabs.isNotEmpty()) }.clip(RoundedCornerShape(16.dp)).background(Palette.PanelHigh).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (root.network) Icons.Rounded.Dns else Icons.Rounded.FolderSpecial, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.widthIn(max = 180.dp)) {
                                Text(root.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (root.network) root.address.removePrefix("smb://").trimEnd('/') else "Folder systemowy", fontSize = 10.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        item { Text("BEZ REKLAM · BEZ TELEMETRII · OPEN SOURCE · v${BuildConfig.VERSION_NAME}", color = Palette.TextFaint, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), textAlign = TextAlign.Center) }
    }
}

fun folderIcon(name: String): ImageVector = when (name) {
    "DCIM" -> Icons.Rounded.PhotoCamera; "Pictures" -> Icons.Rounded.Photo; "Download" -> Icons.Rounded.Download; "Documents" -> Icons.Rounded.Description
    "Music" -> Icons.Rounded.LibraryMusic; "Movies" -> Icons.Rounded.VideoLibrary; "Recordings" -> Icons.Rounded.Mic; else -> Icons.Rounded.Android
}

@Composable private fun Header(title: String, side: Dp, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = side + 2.dp, end = side - 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = Palette.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
        if (action != null) Text(action, color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.clip(Pill).clickable(onClick = onAction).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.pressable(label, onClick = onClick).clip(Pill).background(Palette.PanelHigh).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable private fun AccessCard(grant: () -> Unit, folder: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.White).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Security, null, tint = Palette.Ink, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text("Pełny dostęp do plików", color = Palette.Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text("Potrzebny, by pokazać całą pamięć, galerię, RAW-y, kategorie, miejsce na dysku i kosz. Dane nie opuszczają urządzenia.", color = Palette.Ink.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Nadaj dostęp", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.pressable("Nadaj dostęp", onClick = grant).clip(Pill).background(Palette.Ink).padding(horizontal = 18.dp, vertical = 10.dp))
            Text("Tylko wybrany folder", color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.clip(Pill).clickable(onClick = folder).padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RecentCard(vm: ExplorerModel, item: Item, inspect: (Item) -> Unit) {
    Column(Modifier.width(132.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { vm.open(item) }, onLongClick = { inspect(item) }).padding(2.dp)) {
        Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(12.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
            Icon(iconFor(item), null, tint = Palette.TextDim, modifier = Modifier.size(32.dp))
            Text(item.extension.uppercase().ifBlank { "PLIK" }.take(5), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(Pill).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp))
        Text("${formatShortDate(item.modified)} · ${formatSize(item.size)}", color = Palette.TextDim, fontSize = 10.sp, maxLines = 1, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Baner nowej wersji: jednym dotknięciem pobiera i instaluje APK z GitHub Releases. */
@Composable fun UpdateBanner(vm: ExplorerModel) {
    val r = vm.release
    AnimatedVisibility(visible = r != null && !vm.updateBannerHidden) {
        if (r == null) return@AnimatedVisibility
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(vm.updateProgress?.let { "Pobieranie ${r.version} · ${(it * 100).toInt()}%" } ?: "Nowa wersja ${r.version}", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(if (vm.updateBusy) "Nie zamykaj aplikacji" else "Podpis i suma SHA-256 są sprawdzane przed instalacją", color = Palette.TextDim, fontSize = 11.sp)
                }
                if (!vm.updateBusy) Text(if (vm.downloaded != null) "Zainstaluj" else "Aktualizuj", color = Palette.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clip(Pill).background(Color.White).clickable { vm.installUpdate() }.padding(horizontal = 14.dp, vertical = 8.dp))
                if (!vm.updateBusy) Icon(Icons.Rounded.Close, "Później", tint = Palette.TextDim, modifier = Modifier.padding(start = 6.dp).size(32.dp).clip(CircleShape).clickable { vm.updateBannerHidden = true }.padding(6.dp))
            }
            vm.updateProgress?.let { Meter(it, Modifier.fillMaxWidth().padding(top = 10.dp)) }
            if (r.notes.isNotBlank()) Text(r.notes.replace(Regex("[#*`]"), "").trim(), color = Palette.TextDim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 34.dp))
        }
    }
}
