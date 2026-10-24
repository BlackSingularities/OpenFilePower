package pl.openfilepower.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import pl.openfilepower.Preferences

/**
 * Zestaw kolorów jednego motywu. Nazwy są „z perspektywy ciemnego motywu”: [white] to kolor
 * pierwszego planu (tekst, ikony, akcent), [ink] to jego przeciwieństwo – tło i tekst na akcencie.
 * W jasnym motywie oba zamieniają się miejscami, więc cały interfejs odwraca się bez zmian w kodzie.
 */
class Scheme(
    val white: Color, val ink: Color, val panel: Color, val panelHigh: Color, val card: Color,
    val textDim: Color, val textFaint: Color, val line: Color, val folder: Color, val record: Color, val dark: Boolean,
)

private val DarkScheme = Scheme(
    white = Color(0xFFFFFFFF), ink = Color(0xFF000000), panel = Color(0xFF000000), panelHigh = Color(0xFF1C1C1C), card = Color(0x0FFFFFFF),
    textDim = Color(0xB3FFFFFF), textFaint = Color(0x66FFFFFF), line = Color(0x1FFFFFFF), folder = Color(0xFFFFC94A), record = Color(0xFFFF3B47), dark = true,
)
private val LightScheme = Scheme(
    white = Color(0xFF101010), ink = Color(0xFFFFFFFF), panel = Color(0xFFFFFFFF), panelHigh = Color(0xFFEFEFEF), card = Color(0x0D000000),
    textDim = Color(0x99000000), textFaint = Color(0x61000000), line = Color(0x1F000000), folder = Color(0xFFE39B00), record = Color(0xFFD32030), dark = false,
)

/** Czarno-biała paleta wspólna z OpenPDF: akcent = pierwszy plan, czerwień dla usuwania. */
object Palette {
    internal var scheme by mutableStateOf(DarkScheme)
    val White get() = scheme.white
    val Ink get() = scheme.ink
    val Panel get() = scheme.panel
    val PanelHigh get() = scheme.panelHigh
    val Card get() = scheme.card
    val Record get() = scheme.record
    val TextDim get() = scheme.textDim
    val TextFaint get() = scheme.textFaint
    val Line get() = scheme.line
    val Folder get() = scheme.folder
    val dark get() = scheme.dark
}

enum class ThemeMode(val label: String) { AUTO("Auto"), LIGHT("Jasny"), DARK("Ciemny") }

/** Wybrany motyw: trzymany poza modelem, bo [AppTheme] opakowuje cały ekran. */
object Theme {
    var mode by mutableStateOf(ThemeMode.AUTO)
    fun load(context: android.content.Context) {
        mode = runCatching { ThemeMode.valueOf(Preferences(context).text("theme", ThemeMode.AUTO.name)) }.getOrDefault(ThemeMode.AUTO)
    }
}

private fun colors(s: Scheme) = (if (s.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = s.white, onPrimary = s.ink, secondary = s.white.copy(alpha = 0.85f), onSecondary = s.ink,
    background = s.panel, onBackground = s.white, surface = s.panel, onSurface = s.white,
    surfaceVariant = s.panelHigh, onSurfaceVariant = s.textDim, surfaceContainerLow = s.panel, surfaceContainer = s.panelHigh,
    surfaceContainerHigh = s.panelHigh, surfaceContainerHighest = s.panelHigh, outline = s.white.copy(alpha = 0.2f), error = s.record,
)

@Composable fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    remember { Theme.load(context) }
    val dark = when (Theme.mode) { ThemeMode.AUTO -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val active = if (dark) DarkScheme else LightScheme
    Palette.scheme = active
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        window.decorView.setBackgroundColor(active.panel.toArgb())
        WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
    }
    MaterialTheme(colorScheme = colors(active), typography = Typography()) {
        CompositionLocalProvider(LocalContentColor provides active.white, content = content)
    }
}

val Pill = RoundedCornerShape(50)

/** Sprężyste wciśnięcie z haptyką – jak w OpenPDF. */
@Composable fun Modifier.pressable(label: String?, enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.45f, stiffness = 700f), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.38f }
        .then(if (onLongClick != null) Modifier.pointerInput(enabled) {
            detectTapGestures(onLongPress = { if (enabled) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() } }, onTap = { if (enabled) onClick() })
        } else Modifier.clickable(source, null, enabled = enabled, role = Role.Button) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() })
        .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
}

/** Okrągły przycisk z ikoną; aktywny – biały blok z czarną ikoną. */
@Composable fun RoundIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, active: Boolean = false, enabled: Boolean = true, badge: String? = null, size: Dp = 44.dp, flat: Boolean = false, tint: Color? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) Palette.White else if (flat) Color.Transparent else Palette.PanelHigh, tween(160), label = "bg")
    Box(modifier.pressable(label, enabled, onLongClick, onClick).size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint ?: if (active) Palette.Ink else Palette.White, modifier = Modifier.size(size * 0.5f))
        if (badge != null) Text(badge, color = Palette.Ink, fontSize = 9.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).background(Palette.White, Pill).padding(horizontal = 4.dp))
    }
}

/** Przycisk-pigułka: biały (główny), półprzezroczysty albo czerwony (niebezpieczny). */
@Composable fun PillButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, danger: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val bg = when { !enabled -> Palette.White.copy(alpha = 0.08f); danger -> Palette.Record; primary -> Palette.White; else -> Palette.White.copy(alpha = 0.1f) }
    val fg = when { !enabled -> Palette.TextFaint; danger -> Color.White; primary -> Palette.Ink; else -> Palette.White }
    Row(modifier.pressable(text, enabled, onClick = onClick).clip(Pill).background(bg).padding(horizontal = if (icon != null) 14.dp else 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
    }
}

@Composable fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = Palette.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Grupa ustawień: nagłówek i zaokrąglona karta (opcjonalnie zwijana). */
@Composable fun Group(title: String?, collapsible: Boolean = false, initiallyExpanded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(title) { mutableStateOf(initiallyExpanded) }
    Column {
        if (title != null) Row(Modifier.fillMaxWidth().then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier).padding(start = 4.dp, end = 4.dp, bottom = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), color = Palette.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
            if (collapsible) Icon(Icons.Rounded.ExpandMore, if (expanded) "Zwiń $title" else "Rozwiń $title", tint = Palette.White, modifier = Modifier.size(18.dp).rotate(if (expanded) 0f else -90f))
        }
        AnimatedVisibility(visible = !collapsible || expanded) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card).padding(vertical = 2.dp), content = content)
        }
    }
}

@Composable fun RowIcon(icon: ImageVector, tint: Color = Palette.White) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
}

@Composable fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, enabled: Boolean = true, danger: Boolean = false, trailing: @Composable (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon, if (danger) Palette.Record else Palette.White)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontSize = 14.sp, color = when { !enabled -> Palette.TextFaint; danger -> Palette.Record; else -> Palette.White }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = Palette.TextDim, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable fun SwitchRow(icon: ImageVector, title: String, subtitle: String?, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!value) }.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontSize = 14.sp, color = if (enabled) Palette.White else Palette.TextFaint)
            if (subtitle != null) Text(subtitle, fontSize = 11.sp, color = Palette.TextDim, lineHeight = 14.sp)
        }
        Switch(value, onChange, enabled = enabled, modifier = Modifier.scale(0.82f), colors = SwitchDefaults.colors(checkedTrackColor = Palette.White, checkedThumbColor = Palette.Ink))
    }
}

@Composable fun InfoRow(icon: ImageVector, title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(icon)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, fontSize = 11.sp, color = Palette.TextDim)
            Text(value.ifBlank { "—" }, fontSize = 13.sp, color = Palette.White)
        }
    }
}

/** Wybór w jednej linii z przełącznikiem segmentowym po prawej. */
@Composable fun <T> CompactChoice(rowIcon: ImageVector, title: String, options: List<T>, selected: T, label: (T) -> String, icon: ((T) -> ImageVector)? = null, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RowIcon(rowIcon)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label(selected), fontSize = 11.sp, color = Palette.TextDim)
        }
        Row(Modifier.clip(Pill).background(Palette.Card).padding(2.dp)) {
            options.forEach { o ->
                val sel = o == selected
                Box(Modifier.clip(Pill).background(if (sel) Palette.White else Color.Transparent).selectable(selected = sel, role = Role.RadioButton, onClick = { onSelect(o) }).padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    if (icon != null) Icon(icon(o), label(o), tint = if (sel) Palette.Ink else Palette.TextDim, modifier = Modifier.size(16.dp))
                    else Text(label(o), fontSize = 11.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium, color = if (sel) Palette.Ink else Palette.TextDim)
                }
            }
        }
    }
}

@Composable fun <T> Chips(icon: ImageVector, title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { RowIcon(icon); Text(title, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 12.dp)) }
        Row(Modifier.padding(start = 44.dp, top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o -> Chip(label(o), o == selected) { onSelect(o) } }
        }
    }
}

@Composable fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(modifier.clip(Pill).background(if (selected) Palette.White else Palette.White.copy(alpha = 0.08f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (selected) Palette.Ink else Palette.White, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, color = if (selected) Palette.Ink else Palette.White, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

/** Ciemne okno dialogowe w stylu aplikacji. */
@Composable fun DarkDialog(title: String, onDismiss: () -> Unit, buttons: @Composable RowScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(20.dp).widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.PanelHigh).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.White)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = buttons)
        }
    }
}

@Composable fun DarkField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, password: Boolean = false, error: Boolean = false, leading: ImageVector? = null, onDone: (() -> Unit)? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, isError = error,
        leadingIcon = leading?.let { { Icon(it, null) } },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else KeyboardType.Text, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.White, unfocusedBorderColor = Palette.White.copy(alpha = 0.25f), focusedLabelColor = Palette.White, unfocusedLabelColor = Palette.TextDim, cursorColor = Palette.White, focusedTextColor = Palette.White, unfocusedTextColor = Palette.White),
    )
}

/**
 * Panel z nagłówkiem i przyciskiem zamknięcia: na wąskim ekranie – na cały ekran,
 * na szerokim – kolumna przy prawej krawędzi (lista plików zostaje widoczna).
 */
@Composable fun Panel(title: String, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {}, scrollable: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Box(Modifier.fillMaxSize().background(if (wide) Color.Black.copy(alpha = if (Palette.dark) 0.35f else 0.18f) else Color.Transparent).pointerInput(Unit) { detectTapGestures { onClose() } })
        Column(
            Modifier.then(if (wide) Modifier.align(Alignment.CenterEnd).width(420.dp).fillMaxHeight() else Modifier.fillMaxSize())
                .background(Palette.Panel).pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
        ) {
            Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions()
                Spacer(Modifier.width(6.dp))
                RoundIcon(Icons.Rounded.Close, "Zamknij", size = 38.dp, onClick = onClose)
            }
            Column(Modifier.fillMaxWidth().weight(1f).then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

/** Pasek postępu w stylu aplikacji. */
@Composable fun Meter(fraction: Float, modifier: Modifier = Modifier, danger: Boolean = false) {
    Box(modifier.height(6.dp).clip(Pill).background(Palette.White.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(Pill).background(if (danger) Palette.Record else Palette.White))
    }
}
