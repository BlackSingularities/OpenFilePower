package pl.openfilepower.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.openfilepower.*

/** Lewy panel: start, pamięć, foldery SAF, NAS, ulubione i kategorie. [done] zamyka szufladę na telefonie. */
@Composable fun Sidebar(vm: ExplorerModel, modifier: Modifier, addLocal: () -> Unit, addNetwork: () -> Unit, settings: () -> Unit, done: () -> Unit = {}) {
    Column(modifier.fillMaxHeight().background(Color(0xFF0B0B0B)).padding(horizontal = 12.dp)) {
        Row(Modifier.height(72.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.White), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FolderOpen, null, tint = Palette.Ink, modifier = Modifier.size(22.dp)) }
            Spacer(Modifier.width(10.dp))
            Column { Text("OpenFilePower", fontWeight = FontWeight.Black, fontSize = 17.sp); Text("v${BuildConfig.VERSION_NAME}", color = Palette.TextFaint, fontSize = 10.sp) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            NavRow(Icons.Rounded.Home, "Start", vm.view == View.Home) { vm.goHome(); done() }
            NavRow(Icons.Rounded.PhotoLibrary, "Galeria", vm.view == View.Gallery) { vm.openGallery(); done() }
            NavRow(Icons.Rounded.DeleteOutline, "Kosz", vm.view == View.Trash) { vm.openTrash(); done() }

            val direct = vm.roots.filter { it.direct }
            if (direct.isNotEmpty()) { SectionTitle("Pamięć"); direct.forEach { TreeNode(vm, Place(it.id), 0, done) } }

            if (vm.favorites.isNotEmpty()) {
                SectionTitle("Ulubione")
                vm.favorites.forEach { p ->
                    Row(Modifier.fillMaxWidth().dropFolder(vm, p), verticalAlignment = Alignment.CenterVertically) {
                        NavRow(Icons.Rounded.Star, vm.title(p), vm.place == p, Modifier.weight(1f)) { vm.navigate(p); done() }
                        Icon(Icons.Rounded.Close, "Odepnij ${vm.title(p)}", tint = Palette.TextFaint, modifier = Modifier.size(30.dp).clip(CircleShape).clickable { vm.toggleFavorite(p) }.padding(7.dp))
                    }
                }
            }

            val local = vm.roots.filter { !it.direct && !it.network }
            SectionTitle("Foldery")
            local.forEach { root ->
                TreeNode(vm, Place(root.id), 0, done)
                RootActions(vm, root)
            }
            NavRow(Icons.Rounded.Add, "Dodaj folder", false) { addLocal(); done() }

            SectionTitle("Sieć")
            vm.roots.filter { it.network }.forEach { root -> TreeNode(vm, Place(root.id), 0, done); RootActions(vm, root) }
            NavRow(Icons.Rounded.Dns, "Połącz z NAS (SMB)", false) { addNetwork(); done() }

            if (vm.access) {
                SectionTitle("Kategorie")
                Category.entries.forEach { c -> NavRow(iconFor(c), c.label, vm.view == View.Category(c)) { vm.openCategory(c); done() } }
            }
            Spacer(Modifier.height(12.dp))
        }
        NavRow(Icons.Rounded.Settings, "Ustawienia i aktualizacje", false, badge = vm.release != null) { settings(); done() }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun RootActions(vm: ExplorerModel, root: Root) {
    Row(Modifier.fillMaxWidth().padding(start = 36.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Nowa karta", fontSize = 11.sp, color = Palette.TextDim, modifier = Modifier.clip(Pill).clickable { vm.navigate(Place(root.id), true) }.padding(horizontal = 8.dp, vertical = 4.dp))
        Text("Odłącz", fontSize = 11.sp, color = Palette.TextFaint, modifier = Modifier.clip(Pill).clickable(enabled = !vm.busy) { vm.removeRoot(root) }.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable fun NavRow(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, badge: Boolean = false, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().clip(Pill).background(if (selected) Palette.White else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(19.dp), tint = if (selected) Palette.Ink else Palette.TextDim)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Palette.Ink else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (badge) Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.White))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun TreeNode(vm: ExplorerModel, p: Place, depth: Int, done: () -> Unit) {
    val expanded = vm.expanded[p] == true
    val current = vm.place == p
    val root = vm.rootOf(p)
    Row(Modifier.fillMaxWidth().padding(start = (depth * 12).dp).clip(Pill).background(if (current) Palette.White else Color.Transparent).dropFolder(vm, p), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight, "Rozwiń ${vm.title(p)}", tint = if (current) Palette.Ink else Palette.TextFaint, modifier = Modifier.size(32.dp).clip(CircleShape).clickable { vm.toggleTree(p) }.padding(7.dp))
        Row(Modifier.weight(1f).combinedClickable(onClick = { vm.navigate(p); done() }, onLongClick = { vm.navigate(p, true); done() }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                depth > 0 -> Icons.Rounded.Folder
                root?.network == true -> Icons.Rounded.Dns
                root?.direct == true && root.id != StorageAccess.INTERNAL_ID -> Icons.Rounded.SdCard
                root?.direct == true -> Icons.Rounded.Smartphone
                else -> Icons.Rounded.FolderSpecial
            }
            Icon(icon, null, Modifier.size(17.dp), tint = if (current) Palette.Ink else Palette.TextDim)
            Spacer(Modifier.width(9.dp))
            Text(vm.title(p), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) Palette.Ink else Color.White, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
        }
    }
    if (expanded && depth < 12) vm.tree[p]?.forEach { TreeNode(vm, it.place, depth + 1, done) }
}
