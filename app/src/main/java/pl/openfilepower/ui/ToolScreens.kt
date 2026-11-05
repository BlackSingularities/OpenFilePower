package pl.openfilepower.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.openfilepower.*
import java.io.File

@Composable private fun ScreenHeader(vm: ExplorerModel, medium: Boolean, menu: () -> Unit, title: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!medium || vm.sidebarHidden) RoundIcon(Icons.Rounded.Menu, "Źródła i foldery", onClick = menu)
        RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Wstecz") { vm.back() }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, fontSize = 11.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

// ——— Aplikacje ———

@Composable fun AppsScreen(vm: ExplorerModel, medium: Boolean, menu: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<AppInfo?>(null) }
    val list = vm.apps.filter { (vm.showSystemApps || !it.system) && (filter.isBlank() || it.label.contains(filter, true) || it.packageName.contains(filter, true)) }
    Column(Modifier.fillMaxSize().padding(horizontal = if (medium) 18.dp else 10.dp)) {
        ScreenHeader(vm, medium, menu, "Aplikacje", "${list.size} aplikacji · ${formatSize(list.sumOf { it.apkSize })} plików APK") {
            RoundIcon(Icons.Rounded.Refresh, "Odśwież", flat = true) { vm.refresh() }
        }
        DarkField(filter, { filter = it }, "Szukaj aplikacji", leading = Icons.Rounded.Search, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Pobrane aplikacje", !vm.showSystemApps) { vm.showSystemApps = false }
            Chip("Wszystkie, także systemowe", vm.showSystemApps) { vm.showSystemApps = true }
        }
        if (vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp), color = Palette.White, trackColor = Palette.White.copy(alpha = 0.12f))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(list, key = { it.packageName }) { app ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(app.label) { chosen = app }.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(app.packageName, Modifier.size(42.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${app.version.ifBlank { "—" }} · ${formatSize(app.apkSize)}" + if (app.system) " · systemowa" else "", fontSize = 11.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    RoundIcon(Icons.Rounded.SaveAlt, "Kopia APK ${app.label}", size = 38.dp, flat = true, enabled = !vm.busy) { vm.backupApk(app) }
                }
            }
        }
    }
    chosen?.let { app ->
        DarkDialog(app.label, { chosen = null }, buttons = { PillButton("Zamknij", primary = false) { chosen = null } }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.packageName, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(app.packageName, fontSize = 12.sp, color = Palette.TextDim)
                    Text("Wersja ${app.version} (${app.versionCode})", fontSize = 12.sp, color = Palette.TextDim)
                }
            }
            Group(null) {
                InfoRow(Icons.Rounded.Android, "Rozmiar APK", formatSize(app.apkSize) + if (app.splits > 0) " · ${app.splits + 1} części" else "")
                InfoRow(Icons.Rounded.Event, "Zainstalowano", formatDate(app.installed))
                InfoRow(Icons.Rounded.Update, "Zaktualizowano", formatDate(app.updated))
            }
            Group(null) {
                ActionRow(Icons.Rounded.PlayArrow, "Uruchom") { chosen = null; vm.launchApp(app) }
                ActionRow(Icons.Rounded.SaveAlt, "Zapisz kopię APK", if (app.splits > 0) "Pakiet dzielony – zapis jako .apks" else "Do folderu Pobrane/APK", enabled = !vm.busy) { chosen = null; vm.backupApk(app) }
                ActionRow(Icons.Rounded.Settings, "Informacje o aplikacji", "Ustawienia systemu: uprawnienia, pamięć, wymuś zatrzymanie") { vm.appDetails(app) }
                if (!app.system) ActionRow(Icons.Rounded.Delete, "Odinstaluj", danger = true) { chosen = null; vm.uninstall(app) }
            }
        }
    }
}

/** Ikona aplikacji wczytywana w tle. */
@Composable private fun AppIcon(packageName: String, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) { runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize()) } ?: Icon(Icons.Rounded.Android, null, tint = Palette.TextDim)
    }
}

// ——— Porządki ———

@Composable fun CleanupScreen(vm: ExplorerModel, medium: Boolean, menu: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    // Zaznaczone do usunięcia pliki (ścieżki).
    var marked by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(tab) { marked = emptySet() }
    val duplicates = vm.duplicates
    val junk = vm.junk
    Column(Modifier.fillMaxSize().padding(horizontal = if (medium) 18.dp else 10.dp)) {
        ScreenHeader(vm, medium, menu, "Porządki", if (tab == 0) "Identyczne pliki w pamięci wewnętrznej" else "Pliki tymczasowe, stare APK, puste foldery")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Duplikaty", tab == 0, icon = Icons.Rounded.FileCopy) { tab = 0 }
            Chip("Zbędne pliki", tab == 1, icon = Icons.Rounded.CleaningServices) { tab = 1 }
        }
        Spacer(Modifier.height(8.dp))
        vm.cleanupStatus?.let { status ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.PanelHigh).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status, fontSize = 13.sp, modifier = Modifier.weight(1f))
                PillButton("Stop", primary = false) { vm.stopCleanupScan() }
            }
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(3.dp), color = Palette.White, trackColor = Palette.White.copy(alpha = 0.12f))
        }
        val files: List<File> = if (tab == 0) duplicates?.flatMap { it.files }.orEmpty() else junk?.flatMap { it.files }.orEmpty()
        val selected = files.filter { it.path in marked }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0 && duplicates == null && vm.cleanupStatus == null) StartCard(Icons.Rounded.FileCopy, "Znajdź duplikaty", "Porównuje rozmiar i treść (SHA-256) plików od 1 KB. Pliki w Android/ i ukrytych folderach są pomijane.") { vm.scanDuplicates() }
            else if (tab == 1 && junk == null && vm.cleanupStatus == null) StartCard(Icons.Rounded.CleaningServices, "Znajdź zbędne pliki", "Stare pliki APK, niedokończone pobrania, logi, miniatury galerii i puste foldery. Pamięci podręcznej innych aplikacji Android nie pozwala czyścić – służy do tego „Informacje o aplikacji”.") { vm.scanJunk() }
            else if (files.isEmpty() && vm.cleanupStatus == null) Empty(Icons.Rounded.CheckCircle, "Nic do posprzątania", if (tab == 0) "Nie znaleziono identycznych plików." else "Nie znaleziono zbędnych plików.", Modifier.align(Alignment.Center))
            else LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (tab == 0) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${duplicates.orEmpty().size} grup · do odzyskania ${formatSize(duplicates.orEmpty().sumOf { it.wasted })}", fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.weight(1f))
                            // Zaznacza wszystkie kopie poza pierwszą (najkrótsza ścieżka – zwykle oryginał).
                            PillButton("Zaznacz kopie", primary = false) { marked = duplicates.orEmpty().flatMap { it.files.drop(1) }.map { it.path }.toSet() }
                        }
                    }
                    items(duplicates.orEmpty(), key = { it.hash + it.size }) { g ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(vertical = 6.dp)) {
                            Text("${g.files.size} × ${formatSize(g.size)}", fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                            g.files.forEach { f -> FileCheckRow(f, f.path in marked) { marked = if (it) marked + f.path else marked - f.path } }
                        }
                    }
                } else items(junk.orEmpty(), key = { it.key }) { g ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(vertical = 6.dp)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(g.title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Text("${g.files.size} elem. · ${formatSize(g.size)} · ${g.description}", fontSize = 11.sp, color = Palette.TextDim)
                            }
                            val all = g.files.all { it.path in marked }
                            PillButton(if (all) "Odznacz" else "Zaznacz", primary = false) { marked = if (all) marked - g.files.map { it.path }.toSet() else marked + g.files.map { it.path } }
                        }
                        g.files.take(50).forEach { f -> FileCheckRow(f, f.path in marked) { marked = if (it) marked + f.path else marked - f.path } }
                        if (g.files.size > 50) Text("… i ${g.files.size - 50} więcej (zaznaczane przyciskiem „Zaznacz”)", fontSize = 11.sp, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
            }
            if (selected.isNotEmpty()) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(22.dp)).background(Palette.PanelHigh).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${selected.size} zaznaczonych · ${formatSize(selected.sumOf { if (it.isFile) it.length() else 0L })}", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                PillButton(if (vm.useTrash) "Do kosza" else "Usuń", danger = !vm.useTrash, icon = Icons.Rounded.Delete, enabled = !vm.busy) { vm.cleanFiles(selected); marked = emptySet() }
            }
        }
    }
}

@Composable private fun StartCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, start: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp).clip(RoundedCornerShape(20.dp)).background(Palette.Card).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(40.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(text, fontSize = 12.sp, color = Palette.TextDim, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        PillButton("Szukaj", icon = Icons.Rounded.Search, onClick = start)
    }
}

@Composable private fun FileCheckRow(file: File, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().pressable(file.name) { onChange(!checked) }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Palette.White, checkmarkColor = Palette.Ink, uncheckedColor = Palette.TextDim))
        Column(Modifier.weight(1f)) {
            RawText(file.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(file.parent?.substringAfter("/storage/emulated/0")?.ifBlank { "/" } ?: "", fontSize = 10.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (file.isFile) Text(formatSize(file.length()), fontSize = 11.sp, color = Palette.TextDim, modifier = Modifier.padding(end = 8.dp))
    }
}
