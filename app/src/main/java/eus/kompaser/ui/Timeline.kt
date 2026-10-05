package eus.kompaser.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.draw.clip
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
import kotlin.math.roundToInt

/**
 * Operaciones de la línea de tiempo. Ninguna descuadra el resto de la canción: mover un acorde solo
 * cambia el anterior; quitarlo da su tiempo al anterior; insertar parte un bloque en dos.
 * Si hay tiempos del vídeo ([ChordEvent.t]) se mantienen coherentes.
 */
object Timeline {
	const val MIN = 0.5f

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

private fun fmtBeats(x: Float) = if (x % 1f == 0f) x.toInt().toString() else "%.1f".format(x)

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
) {
	Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
		line.section?.let { Text(it, color = Fun.Purple, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
		Row(verticalAlignment = Alignment.CenterVertically) {
			if (timeLabel != null) Text(
				"$timeLabel  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (line.lyric.isNotBlank()) Text(line.lyric, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 1)
		}
		if (blocks.isEmpty()) return@Column
		val rowBeats = blocks.sumOf { it.value.beats.toDouble() }.toFloat()
		BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 2.dp)) {
			// Escala común (hasta 26 dp por pulso) que encaja la fila en el ancho; si así los bloques quedarían
			// ilegibles (fila muy larga), se mantiene un mínimo y la fila se desplaza en horizontal.
			val scale: Dp = max(18.dp, min(26.dp, maxWidth / rowBeats.coerceAtLeast(1f)))
			val density = LocalDensity.current
			val scalePx = with(density) { scale.toPx() }
			Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
			Box(Modifier.width(scale * rowBeats).height(46.dp)) {
				// Rejilla: un trazo por pulso, más marcado en cada compás.
				val grid = MaterialTheme.colorScheme.outline
				Canvas(Modifier.fillMaxSize()) {
					var b = kotlin.math.ceil(rowStart).toFloat()
					while (b <= rowStart + rowBeats + 1e-3) {
						val x = ((b - rowStart) * scalePx).toFloat()
						val bar = abs(b % beatsPerBar) < 1e-3
						drawLine(grid.copy(alpha = if (bar) 0.9f else 0.35f), Offset(x, 0f), Offset(x, size.height), if (bar) 2.5f else 1f)
						b += 1f
					}
				}
				var x = 0f
				for ((i, e) in blocks) {
					Block(
						e, i, x, scale, scalePx, selected == i, onSelect, onMove, onInsert,
					)
					x += e.beats
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
) {
	var menuAt by remember { mutableStateOf<Float?>(null) }
	val move by rememberUpdatedState(onMove)
	Box(
		Modifier.offset(x = scale * startBeats).width(scale * e.beats).fillMaxHeight().padding(horizontal = 1.dp, vertical = 3.dp)
			.clip(RoundedCornerShape(8.dp)).background(blockColor(e))
			.then(if (isSelected) Modifier.border(2.5.dp, Fun.Coral, RoundedCornerShape(8.dp)) else Modifier)
			.pointerInput(i) {
				detectTapGestures(
					onTap = { onSelect(i) },
					onLongPress = { p ->
						onSelect(i)
						menuAt = ((p.x / scalePx) * 2).roundToInt() / 2f
					},
				)
			},
	) {
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
		// Asa para arrastrar el inicio del acorde.
		var acc by remember { mutableFloatStateOf(0f) }
		Box(
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
