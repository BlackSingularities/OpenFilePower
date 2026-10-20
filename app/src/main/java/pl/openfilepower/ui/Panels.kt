package pl.openfilepower.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import pl.openfilepower.*

/** Właściwości pliku: podgląd, metadane, EXIF, sumy kontrolne i szybkie akcje. */
@Composable fun InspectorContent(vm: ExplorerModel, close: (() -> Unit)? = null) {
    val details = vm.details
    if (details == null) {
        Column(Modifier.fillMaxWidth().padding(top = 60.dp, start = 8.dp, end = 8.dp)) {
            Icon(Icons.Rounded.TouchApp, null, Modifier.size(44.dp), tint = Palette.TextFaint)
            Spacer(Modifier.height(16.dp))
            Text("Szczegóły pliku", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Dotknij ⓘ przy pliku, aby zobaczyć podgląd, rozmiar folderu, EXIF zdjęcia i sumy kontrolne.", color = Palette.TextDim, fontSize = 13.sp, lineHeight = 18.sp)
        }
        return
    }
    val item = details.item
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
            Icon(iconFor(item), null, Modifier.size(64.dp), tint = Palette.TextDim)
            if (details.preview != null) AsyncImage(details.preview, "Podgląd", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Spacer(Modifier.height(14.dp))
        Text(item.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(if (item.directory) "FOLDER" else item.mime.uppercase(), color = Palette.TextDim, fontSize = 10.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Otwórz", Modifier.weight(1f)) { close?.invoke(); vm.open(item) }
            if (!item.directory) PillButton("Udostępnij", Modifier.weight(1f), primary = false) { vm.share(listOf(item)) }
            else PillButton("Nowa karta", Modifier.weight(1f), primary = false) { close?.invoke(); vm.navigate(item.place, true) }
        }
        Spacer(Modifier.height(12.dp))
        Group(null) {
            if (details.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp), color = Palette.White, trackColor = Color.White.copy(alpha = 0.12f))
            details.values.forEach { (key, value) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(key.uppercase(), color = Palette.TextDim, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    SelectionContainer { Text(value, fontSize = 13.sp, fontFamily = if (key.startsWith("SHA") || key == "MD5") FontFamily.Monospace else FontFamily.Default) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Group("Działania") {
            if (!item.directory) {
                ActionRow(Icons.AutoMirrored.Rounded.OpenInNew, "Otwórz za pomocą…") { vm.openExternal(item, true) }
                ActionRow(Icons.Rounded.Fingerprint, "Oblicz SHA-256", enabled = !vm.busy) { vm.hash(item, "SHA-256") }
                ActionRow(Icons.Rounded.Tag, "Oblicz MD5", enabled = !vm.busy) { vm.hash(item, "MD5") }
            }
            if (FileRules.isZip(item)) ActionRow(Icons.Rounded.Unarchive, "Rozpakuj obok archiwum", enabled = !vm.busy) { close?.invoke(); vm.extract(item) }
            ActionRow(Icons.Rounded.Link, "Kopiuj ścieżkę") { vm.copyPath(item) }
            item.place.parent()?.takeIf { it != vm.place }?.let { parent -> ActionRow(Icons.Rounded.FolderOpen, "Pokaż w folderze") { close?.invoke(); vm.navigate(parent, true) } }
            if (item.directory) ActionRow(Icons.Rounded.SyncAlt, "Synchronizuj z dyskiem…", "NAS, karta SD lub USB") { close?.invoke(); vm.syncDraft = item.place }
            if (item.directory) ActionRow(if (item.place in vm.favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (item.place in vm.favorites) "Usuń z ulubionych" else "Dodaj do ulubionych") { vm.toggleFavorite(item.place) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable fun Inspector(vm: ExplorerModel, modifier: Modifier) {
    Column(modifier.background(Color(0xFF0B0B0B)).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Właściwości", Modifier.padding(top = 12.dp))
        InspectorContent(vm)
    }
}

/** Ustawienia w panelu (jak w OpenPDF): widok, pliki, pamięć, aktualizacje, o aplikacji. */
@Composable fun SettingsPanel(vm: ExplorerModel, close: () -> Unit, grantAccess: () -> Unit, addFolder: () -> Unit, addNetwork: () -> Unit, androidData: () -> Unit) {
    val context = LocalContext.current
    Panel("Ustawienia", close) {
        Group("Widok") {
            CompactChoice(Icons.Rounded.GridView, "Układ", listOf(false, true), vm.grid, { if (it) "Siatka" else "Lista" }, icon = { if (it) Icons.Rounded.GridView else Icons.AutoMirrored.Rounded.ViewList }) { vm.updateGrid(it) }
            SwitchRow(Icons.Rounded.Visibility, "Ukryte pliki", "Pokazuj pliki i foldery zaczynające się od kropki", vm.showHidden) { vm.updateHidden(it) }
            SwitchRow(Icons.Rounded.Folder, "Foldery na początku", null, vm.foldersFirst) { vm.updateFoldersFirst(it) }
            SwitchRow(Icons.Rounded.Image, "Miniatury", "Podgląd zdjęć, RAW-ów i filmów na liście", vm.thumbnails) { vm.updateThumbnails(it) }
            SwitchRow(Icons.Rounded.Compress, "Widok kompaktowy", "Niższe wiersze – więcej plików na ekranie", vm.compact) { vm.updateCompact(it) }
            SwitchRow(Icons.Rounded.FilterList, "Filtry typów", "Pasek Wszystko · Foldery · Zdjęcia · Wideo… nad listą plików i w galerii", vm.showFilters) { vm.updateShowFilters(it) }
            CompactChoice(Icons.Rounded.PhotoSizeSelectLarge, "Rozmiar siatki i galerii", GridSize.entries, vm.gridSize, { it.label }) { vm.updateGridSize(it) }
        }
        Group("Zdjęcia i sesja") {
            SwitchRow(Icons.Rounded.CalendarMonth, "Galeria według miesięcy", "Grupuj oś czasu po miesiącach zamiast po dniach", vm.galleryByMonth) { vm.updateGalleryByMonth(it) }
            ActionRow(Icons.Rounded.PhotoLibrary, "Otwórz galerię", "Oś czasu, albumy, filtry Zdjęcia/RAW/Wideo") { close(); vm.openGallery() }
            InfoRow(Icons.Rounded.Camera, "Obsługiwane RAW", RawFormats.extensions.sorted().joinToString(", ") { it.uppercase() })
        }
        Group("Pasek nawigacji", collapsible = true, initiallyExpanded = false) {
            SwitchRow(Icons.Rounded.Category, "Kategorie w pasku", "Wyłącz, aby ukryć całą sekcję", vm.navCategories.isNotEmpty()) { vm.showAllCategories(it) }
            Category.entries.forEach { c -> SwitchRow(iconFor(c), c.label, null, c.name !in vm.hiddenCategories) { vm.showCategory(c, it) } }
        }
        Group("Sortowanie") {
            Chips(Icons.AutoMirrored.Rounded.Sort, "Sortuj według", Sort.entries, vm.sort, { it.label }) { vm.updateSort(it) }
            SwitchRow(Icons.Rounded.SwapVert, "Malejąco", null, vm.descending) { vm.updateDescending(it) }
        }
        Group("Pliki") {
            SwitchRow(Icons.Rounded.DeleteOutline, "Usuwaj do kosza", "Pamięć wewnętrzna: pliki można przywrócić. Foldery SAF i NAS usuwane są trwale.", vm.useTrash, enabled = vm.access) { vm.updateTrash(it) }
            ActionRow(Icons.Rounded.Delete, "Otwórz kosz") { close(); vm.openTrash() }
        }
        Group("Pamięć i źródła") {
            ActionRow(if (vm.access) Icons.Rounded.VerifiedUser else Icons.Rounded.Security, if (vm.access) "Pełny dostęp do plików: włączony" else "Nadaj pełny dostęp do plików", if (vm.access) "Wszystkie foldery, kategorie, kosz i miejsce na dysku" else "Bez niego widać tylko foldery wybrane ręcznie", onClick = grantAccess)
            ActionRow(Icons.Rounded.CreateNewFolder, "Dodaj folder przez system", "Karta SD, USB lub folder chmury (SAF)") { close(); addFolder() }
            if (android.os.Build.VERSION.SDK_INT in 30..32) ActionRow(Icons.Rounded.Android, "Dostęp do Android/data", "Android 11–12: wybierz „Użyj tego folderu” w oknie systemu") { close(); androidData() }
            ActionRow(Icons.Rounded.Dns, "Połącz z NAS", "SMB 2/3, hasło szyfrowane w Android Keystore") { close(); addNetwork() }
        }
        Group("Aktualizacje") {
            SwitchRow(Icons.Rounded.SystemUpdate, "Sprawdzaj przy uruchomieniu", "GitHub Releases, bez telemetrii", vm.autoUpdate, enabled = BuildConfig.UPDATES_ENABLED) { vm.updateAutoUpdate(it) }
            ActionRow(Icons.Rounded.Refresh, if (vm.updateBusy) "Sprawdzanie…" else "Sprawdź teraz", vm.release?.let { "Dostępna wersja ${it.version}" } ?: "Zainstalowana ${BuildConfig.VERSION_NAME}", enabled = !vm.updateBusy) { vm.checkUpdates() }
            if (vm.release != null) ActionRow(Icons.Rounded.Download, "Pobierz i zainstaluj ${vm.release!!.version}", enabled = !vm.updateBusy) { vm.installUpdate() }
        }
        Group("O aplikacji") {
            InfoRow(Icons.Rounded.Info, "Wersja", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            val cert = vm.certificate
            val official = cert.equals(BuildConfig.RELEASE_CERT_SHA256, true)
            InfoRow(if (official) Icons.Rounded.Verified else Icons.Rounded.Warning, if (official) "Podpis: oficjalne wydanie" else "Podpis: kompilacja lokalna", cert?.chunked(2)?.take(8)?.joinToString(":")?.uppercase()?.let { "$it…" } ?: "nieznany")
            ActionRow(Icons.Rounded.Code, "Kod źródłowy i wydania", "github.com/${BuildConfig.UPDATE_REPO}") { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            InfoRow(Icons.Rounded.Gavel, "Licencja", "MIT · jcifs-ng: LGPL-2.1")
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ——— Okna dialogowe ———

@Composable fun NameDialog(title: String, initial: String, confirm: String = "Zapisz", selectStem: Boolean = true, dismiss: () -> Unit, submit: (String) -> Unit) {
    val stem = if (selectStem) initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stem))) }
    val valid = runCatching { FileRules.validateName(value.text) }.isSuccess
    DarkDialog(title, dismiss, buttons = {
        PillButton("Anuluj", primary = false, onClick = dismiss)
        PillButton(confirm, enabled = valid) { submit(value.text) }
    }) {
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        androidx.compose.material3.OutlinedTextField(
            value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true, label = { Text("Nazwa") }, isError = value.text.isNotEmpty() && !valid,
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha = 0.25f), focusedLabelColor = Color.White, cursorColor = Color.White),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (valid) submit(value.text) }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        )
        if (value.text.isNotEmpty() && !valid) Text("Nazwa nie może zawierać znaków / \\ : * ? \" < > | ani spacji na początku i końcu.", color = Palette.Record, fontSize = 12.sp)
    }
}

@Composable fun DeleteDialog(vm: ExplorerModel, items: List<Item>, dismiss: () -> Unit) {
    val toTrash = vm.trashable(items)
    DarkDialog(if (toTrash) "Przenieść do kosza?" else "Usunąć trwale?", dismiss, buttons = {
        PillButton("Anuluj", primary = false, onClick = dismiss)
        PillButton(if (toTrash) "Do kosza" else "Usuń trwale", danger = !toTrash) { dismiss(); vm.deleteSelected(items) }
    }) {
        Text(items.take(4).joinToString("\n") { "• " + it.name } + if (items.size > 4) "\n… i ${items.size - 4} więcej" else "", fontSize = 13.sp)
        Text(if (toTrash) "Elementy można przywrócić z kosza." else "Pliki zostaną usunięte wraz z zawartością folderów. Tej operacji nie można cofnąć.", color = if (toTrash) Palette.TextDim else Palette.Record, fontSize = 13.sp)
    }
}

@Composable fun ConflictDialog(vm: ExplorerModel, pending: PendingPaste) {
    DarkDialog("Elementy już istnieją", { vm.pendingPaste = null }, buttons = {}) {
        Text("W folderze ${vm.title(pending.destination)} są już elementy o tych nazwach:", color = Palette.TextDim, fontSize = 13.sp)
        Text(pending.conflicts.take(5).joinToString("\n") { (if (it.directory) "📁 " else "• ") + it.name } + if (pending.conflicts.size > 5) "\n… i ${pending.conflicts.size - 5} więcej" else "", fontSize = 13.sp)
        Group(null) {
            ActionRow(Icons.Rounded.ContentCopy, "Zachowaj oba", "Nowe elementy dostaną numer, np. „plik (2).jpg”") { vm.resolvePaste(Conflict.KEEP_BOTH) }
            ActionRow(Icons.Rounded.SkipNext, "Pomiń istniejące", "Skopiowane zostaną tylko elementy bez konfliktu") { vm.resolvePaste(Conflict.SKIP) }
            ActionRow(Icons.Rounded.SwapHoriz, "Zastąp", "Pliki zostaną nadpisane, foldery scalone", danger = true) { vm.resolvePaste(Conflict.REPLACE) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PillButton("Anuluj", primary = false) { vm.pendingPaste = null } }
    }
}

@Composable fun ArchiveDialog(vm: ExplorerModel, state: ArchiveState) {
    DarkDialog(state.item.name, { vm.archive = null }, buttons = {
        PillButton("Zamknij", primary = false) { vm.archive = null }
        PillButton("Rozpakuj", icon = Icons.Rounded.Unarchive, enabled = !vm.busy) { vm.extract(state.item) }
    }) {
        val files = state.entries.filterNot { it.first.endsWith('/') }
        Text("${files.size} plików · ${formatSize(files.sumOf { it.second.coerceAtLeast(0) })} po rozpakowaniu", color = Palette.TextDim, fontSize = 13.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Card).padding(vertical = 4.dp)) {
            state.entries.take(200).forEach { (name, size) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (name.endsWith('/')) Icons.Rounded.Folder else Icons.Rounded.Description, null, Modifier.size(16.dp), tint = Palette.TextDim)
                    Spacer(Modifier.width(8.dp))
                    Text(name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (!name.endsWith('/') && size >= 0) Text(formatSize(size), fontSize = 11.sp, color = Palette.TextDim)
                }
            }
            if (state.entries.size > 200) Text("… i ${state.entries.size - 200} więcej", fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(12.dp))
        }
        Text("Archiwum zostanie rozpakowane do nowego folderu obok pliku.", color = Palette.TextFaint, fontSize = 11.sp)
    }
}

@Composable fun NetworkDialog(dismiss: () -> Unit, submit: (String, String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }; var share by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var domain by remember { mutableStateOf("") }
    DarkDialog("Połącz z NAS", dismiss, buttons = {
        PillButton("Anuluj", primary = false, onClick = dismiss)
        PillButton("Połącz", enabled = host.isNotBlank() && share.isNotBlank()) { submit(name.ifBlank { "$share na $host" }, host, share, user, pass, domain) }
    }) {
        Text("SMB 2 / 3 · urządzenia w sieci lokalnej lub przez VPN", color = Palette.TextDim, fontSize = 12.sp)
        DarkField(host, { host = it.trim() }, "Serwer, np. 192.168.1.10", leading = Icons.Rounded.Dns)
        DarkField(share, { share = it }, "Udział, np. Zdjecia", leading = Icons.Rounded.FolderShared)
        DarkField(user, { user = it }, "Użytkownik", leading = Icons.Rounded.Person)
        DarkField(pass, { pass = it }, "Hasło", password = true, leading = Icons.Rounded.Lock)
        DarkField(domain, { domain = it }, "Domena (opcjonalnie)")
        DarkField(name, { name = it }, "Nazwa połączenia (opcjonalnie)")
        Text("Połączenie zostanie sprawdzone przed zapisaniem. Hasło jest szyfrowane kluczem Android Keystore.", color = Palette.TextFaint, fontSize = 11.sp)
    }
}

@Composable fun ErrorDialog(message: String, dismiss: () -> Unit) {
    DarkDialog("Nie udało się wykonać operacji", dismiss, buttons = { PillButton("Rozumiem", onClick = dismiss) }) {
        SelectionContainer { Text(message, color = Palette.TextDim, fontSize = 13.sp) }
    }
}
