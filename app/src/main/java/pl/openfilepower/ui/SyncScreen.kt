package pl.openfilepower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.openfilepower.*

/** Synchronizacja folderów: pary telefon ↔ dysk, ich stan, podgląd zmian, konflikty i historia. */
@Composable fun SyncScreen(vm: ExplorerModel, medium: Boolean, menu: () -> Unit) {
    val sync = vm.sync
    var editing by remember { mutableStateOf<SyncPair?>(null) }
    var creating by remember { mutableStateOf(false) }
    var conflictsOf by remember { mutableStateOf<SyncPair?>(null) }
    var historyOf by remember { mutableStateOf<SyncPair?>(null) }
    var removing by remember { mutableStateOf<SyncPair?>(null) }
    val side = if (medium) 20.dp else 12.dp

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 8.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!medium || vm.sidebarHidden) RoundIcon(Icons.Rounded.Menu, "Źródła i foldery", onClick = menu)
                    RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Wstecz") { vm.back() }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text("Synchronizacja", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Telefon ↔ NAS, karta SD, USB", fontSize = 11.sp, color = Palette.TextDim)
                    }
                    if (sync.pairs.isNotEmpty()) RoundIcon(Icons.Rounded.Sync, "Synchronizuj wszystkie", enabled = sync.running.isEmpty()) { sync.syncAll() }
                }
            }
            if (sync.pairs.isEmpty()) item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.PanelHigh).padding(18.dp)) {
                    Icon(Icons.Rounded.SyncAlt, null, Modifier.size(32.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Wskaż folder na telefonie i na dysku", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Pliki zawsze zostają na telefonie i działają bez dysku. Gdy NAS lub nośnik jest dostępny, zmiany z obu stron są wyrównywane; konflikty nie giną – możesz je rozstrzygnąć albo zachować obie wersje.", color = Palette.TextDim, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            items(sync.pairs, key = { it.id }) { pair ->
                PairCard(vm, pair, { editing = pair }, { conflictsOf = pair }, { historyOf = pair }, { removing = pair })
            }
        }
        PillButton("Nowa para folderów", Modifier.align(Alignment.BottomEnd).padding(side), icon = Icons.Rounded.Add) { creating = true }
    }

    if (creating) PairEditor(vm, null, { creating = false })
    editing?.let { PairEditor(vm, it, { editing = null }) }
    conflictsOf?.let { p -> sync.pairs.firstOrNull { it.id == p.id }?.let { ConflictsPanel(vm, it) { conflictsOf = null } } }
    historyOf?.let { HistoryPanel(vm, it) { historyOf = null } }
    sync.preview?.let { (pair, preview) -> PreviewPanel(vm, pair, preview) }
    removing?.let { p ->
        DarkDialog("Usunąć parę „${p.name}”?", { removing = null }, buttons = {
            PillButton("Anuluj", primary = false) { removing = null }
            PillButton("Usuń parę", danger = true) { sync.remove(p); removing = null }
        }) { Text("Synchronizacja przestanie działać. Pliki na telefonie i na dysku pozostaną nietknięte.", color = Palette.TextDim, fontSize = 13.sp) }
    }
}

@Composable private fun PairCard(vm: ExplorerModel, pair: SyncPair, edit: () -> Unit, conflicts: () -> Unit, history: () -> Unit, remove: () -> Unit) {
    val sync = vm.sync
    val state = sync.states[pair.id] ?: SyncState()
    val progress = sync.running[pair.id]
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.PanelHigh).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(when (pair.mode) { SyncMode.TWO_WAY -> Icons.Rounded.SyncAlt; SyncMode.UPLOAD -> Icons.Rounded.CloudUpload; SyncMode.DOWNLOAD -> Icons.Rounded.CloudDownload }, null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(pair.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pair.mode.label + " · konflikty: " + pair.policy.label.lowercase() + if (pair.auto) " · auto" else "", fontSize = 11.sp, color = Palette.TextDim, maxLines = 1)
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                RoundIcon(Icons.Rounded.MoreVert, "Więcej", size = 36.dp, flat = true) { menu = true }
                DarkMenu(menu, { menu = false }) {
                    MenuRow(Icons.Rounded.Edit, "Ustawienia pary") { menu = false; edit() }
                    MenuRow(Icons.Rounded.Preview, "Podgląd zmian") { menu = false; sync.showPreview(pair) }
                    MenuRow(Icons.Rounded.History, "Historia") { menu = false; history() }
                    MenuRow(Icons.Rounded.Smartphone, "Otwórz folder na telefonie") { menu = false; vm.openShortcut(pair.local) }
                    MenuRow(Icons.Rounded.Dns, "Otwórz folder na dysku") { menu = false; vm.openShortcut(pair.remote) }
                    MenuRow(Icons.Rounded.Tab, "Otwórz oba foldery w kartach") { menu = false; vm.openShortcut(pair.local); vm.openShortcut(pair.remote) }
                    MenuRow(Icons.Rounded.DeleteOutline, "Usuń parę", danger = true) { menu = false; remove() }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Endpoint(Icons.Rounded.Smartphone, vm.pathOf(pair.local)) { vm.openShortcut(pair.local) }
        Endpoint(if (vm.rootOf(pair.remote)?.network == true) Icons.Rounded.Dns else Icons.Rounded.Usb, vm.pathOf(pair.remote)) { vm.openShortcut(pair.remote) }
        Spacer(Modifier.height(10.dp))
        when {
            progress != null -> {
                Text(progress.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Meter(progress.fraction ?: 0f, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            state.guard != null -> {
                Status(Icons.Rounded.Warning, state.guard, Palette.Record)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Podgląd", primary = false) { sync.showPreview(pair) }
                    PillButton("Kontynuuj mimo to", danger = true) { sync.confirmGuard(pair) }
                    PillButton("Wstrzymaj", primary = false) { sync.dismissGuard(pair) }
                }
            }
            state.available == false -> {
                Status(Icons.Rounded.CloudOff, "Dysk niedostępny – pliki na telefonie działają normalnie")
                state.pendingLocal?.takeIf { it.total > 0 }?.let { Status(Icons.Rounded.PendingActions, "Czeka na wysłanie: ${it.describe()}") }
            }
            else -> {
                val last = state.history.firstOrNull()
                Status(if (state.message == null) Icons.Rounded.CloudDone else Icons.Rounded.ErrorOutline,
                    if (state.lastSuccess > 0) "Zsynchronizowano ${formatShortDate(state.lastSuccess)}" + (last?.let { " · ${it.summary()}" } ?: "") else "Jeszcze nie synchronizowano – zacznij od podglądu zmian")
                state.message?.let { Status(Icons.Rounded.Info, it, Palette.TextDim) }
            }
        }
        if (state.conflicts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.White).clickable(onClick = conflicts).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CallSplit, null, Modifier.size(18.dp), tint = Palette.Ink)
                Spacer(Modifier.width(8.dp))
                Text("Konflikty do rozstrzygnięcia: ${state.conflicts.size}", color = Palette.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ChevronRight, null, tint = Palette.Ink)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (progress != null) PillButton("Przerwij", primary = false, icon = Icons.Rounded.Stop) { sync.cancel(pair) }
            else PillButton("Synchronizuj", icon = Icons.Rounded.Sync) { sync.sync(pair) }
            PillButton(if (sync.previewBusy == pair.id) "Porównuję…" else "Podgląd", primary = false, enabled = progress == null && sync.previewBusy == null, icon = Icons.Rounded.Preview) { sync.showPreview(pair) }
        }
    }
}

/** Jedna strona pary – dotknięcie otwiera ten folder w karcie. */
@Composable private fun Endpoint(icon: ImageVector, text: String, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(Pill).clickable(onClick = open).padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = Palette.TextDim)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Otwórz $text", Modifier.size(14.dp), tint = Palette.TextFaint)
    }
}
@Composable private fun Status(icon: ImageVector, text: String, color: Color = Palette.White) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(16.dp).padding(top = 1.dp), tint = color)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = color, lineHeight = 16.sp)
    }
}

/** Tworzenie i edycja pary: foldery, kierunek, zasada konfliktów, usuwanie, wykluczenia. */
@Composable fun PairEditor(vm: ExplorerModel, existing: SyncPair?, close: () -> Unit, initialLocal: Place? = null) {
    var local by remember { mutableStateOf(existing?.local ?: initialLocal) }
    var remote by remember { mutableStateOf(existing?.remote) }
    var name by remember { mutableStateOf(existing?.name ?: initialLocal?.let(vm::title) ?: "") }
    var mode by remember { mutableStateOf(existing?.mode ?: SyncMode.TWO_WAY) }
    var policy by remember { mutableStateOf(existing?.policy ?: ConflictPolicy.ASK) }
    var deletions by remember { mutableStateOf(existing?.deletions ?: true) }
    var auto by remember { mutableStateOf(existing?.auto ?: true) }
    var hidden by remember { mutableStateOf(existing?.hidden ?: false) }
    var trash by remember { mutableStateOf(existing?.trash ?: true) }
    var excludes by remember { mutableStateOf(existing?.excludes ?: SyncFilter.DEFAULT) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true – telefon, false – dysk

    Panel(if (existing == null) "Nowa para folderów" else "Ustawienia pary", close) {
        Group("Foldery") {
            ActionRow(Icons.Rounded.Smartphone, "Na telefonie", local?.let(vm::pathOf) ?: "Wybierz folder") { picking = true }
            ActionRow(Icons.Rounded.Dns, "Na dysku (NAS, SD, USB)", remote?.let(vm::pathOf) ?: "Wybierz folder") { picking = false }
            Box(Modifier.padding(12.dp)) { DarkField(name, { name = it }, "Nazwa pary") }
        }
        Group("Kierunek") {
            SyncMode.entries.forEach { m ->
                ActionRow(when (m) { SyncMode.TWO_WAY -> Icons.Rounded.SyncAlt; SyncMode.UPLOAD -> Icons.Rounded.CloudUpload; SyncMode.DOWNLOAD -> Icons.Rounded.CloudDownload }, m.label, m.hint,
                    trailing = { if (mode == m) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp)) }) { mode = m }
            }
        }
        Group("Konflikty – gdy plik zmieniono po obu stronach") {
            ConflictPolicy.entries.forEach { c ->
                ActionRow(Icons.Rounded.CallSplit, c.label, c.hint, trailing = { if (policy == c) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp)) }) { policy = c }
            }
        }
        Group("Zachowanie") {
            SwitchRow(Icons.Rounded.Autorenew, "Automatycznie", "Przy otwarciu aplikacji, po połączeniu z siecią i w tle co ~15 min", auto) { auto = it }
            SwitchRow(Icons.Rounded.DeleteSweep, "Przenoś usuwanie", "Plik usunięty po jednej stronie znika też z drugiej", deletions) { deletions = it }
            SwitchRow(Icons.Rounded.RestoreFromTrash, "Kosz dla plików z telefonu", "Nadpisane i usunięte przez synchronizację pliki z pamięci telefonu trafiają do kosza", trash) { trash = it }
            SwitchRow(Icons.Rounded.Visibility, "Ukryte pliki", "Synchronizuj pliki i foldery zaczynające się od kropki", hidden) { hidden = it }
            Box(Modifier.padding(12.dp)) { DarkField(excludes, { excludes = it }, "Pomijaj (wzorce, np. *.tmp, Thumbs.db)") }
        }
        Text("Pierwsza synchronizacja porównuje pliki o tej samej wielkości po treści – nic się nie zduplikuje, a różniące się pliki staną się konfliktami.", color = Palette.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PillButton("Anuluj", primary = false, onClick = close)
            PillButton("Zapisz", enabled = local != null && remote != null && name.isNotBlank(), icon = Icons.Rounded.Check) {
                runCatching {
                    vm.sync.save((existing ?: SyncPair(name = name, local = local!!, remote = remote!!)).copy(name = name.trim(), local = local!!, remote = remote!!, mode = mode, policy = policy, deletions = deletions, auto = auto, hidden = hidden, excludes = excludes, trash = trash))
                }.onSuccess { close() }.onFailure { vm.error = it.message }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    picking?.let { phone ->
        FolderPicker(vm, if (phone) "Folder na telefonie" else "Folder na dysku", if (phone) local else remote, phone, { picking = null }) { p ->
            if (phone) { local = p; if (name.isBlank()) name = vm.title(p) } else remote = p
            picking = null
        }
    }
}

/** Wybór folderu spośród wszystkich źródeł (pamięć, SAF, NAS), z tworzeniem nowego folderu. */
@Composable private fun FolderPicker(vm: ExplorerModel, title: String, initial: Place?, phone: Boolean, close: () -> Unit, pick: (Place) -> Unit) {
    var current by remember { mutableStateOf(initial) }
    var folders by remember { mutableStateOf<List<Item>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(current, reload) {
        folders = null; failure = null
        val p = current ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { vm.files.list(p).filter { it.directory && !it.hidden }.sortedWith { a, b -> naturalCompare(a.name, b.name) } } }
            .onSuccess { folders = it }.onFailure { failure = it.message }
    }
    Panel(title, close) {
        val p = current
        if (p == null) {
            val roots = vm.roots.sortedBy { if (phone == it.direct) 0 else 1 }
            Group("Źródła") {
                roots.forEach { r -> ActionRow(when { r.network -> Icons.Rounded.Dns; r.direct -> Icons.Rounded.Smartphone; else -> Icons.Rounded.FolderSpecial }, r.name, if (r.network) r.address.removePrefix("smb://").trimEnd('/') else null) { current = Place(r.id) } }
            }
            Text("Brakuje dysku? Dodaj NAS lub folder USB w panelu bocznym.", color = Palette.TextFaint, fontSize = 11.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "W górę", size = 36.dp) { current = p.parent() }
                Spacer(Modifier.width(10.dp))
                Text(vm.pathOf(p), fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Group(null) {
                when {
                    failure != null -> Text(failure!!, color = Palette.Record, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                    folders == null -> CircularProgressIndicator(Modifier.padding(16.dp).size(22.dp), color = Palette.White, strokeWidth = 2.dp)
                    folders!!.isEmpty() -> Text("Brak podfolderów", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                    else -> folders!!.forEach { f -> ActionRow(Icons.Rounded.Folder, f.name) { current = f.place } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PillButton("Nowy folder", primary = false, icon = Icons.Rounded.CreateNewFolder) { creating = true }
                PillButton("Wybierz ten folder", icon = Icons.Rounded.Check) { pick(p) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    if (creating) NameDialog("Nowy folder", "Synchronizacja", "Utwórz", selectStem = false, dismiss = { creating = false }) { n ->
        creating = false
        val p = current ?: return@NameDialog
        vm.viewModelRun { withContext(Dispatchers.IO) { vm.files.mkdir(p, n) }; current = p.child(n); reload++ }
    }
}

/** Co zrobi synchronizacja – zanim cokolwiek zostanie zmienione. */
@Composable private fun PreviewPanel(vm: ExplorerModel, pair: SyncPair, preview: SyncPreview) {
    val sync = vm.sync
    val actions = preview.actions
    Panel("Podgląd: ${pair.name}", { sync.preview = null }) {
        Group("Od ostatniej synchronizacji") {
            InfoRow(Icons.Rounded.Smartphone, "Na telefonie", preview.local.describe())
            InfoRow(Icons.Rounded.Dns, "Na dysku", preview.remote.describe())
        }
        val up = actions.filterIsInstance<SyncAction.Copy>().filter { it.from == SyncSideId.LOCAL }
        val down = actions.filterIsInstance<SyncAction.Copy>().filter { it.from == SyncSideId.REMOTE }
        val delRemote = actions.filterIsInstance<SyncAction.Delete>().filter { it.on == SyncSideId.REMOTE }
        val delLocal = actions.filterIsInstance<SyncAction.Delete>().filter { it.on == SyncSideId.LOCAL }
        val dirs = actions.filterIsInstance<SyncAction.Mkdir>()
        val both = actions.filterIsInstance<SyncAction.KeepBoth>()
        if (actions.isEmpty() && preview.conflicts.isEmpty()) Text("Wszystko aktualne – nic do zrobienia.", fontSize = 14.sp, modifier = Modifier.padding(8.dp))
        PlanGroup("Wyśle na dysk", Icons.Rounded.Upload, up.map { it.path + if (it.replacing != null) " (aktualizacja)" else "" }, up.sumOf { it.entry.size })
        PlanGroup("Pobierze na telefon", Icons.Rounded.Download, down.map { it.path + if (it.replacing != null) " (aktualizacja)" else "" }, down.sumOf { it.entry.size })
        PlanGroup("Usunie z dysku", Icons.Rounded.DeleteOutline, delRemote.map { it.path })
        PlanGroup("Usunie z telefonu" + if (pair.trash) " (do kosza)" else "", Icons.Rounded.DeleteOutline, delLocal.map { it.path })
        PlanGroup("Utworzy foldery", Icons.Rounded.CreateNewFolder, dirs.map { it.path + " → " + it.on.label })
        PlanGroup("Zachowa obie wersje", Icons.Rounded.CallSplit, both.map { it.path })
        PlanGroup("Konflikty do decyzji", Icons.Rounded.Warning, preview.conflicts.map { it.path + " – " + it.kind.label.lowercase() })
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PillButton("Zamknij", primary = false) { sync.preview = null }
            PillButton("Synchronizuj", enabled = actions.isNotEmpty() || preview.conflicts.isNotEmpty(), icon = Icons.Rounded.Sync) { sync.sync(pair, force = sync.states[pair.id]?.guard != null) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun PlanGroup(title: String, icon: ImageVector, paths: List<String>, bytes: Long = -1) {
    if (paths.isEmpty()) return
    Group("$title · ${paths.size}" + if (bytes > 0) " · ${formatSize(bytes)}" else "", collapsible = true, initiallyExpanded = paths.size <= 8) {
        paths.take(300).forEach { p ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(15.dp), tint = Palette.TextDim)
                Spacer(Modifier.width(8.dp))
                Text(p, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (paths.size > 300) Text("… i ${paths.size - 300} więcej", fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(12.dp))
    }
}

/** Konflikty: obie wersje obok siebie, wybór dla każdego pliku albo dla wszystkich naraz. */
@Composable private fun ConflictsPanel(vm: ExplorerModel, pair: SyncPair, close: () -> Unit) {
    val conflicts = vm.sync.states[pair.id]?.conflicts.orEmpty()
    val choices = remember { mutableStateMapOf<String, Resolution>() }
    Panel("Konflikty: ${pair.name}", close) {
        if (conflicts.isEmpty()) Text("Brak konfliktów.", fontSize = 14.sp, modifier = Modifier.padding(8.dp))
        else {
            Text("Wybierz, która wersja zostaje. „Zachowaj obie” zapisze starszą jako „nazwa (konflikt – …)” po obu stronach.", color = Palette.TextDim, fontSize = 12.sp, lineHeight = 16.sp)
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Wszystkie:", fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.align(Alignment.CenterVertically))
                Resolution.entries.forEach { r -> Chip(r.label.removePrefix("Zachowaj "), conflicts.all { choices[it.path] == r }) { conflicts.forEach { c -> choices[c.path] = if (c.kind == ConflictKind.TYPE) Resolution.BOTH else r } } }
            }
            conflicts.forEach { c ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Card).padding(12.dp)) {
                    Text(c.path, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(c.kind.label, fontSize = 11.sp, color = Palette.TextDim)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Version(Modifier.weight(1f), Icons.Rounded.Smartphone, "Telefon", c.local, c.remote)
                        Version(Modifier.weight(1f), Icons.Rounded.Dns, "Dysk", c.remote, c.local)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (if (c.kind == ConflictKind.TYPE) listOf(Resolution.BOTH) else Resolution.entries).forEach { r -> Chip(r.label.removePrefix("Zachowaj "), choices[c.path] == r) { choices[c.path] = r } }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                PillButton("Później", primary = false, onClick = close)
                PillButton("Zastosuj (${choices.size})", enabled = choices.isNotEmpty() && vm.sync.running[pair.id] == null, icon = Icons.Rounded.Check) { vm.sync.resolve(pair, choices.toMap()); close() }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun Version(modifier: Modifier, icon: ImageVector, label: String, e: SyncEntry?, other: SyncEntry?) {
    val newer = e != null && other != null && !e.directory && e.modified > other.modified + SyncPlanner.TOLERANCE
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(if (newer) Palette.White.copy(alpha = 0.14f) else Palette.White.copy(alpha = 0.05f)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(14.dp), tint = Palette.TextDim)
            Spacer(Modifier.width(6.dp))
            Text(label.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Palette.TextDim)
            if (newer) Text("  NOWSZA", fontSize = 9.sp, fontWeight = FontWeight.Black)
        }
        when {
            e == null -> Text("usunięty", fontSize = 12.sp)
            e.directory -> Text("folder", fontSize = 12.sp)
            else -> { Text(formatSize(e.size), fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(formatDate(e.modified), fontSize = 11.sp, color = Palette.TextDim) }
        }
    }
}

@Composable private fun HistoryPanel(vm: ExplorerModel, pair: SyncPair, close: () -> Unit) {
    val history = vm.sync.states[pair.id]?.history.orEmpty()
    Panel("Historia: ${pair.name}", close) {
        if (history.isEmpty()) Text("Jeszcze nie synchronizowano.", fontSize = 14.sp, modifier = Modifier.padding(8.dp))
        Group(null) {
            history.forEach { r ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (r.failure != null || r.errors.isNotEmpty()) Icons.Rounded.ErrorOutline else Icons.Rounded.CloudDone, null, Modifier.size(16.dp), tint = if (r.failure != null) Palette.Record else Palette.White)
                        Spacer(Modifier.width(8.dp))
                        Text(formatDate(r.time), fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(r.trigger + if (r.duration > 0) " · ${r.duration / 1000} s" else "", fontSize = 11.sp, color = Palette.TextDim)
                    }
                    Text(r.summary() + if (r.bytes > 0) " · ${formatSize(r.bytes)}" else "", fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(start = 24.dp))
                    r.errors.take(5).forEach { Text("• $it", fontSize = 11.sp, color = Palette.Record, modifier = Modifier.padding(start = 24.dp)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Baner na ekranie startowym: co zsynchronizowało się od ostatniej wizyty i ile konfliktów czeka. */
@Composable fun SyncBanner(vm: ExplorerModel) {
    val sync = vm.sync
    val runs = sync.unseen
    val conflicts = sync.conflictCount
    if (runs.isEmpty() && conflicts == 0) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).clickable { vm.openSync() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (conflicts > 0) Icons.Rounded.CallSplit else Icons.Rounded.CloudDone, null, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (conflicts > 0) "Synchronizacja: konflikty $conflicts" else "Zsynchronizowano od ostatniej wizyty", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (runs.isNotEmpty()) {
                val total = SyncRun(0, 0, "", runs.sumOf { it.second.uploaded }, runs.sumOf { it.second.downloaded }, runs.sumOf { it.second.deletedLocal }, runs.sumOf { it.second.deletedRemote }, runs.sumOf { it.second.folders })
                Text(total.summary() + " · " + runs.map { it.first.name }.distinct().joinToString(", "), fontSize = 11.sp, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.TextDim)
    }
}
