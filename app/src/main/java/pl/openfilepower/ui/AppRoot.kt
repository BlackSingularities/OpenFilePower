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
import androidx.compose.animation.fadeIn
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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    var newFolder by remember { mutableStateOf(false) }
    var newFile by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Item?>(null) }
    var deleting by remember { mutableStateOf<List<Item>?>(null) }
    var compressing by remember { mutableStateOf<List<Item>?>(null) }
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
    LaunchedEffect(vm.notice) { vm.notice?.let { snacks.showSnackbar(it); vm.notice = null } }

    val overlay = vm.viewer != null || vm.editor != null
    BackHandler(enabled = !overlay && (settings || phoneDetails || drawer || vm.view != View.Home || vm.selecting)) {
        when {
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
            menu = { drawer = true }, inspect = inspect,
            newFolder = { newFolder = true }, newFile = { newFile = true }, rename = { rename = it },
            delete = { deleting = vm.selectedItems }, compress = { compressing = vm.selectedItems }, settings = { settings = true },
        )
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (medium) Sidebar(vm, Modifier.width(if (wide) 268.dp else 232.dp), { picker.launch(null) }, { network = true }, { settings = true })
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (vm.view) {
                    View.Home -> HomeScreen(vm, medium, HomeActions(grantAccess, { picker.launch(null) }, { network = true }, { settings = true }, inspect))
                    View.Trash -> TrashScreen(vm, medium) { drawer = true }
                    else -> BrowserScreen(vm, medium, wide, actions)
                }
                SnackbarHost(snacks, Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)) { data ->
                    Snackbar(containerColor = Palette.White, contentColor = Palette.Ink, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 16.dp)) { Text(data.visuals.message) }
                }
            }
            if (wide && inspectorOpen && (vm.view == View.Browse || vm.view is View.Category)) Inspector(vm, Modifier.width(320.dp).fillMaxHeight())
        }

        // Szuflada z lewej na telefonie.
        if (!medium) {
            AnimatedVisibility(drawer, enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { drawer = false } }) }
            AnimatedVisibility(drawer, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
                Sidebar(vm, Modifier.width(300.dp).windowInsetsPadding(WindowInsets.safeDrawing), { picker.launch(null) }, { network = true }, { settings = true }) { drawer = false }
            }
        }
        if (phoneDetails && !wide) Panel("Właściwości", { phoneDetails = false }) { InspectorContent(vm) { phoneDetails = false } }
        if (settings) SettingsPanel(vm, { settings = false }, grantAccess, { picker.launch(null) }, { network = true }) {
            picker.launch(Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata"))
        }
        vm.viewer?.let { (images, index) -> ImageViewer(vm, images, index, { vm.viewer = null }, inspect) }
        vm.editor?.let { TextEditor(vm, it) { vm.editor = null } }
    }

    if (network) NetworkDialog({ network = false }) { name, host, share, user, pass, domain -> vm.addNetwork(name, host, share, user, pass, domain); network = false }
    if (newFolder) NameDialog("Nowy folder", "Nowy folder", "Utwórz", selectStem = false, dismiss = { newFolder = false }) { vm.mkdir(it); newFolder = false }
    if (newFile) NameDialog("Nowy plik", "notatka.txt", "Utwórz", dismiss = { newFile = false }) { vm.createFile(it); newFile = false }
    rename?.let { item -> NameDialog("Zmień nazwę", item.name, dismiss = { rename = null }) { vm.rename(item, it); rename = null; vm.clearSelection() } }
    deleting?.let { items -> DeleteDialog(vm, items) { deleting = null } }
    compressing?.let { items ->
        val suggested = (items.singleOrNull()?.name?.let { if (it.contains('.') && !items.single().directory) it.substringBeforeLast('.') else it } ?: vm.place?.let(vm::title) ?: "Archiwum") + ".zip"
        NameDialog("Spakuj ${items.size} elem. do ZIP", suggested, "Spakuj", dismiss = { compressing = null }) { vm.compress(it, items); compressing = null }
    }
    vm.pendingPaste?.let { ConflictDialog(vm, it) }
    vm.archive?.let { ArchiveDialog(vm, it) }
    vm.error?.let { ErrorDialog(it) { vm.error = null } }
}
