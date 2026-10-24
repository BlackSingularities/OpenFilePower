package pl.openfilepower.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import pl.openfilepower.*

/** Edytor plików tekstowych do 2 MB: zapis z potwierdzeniem przy niezapisanych zmianach. */
@Composable fun TextEditor(vm: ExplorerModel, state: EditorState, close: () -> Unit) {
    var confirmClose by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }
    var fontSize by remember { mutableFloatStateOf(13f) }
    val tryClose = { if (state.modified) confirmClose = true else close() }
    BackHandler(onBack = tryClose)
    Column(Modifier.fillMaxSize().background(Palette.Panel).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundIcon(Icons.Rounded.Close, "Zamknij edytor", onClick = tryClose)
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(state.item.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (state.modified) "● Niezapisane zmiany · " else "") + "${state.text.lines().size} wierszy · ${formatSize(state.text.toByteArray().size.toLong())}", fontSize = 11.sp, color = if (state.modified) Palette.White else Palette.TextDim)
            }
            RoundIcon(Icons.Rounded.TextDecrease, "Mniejsza czcionka", size = 38.dp, flat = true) { fontSize = (fontSize - 1).coerceAtLeast(9f) }
            RoundIcon(Icons.Rounded.TextIncrease, "Większa czcionka", size = 38.dp, flat = true) { fontSize = (fontSize + 1).coerceAtMost(24f) }
            RoundIcon(Icons.AutoMirrored.Rounded.WrapText, "Zawijanie wierszy", active = wrap, size = 38.dp) { wrap = !wrap }
            PillButton("Zapisz", enabled = state.modified && !vm.busy && !state.readOnly, icon = Icons.Rounded.Save) { vm.saveEditor() }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).verticalScroll(rememberScrollState()).then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))) {
            BasicTextField(
                state.text, { vm.editor = state.copy(text = it) },
                Modifier.then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = 400.dp)).padding(bottom = 80.dp),
                readOnly = state.readOnly,
                textStyle = TextStyle(color = Palette.White, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.45f).sp),
                cursorBrush = SolidColor(Palette.White),
            )
        }
    }
    if (confirmClose) DarkDialog("Zapisać zmiany?", { confirmClose = false }, buttons = {
        PillButton("Odrzuć", primary = false) { confirmClose = false; close() }
        PillButton("Zapisz") { confirmClose = false; vm.saveEditor(); close() }
    }) { Text("Plik ${state.item.name} ma niezapisane zmiany.", color = Palette.TextDim, fontSize = 13.sp) }
}

