@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package pl.openfilepower

import android.content.ClipData
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.*
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

private val Ink = Color(0xFF10141D)
private val Panel = Color(0xFF171D29)
private val Mint = Color(0xFFA8E6CF)
private val Muted = Color(0xFF929EAF)
private val Lavender = Color(0xFFC3B8F4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink, secondary = Lavender, background = Ink, surface = Panel, surfaceVariant = Color(0xFF242D3E), onSurface = Color(0xFFECF0F7), onSurfaceVariant = Muted)) {
                Surface(Modifier.fillMaxSize(), color = Ink) { Explorer() }
            }
        }
    }
}

@Composable private fun Explorer(vm: ExplorerModel = viewModel()) {
    var sidebar by remember { mutableStateOf(false) }
    var inspector by remember { mutableStateOf(true) }
    var network by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Item?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var phoneDetails by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addLocal) }
    val snacks = remember { SnackbarHostState() }
    LaunchedEffect(vm.notice) { vm.notice?.let { snacks.showSnackbar(it); vm.notice = null } }
    BackHandler(enabled = vm.tabs.getOrNull(vm.active)?.history?.isNotEmpty() == true) { vm.back() }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 1000.dp
        val medium = maxWidth >= 720.dp
        Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snacks) }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (medium) Sidebar(vm, Modifier.width(if (wide) 246.dp else 210.dp), { picker.launch(null) }, { network = true }, { settings = true })
                Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = if (medium) 22.dp else 12.dp)) {
                    Row(Modifier.fillMaxWidth().height(78.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!medium) Action(Icons.Outlined.Menu, "Źródła i foldery") { sidebar = true }
                        Column(Modifier.weight(1f)) {
                            Text("PRZESTRZEŃ ROBOCZA", color = Mint, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                            Text(vm.place?.let(vm::title) ?: "Twoje pliki. Twój porządek.", fontSize = if (medium) 25.sp else 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Action(Icons.Outlined.Refresh, "Odśwież", !vm.loading) { vm.refresh() }
                        Action(Icons.Outlined.Info, "Panel właściwości") { if (wide) inspector = !inspector else phoneDetails = true }
                    }
                    if (vm.tabs.isNotEmpty()) ScrollableTabRow(selectedTabIndex = vm.active, edgePadding = 0.dp, containerColor = Ink, divider = {}) {
                        vm.tabs.forEachIndexed { index, tab ->
                            Tab(selected = index == vm.active, onClick = { vm.switch(index) }, modifier = Modifier.dropFolder(vm, tab.place)) {
                                Row(Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Folder, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp))
                                    Text(vm.title(tab.place), maxLines = 1, modifier = Modifier.widthIn(max = 150.dp))
                                    Action(Icons.Outlined.Close, "Zamknij kartę") { vm.closeTab(index) }
                                }
                            }
                        }
                        Tab(selected = false, onClick = { vm.place?.let { vm.navigate(it, true) } }) { Icon(Icons.Outlined.Add, "Nowa karta", Modifier.padding(14.dp)) }
                    }
                    vm.place?.let { p ->
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Action(Icons.Outlined.ArrowUpward, "Folder nadrzędny", p.parent() != null) { p.parent()?.let { vm.navigate(it) } }
                            (0..p.parts.size).forEach { depth ->
                                val crumb = p.copy(parts = p.parts.take(depth))
                                TextButton(onClick = { vm.navigate(crumb) }, modifier = Modifier.dropFolder(vm, crumb)) { Text(vm.title(crumb), maxLines = 1, color = if (depth == p.parts.size) Mint else Muted) }
                                if (depth < p.parts.size) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(14.dp), tint = Muted)
                            }
                            Action(if (p in vm.favorites) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Przypnij folder") { vm.toggleFavorite(p) }
                        }
                    }
                    if (vm.roots.isNotEmpty() && vm.place != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(vm.query, { vm.query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Szukaj w tym folderze…", fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(14.dp))
                            Action(if (vm.grid) Icons.Outlined.ViewList else Icons.Outlined.GridView, "Zmień widok") { vm.grid = !vm.grid }
                            var menu by remember { mutableStateOf(false) }
                            Box { Action(Icons.Outlined.Sort, "Sortowanie") { menu = true }
                                DropdownMenu(menu, { menu = false }) {
                                    listOf("Nazwa", "Rozmiar", "Data", "Typ").forEach { s -> DropdownMenuItem(text = { Text((if (vm.sort == s) "✓ " else "") + s) }, onClick = { vm.sort = s; menu = false }) }
                                    DropdownMenuItem(text = { Text(if (vm.descending) "✓ Malejąco" else "Rosnąco / malejąco") }, onClick = { vm.descending = !vm.descending })
                                    DropdownMenuItem(text = { Text(if (vm.showHidden) "✓ Ukryte pliki" else "Pokaż ukryte pliki") }, onClick = { vm.showHidden = !vm.showHidden })
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { newFolder = true }, enabled = !vm.busy) { Icon(Icons.Outlined.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Nowy folder") }
                            Action(Icons.Outlined.SelectAll, "Zaznacz wszystko") { vm.selectAll() }
                            if (vm.selected.isNotEmpty()) {
                                Text("${vm.selected.size}", color = Mint)
                                Action(Icons.Outlined.ContentCopy, "Kopiuj") { vm.clip(false) }
                                Action(Icons.Outlined.ContentCut, "Wytnij") { vm.clip(true) }
                                if (vm.selected.size == 1) {
                                    val item = vm.entries.firstOrNull { it.place in vm.selected }
                                    Action(Icons.Outlined.Edit, "Zmień nazwę", !vm.busy) { rename = item }
                                    if (item != null && !item.directory) Action(Icons.Outlined.Share, "Udostępnij", !vm.busy) { vm.open(item, true) }
                                }
                                Action(Icons.Outlined.DeleteOutline, "Usuń", !vm.busy) { deleting = true }
                            }
                            if (vm.clipboard != null) {
                                FilledTonalButton(onClick = { vm.paste() }, enabled = !vm.busy) { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Wklej (${vm.clipboard!!.items.size})") }
                                Action(Icons.Outlined.Close, "Wyczyść schowek") { vm.clearClip() }
                            }
                        }
                    }
                    if (vm.busy) Surface(shape = RoundedCornerShape(12.dp), color = Panel) {
                        Column(Modifier.padding(12.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) { Text(vm.progress, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); if (vm.cancellable) TextButton(onClick = vm::cancel) { Text("Anuluj transfer") } }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                            vm.place == null -> Welcome({ picker.launch(null) }, { network = true })
                            vm.visible.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(64.dp), tint = Muted); Spacer(Modifier.height(16.dp)); Text(if (vm.query.isEmpty()) "Tutaj zaczyna się porządek" else "Brak pasujących plików", fontWeight = FontWeight.Medium); Text(if (vm.query.isEmpty()) "Utwórz folder lub wklej pliki ze schowka." else "Spróbuj innej nazwy.", color = Muted, fontSize = 12.sp) }
                            vm.grid -> LazyVerticalGrid(columns = GridCells.Adaptive(148.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) { items(vm.visible, key = { it.place.toString() }) { FileTile(it, vm) { item -> vm.inspect(item); if (!wide) phoneDetails = true } } }
                            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) { items(vm.visible, key = { it.place.toString() }) { FileRow(it, vm, medium) { item -> vm.inspect(item); if (!wide) phoneDetails = true } } }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${vm.visible.size} elementów · ${formatSize(vm.visible.filterNot { it.directory }.sumOf { it.size })}", color = Muted, fontSize = 11.sp)
                        Text(if (vm.place?.let { p -> vm.roots.find { it.id == p.root }?.network } == true) "SMB 2 / 3" else "LOKALNIE", color = Mint, fontSize = 10.sp, letterSpacing = 1.sp)
                    }
                }
                if (wide && inspector) Inspector(vm, Modifier.width(282.dp).fillMaxHeight())
            }
        }
        if (sidebar && !medium) ModalBottomSheet(onDismissRequest = { sidebar = false }, containerColor = Panel) { Sidebar(vm, Modifier.fillMaxWidth().heightIn(max = 600.dp), { sidebar = false; picker.launch(null) }, { sidebar = false; network = true }, { sidebar = false; settings = true }) }
        if (phoneDetails) ModalBottomSheet(onDismissRequest = { phoneDetails = false }, containerColor = Panel) { Inspector(vm, Modifier.fillMaxWidth().heightIn(max = 650.dp)) }
    }
    if (network) NetworkDialog({ network = false }) { name, host, share, user, pass, domain -> vm.addNetwork(name, host, share, user, pass, domain); network = false }
    if (newFolder) NameDialog("Nowy folder", "", { newFolder = false }) { vm.mkdir(it); newFolder = false }
    rename?.let { item -> NameDialog("Zmień nazwę", item.name, { rename = null }) { vm.rename(item, it); rename = null } }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, icon = { Icon(Icons.Outlined.DeleteOutline, null) }, title = { Text("Usunąć ${vm.selected.size} elementów?") }, text = { Text("Pliki zostaną trwale usunięte, wraz z zawartością zaznaczonych folderów. Tej operacji nie można cofnąć.") }, confirmButton = { TextButton(onClick = { vm.deleteSelected(); deleting = false }) { Text("Usuń trwale", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("Anuluj") } })
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text("OpenFilePower") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Wersja ${BuildConfig.VERSION_NAME} · Android 8+")
            Text("Natywny menedżer plików. Twoje foldery, zdjęcia i serwery w jednej przestrzeni.", color = Muted)
            var auto by remember { mutableStateOf(vm.prefs.autoUpdate) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Sprawdzaj aktualizacje przy uruchomieniu", Modifier.weight(1f)); Switch(auto, { auto = it; vm.prefs.autoUpdate = it }) }
            Text("Przytrzymaj ikonę pliku i przeciągnij na folder, kartę lub pozycję drzewa, aby skopiować. Zaznaczaj pliki polem wyboru. Przenoszenie: Wytnij → Wklej.", fontSize = 13.sp)
            Text("Dostęp do plików nadajesz systemowym wyborem folderu. Hasła NAS są szyfrowane na urządzeniu. Aplikacja nie wysyła telemetrii.", fontSize = 13.sp)
        }
    }, confirmButton = { TextButton(onClick = { vm.checkUpdates(); settings = false }, enabled = !vm.updateBusy) { Text("Sprawdź aktualizacje") } }, dismissButton = { TextButton(onClick = { settings = false }) { Text("Zamknij") } })
    vm.release?.let { release -> AlertDialog(onDismissRequest = { if (!vm.updateBusy) vm.release = null }, title = { Text("Nowa wersja ${release.version}") }, text = { Column { Text(release.notes.take(2000), Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())); Spacer(Modifier.height(12.dp)); Text("APK z GitHub Releases. Sprawdzamy SHA-256 i podpis aplikacji. Instalację potwierdzisz w Androidzie.", fontSize = 12.sp, color = Muted); if (vm.updateBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp)) } }, confirmButton = { TextButton(onClick = vm::installUpdate, enabled = !vm.updateBusy) { Text(if (vm.downloaded == null) "Pobierz i zainstaluj" else "Zainstaluj") } }, dismissButton = { TextButton(onClick = { vm.release = null }, enabled = !vm.updateBusy) { Text("Później") } }) }
    vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("Nie udało się wykonać operacji") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error = null }) { Text("Rozumiem") } }) }
}

@Composable private fun Sidebar(vm: ExplorerModel, modifier: Modifier, addLocal: () -> Unit, addNetwork: () -> Unit, settings: () -> Unit) {
    Column(modifier.fillMaxHeight().background(Panel).padding(horizontal = 16.dp)) {
        Row(Modifier.height(84.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Mint, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.Bolt, null, Modifier.padding(10.dp).size(24.dp), tint = Ink) }
            Spacer(Modifier.width(10.dp)); Column { Text("OpenFile", fontWeight = FontWeight.Bold, fontSize = 20.sp); Text("POWER", color = Mint, fontSize = 10.sp, letterSpacing = 4.sp) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionLabel("ULUBIONE")
            if (vm.favorites.isEmpty()) Text("Przypnij folder gwiazdką przy ścieżce.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp))
            vm.favorites.forEach { p -> Row(Modifier.fillMaxWidth().dropFolder(vm, p), verticalAlignment = Alignment.CenterVertically) { TextButton(onClick = { vm.navigate(p) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.StarBorder, null, Modifier.size(17.dp)); Spacer(Modifier.width(8.dp)); Text(vm.title(p), maxLines = 1, overflow = TextOverflow.Ellipsis) }; Action(Icons.Outlined.Close, "Odepnij") { vm.toggleFavorite(p) } } }
            Spacer(Modifier.height(20.dp)); SectionLabel("FOLDERY I URZĄDZENIA")
            vm.roots.forEach { root ->
                TreeNode(vm, Place(root.id), 0)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = { vm.navigate(Place(root.id), true) }) { Text("Nowa karta", fontSize = 10.sp) }; TextButton(onClick = { vm.removeRoot(root) }, enabled = !vm.busy) { Text("Odłącz", color = Muted, fontSize = 10.sp) } }
            }
            TextButton(onClick = addLocal) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Dodaj folder") }
            Spacer(Modifier.height(20.dp)); SectionLabel("SIEĆ")
            TextButton(onClick = addNetwork) { Icon(Icons.Outlined.Dns, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Połącz z NAS") }
            Surface(Modifier.padding(top = 16.dp), shape = RoundedCornerShape(16.dp), color = Color(0xFF202D34)) { Column(Modifier.padding(16.dp)) { Icon(Icons.Outlined.Layers, null, tint = Mint); Spacer(Modifier.height(8.dp)); Text("Więcej miejsca na działanie", fontSize = 13.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(6.dp)); Text("Foldery w kartach. Pliki tam, gdzie ich potrzebujesz.", color = Muted, fontSize = 12.sp) } }
        }
        TextButton(onClick = settings, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) { Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("Ustawienia i aktualizacje") }
    }
}
@Composable private fun TreeNode(vm: ExplorerModel, p: Place, depth: Int) {
    val expanded = vm.expanded[p] == true
    Row(Modifier.fillMaxWidth().padding(start = (depth * 12).dp).clip(RoundedCornerShape(8.dp)).background(if (vm.place == p) Mint.copy(alpha = .1f) else Color.Transparent).dropFolder(vm, p), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.toggleTree(p) }, modifier = Modifier.size(32.dp)) { Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, "Rozwiń folder", Modifier.size(18.dp), tint = Muted) }
        Row(Modifier.weight(1f).combinedClickable(onClick = { vm.navigate(p) }, onLongClick = { vm.navigate(p, true) }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (vm.roots.find { it.id == p.root }?.network == true && depth == 0) Icons.Outlined.Dns else Icons.Outlined.Folder, null, Modifier.size(18.dp), tint = if (vm.place == p) Mint else Muted); Spacer(Modifier.width(8.dp)); Text(vm.title(p), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    if (expanded && depth < 12) vm.tree[p]?.forEach { TreeNode(vm, it.place, depth + 1) }
}
@Composable private fun SectionLabel(text: String) { Text(text, color = Muted, fontSize = 10.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp)) }
@Composable private fun Action(icon: ImageVector, label: String, enabled: Boolean = true, action: () -> Unit) { TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(), tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) { IconButton(onClick = action, enabled = enabled) { Icon(icon, label, Modifier.size(20.dp)) } } }

private fun iconFor(item: Item): ImageVector = when { item.directory -> Icons.Outlined.Folder; item.mime.startsWith("image/") -> Icons.Outlined.Image; item.mime.startsWith("video/") -> Icons.Outlined.Movie; item.mime.startsWith("audio/") -> Icons.Outlined.MusicNote; item.name.endsWith("zip", true) -> Icons.Outlined.FolderZip; else -> Icons.Outlined.Description }
private fun tintFor(item: Item) = when { item.directory -> Mint; item.mime.startsWith("image/") -> Lavender; item.mime.startsWith("video/") -> Color(0xFFF2B49B); else -> Color(0xFF9ABFEF) }
@Composable private fun FileGlyph(item: Item, vm: ExplorerModel, large: Boolean = false) {
    Box(Modifier.size(if (large) 66.dp else 44.dp).clip(RoundedCornerShape(12.dp)).background(tintFor(item).copy(alpha = .1f)).dragAndDropSource(transferData = { vm.drag = item; DragAndDropTransferData(ClipData.newPlainText("OpenFilePower", item.name)) }), contentAlignment = Alignment.Center) {
        Icon(iconFor(item), "Przytrzymaj, aby przeciągnąć ${item.name}", Modifier.size(if (large) 36.dp else 25.dp), tint = tintFor(item))
        if (item.localUri != null && item.mime.startsWith("image/")) AsyncImage(item.localUri, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}
@Composable private fun FileRow(item: Item, vm: ExplorerModel, medium: Boolean, inspect: (Item) -> Unit) {
    val selected = item.place in vm.selected
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) Mint.copy(alpha = .1f) else Panel.copy(alpha = .55f)).then(if (item.directory) Modifier.dropFolder(vm, item.place) else Modifier).combinedClickable(onClick = { vm.open(item) }, onLongClick = { vm.select(item) }).padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        FileGlyph(item, vm); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(item.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium); Text(if (item.directory) "Folder" else "${item.name.substringAfterLast('.', "plik").uppercase()} · ${formatSize(item.size)}", color = Muted, fontSize = 10.sp) }
        if (medium) Text(formatDate(item.modified), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        Action(Icons.Outlined.Info, "Właściwości ${item.name}") { inspect(item) }
        Checkbox(selected, { vm.select(item) }, modifier = Modifier.size(40.dp))
    }
}
@Composable private fun FileTile(item: Item, vm: ExplorerModel, inspect: (Item) -> Unit) {
    val selected = item.place in vm.selected
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (selected) Color(0xFF263E3B) else Panel).then(if (item.directory) Modifier.dropFolder(vm, item.place) else Modifier).combinedClickable(onClick = { vm.open(item) }, onLongClick = { vm.select(item) }).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { FileGlyph(item, vm, true); Checkbox(selected, { vm.select(item) }, Modifier.size(30.dp)) }
        Spacer(Modifier.height(14.dp)); Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.heightIn(min = 36.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Text(if (item.directory) "Folder" else formatSize(item.size), color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1f)); Action(Icons.Outlined.Info, "Właściwości") { inspect(item) } }
    }
}
@Composable private fun Inspector(vm: ExplorerModel, modifier: Modifier) {
    Column(modifier.background(Panel).padding(20.dp).verticalScroll(rememberScrollState())) {
        SectionLabel("SZCZEGÓŁY PLIKU")
        val details = vm.details
        if (details == null) { Spacer(Modifier.height(60.dp)); Icon(Icons.Outlined.TouchApp, null, Modifier.size(48.dp), tint = Muted); Spacer(Modifier.height(16.dp)); Text("Wszystko, co warto wiedzieć", fontSize = 20.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.height(10.dp)); Text("Wybierz ⓘ przy pliku, aby zobaczyć właściwości, podgląd zdjęcia i metadane EXIF.", color = Muted, fontSize = 13.sp) }
        else {
            Spacer(Modifier.height(16.dp))
            if (details.preview != null) AsyncImage(details.preview, "Podgląd zdjęcia", Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(14.dp)))
            else Box(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF2A3B43), Color(0xFF303044)))), contentAlignment = Alignment.Center) { Icon(iconFor(details.item), null, Modifier.size(64.dp), tint = tintFor(details.item)) }
            Spacer(Modifier.height(20.dp)); Text(details.item.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp)); Text(if (details.item.directory) "FOLDER" else details.item.mime.uppercase(), color = Mint, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(20.dp)); HorizontalDivider(color = Muted.copy(alpha = .15f)); Spacer(Modifier.height(12.dp))
            details.values.forEach { (key, value) -> Column(Modifier.padding(vertical = 7.dp)) { Text(key.uppercase(), color = Muted, fontSize = 9.sp, letterSpacing = 1.sp); Spacer(Modifier.height(3.dp)); androidx.compose.foundation.text.selection.SelectionContainer { Text(value, fontSize = 12.sp) } } }
            if (details.values.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp)); OutlinedButton(onClick = { vm.open(details.item) }, modifier = Modifier.fillMaxWidth()) { Text("Otwórz") }
            if (details.item.directory) TextButton(onClick = { vm.navigate(details.item.place, true) }) { Text("Otwórz w nowej karcie") }
        }
    }
}
@Composable private fun Welcome(local: () -> Unit, network: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 36.dp, horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.FolderOpen, null, Modifier.size(72.dp), tint = Mint)
        Spacer(Modifier.height(24.dp)); Text("Miejsce na\nwszystko Twoje.", fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp)); Text("Pamięć urządzenia i domowy NAS. Wygodne karty, przejrzyste foldery i pełny obraz Twoich plików.", color = Muted, fontSize = 15.sp, lineHeight = 23.sp)
        Spacer(Modifier.height(28.dp)); Button(onClick = local, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Wybierz pierwszy folder") }
        TextButton(onClick = network) { Text("lub połącz serwer NAS"); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp)) }
        Spacer(Modifier.height(30.dp)); Text("BEZ REKLAM  /  BEZ TELEMETRII  /  OPEN SOURCE", color = Mint, fontSize = 9.sp, letterSpacing = 1.sp)
    }
}
@Composable private fun NameDialog(title: String, initial: String, dismiss: () -> Unit, submit: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val valid = runCatching { FileRules.validateName(value) }.isSuccess
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Nazwa") }, isError = value.isNotEmpty() && !valid) }, confirmButton = { TextButton(onClick = { submit(value) }, enabled = valid) { Text("Zapisz") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Anuluj") } })
}
@Composable private fun NetworkDialog(dismiss: () -> Unit, submit: (String, String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }; var share by remember { mutableStateOf("") }; var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var domain by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Połącz z NAS") }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SMB 2 / 3 · urządzenia w sieci lokalnej lub przez VPN", color = Muted, fontSize = 12.sp)
        OutlinedTextField(name, { name = it }, label = { Text("Nazwa połączenia") }, singleLine = true)
        OutlinedTextField(host, { host = it }, label = { Text("Serwer, np. 192.168.1.10") }, singleLine = true)
        OutlinedTextField(share, { share = it }, label = { Text("Udział, np. Zdjecia") }, singleLine = true)
        OutlinedTextField(user, { user = it }, label = { Text("Użytkownik") }, singleLine = true)
        OutlinedTextField(pass, { pass = it }, label = { Text("Hasło") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(domain, { domain = it }, label = { Text("Domena (opcjonalnie)") }, singleLine = true)
        Text("Połączenie zostanie sprawdzone przed zapisaniem. Użyj konta z uprawnieniami do udziału.", color = Muted, fontSize = 11.sp)
    } }, confirmButton = { TextButton(onClick = { submit(name, host, share, user, pass, domain) }, enabled = name.isNotBlank() && host.isNotBlank() && share.isNotBlank()) { Text("Połącz") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Anuluj") } })
}
@Composable private fun Modifier.dropFolder(vm: ExplorerModel, place: Place): Modifier {
    var hovered by remember(place) { mutableStateOf(false) }
    val target = remember(place) { object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) { hovered = true }
        override fun onExited(event: DragAndDropEvent) { hovered = false }
        override fun onEnded(event: DragAndDropEvent) { hovered = false; vm.drag = null }
        override fun onDrop(event: DragAndDropEvent): Boolean { hovered = false; val item = vm.drag ?: return false; vm.paste(place, item); vm.drag = null; return true }
    } }
    return this.then(if (hovered) Modifier.border(2.dp, Mint, RoundedCornerShape(8.dp)) else Modifier).dragAndDropTarget(shouldStartDragAndDrop = { vm.drag != null && !vm.busy }, target = target)
}
