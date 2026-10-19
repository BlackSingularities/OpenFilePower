package pl.openfilepower.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import pl.openfilepower.*

class HomeActions(val grantAccess: () -> Unit, val addFolder: () -> Unit, val addNetwork: () -> Unit, val settings: () -> Unit, val inspect: (Item) -> Unit)

@Composable fun HomeScreen(vm: ExplorerModel, medium: Boolean, actions: HomeActions) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (medium) 160.dp else 140.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        full {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(Palette.White), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FolderOpen, null, tint = Palette.Ink, modifier = Modifier.size(32.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("OpenFilePower", fontSize = if (medium) 30.sp else 26.sp, fontWeight = FontWeight.Black, maxLines = 1)
                    Text("Pamięć, foldery i NAS w jednym miejscu", fontSize = 13.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RoundIcon(Icons.Rounded.Settings, "Ustawienia", badge = if (vm.release != null) "1" else null, onClick = actions.settings)
            }
        }
        full { UpdateBanner(vm) }
        if (!vm.access) full { AccessCard(actions.grantAccess, actions.addFolder) }
        vm.spaces.forEach { (volume, space) ->
            full {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (vm.access) Palette.White else Palette.PanelHigh).clickable(enabled = vm.access) { vm.navigate(Place(volume.id), vm.tabs.isNotEmpty()) }.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    val fg = if (vm.access) Palette.Ink else Color.White
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (volume.removable) Icons.Rounded.SdCard else Icons.Rounded.Smartphone, null, tint = fg, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(volume.name, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Wolne ${formatSize(space.free)} z ${formatSize(space.total)}", color = fg.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        Text("${(space.fraction * 100).toInt()}%", color = fg, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(Pill).background(fg.copy(alpha = 0.12f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(space.fraction).clip(Pill).background(if (space.fraction > 0.9f) Palette.Record else fg))
                    }
                }
            }
        }
        full {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HomeAction(Icons.Rounded.CreateNewFolder, "Dodaj folder", Modifier.weight(1f), onClick = actions.addFolder)
                HomeAction(Icons.Rounded.Dns, "Połącz NAS", Modifier.weight(1f), onClick = actions.addNetwork)
                HomeAction(Icons.Rounded.DeleteOutline, "Kosz", Modifier.weight(1f)) { vm.openTrash() }
            }
        }
        val main = vm.mainFolders
        if (main.isNotEmpty()) {
            full { SectionTitle("Foldery główne") }
            items(main, key = { "main:" + it.first }) { (label, p) ->
                Row(Modifier.dropFolder(vm, p).pressable(label) { vm.navigate(p, vm.tabs.isNotEmpty()) }.clip(RoundedCornerShape(18.dp)).background(Palette.PanelHigh).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (p.parts.last()) { "DCIM" -> Icons.Rounded.PhotoCamera; "Pictures" -> Icons.Rounded.Photo; "Download" -> Icons.Rounded.Download; "Documents" -> Icons.Rounded.Description; "Music" -> Icons.Rounded.LibraryMusic; "Movies" -> Icons.Rounded.VideoLibrary; "Recordings" -> Icons.Rounded.Mic; else -> Icons.Rounded.Android }, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column { Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1); Text(p.parts.last(), fontSize = 11.sp, color = Palette.TextDim, maxLines = 1) }
                }
            }
        }
        if (vm.tabs.isNotEmpty()) full {
            Column {
                SectionTitle("Otwarte karty")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.tabs.forEachIndexed { i, t -> Chip(vm.title(t.place), false, icon = Icons.Rounded.Tab) { vm.switch(i) } }
                }
            }
        }
        if (vm.access) {
            full { SectionTitle("Kategorie") }
            items(Category.entries, key = { it.name }) { c ->
                val stat = vm.stats.firstOrNull { it.category == c }
                Column(Modifier.pressable(c.label) { vm.openCategory(c) }.clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).padding(16.dp)) {
                    Icon(iconFor(c), null, tint = Color.White, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(c.label, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(stat?.let { "${it.count} · ${formatSize(it.bytes)}" } ?: "…", fontSize = 11.sp, color = Palette.TextDim, maxLines = 1)
                }
            }
        }
        if (vm.favorites.isNotEmpty()) full {
            Column {
                SectionTitle("Ulubione")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.favorites.forEach { p -> Chip(vm.title(p), false, icon = Icons.Rounded.Star) { vm.navigate(p, vm.tabs.isNotEmpty()) } }
                }
            }
        }
        val others = vm.roots.filterNot { it.direct }
        if (others.isNotEmpty()) {
            full { SectionTitle("Foldery i serwery") }
            full {
                Group(null) {
                    others.forEach { root ->
                        ActionRow(if (root.network) Icons.Rounded.Dns else Icons.Rounded.Folder, root.name, if (root.network) root.address.removePrefix("smb://").trimEnd('/') else "Folder udostępniony przez system") { vm.navigate(Place(root.id), vm.tabs.isNotEmpty()) }
                    }
                }
            }
        }
        if (vm.recent.isNotEmpty()) {
            full { SectionTitle("Ostatnio zmienione") }
            items(vm.recent, key = { "recent:" + it.place.toString() }) { RecentCard(vm, it, actions.inspect) }
        }
        full { Text("BEZ REKLAM · BEZ TELEMETRII · OPEN SOURCE · v${BuildConfig.VERSION_NAME}", color = Palette.TextFaint, fontSize = 10.sp, letterSpacing = 1.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable private fun AccessCard(grant: () -> Unit, folder: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.White).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Security, null, tint = Palette.Ink, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text("Pełny dostęp do plików", color = Palette.Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Text("Menedżer plików potrzebuje dostępu do pamięci, aby pokazać wszystkie foldery, kategorie, miejsce na dysku i kosz. Dane nie opuszczają urządzenia.", color = Palette.Ink.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Nadaj dostęp", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.pressable("Nadaj dostęp", onClick = grant).clip(Pill).background(Palette.Ink).padding(horizontal = 18.dp, vertical = 10.dp))
            Text("Tylko wybrany folder", color = Palette.Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.clip(Pill).clickable(onClick = folder).padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

@Composable private fun HomeAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.pressable(label, onClick = onClick).clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RecentCard(vm: ExplorerModel, item: Item, inspect: (Item) -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = { vm.open(item) }, onLongClick = { inspect(item) }).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
            Icon(iconFor(item), null, tint = Palette.TextFaint, modifier = Modifier.size(36.dp))
            if (vm.thumbnails && item.localUri != null && (FileRules.isImage(item) || FileRules.isVideo(item))) AsyncImage(item.localUri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Text(item.extension.uppercase().ifBlank { "PLIK" }, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(Pill).background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 7.dp, vertical = 3.dp))
        }
        Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
        Text("${formatShortDate(item.modified)} · ${formatSize(item.size)}", color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp))
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
            if (r.notes.isNotBlank()) Text(r.notes.replace(Regex("[#*`]"), "").trim(), color = Palette.TextDim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 34.dp))
        }
    }
}
