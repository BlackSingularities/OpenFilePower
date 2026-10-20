package pl.openfilepower.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.openfilepower.ExplorerModel
import pl.openfilepower.Item
import pl.openfilepower.Place

/**
 * Przytrzymanie elementu zaznacza go (albo odznacza), a przesuwanie palca w górę lub w dół
 * rozciąga to samo działanie na wszystkie elementy pomiędzy; przy krawędzi lista sama się przewija.
 * Klucze elementów listy muszą być równe `place.toString()`.
 */
@Composable fun Modifier.dragSelect(vm: ExplorerModel, state: ScrollableState, items: () -> List<Item>): Modifier {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    return this.then(Modifier.pointerInput(state) {
        fun keyAt(o: Offset): Any? = when (state) {
            is LazyListState -> state.layoutInfo.visibleItemsInfo.firstOrNull { o.y.toInt() in it.offset until it.offset + it.size }?.key
            is LazyGridState -> state.layoutInfo.visibleItemsInfo.firstOrNull { o.x.toInt() in it.offset.x until it.offset.x + it.size.width && o.y.toInt() in it.offset.y until it.offset.y + it.size.height }?.key
            else -> null
        }
        fun indexAt(o: Offset): Int? { val key = keyAt(o) ?: return null; return items().indexOfFirst { it.place.toString() == key }.takeIf { it >= 0 } }
        var anchor = -1
        var initial = emptySet<Place>()
        var adding = true
        var last = Offset.Zero
        var scroller: Job? = null
        fun apply(o: Offset) {
            val i = indexAt(o) ?: return
            val list = items()
            val range = list.subList(minOf(anchor, i), maxOf(anchor, i) + 1).map { it.place }
            vm.setSelection(if (adding) initial + range else initial - range.toSet())
        }
        detectDragGesturesAfterLongPress(
            onDragStart = { o ->
                val i = indexAt(o) ?: return@detectDragGesturesAfterLongPress
                anchor = i; last = o; initial = vm.selected
                adding = items()[i].place !in initial
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                apply(o)
                scroller = scope.launch {
                    while (isActive) {
                        val edge = 64.dp.toPx()
                        val speed = when {
                            last.y < edge -> -(edge - last.y) / 3
                            last.y > size.height - edge -> (last.y - (size.height - edge)) / 3
                            else -> 0f
                        }
                        if (speed != 0f && anchor >= 0) { state.scrollBy(speed); apply(last) }
                        delay(16)
                    }
                }
            },
            onDrag = { change, _ -> if (anchor >= 0) { change.consume(); last = change.position; apply(last) } },
            onDragEnd = { anchor = -1; scroller?.cancel() },
            onDragCancel = { anchor = -1; scroller?.cancel() },
        )
    })
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
