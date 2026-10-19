package pl.openfilepower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.openfilepower.*

/** Działania wywoływane z ekranu przeglądania, a obsługiwane w [AppRoot] (okna dialogowe, panele). */
class BrowserActions(
    val menu: () -> Unit,
    val inspect: (Item) -> Unit,
    val newFolder: () -> Unit,
    val newFile: () -> Unit,
    val rename: (Item) -> Unit,
    val delete: () -> Unit,
    val compress: () -> Unit,
    val settings: () -> Unit,
    val batchRename: () -> Unit = {},
    val analyze: () -> Unit = {},
)

@Composable fun BrowserScreen(vm: ExplorerModel, medium: Boolean, wide: Boolean, actions: BrowserActions) {
    val category = (vm.view as? View.Category)?.category
    var searchOpen by remember { mutableStateOf(false) }
    LaunchedEffect(vm.place, vm.view) { if (vm.query.isEmpty() && vm.searchResults == null) searchOpen = false }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = if (medium) 18.dp else 10.dp)) {
            // Karty na samej górze; nazwa miejsca jest tylko w karcie, nagłówek pokazuje ścieżkę nadrzędną.
            if (category == null) Tabs(vm)
            if (vm.selecting) SelectionBar(vm)
            else TopBar(vm, medium, category, searchOpen, { searchOpen = it }, actions)
            if (searchOpen && !vm.selecting) SearchField(vm, category == null)
            if (category == null && vm.live) LiveBanner(vm)
            if (vm.showFilters) TypeFilters(vm)
            InfoLine(vm, category)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val items = vm.visible
                val showPath = category != null || vm.searchResults != null
                when {
                    vm.loading && items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Palette.White)
                    items.isEmpty() && vm.searching -> Empty(Icons.Rounded.Search, "Szukam…", "Przeszukuję podfoldery ${vm.place?.let(vm::title) ?: ""}", Modifier.align(Alignment.Center))
                    items.isEmpty() -> Empty(
                        if (category != null) iconFor(category) else Icons.Rounded.FolderOpen,
                        when { vm.searchResults != null || vm.query.isNotEmpty() -> "Brak pasujących plików"; category != null -> "Brak plików w tej kategorii"; else -> "Pusty folder" },
                        when { vm.searchResults != null || vm.query.isNotEmpty() -> "Spróbuj innej nazwy lub użyj * jako dowolnego ciągu."; category != null -> "Pliki pojawią się, gdy system je zindeksuje."; else -> "Utwórz folder przyciskiem + lub wklej pliki ze schowka." },
                        Modifier.align(Alignment.Center),
                    )
                    vm.grid -> LazyVerticalGrid(columns = GridCells.Adaptive((if (medium) vm.gridSize.tablet else vm.gridSize.phone).dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 160.dp)) {
                        items(items, key = { it.place.toString() }) { FileTile(it, vm, actions.inspect) }
                    }
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(items, key = { it.place.toString() }) { FileRow(it, vm, wide, showPath, actions.inspect) }
                    }
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = if (medium) 18.dp else 10.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            if (category == null && !vm.selecting && vm.clipboard == null && vm.searchResults == null) AddButton(actions)
            vm.progress?.let { ProgressCard(vm, it) }
            AnimatedVisibility(vm.clipboard != null && !vm.selecting && category == null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) { PasteBanner(vm) }
            AnimatedVisibility(vm.selecting, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) { SelectionDock(vm, actions, category == null && vm.searchResults == null) }
        }
    }
}

@Composable private fun TopBar(vm: ExplorerModel, medium: Boolean, category: Category?, searchOpen: Boolean, setSearch: (Boolean) -> Unit, actions: BrowserActions) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!medium) RoundIcon(Icons.Rounded.Menu, "Źródła i foldery", size = 40.dp, onClick = actions.menu)
        RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Wstecz", size = 40.dp) { vm.back() }
        Box(Modifier.weight(1f)) {
            if (category != null) Text(category.label, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
            else vm.place?.let { Breadcrumbs(vm, it) }
        }
        if (category == null && vm.place != null && medium) RoundIcon(Icons.Rounded.Sensors, if (vm.live) "Zatrzymaj tryb sesji" else "Tryb sesji na żywo", active = vm.live, size = 40.dp) { vm.toggleLive() }
        RoundIcon(Icons.Rounded.Search, "Szukaj", active = searchOpen, size = 40.dp) {
            if (searchOpen) { vm.query = ""; if (vm.searchResults != null || vm.searching) vm.back() }
            setSearch(!searchOpen)
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            RoundIcon(Icons.Rounded.MoreVert, "Więcej", size = 40.dp) { menu = true }
            DarkMenu(menu, { menu = false }) {
                if (category == null) {
                    MenuRow(Icons.Rounded.CreateNewFolder, "Nowy folder") { menu = false; actions.newFolder() }
                    MenuRow(Icons.Rounded.NoteAdd, "Nowy plik tekstowy") { menu = false; actions.newFile() }
                }
                MenuRow(Icons.Rounded.SelectAll, "Zaznacz wszystko") { menu = false; vm.selectAll() }
                MenuRow(if (vm.showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (vm.showHidden) "Ukryj ukryte pliki" else "Pokaż ukryte pliki") { menu = false; vm.updateHidden(!vm.showHidden) }
                vm.place?.let { p -> MenuRow(if (p in vm.favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (p in vm.favorites) "Usuń z ulubionych" else "Dodaj do ulubionych") { menu = false; vm.toggleFavorite(p) } }
                vm.place?.let { p -> MenuRow(Icons.Rounded.Tab, "Otwórz w nowej karcie") { menu = false; vm.navigate(p, true) } }
                vm.place?.let { MenuRow(Icons.Rounded.Sensors, if (vm.live) "Zatrzymaj tryb na żywo" else "Na żywo (nowe zdjęcia od razu)") { menu = false; vm.toggleLive() } }
                MenuRow(Icons.Rounded.FilterList, if (vm.showFilters) "Ukryj filtry typów" else "Pokaż filtry typów") { menu = false; vm.updateShowFilters(!vm.showFilters) }
                vm.place?.let { MenuRow(Icons.Rounded.DonutLarge, "Analizuj zajęte miejsce") { menu = false; actions.analyze() } }
                vm.place?.let { p -> MenuRow(Icons.Rounded.AddToHomeScreen, "Skrót na ekranie głównym") { menu = false; vm.pinShortcut(p) } }
                MenuRow(Icons.Rounded.PhotoLibrary, "Galeria") { menu = false; vm.openGallery() }
                MenuRow(Icons.Rounded.Refresh, "Odśwież") { menu = false; vm.refresh() }
                MenuRow(Icons.Rounded.Settings, "Ustawienia") { menu = false; actions.settings() }
            }
        }
    }
}

@Composable private fun SelectionBar(vm: ExplorerModel) {
    Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        RoundIcon(Icons.Rounded.Close, "Zakończ zaznaczanie", size = 40.dp) { vm.clearSelection() }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text("Zaznaczono ${vm.selected.size}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            val items = vm.selectedItems
            Text(formatSize(items.filterNot { it.directory }.sumOf { it.size }) + if (items.any { it.directory }) " + foldery" else "", fontSize = 11.sp, color = Palette.TextDim)
        }
        RoundIcon(Icons.Rounded.SelectAll, "Zaznacz wszystko", active = vm.selected.size == vm.visible.size) { vm.selectAll() }
        RoundIcon(Icons.Rounded.SwapHoriz, "Odwróć zaznaczenie") { vm.invertSelection() }
    }
}

@Composable private fun SearchField(vm: ExplorerModel, recursive: Boolean) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().clip(Pill).background(Palette.PanelHigh).padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = Palette.TextDim, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (vm.query.isEmpty()) Text(if (recursive) "Filtruj folder lub szukaj w podfolderach…" else "Filtruj…", color = Palette.TextFaint, fontSize = 14.sp)
                BasicTextField(vm.query, { vm.query = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { if (recursive) vm.search(vm.query) }))
            }
            if (vm.query.isNotEmpty()) Icon(Icons.Rounded.Close, "Wyczyść", tint = Palette.TextDim, modifier = Modifier.size(32.dp).clip(CircleShape).clickable { vm.query = ""; if (vm.searchResults != null) vm.back() }.padding(6.dp))
        }
        if (recursive && vm.query.isNotBlank()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip(if (vm.searching) "Szukam… ${vm.searchResults?.size ?: 0}" else "Szukaj w podfolderach", vm.searchResults != null, icon = Icons.Rounded.TravelExplore) { if (vm.searching) vm.stopSearch() else vm.search(vm.query) }
            if (vm.searching) Chip("Zatrzymaj", false, icon = Icons.Rounded.Stop) { vm.stopSearch() }
        }
    }
}

@Composable private fun Tabs(vm: ExplorerModel) {
    if (vm.tabs.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        vm.tabs.forEachIndexed { index, tab ->
            val activeTab = index == vm.active
            Row(Modifier.dropFolder(vm, tab.place).clip(Pill).background(if (activeTab) Palette.White else Palette.PanelHigh).clickable { vm.switch(index) }.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (vm.rootOf(tab.place)?.network == true) Icons.Rounded.Dns else Icons.Rounded.Folder, null, Modifier.size(15.dp), tint = if (activeTab) Palette.Ink else Palette.TextDim)
                Spacer(Modifier.width(6.dp))
                Text(vm.title(tab.place), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = if (activeTab) FontWeight.Bold else FontWeight.Medium, color = if (activeTab) Palette.Ink else Color.White, modifier = Modifier.widthIn(max = 140.dp))
                Icon(Icons.Rounded.Close, "Zamknij kartę", tint = if (activeTab) Palette.Ink else Palette.TextDim, modifier = Modifier.padding(start = 2.dp).size(26.dp).clip(CircleShape).clickable { vm.closeTab(index) }.padding(5.dp))
            }
        }
        RoundIcon(Icons.Rounded.Add, "Nowa karta", size = 32.dp) { vm.place?.let { vm.navigate(it, true) } }
    }
}

@Composable private fun Breadcrumbs(vm: ExplorerModel, p: Place) {
    val scroll = rememberScrollState()
    LaunchedEffect(p) { scroll.animateScrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        // Bez ostatniego elementu – nazwa bieżącego folderu jest już w karcie.
        if (p.parts.isEmpty()) Text(vm.rootOf(p)?.let { if (it.network) "NAS" else "Katalog główny" } ?: "", fontSize = 12.sp, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 8.dp))
        (0 until p.parts.size).forEach { depth ->
            val crumb = p.copy(parts = p.parts.take(depth))
            val last = depth == p.parts.size - 1
            Text(vm.title(crumb), maxLines = 1, fontSize = 12.sp, fontWeight = if (last) FontWeight.Bold else FontWeight.Medium, color = if (last) Color.White else Palette.TextDim,
                modifier = Modifier.dropFolder(vm, crumb).clip(Pill).clickable { vm.navigate(crumb) }.padding(horizontal = 8.dp, vertical = 6.dp))
            if (!last) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(14.dp), tint = Palette.TextFaint)
        }
    }
}

@Composable private fun InfoLine(vm: ExplorerModel, category: Category?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val items = vm.visible
        Text(
            (if (vm.searchResults != null) "Wyniki: " else "") + "${items.size} elem. · ${formatSize(items.filterNot { it.directory }.sumOf { it.size })}",
            color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1,
        )
        var menu by remember { mutableStateOf(false) }
        Box {
            Chip("${vm.sort.label} ${if (vm.descending) "↓" else "↑"}", false, icon = Icons.AutoMirrored.Rounded.Sort) { menu = true }
            DarkMenu(menu, { menu = false }) {
                Sort.entries.forEach { s -> MenuRow(if (vm.sort == s) Icons.Rounded.Check else null, s.label) { vm.updateSort(s); menu = false } }
                HorizontalDivider(color = Palette.Line)
                MenuRow(if (vm.descending) Icons.Rounded.Check else null, "Malejąco") { vm.updateDescending(!vm.descending) }
                MenuRow(if (vm.foldersFirst) Icons.Rounded.Check else null, "Foldery na początku") { vm.updateFoldersFirst(!vm.foldersFirst) }
            }
        }
        Spacer(Modifier.width(4.dp))
        RoundIcon(if (vm.grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, if (vm.grid) "Widok listy" else "Widok siatki", size = 34.dp, flat = true) { vm.updateGrid(!vm.grid) }
    }
}

@Composable private fun TypeFilters(vm: ExplorerModel) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        TypeFilter.entries.forEach { f -> Chip(f.label, vm.typeFilter == f) { vm.typeFilter = f } }
    }
}

@Composable private fun LiveBanner(vm: ExplorerModel) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Pill).background(Palette.White).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Record))
        Spacer(Modifier.width(8.dp))
        Text("Na żywo · nowych: ${vm.liveNew}", color = Palette.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("Stop", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(Pill).background(Palette.Ink).clickable { vm.toggleLive() }.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable fun Empty(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(Palette.PanelHigh), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(40.dp), tint = Palette.TextDim) }
        Spacer(Modifier.height(16.dp)); Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp)); Text(subtitle, color = Palette.TextDim, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable private fun AddButton(actions: BrowserActions) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.pressable("Utwórz") { menu = true }.size(56.dp).clip(CircleShape).background(Palette.White), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, null, tint = Palette.Ink, modifier = Modifier.size(28.dp)) }
        DarkMenu(menu, { menu = false }) {
            MenuRow(Icons.Rounded.CreateNewFolder, "Nowy folder") { menu = false; actions.newFolder() }
            MenuRow(Icons.Rounded.NoteAdd, "Nowy plik tekstowy") { menu = false; actions.newFile() }
        }
    }
}

@Composable fun ProgressCard(vm: ExplorerModel, p: Progress) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.text.isNotEmpty()) Text(p.text, fontSize = 11.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            p.fraction?.let { Text("${(it * 100).toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp)) }
            if (p.cancellable) PillButton("Anuluj", primary = false) { vm.cancel() }
        }
        Spacer(Modifier.height(10.dp))
        if (p.fraction != null) Meter(p.fraction, Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(Pill), color = Palette.White, trackColor = Color.White.copy(alpha = 0.12f))
    }
}

@Composable private fun PasteBanner(vm: ExplorerModel) {
    val clip = vm.clipboard ?: return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.PanelHigh).padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (clip.move) Icons.Rounded.ContentCut else Icons.Rounded.ContentCopy, null, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (clip.move) "Przenieś ${clip.items.size} elem." else "Kopiuj ${clip.items.size} elem.", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(clip.items.take(3).joinToString { it.name } + if (clip.items.size > 3) "…" else "", fontSize = 11.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PillButton("Wklej tutaj", enabled = !vm.busy && vm.place != null, icon = Icons.Rounded.ContentPaste) { vm.paste() }
        RoundIcon(Icons.Rounded.Close, "Wyczyść schowek", size = 36.dp, flat = true) { vm.clearClip() }
    }
}

@Composable fun SelectionDock(vm: ExplorerModel, actions: BrowserActions, inFolder: Boolean) {
    val items = vm.selectedItems
    val single = items.singleOrNull()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Palette.PanelHigh).padding(horizontal = 4.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        DockAction(Icons.Rounded.ContentCopy, "Kopiuj") { vm.clip(false) }
        DockAction(Icons.Rounded.ContentCut, "Wytnij") { vm.clip(true) }
        DockAction(Icons.Rounded.Delete, if (vm.trashable()) "Do kosza" else "Usuń", enabled = !vm.busy) { actions.delete() }
        DockAction(Icons.Rounded.Share, "Udostępnij", enabled = items.any { !it.directory } && !vm.busy) { vm.share() }
        var menu by remember { mutableStateOf(false) }
        Box {
            DockAction(Icons.Rounded.MoreHoriz, "Więcej") { menu = true }
            DarkMenu(menu, { menu = false }) {
                if (single != null) MenuRow(Icons.Rounded.Edit, "Zmień nazwę") { menu = false; actions.rename(single) }
                if (items.size > 1) MenuRow(Icons.Rounded.DriveFileRenameOutline, "Zmień nazwy (${items.size})") { menu = false; actions.batchRename() }
                if (single != null && single.directory) MenuRow(Icons.Rounded.AddToHomeScreen, "Skrót na ekranie głównym") { menu = false; vm.pinShortcut(single.place) }
                MenuRow(Icons.Rounded.Archive, "Spakuj do ZIP") { menu = false; actions.compress() }
                if (single != null && FileRules.isZip(single)) MenuRow(Icons.Rounded.Unarchive, "Rozpakuj") { menu = false; vm.clearSelection(); vm.extract(single) }
                if (single != null) MenuRow(Icons.Rounded.Info, "Właściwości") { menu = false; actions.inspect(single) }
                if (single != null && !single.directory) MenuRow(Icons.AutoMirrored.Rounded.OpenInNew, "Otwórz za pomocą…") { menu = false; vm.openExternal(single, true) }
                if (single != null) MenuRow(Icons.Rounded.Link, "Kopiuj ścieżkę") { menu = false; vm.copyPath(single) }
                if (single != null && single.directory) MenuRow(Icons.Rounded.Tab, "Otwórz w nowej karcie") { menu = false; vm.clearSelection(); vm.navigate(single.place, true) }
                if (single != null && single.directory) MenuRow(if (single.place in vm.favorites) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (single.place in vm.favorites) "Usuń z ulubionych" else "Dodaj do ulubionych") { menu = false; vm.toggleFavorite(single.place) }
                if (!inFolder && single != null) single.place.parent()?.let { parent -> MenuRow(Icons.Rounded.FolderOpen, "Pokaż w folderze") { menu = false; vm.clearSelection(); vm.navigate(parent, true) } }
                if (single != null && !single.directory) {
                    MenuRow(Icons.Rounded.Fingerprint, "Suma SHA-256") { menu = false; actions.inspect(single); vm.hash(single, "SHA-256") }
                }
            }
        }
    }
}

@Composable private fun DockAction(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.pressable(label, enabled, onClick = onClick).clip(RoundedCornerShape(14.dp)).padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(22.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable fun DarkMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded, onDismiss, shape = RoundedCornerShape(18.dp), containerColor = Palette.PanelHigh, content = content)
}
@Composable fun MenuRow(icon: ImageVector?, text: String, danger: Boolean = false, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 14.sp, color = if (danger) Palette.Record else Color.White) },
        leadingIcon = { if (icon != null) Icon(icon, null, tint = if (danger) Palette.Record else Color.White, modifier = Modifier.size(20.dp)) else Spacer(Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/** Kosz: przywracanie i trwałe usuwanie elementów z pamięci wewnętrznej. */
@Composable fun TrashScreen(vm: ExplorerModel, medium: Boolean, menu: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = if (medium) 18.dp else 10.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!medium) RoundIcon(Icons.Rounded.Menu, "Źródła i foldery", onClick = menu)
            RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Wstecz") { vm.back() }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Kosz", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("${vm.trashEntries.size} elem. · ${formatSize(vm.trashEntries.sumOf { it.size })}", fontSize = 11.sp, color = Palette.TextDim)
            }
            if (vm.trashEntries.isNotEmpty()) PillButton("Opróżnij", danger = true, enabled = !vm.busy) { confirm = true }
        }
        Text("Elementy usunięte z pamięci wewnętrznej. Przywrócone wracają do pierwotnego folderu.", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(4.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (vm.trashEntries.isEmpty() && !vm.loading) Empty(Icons.Rounded.DeleteOutline, "Kosz jest pusty", if (vm.access) "Usunięte pliki z pamięci wewnętrznej trafią tutaj." else "Kosz działa po nadaniu pełnego dostępu do pamięci.", Modifier.align(Alignment.Center))
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(vm.trashEntries, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Palette.PanelHigh), contentAlignment = Alignment.Center) {
                            Icon(if (e.directory) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = Palette.TextDim)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${formatShortDate(e.deleted)} · ${formatSize(e.size)} · ${e.original.substringBeforeLast('/')}", fontSize = 11.sp, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        RoundIcon(Icons.Rounded.Restore, "Przywróć ${e.name}", size = 38.dp, flat = true, enabled = !vm.busy) { vm.restore(e) }
                        RoundIcon(Icons.Rounded.DeleteForever, "Usuń trwale ${e.name}", size = 38.dp, flat = true, tint = Palette.Record, enabled = !vm.busy) { vm.forget(e) }
                    }
                }
            }
        }
    }
    if (confirm) DarkDialog("Opróżnić kosz?", { confirm = false }, buttons = {
        PillButton("Anuluj", primary = false) { confirm = false }
        PillButton("Usuń trwale", danger = true) { confirm = false; vm.emptyTrash() }
    }) { Text("${vm.trashEntries.size} elem. (${formatSize(vm.trashEntries.sumOf { it.size })}) zostanie usuniętych na zawsze.", color = Palette.TextDim, fontSize = 13.sp) }
}
