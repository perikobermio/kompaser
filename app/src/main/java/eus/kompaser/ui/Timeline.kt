package eus.kompaser.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import eus.kompaser.model.ChordEvent
import eus.kompaser.model.SongLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max as maxFloat
import kotlin.math.min as minFloat
import kotlin.math.roundToInt

private data class TimelineBlockPart(
	val index: Int,
	val event: ChordEvent,
	val start: Float,
	val beats: Float,
	val offset: Float,
)

internal data class TimelineDisplayRow(
	val lineIndex: Int?,
	val blocks: List<IndexedValue<ChordEvent>>,
)

/** Builds lyric rows and puts every rest on its own row immediately after its lyric line. */
internal fun timelineDisplayRows(lines: List<SongLine>, events: List<ChordEvent>): List<TimelineDisplayRow> {
	val byLine = events.withIndex().groupBy { it.value.line }
	val rows = mutableListOf<TimelineDisplayRow>()
	for (li in lines.indices) {
		val lineEvents = byLine[li].orEmpty()
		val chords = lineEvents.filterNot { it.value.isRest }
		if (chords.isNotEmpty() || lines[li].section != null) rows += TimelineDisplayRow(li, chords)
		lineEvents.filter { it.value.isRest }.forEach { rows += TimelineDisplayRow(null, listOf(it)) }
	}
	for (li in byLine.keys.filter { it !in lines.indices }.sorted()) {
		val extra = byLine[li].orEmpty()
		val chords = extra.filterNot { it.value.isRest }
		if (chords.isNotEmpty()) rows += TimelineDisplayRow(null, chords)
		extra.filter { it.value.isRest }.forEach { rows += TimelineDisplayRow(null, listOf(it)) }
	}
	return rows
}

/**
 * Operaciones de la línea de tiempo. Ninguna descuadra el resto de la canción: mover un acorde solo
 * cambia el anterior; quitarlo da su tiempo al anterior; insertar parte un bloque en dos.
 * Si hay tiempos del vídeo ([ChordEvent.t]) se mantienen coherentes.
 */
object Timeline {
	const val MIN = 0.5f

	/** La duración más corta posible: una semifusa (1/16 de tiempo en compases de x/4). */
	const val FINE = 1f / 16

	/** Mueve el inicio del acorde [i] [d] pulsos (negativo = antes). */
	fun moveStart(evs: List<ChordEvent>, i: Int, d: Float, secPerBeat: Double): List<ChordEvent> {
		val cur = evs.getOrNull(i) ?: return evs
		if (cur.beats - d < MIN) return evs
		val out = evs.toMutableList()
		if (i > 0) {
			val prev = evs[i - 1]
			if (prev.beats + d < MIN) return evs
			out[i - 1] = prev.copy(beats = prev.beats + d)
		}
		out[i] = cur.copy(beats = cur.beats - d, t = cur.t?.let { it + d * secPerBeat })
		return out
	}

	/** Quita el acorde [i]; su tiempo pasa al anterior (o al siguiente si es el primero). */
	fun remove(evs: List<ChordEvent>, i: Int): List<ChordEvent> {
		if (evs.size <= 1 || i !in evs.indices) return evs
		val out = evs.toMutableList()
		val cur = out.removeAt(i)
		if (i > 0) out[i - 1] = out[i - 1].copy(beats = out[i - 1].beats + cur.beats)
		else out[0] = out[0].copy(beats = out[0].beats + cur.beats, t = cur.t ?: out[0].t)
		return out
	}

	/**
	 * Deja los tiempos coherentes sin mover lo que ya está colocado: rellena los que faltan (siguiendo la
	 * duración del anterior), evita que un acorde empiece antes que el anterior y recalcula las duraciones
	 * a partir de los tiempos. Así cada marca solo cambia lo que tiene al lado.
	 */
	fun normalize(evs: List<ChordEvent>, secPerBeat: Double, offset: Double): List<ChordEvent> {
		if (evs.isEmpty()) return evs
		val n = evs.size
		val t = DoubleArray(n)
		for (k in 0 until n) {
			t[k] = evs[k].t ?: if (k == 0) offset else t[k - 1] + evs[k - 1].beats * secPerBeat
			if (k > 0 && t[k] < t[k - 1] + FINE * secPerBeat) t[k] = t[k - 1] + FINE * secPerBeat
		}
		return evs.mapIndexed { k, e ->
			val b = if (k < n - 1) (((t[k + 1] - t[k]) / secPerBeat * 16).roundToInt() / 16f).coerceAtLeast(FINE) else e.beats
			e.copy(t = t[k].coerceAtLeast(0.0), beats = b)
		}
	}

	/** Parte el bloque [i] en el pulso [at] (desde su inicio) e inserta ahí [chord] ("" = pausa). */
	fun split(evs: List<ChordEvent>, i: Int, at: Float, chord: String, secPerBeat: Double): List<ChordEvent> {
		val cur = evs.getOrNull(i) ?: return evs
		val a = at.coerceIn(MIN, cur.beats - MIN)
		if (cur.beats < 2 * MIN) return evs
		val out = evs.toMutableList()
		out[i] = cur.copy(beats = a)
		out.add(i + 1, ChordEvent(chord, cur.beats - a, cur.line, -1, cur.t?.let { it + a * secPerBeat }))
		return out
	}
}

internal fun isTabLine(s: String) = "|-" in s || "-|" in s || Regex("""^\s*[A-Ga-g][#b]?\|""").containsMatchIn(s)

private fun fmtBeats(x: Float) = when {
	x % 1f == 0f -> x.toInt().toString()
	x == 0.5f -> "½"
	x == 0.25f -> "¼"
	x == 0.125f -> "⅛"
	x == 0.0625f -> "1/16"
	(x * 2) % 1f == 0f -> "%.1f".format(x)
	else -> "%.2f".format(x).trimEnd('0')
}

private fun blockColor(e: ChordEvent): Color = when {
	e.chord == UNKNOWN_CHORD -> Fun.Pink.copy(alpha = 0.55f)
	e.isRest -> Fun.Purple.copy(alpha = 0.22f)
	else -> Fun.beats[Math.floorMod(e.chord.hashCode(), Fun.beats.size)].copy(alpha = 0.32f)
}

/**
 * Una línea de la letra con su pista de tiempo debajo. Cada acorde es un bloque proporcional a su
 * duración sobre la rejilla de pulsos (líneas más marcadas en cada compás).
 *  - Tocar: selecciona. Arrastrar el borde izquierdo: mueve el acorde (de medio en medio pulso).
 *  - Mantener pulsado en un punto: insertar ahí un acorde o una pausa.
 */
@Composable
fun TimelineRow(
	line: SongLine,
	blocks: List<IndexedValue<ChordEvent>>,
	rowStart: Double,
	beatsPerBar: Int,
	selected: Int?,
	timeLabel: String?,
	onSelect: (Int) -> Unit,
	onMove: (Int, Float) -> Unit,
	onInsert: (Int, Float, String) -> Unit,
	nowIndex: Int? = null,
	playhead: Double? = null,
	playedBefore: Int? = null,
	editable: Boolean = true,
	showMarks: Boolean = false,
	dimUnmarked: Boolean = false,
	onLongPress: ((Int) -> Unit)? = null,
	dragBlocks: Boolean = false,
	onBlockTap: ((Int) -> Unit)? = null,
	longPressEnabled: Boolean = true,
	modifier: Modifier = Modifier,
) {
	Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
		line.section?.let { Text(it, color = Fun.Purple, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
		Row(verticalAlignment = Alignment.CenterVertically) {
			if (timeLabel != null) Text(
				"$timeLabel  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			// Las líneas de tablatura (E|--3--…) no se muestran aquí: solo ocupan sitio.
			if (line.lyric.isNotBlank() && !isTabLine(line.lyric)) {
				Text(line.lyric, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1)
			}
		}
		if (blocks.isEmpty()) return@Column
		val rowBeats = blocks.sumOf { it.value.beats.toDouble() }.toFloat()
		BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp)) {
			// Cada fila admite dos compases; lo que excede continúa debajo.
			val rowCapacity = (beatsPerBar * 2).coerceAtLeast(1)
			val scale: Dp = max(18.dp, min(26.dp, maxWidth / rowCapacity))
			val density = LocalDensity.current
			val scalePx = with(density) { scale.toPx() }
			val measureCount = ceil(rowBeats / rowCapacity).toInt().coerceAtLeast(1)
			val measures = remember(blocks, beatsPerBar) {
				List(measureCount) { m ->
					val mStart = m * rowCapacity.toFloat()
					val mEnd = minFloat(rowBeats, mStart + rowCapacity)
					var eventStart = 0f
					buildList {
						for ((index, event) in blocks) {
							val eventEnd = eventStart + event.beats
							val partStart = maxFloat(eventStart, mStart)
							val partEnd = minFloat(eventEnd, mEnd)
							if (partEnd > partStart + 1e-4f) add(
								TimelineBlockPart(index, event, partStart - mStart, partEnd - partStart, partStart - eventStart),
							)
							eventStart = eventEnd
						}
					}
				}
			}
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				for ((measureIndex, parts) in measures.withIndex()) {
					val measureStart = measureIndex * rowCapacity.toFloat()
					val measureBeats = minFloat(rowBeats - measureStart, rowCapacity.toFloat()).coerceAtLeast(0f)
					Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
						// Arrastrar en cualquier segmento sigue moviendo el acorde original.
						val currentParts by rememberUpdatedState(parts)
						val dragMove by rememberUpdatedState(onMove)
						val rowDrag = if (!dragBlocks) Modifier else Modifier.pointerInput(Unit) {
							var target = -1
							var acc = 0f
							detectHorizontalDragGestures(
								onDragStart = { p ->
									acc = 0f
									val x = p.x / scalePx
									target = currentParts.firstOrNull { x >= it.start && x < it.start + it.beats }?.index ?: -1
								},
							) { change, dx ->
								if (target < 0) return@detectHorizontalDragGestures
								change.consume()
								acc += dx
								while (acc >= scalePx) { dragMove(target, 1f); acc -= scalePx }
								while (acc <= -scalePx) { dragMove(target, -1f); acc += scalePx }
							}
						}
						Box(Modifier.width(scale * measureBeats).height(46.dp).then(rowDrag)) {
							val grid = MaterialTheme.colorScheme.outline
							Canvas(Modifier.fillMaxSize()) {
								var b = 0f
								while (b <= measureBeats + 1e-3f) {
									val x = b * scalePx
								val globalBeat = rowStart + measureStart + b
								val bar = abs(globalBeat % beatsPerBar) < 1e-3
									drawLine(grid.copy(alpha = if (bar) 0.9f else 0.35f), Offset(x, 0f), Offset(x, size.height), if (bar) 2.5f else 1f)
									b += 1f
								}
							}
							for (part in parts) {
								val i = part.index
								val partEvent = part.event.copy(beats = part.beats)
								Block(
									partEvent, i, part.start, scale, scalePx, selected == i, onSelect, onMove,
										onInsert = { eventIndex, at, chord -> onInsert(eventIndex, part.offset + at, chord) },
									played = playedBefore != null && i < playedBefore,
									editable = editable, showMarks = showMarks, dimUnmarked = dimUnmarked, onLongPress = onLongPress,
									onBlockTap = onBlockTap, longPressEnabled = longPressEnabled,
									progress = if (nowIndex == i && playhead != null) {
										((playhead - rowStart - measureStart - part.start) / part.beats).toFloat().coerceIn(0f, 1f)
									} else null,
								)
							}
						}
					}
				}
			}
		}
	}
}

@Composable
private fun Block(
	e: ChordEvent, i: Int, startBeats: Float, scale: Dp, scalePx: Float, isSelected: Boolean,
	onSelect: (Int) -> Unit, onMove: (Int, Float) -> Unit, onInsert: (Int, Float, String) -> Unit,
	played: Boolean = false, progress: Float? = null,
	editable: Boolean = true, showMarks: Boolean = false, dimUnmarked: Boolean = false,
	onLongPress: ((Int) -> Unit)? = null, dragBlock: Boolean = false,
	onBlockTap: ((Int) -> Unit)? = null, longPressEnabled: Boolean = true,
) {
	var menuAt by remember { mutableStateOf<Float?>(null) }
	val move by rememberUpdatedState(onMove)
	Box(
		Modifier.offset(x = scale * startBeats).width(scale * e.beats).fillMaxHeight().padding(horizontal = 1.dp, vertical = 3.dp)
			.alpha(if (dimUnmarked && !e.manual) 0.45f else 1f)
			.clip(RoundedCornerShape(8.dp)).background(blockColor(e))
			.then(if (isSelected) Modifier.border(2.5.dp, Fun.Coral, RoundedCornerShape(8.dp)) else Modifier)
			.pointerInput(i) {
				detectTapGestures(
					onTap = { onBlockTap?.invoke(i) ?: onSelect(i) },
					onLongPress = { p ->
						if (longPressEnabled) {
							if (onLongPress != null) onLongPress(i)
							else {
								onSelect(i)
								if (editable) menuAt = ((p.x / scalePx) * 2).roundToInt() / 2f
							}
						}
					},
				)
			},
	) {
		// Iluminación según avanza la canción: lo ya sonado en turquesa; el bloque actual se va rellenando.
		if (played) Box(Modifier.fillMaxSize().background(Fun.Turquoise.copy(alpha = 0.35f)))
		if (progress != null) Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(Fun.Turquoise.copy(alpha = 0.7f)))
		BasicText(
			if (e.isRest) "pausa" else e.chord,
			Modifier.align(Alignment.Center).padding(horizontal = 6.dp),
			style = TextStyle(fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
			maxLines = 1, softWrap = false,
			autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 16.sp),
		)
		Text(
			fmtBeats(e.beats), Modifier.align(Alignment.BottomEnd).padding(end = 4.dp),
			fontSize = 8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		// Punto en los marcados a mano.
		if (showMarks && e.manual) Box(
			Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 4.dp).size(7.dp).clip(CircleShape).background(Fun.Purple),
		)
		// Asa para arrastrar el inicio del acorde.
		var acc by remember { mutableFloatStateOf(0f) }
		if (editable) Box(
			Modifier.align(Alignment.CenterStart).width(14.dp).fillMaxHeight()
				.pointerInput(i) {
					detectHorizontalDragGestures(onDragStart = { acc = 0f; onSelect(i) }) { change, dx ->
						change.consume()
						acc += dx
						val step = scalePx / 2 // medio pulso
						while (acc >= step) { move(i, 0.5f); acc -= step }
						while (acc <= -step) { move(i, -0.5f); acc += step }
					}
				},
		) {
			Box(
				Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight().padding(vertical = 6.dp)
					.clip(RoundedCornerShape(2.dp)).background(if (isSelected) Fun.Coral else Color.Black.copy(alpha = 0.25f)),
			)
		}
		DropdownMenu(menuAt != null, { menuAt = null }) {
			val at = menuAt ?: 0f
			DropdownMenuItem(text = { Text("Insertar acorde aquí") }, onClick = { menuAt = null; onInsert(i, at, UNKNOWN_CHORD) })
			DropdownMenuItem(text = { Text("Insertar pausa aquí") }, onClick = { menuAt = null; onInsert(i, at, "") })
		}
	}
}

/**
 * Barra del bloque seleccionado: mover su inicio (◀ ▶, de [step] en [step] pulsos), cambiar el acorde,
 * quitarlo (su tiempo pasa al anterior) y cerrar.
 */
@Composable
fun TimelineToolbar(
	e: ChordEvent, step: Float, onStep: () -> Unit, onMove: (Float) -> Unit, onPick: () -> Unit,
	onRemove: (() -> Unit)?, onClose: () -> Unit, modifier: Modifier = Modifier,
) {
	Surface(modifier, tonalElevation = 6.dp, shadowElevation = 8.dp) {
		Row(
			Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
			verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Column(Modifier.width(64.dp)) {
				Text(if (e.isRest) "pausa" else e.chord, fontWeight = FontWeight.Black, maxLines = 1)
				TextButton(onClick = onStep, contentPadding = PaddingValues(0.dp)) {
					Text(if (step == 1f) "paso 1" else "paso ½", style = MaterialTheme.typography.labelSmall)
				}
			}
			FilledTonalIconButton(onClick = { onMove(-step) }) { Icon(Icons.Filled.ChevronLeft, "Antes") }
			FilledTonalIconButton(onClick = { onMove(step) }) { Icon(Icons.Filled.ChevronRight, "Después") }
			TextButton(onClick = onPick) {
				Icon(Icons.Filled.MusicNote, null)
				Text(if (e.isRest) "Acorde" else "Cambiar")
			}
			IconButton(onClick = { onRemove?.invoke() }, enabled = onRemove != null) { Icon(Icons.Filled.Delete, "Quitar") }
			IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cerrar") }
		}
	}
}
