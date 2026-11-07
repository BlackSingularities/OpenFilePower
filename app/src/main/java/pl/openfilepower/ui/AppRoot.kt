package pl.openfilepower.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.openfilepower.*

@Composable fun AppRoot(vm: ExplorerModel = viewModel()) {
    val context = LocalContext.current
    var drawer by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var phoneDetails by remember { mutableStateOf(false) }
    var inspectorOpen by remember { mutableStateOf(true) }
    var network by remember { mutableStateOf(false) }
    var remoteServer by remember { mutableStateOf(false) }
    var wifiPanel by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Item?>(null) }
    var deleting by remember { mutableStateOf<List<Item>?>(null) }
    var compressing by remember { mutableStateOf<List<Item>?>(null) }
    var renaming by remember { mutableStateOf<List<Item>?>(null) }
    val snacks = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addLocal) }
    val legacyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onResume() }
    val grantAccess: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            runCatching { context.startActivity(intent) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
        } else legacyPermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
    LifecycleResumeEffect(Unit) { vm.onResume(); onPauseOrDispose { } }
    val shortcut by ShortcutBus.place.collectAsState()
    val openSync by ShortcutBus.sync.collectAsState()
    LaunchedEffect(openSync) { if (openSync) { vm.openSync(); ShortcutBus.sync.value = false } }
    LaunchedEffect(shortcut) { shortcut?.let { vm.openShortcut(it); ShortcutBus.place.value = null } }
    // Blokada: każda nowa blokada zamyka folder prywatny.
    val locked by AppLock.locked.collectAsState()
    val lockEpoch by AppLock.epoch.collectAsState()
    LaunchedEffect(lockEpoch) { if (lockEpoch > 0) vm.closeVault() }
    LaunchedEffect(locked) { if (locked) vm.closeVault() }
    LaunchedEffect(vm.notice) { vm.notice?.let { snacks.showSnackbar(it); vm.notice = null } }
    // „Cofnij” dostępne tylko przez krótki czas po operacji.
    LaunchedEffect(vm.undo) {
        val u = vm.undo ?: return@LaunchedEffect
        val result = snacks.showSnackbar(u.message, actionLabel = "Cofnij", duration = androidx.compose.material3.SnackbarDuration.Short)
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.performUndo(u) else if (vm.undo === u) vm.undo = null
    }

    val overlay = vm.viewer != null || vm.editor != null
    BackHandler(enabled = !overlay && (network || vm.syncDraft != null || vm.analysis != null || settings || phoneDetails || drawer || vm.view != View.Home || vm.selecting)) {
        when {
            network -> network = false
            vm.syncDraft != null -> vm.syncDraft = null
            vm.analysis != null -> vm.closeAnalysis()
            settings -> settings = false
            phoneDetails -> phoneDetails = false
            drawer -> drawer = false
            else -> vm.back()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Ink)) {
        val wide = maxWidth >= 1000.dp
        val medium = maxWidth >= 720.dp
        val inspect: (Item) -> Unit = { item -> vm.inspect(item); if (wide) inspectorOpen = true else phoneDetails = true }
        val actions = BrowserActions(
            menu = { if (medium) vm.updateSidebarHidden(false) else drawer = true }, inspect = inspect,
            newFolder = { newFolder = true }, newFile = { newFile = true }, rename = { rename = it },
            delete = { deleting = vm.selectedItems }, compress = { compressing = vm.selectedItems }, settings = { settings = true },
            batchRename = { renaming = vm.selectedItems }, analyze = { vm.analyze() },
        )
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        vm.external?.let { ExternalBar(vm, it) }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            AnimatedVisibility(medium && !vm.sidebarHidden, enter = expandHorizontally() + fadeIn(), exit = shrinkHorizontally() + fadeOut()) {
                Sidebar(vm, Modifier.width(if (wide) 248.dp else 216.dp), { picker.launch(null) }, { network = true }, { settings = true }, collapse = { vm.updateSidebarHidden(true) }, addRemote = { remoteServer = true }, wifiServer = { wifiPanel = true })
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (vm.view) {
                    View.Home -> HomeScreen(vm, medium, HomeActions(grantAccess, { picker.launch(null) }, { network = true }, { settings = true }, inspect, actions.menu))
                    View.Trash -> TrashScreen(vm, medium, actions.menu)
                    View.Sync -> SyncScreen(vm, medium, actions.menu)
                    View.Apps -> AppsScreen(vm, medium, actions.menu)
                    View.Cleanup -> CleanupScreen(vm, medium, actions.menu)
                    View.Gallery -> GalleryScreen(vm, medium, actions)
                    else -> BrowserScreen(vm, medium, wide, actions)
                }
                SnackbarHost(snacks, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)) { data ->
                    Snackbar(containerColor = Palette.White, contentColor = Palette.Ink, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp), action = data.visuals.actionLabel?.let { label -> @Composable {
                        Text(label, color = Palette.Ink, fontWeight = FontWeight.Bold, modifier = Modifier.clip(Pill).pressable(label) { data.performAction() }.padding(horizontal = 12.dp, vertical = 8.dp))
                    } }) { Text(data.visuals.message) }
                }
            }
            if (wide && inspectorOpen && (vm.view == View.Browse || vm.view is View.Category || vm.view == View.Gallery)) Inspector(vm, Modifier.width(320.dp).fillMaxHeight())
        }
        }

        // Szuflada z lewej na telefonie.
        if (!medium) {
            AnimatedVisibility(drawer, enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { drawer = false } }) }
            AnimatedVisibility(drawer, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
                Sidebar(vm, Modifier.width(300.dp).windowInsetsPadding(WindowInsets.safeDrawing), { picker.launch(null) }, { network = true }, { settings = true }, addRemote = { remoteServer = true }, wifiServer = { wifiPanel = true }) { drawer = false }
            }
        }
        if (settings) SettingsPanel(vm, { settings = false }, grantAccess, { picker.launch(null) }, { network = true }) {
            picker.launch(Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata"))
        }
        vm.analysis?.let { AnalysisPanel(vm, it) }
        vm.syncDraft?.let { p -> PairEditor(vm, null, { vm.syncDraft = null }, initialLocal = p) }
        if (wifiPanel) { BackHandler { wifiPanel = false }; WifiServerPanel(vm) { wifiPanel = false } }
        if (remoteServer) { BackHandler { remoteServer = false }; RemotePanel(vm) { remoteServer = false } }
        if (network) NetworkPanel(vm, { network = false }) { name, host, share, user, pass, domain -> vm.addNetwork(name, host, share, user, pass, domain); network = false }
        // Właściwości z przeglądarki zdjęć zawsze jako panel nad nią – inaczej trzeba by ją zamknąć, by je zobaczyć.
        vm.viewer?.let { (images, index) -> ImageViewer(vm, images, index, { vm.viewer = null }) { item -> vm.inspect(item); phoneDetails = true } }
        vm.editor?.let { TextEditor(vm, it) { vm.editor = null } }
        if (phoneDetails && (!wide || vm.viewer != null)) {
            BackHandler { phoneDetails = false }
            Panel("Właściwości", { phoneDetails = false }) { InspectorContent(vm) { phoneDetails = false } }
        }
        if (locked) LockScreen()
    }

    renaming?.let { items -> BatchRenameDialog(vm, items) { renaming = null } }
    if (newFolder) NameDialog("Nowy folder", "Nowy folder", "Utwórz", selectStem = false, dismiss = { newFolder = false }) { vm.mkdir(it); newFolder = false }
    if (newFile) NameDialog("Nowy plik", "notatka.txt", "Utwórz", dismiss = { newFile = false }) { vm.createFile(it); newFile = false }
    rename?.let { item -> NameDialog("Zmień nazwę", item.name, dismiss = { rename = null }) { vm.rename(item, it); rename = null; vm.clearSelection() } }
    deleting?.let { items -> DeleteDialog(vm, items) { deleting = null } }
    compressing?.let { items ->
        val suggested = items.singleOrNull()?.name?.let { if (it.contains('.') && !items.single().directory) it.substringBeforeLast('.') else it } ?: vm.place?.let(vm::title) ?: "Archiwum"
        CompressDialog(items.size, suggested, dismiss = { compressing = null }) { name, format, password -> vm.compress(name, items, format, password); compressing = null }
    }
    vm.pendingPaste?.let { ConflictDialog(vm, it) }
    vm.archive?.let { ArchiveDialog(vm, it) }
    vm.passwordPrompt?.let { p -> PasswordDialog(p.item.name, p.wrong, { vm.passwordPrompt = null }) { vm.submitPassword(it) } }
    vm.error?.let { ErrorDialog(it) { vm.error = null } }
}

/** Pasek trybu „dla innej aplikacji”: wybór pliku albo zapis udostępnionych plików w bieżącym folderze. */
@Composable private fun ExternalBar(vm: ExplorerModel, request: ExternalRequest) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(Palette.White).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(if (request is ExternalRequest.Pick) Icons.Rounded.TouchApp else Icons.Rounded.SaveAlt, null, Modifier.size(20.dp), tint = Palette.Ink)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            when (request) {
                is ExternalRequest.Pick -> {
                    Text(if (request.multiple) "Wybierz pliki" else "Wybierz plik", color = Palette.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(if (request.multiple) "Dotknij pliku lub zaznacz kilka" else "Dotknij pliku, aby go przekazać", color = Palette.Ink.copy(alpha = 0.7f), fontSize = 11.sp)
                }
                is ExternalRequest.Save -> {
                    Text(if (request.uris.isEmpty()) "Zapisz tekst jako plik" else "Zapisz ${request.uris.size} elem.", color = Palette.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(vm.place?.let { "Do: " + vm.title(it) } ?: "Otwórz folder docelowy", color = Palette.Ink.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        when (request) {
            is ExternalRequest.Pick -> if (request.multiple && vm.selecting) {
                val count = vm.selectedItems.count { request.accepts(it) }
                Text("Wybierz ($count)", color = Palette.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(Pill).background(Palette.Ink).pressable("Wybierz", count > 0 && !vm.busy) { vm.pickItems(vm.selectedItems) }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
            is ExternalRequest.Save -> Text("Zapisz tutaj", color = Palette.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(Pill).background(Palette.Ink).pressable("Zapisz tutaj", vm.place != null && !vm.busy) { vm.saveIncomingHere() }.padding(horizontal = 14.dp, vertical = 8.dp))
        }
        androidx.compose.material3.Icon(Icons.Rounded.Close, "Anuluj", Modifier.padding(start = 4.dp).size(36.dp).clip(Pill).pressable("Anuluj") { vm.cancelExternal() }.padding(8.dp), tint = Palette.Ink)
    }
}

/** Ekran blokady: zasłania wszystko, dopóki użytkownik nie potwierdzi tożsamości. */
@Composable private fun LockScreen() {
    val activity = androidx.activity.compose.LocalActivity.current as? androidx.fragment.app.FragmentActivity
    var message by remember { mutableStateOf<String?>(null) }
    val unlock: () -> Unit = {
        // Bez blokady ekranu na urządzeniu nie da się uwierzytelnić – nie zamykamy użytkownika na zewnątrz.
        if (activity != null && AppLock.available(activity)) AppLock.authenticate(activity, "Odblokuj OpenFilePower", onError = { message = it }) { AppLock.locked.value = false }
        else AppLock.locked.value = false
    }
    LaunchedEffect(Unit) { unlock() }
    BackHandler { activity?.moveTaskToBack(true) }
    Column(Modifier.fillMaxSize().background(Palette.Ink).pointerInput(Unit) { detectTapGestures { } }.windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        androidx.compose.material3.Icon(Icons.Rounded.Lock, null, Modifier.size(56.dp), tint = Palette.White)
        Spacer(Modifier.height(16.dp))
        Text("Aplikacja zablokowana", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        message?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Palette.TextDim, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        Spacer(Modifier.height(20.dp))
        PillButton("Odblokuj", icon = Icons.Rounded.Fingerprint, onClick = unlock)
    }
}
