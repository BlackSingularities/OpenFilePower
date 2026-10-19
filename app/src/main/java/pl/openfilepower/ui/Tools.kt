package pl.openfilepower.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.openfilepower.*

/** Połączenie z NAS: wykrywanie serwerów w sieci (mDNS + skan portu 445), lista udziałów, logowanie. */
@Composable fun NetworkPanel(vm: ExplorerModel, dismiss: () -> Unit, submit: (String, String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }; var share by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var domain by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.clearShares(); if (vm.nasHosts.isEmpty()) vm.scanNas() }
    DisposableEffect(Unit) { onDispose { vm.stopNasScan() } }
    Panel("Połącz z NAS", { dismiss() }) {
        Group("Wykryte w sieci") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.nasScanning) CircularProgressIndicator(Modifier.size(18.dp), color = Palette.White, strokeWidth = 2.dp) else Icon(Icons.Rounded.Radar, null, Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(if (vm.nasScanning) "Szukam serwerów SMB… ${(vm.nasProgress * 100).toInt()}%" else "Znaleziono: ${vm.nasHosts.size}", fontSize = 13.sp, modifier = Modifier.weight(1f))
                PillButton(if (vm.nasScanning) "Stop" else "Skanuj", primary = false) { if (vm.nasScanning) vm.stopNasScan() else vm.scanNas() }
            }
            if (vm.nasScanning) Meter(vm.nasProgress, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            vm.nasHosts.forEach { h ->
                ActionRow(Icons.Rounded.Dns, h.name, "${h.address} · ${if (h.source == "mDNS") "ogłasza się w sieci" else "otwarty port SMB"}", trailing = { if (host == h.address) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(20.dp)) }) {
                    host = h.address; vm.clearShares(); share = ""
                    if (name.isBlank() || vm.nasHosts.any { it.name == name }) name = h.name
                }
            }
            if (!vm.nasScanning && vm.nasHosts.isEmpty()) Text("Nie znaleziono serwerów. Upewnij się, że telefon jest w tej samej sieci Wi-Fi co NAS, albo wpisz adres ręcznie.", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        }
        Group("Serwer i logowanie") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DarkField(host, { host = it.trim(); vm.clearShares() }, "Serwer, np. 192.168.1.10", leading = Icons.Rounded.Dns)
                DarkField(user, { user = it }, "Użytkownik (puste = gość)", leading = Icons.Rounded.Person)
                DarkField(pass, { pass = it }, "Hasło", password = true, leading = Icons.Rounded.Lock)
                DarkField(domain, { domain = it }, "Domena (opcjonalnie)")
                PillButton(if (vm.nasSharesBusy) "Pobieranie udziałów…" else "Pokaż udziały", primary = false, enabled = host.isNotBlank() && !vm.nasSharesBusy, icon = Icons.Rounded.FolderShared) { vm.listShares(host, user, pass, domain) }
            }
        }
        val shares = vm.nasShares?.takeIf { it.first == host }?.second
        Group("Udział") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shares != null) {
                    if (shares.isEmpty()) Text("Serwer nie udostępnia publicznych udziałów dla tego konta.", color = Palette.TextDim, fontSize = 12.sp)
                    FlowRowCompat { shares.forEach { s -> Chip(s, share == s, icon = Icons.Rounded.FolderShared) { share = s } } }
                }
                DarkField(share, { share = it }, "Udział, np. Zdjecia", leading = Icons.Rounded.FolderShared)
                DarkField(name, { name = it }, "Nazwa połączenia (opcjonalnie)")
            }
        }
        Text("SMB 2/3. Połączenie zostanie sprawdzone przed zapisaniem; hasło jest szyfrowane kluczem Android Keystore.", color = Palette.TextFaint, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PillButton("Anuluj", primary = false, onClick = dismiss)
            PillButton("Połącz", enabled = host.isNotBlank() && share.isNotBlank() && !vm.busy, icon = Icons.Rounded.Link) { submit(name.ifBlank { "$share na $host" }, host, share, user, pass, domain) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun FlowRowCompat(content: @Composable () -> Unit) = FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }

enum class RenameMode(val label: String) { PATTERN("Wzorzec"), REPLACE("Zamień"), AFFIX("Przedrostek/przyrostek"), CASE("Wielkość liter") }

/** Wiele nazw naraz: wzorzec z numeracją i datą, zamiana tekstu, przedrostek/przyrostek, wielkość liter – z podglądem. */
@Composable fun BatchRenameDialog(vm: ExplorerModel, items: List<Item>, dismiss: () -> Unit) {
    var mode by remember { mutableStateOf(RenameMode.PATTERN) }
    var pattern by remember { mutableStateOf("Zdjęcie_{n}") }
    var start by remember { mutableStateOf("1") }
    var find by remember { mutableStateOf("") }; var replace by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }; var suffix by remember { mutableStateOf("") }
    var lower by remember { mutableStateOf(true) }
    val sorted = remember(items) { items.sortedWith { a, b -> naturalCompare(a.name, b.name) } }
    val digits = (sorted.size + (start.toIntOrNull() ?: 1)).toString().length.coerceAtLeast(2)
    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
    val result = sorted.mapIndexed { i, item ->
        val stem = if (item.directory) item.name else item.name.substringBeforeLast('.', item.name)
        val ext = if (item.directory || !item.name.contains('.') || item.name.startsWith('.') && item.name.count { it == '.' } == 1) "" else "." + item.name.substringAfterLast('.')
        val newStem = when (mode) {
            RenameMode.PATTERN -> pattern.replace("{n}", ((start.toIntOrNull() ?: 1) + i).toString().padStart(digits, '0')).replace("{nazwa}", stem).replace("{data}", date.format(java.util.Date(item.modified)))
            RenameMode.REPLACE -> if (find.isEmpty()) stem else stem.replace(find, replace, ignoreCase = true)
            RenameMode.AFFIX -> prefix + stem + suffix
            RenameMode.CASE -> if (lower) stem.lowercase() else stem.uppercase()
        }
        item to (newStem + ext)
    }
    val valid = result.all { runCatching { FileRules.validateName(it.second) }.isSuccess } && result.map { it.second.lowercase() }.distinct().size == result.size
    DarkDialog("Zmień nazwy ${items.size} elem.", dismiss, buttons = {
        PillButton("Anuluj", primary = false, onClick = dismiss)
        PillButton("Zmień", enabled = valid && result.any { it.first.name != it.second }) { dismiss(); vm.batchRename(result) }
    }) {
        Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { RenameMode.entries.forEach { m -> Chip(m.label, mode == m) { mode = m } } }
        when (mode) {
            RenameMode.PATTERN -> {
                DarkField(pattern, { pattern = it }, "Wzorzec: {n} numer, {nazwa}, {data}")
                DarkField(start, { start = it.filter(Char::isDigit).take(6) }, "Numer początkowy")
            }
            RenameMode.REPLACE -> { DarkField(find, { find = it }, "Znajdź"); DarkField(replace, { replace = it }, "Zamień na") }
            RenameMode.AFFIX -> { DarkField(prefix, { prefix = it }, "Przedrostek"); DarkField(suffix, { suffix = it }, "Przyrostek (przed rozszerzeniem)") }
            RenameMode.CASE -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip("małe litery", lower) { lower = true }; Chip("WIELKIE LITERY", !lower) { lower = false } }
        }
        Text("PODGLĄD", color = Palette.TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Card).padding(vertical = 4.dp)) {
            result.take(12).forEach { (item, name) ->
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text(item.name, fontSize = 11.sp, color = Palette.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(name, fontSize = 13.sp, color = if (runCatching { FileRules.validateName(name) }.isSuccess) Color.White else Palette.Record, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (result.size > 12) Text("… i ${result.size - 12} więcej", fontSize = 11.sp, color = Palette.TextDim, modifier = Modifier.padding(12.dp))
        }
        if (!valid) Text("Nazwy muszą być poprawne i niepowtarzalne.", color = Palette.Record, fontSize = 12.sp)
    }
}

@Composable private fun Modifier.horizontalScrollCompat() = this.then(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()))

/** Analiza miejsca: które pliki i foldery zajmują najwięcej, z paskami udziału. */
@Composable fun AnalysisPanel(vm: ExplorerModel, analysis: Analysis) {
    Panel("Zajęte miejsce", { vm.closeAnalysis() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(vm.title(analysis.place), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatSize(analysis.total)} · ${analysis.entries.size} elem." + if (analysis.done) "" else " · liczę…", fontSize = 12.sp, color = Palette.TextDim)
            }
            if (!analysis.done) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.White, strokeWidth = 2.dp)
        }
        val total = analysis.total.coerceAtLeast(1)
        Group(null) {
            analysis.entries.take(200).forEach { (item, size) ->
                Column(Modifier.fillMaxWidth().clickable { if (item.directory) { vm.closeAnalysis(); vm.navigate(item.place) } else { vm.closeAnalysis(); vm.open(item) } }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(iconFor(item), null, Modifier.size(18.dp), tint = Palette.TextDim)
                        Spacer(Modifier.width(8.dp))
                        Text(item.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(formatSize(size), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(" ${(size * 100 / total)}%", fontSize = 11.sp, color = Palette.TextDim, modifier = Modifier.width(40.dp))
                    }
                    Meter(size.toFloat() / total, Modifier.fillMaxWidth().padding(top = 5.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

