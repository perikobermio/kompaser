package eus.kompaser.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eus.kompaser.model.ChordEvent
import eus.kompaser.model.ChordSheet
import eus.kompaser.model.Song
import eus.kompaser.model.SongLine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * Marca cada cambio de acorde en orden. La primera pulsación fija el inicio; cada «Siguiente» calcula
 * cuántos tiempos han pasado según el BPM y encaja el cambio en esa rejilla. «Vacío» añade un acorde
 * pendiente en esa misma secuencia. Se puede guardar a medias y seguir otro día.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TapScreen(song: Song, onBack: () -> Unit, onSave: (Song) -> Unit, onSaveStay: (Song) -> Unit = onSave) {
	val videoId = song.youtubeId
	if (videoId == null) {
		LaunchedEffect(Unit) { onBack() }
		return
	}
	var bpm by remember(song.id) { mutableFloatStateOf(song.bpm) }
	val secPerBeat = 60.0 / bpm
	val bar = song.beatsPerBar.toFloat()
	val offset = song.videoOffsetMs / 1000.0
	val sourceEvents = remember(song.id) { ChordSheet.defaultEvents(song.lines, bar) }
	var events by remember(song.id) { mutableStateOf(Timeline.normalize(song.events, secPerBeat, offset)) }
	// Siguiente acorde por marcar: el primero que no está marcado a mano.
	var cursor by remember(song.id) { mutableIntStateOf(events.indexOfFirst { !it.manual }.let { if (it < 0) events.size else it }) }
	var sourceCursor by remember(song.id) {
		mutableIntStateOf(if (song.events.isEmpty()) 0 else {
			var progress = 0
			var mismatch = false
			for (event in song.events) {
				if (event.chord == UNKNOWN_CHORD && event.pos == -1) continue
				val source = sourceEvents.getOrNull(progress)
				if (source == null || event.chord != source.chord || event.line != source.line || event.pos != source.pos) {
					mismatch = true
					break
				}
				progress++
			}
			if (mismatch) sourceEvents.size else progress
		})
	}
	val history = remember { mutableStateListOf<Pair<List<ChordEvent>, Int>>() }
	var changed by remember { mutableStateOf(false) }
	var confirmExit by remember { mutableStateOf(false) }
	var confirmReset by remember { mutableStateOf(false) }
	var menu by remember { mutableStateOf(false) }
	var picking by remember { mutableStateOf<Int?>(null) }
	// Bloque seleccionado para poder iniciar el marcado desde ese punto.
	var selected by remember { mutableStateOf<Int?>(null) }
	var notice by remember { mutableStateOf<String?>(null) }
	val video = remember { VideoSync() }
	val view = LocalView.current
	DisposableEffect(Unit) {
		view.keepScreenOn = true
		onDispose { view.keepScreenOn = false }
	}
	LaunchedEffect(notice) {
		if (notice != null) {
			delay(1800)
			notice = null
		}
	}

	fun now() = video.seconds(System.nanoTime())

	fun apply(evs: List<ChordEvent>, newCursor: Int) {
		history += events to cursor
		events = Timeline.normalize(evs, secPerBeat, offset)
		cursor = newCursor.coerceIn(0, events.size)
		changed = true
		view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
	}

	/** Guarda una secuencia ya encajada a pulsos enteros sin recalcularla desde segundos. */
	fun applyMarked(evs: List<ChordEvent>, newCursor: Int) {
		history += events to cursor
		events = evs.map { e ->
			if (e.manual) e.copy(beats = kotlin.math.round(e.beats).toFloat().coerceAtLeast(1f)) else e
		}
		cursor = newCursor.coerceIn(0, events.size)
		changed = true
		view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
	}

	/** Marca el evento actual; el comienzo de cada línea nueva es independiente de la anterior. */
	fun markAt(t: Double) {
		// Una pausa insertada entre líneas ocupa su propio compás, fuera del reparto de acordes.
		if (events.getOrNull(cursor)?.isRest == true) {
			val out = events.toMutableList()
			val rest = out[cursor]
			val delta = t - (rest.t ?: t)
			if (cursor > 0) {
				val prev = out[cursor - 1]
				val prevStart = prev.t ?: t
				out[cursor - 1] = prev.copy(beats = ((t - prevStart) / secPerBeat).toFloat().coerceAtLeast(1f))
			}
			out[cursor] = rest.copy(t = t, manual = true)
			for (i in cursor + 1 until out.size) out[i].t?.let { out[i] = out[i].copy(t = it + delta) }
			applyMarked(out, cursor + 1)
			selected = null
			return
		}
		if (cursor >= events.size) {
			val last = events.lastOrNull() ?: return
			val lt = last.t ?: return
			var first = events.lastIndex
			while (first > 0 && events[first - 1].line == last.line) first--
			val lineStart = events[first].t ?: lt
			val lastOffset = kotlin.math.round((lt - lineStart) / secPerBeat).toInt()
			val beats = (bar.toInt() * 2 - lastOffset).coerceAtLeast(1)
			applyMarked(events.dropLast(1) + last.copy(beats = beats.toFloat(), manual = true), events.size)
			selected = null
			notice = "Final marcado · línea de ${bar.toInt() * 2} tiempos"
			return
		}

		val out = events.toMutableList()
		val currentLine = out[cursor].line
		var first = cursor
		while (first > 0 && out[first - 1].line == currentLine) first--
		var last = cursor
		while (last + 1 < out.size && out[last + 1].line == currentLine) last++
		val lineBeats = bar.toInt() * 2
		if (last - first + 1 > lineBeats) {
			notice = "Esta línea tiene más acordes que tiempos disponibles ($lineBeats)"
			return
		}

		val lineStart: Double
		val targetOffset: Int
		if (cursor == first) {
			// El instante marcado fija el inicio del nuevo compás. No se completa ni se
			// redimensiona el compás anterior para encajarlo en una cuadrícula global.
			lineStart = t
			targetOffset = 0
		} else {
			lineStart = out[first].t ?: t
			val previousOffset = kotlin.math.round((out[cursor - 1].t!! - lineStart) / secPerBeat).toInt()
			val minOffset = previousOffset + 1
			val maxOffset = lineBeats - (last - cursor + 1)
			if (minOffset > maxOffset) {
				notice = "La línea ya ocupa sus $lineBeats tiempos"
				return
			}
			targetOffset = kotlin.math.round((t - lineStart) / secPerBeat).toInt().coerceIn(minOffset, maxOffset)
			for (k in first until cursor - 1) {
				val duration = kotlin.math.round((out[k + 1].t!! - out[k].t!!) / secPerBeat).toInt().coerceAtLeast(1)
				out[k] = out[k].copy(beats = duration.toFloat())
			}
			out[cursor - 1] = out[cursor - 1].copy(beats = (targetOffset - previousOffset).toFloat())
		}

		val remaining = lineBeats - targetOffset
		val count = last - cursor + 1
		val each = remaining / count
		val extra = remaining % count
		var beatOffset = targetOffset
		for (k in cursor..last) {
			val duration = each + if (k - cursor < extra) 1 else 0
			out[k] = out[k].copy(t = lineStart + beatOffset * secPerBeat, beats = duration.toFloat(), manual = if (k == cursor) true else out[k].manual)
			beatOffset += duration
		}
		applyMarked(out, cursor + 1)
		selected = null
	}

	/** Añade un acorde al final de la secuencia. */
	fun appendEvent(event: ChordEvent, t: Double): Boolean {
		val out = events.toMutableList()
		val prevIndex = out.lastIndex
		val prev = out.lastOrNull()
		val prevTime = prev?.t
		val lineBeats = bar.toInt() * 2
		val start: Double
		val duration: Int
		if (prev == null || prevTime == null) {
			start = t
			duration = lineBeats
		} else {
			var first = prevIndex
			while (first > 0 && out[first - 1].line == prev.line) first--
			val lineStart = out[first].t ?: prevTime
			val prevOffset = kotlin.math.round((prevTime - lineStart) / secPerBeat).toInt().coerceIn(0, lineBeats - 1)
			if (prev.isRest) {
				start = prevTime + prev.beats * secPerBeat
				duration = lineBeats
			} else if (event.line == prev.line) {
				val minOffset = prevOffset + 1
				if (minOffset >= lineBeats) {
					notice = "La línea ya ocupa sus $lineBeats tiempos"
					return false
				}
				val clickedOffset = kotlin.math.round((t - lineStart) / secPerBeat).toInt()
				val offsetInLine = clickedOffset.coerceIn(minOffset, lineBeats - 1)
				out[prevIndex] = prev.copy(beats = (offsetInLine - prevOffset).toFloat())
				start = lineStart + offsetInLine * secPerBeat
				duration = lineBeats - offsetInLine
			} else {
				// Una línea nueva comienza al terminar la anterior, aunque esta haya durado
				// menos de ocho tiempos. El acorde anterior queda intacto.
				start = prevTime + prev.beats * secPerBeat
				duration = lineBeats
			}
		}
		out += event.copy(beats = duration.toFloat(), t = start, manual = true)
		applyMarked(out, out.size)
		selected = null
		return true
	}

	fun markNext() {
		val t = now()
		when {
			cursor < events.size -> markAt(t)
			sourceCursor < sourceEvents.size -> {
				if (appendEvent(sourceEvents[sourceCursor], t)) sourceCursor++
			}
			else -> markAt(t)
		}
	}

	/** Inserta un acorde desconocido en el punto actual y avanza como una pulsación normal. */
	fun insertBlank() {
		val line = if (events.isEmpty()) sourceEvents.getOrNull(sourceCursor)?.line ?: 0
		else maxOf(song.lines.size, (events.last().line + 1))
		appendEvent(ChordEvent(UNKNOWN_CHORD, 1f, line, -1), now())
	}

	/** Inserta un compás tras la línea indicada y desplaza la secuencia posterior. */
	fun insertBarAfterLine(line: Int) {
		val last = events.indexOfLast { it.line == line }
		if (last < 0) return
		val insertAt = last + 1
		val end = (events[last].t ?: offset) + events[last].beats * secPerBeat
		val shift = bar * secPerBeat
		val out = events.toMutableList()
		out.add(insertAt, ChordEvent("", bar, line, -1, end))
		for (i in insertAt + 1 until out.size) out[i].t?.let { out[i] = out[i].copy(t = it + shift) }
		apply(out, minOf(cursor, insertAt))
		selected = null
	}

	/**
	 * Mueve el inicio del bloque [i] [deltaBeats] pulsos. Si se come entero al de al lado, lo empuja lo justo
	 * (y así sucesivamente), de modo que un acorde se puede llevar al compás siguiente. No quedan huecos.
	 */
	fun shiftStart(evs: List<ChordEvent>, i: Int, deltaBeats: Float): List<ChordEvent>? {
		if (i !in evs.indices) return null
		val t = DoubleArray(evs.size) { evs[it].t ?: 0.0 }
		val min = Timeline.FINE * secPerBeat // como mínimo, una semifusa
		t[i] += deltaBeats * secPerBeat
		if (i == 0 && t[0] < 0) return null
		for (k in i + 1 until t.size) if (t[k] < t[k - 1] + min) t[k] = t[k - 1] + min else break
		for (k in i - 1 downTo 0) if (t[k] > t[k + 1] - min) t[k] = t[k + 1] - min else break
		if (t[0] < 0) return null
		// El último no puede quedarse sin sitio.
		if (t.last() > (evs.last().t ?: 0.0) + evs.last().beats * secPerBeat - min && i != evs.lastIndex) return null
		return evs.mapIndexed { k, e -> if (t[k] != (e.t ?: 0.0)) e.copy(t = t[k], manual = e.manual || k == i) else e }
	}

	/** Arrastrar un bloque: su inicio se mueve de pulso en pulso. */
	fun drag(i: Int, d: Float) {
		val out = shiftStart(events, i, d) ?: return
		apply(out, cursor)
	}

	/**
	 * Duración del bloque [i] ±[d] pulsos: el sitio lo da (o lo recibe) el acorde de detrás; si no hay o no
	 * le queda, el de delante. Nada más se mueve.
	 */
	fun resize(i: Int, d: Float) {
		val e = events.getOrNull(i) ?: return
		if (e.beats + d < Timeline.FINE - 1e-4f) return
		val next = events.getOrNull(i + 1)
		val prev = events.getOrNull(i - 1)
		val out = when {
			next != null && next.beats - d >= Timeline.FINE - 1e-4f -> shiftStart(events, i + 1, d)
			prev != null && prev.beats - d >= Timeline.FINE - 1e-4f -> shiftStart(events, i, -d)
			else -> null
		}
		if (out == null) notice = "No hay sitio en el compás" else apply(out, cursor)
	}

	/** Cambia la duración del acorde y reparte el tiempo restante entre los acordes de su línea. */
	fun setDuration(i: Int, newBeats: Float) {
		val line = events[i].line
		var a = i
		while (!events[i].isRest && a > 0 && events[a - 1].line == line && !events[a - 1].isRest) a--
		var b = i
		while (!events[i].isRest && b < events.lastIndex && events[b + 1].line == line && !events[b + 1].isRest) b++
		val members = (a..b).toList()
		val others = members.filter { it != i }
		val oldTotal = members.sumOf { events[it].beats.toDouble() }
		val oldBeats = events[i].beats.toDouble()
		val shrinking = newBeats < oldBeats - 1e-6
		// Al acortar, la línea también se acorta. Al alargar, los demás acordes
		// absorben el cambio; si ya no caben, la línea continúa en la fila siguiente.
		val total = when {
			shrinking -> oldTotal - oldBeats + newBeats
			others.isEmpty() -> newBeats.toDouble()
			else -> maxOf(oldTotal, newBeats + others.size.toDouble())
		}
		val rest = total - newBeats
		val weights = others.map { events[it].beats.toDouble().coerceAtLeast(0.01) }
		val beats = HashMap<Int, Double>()
		beats[i] = newBeats.toDouble()
		if (others.isNotEmpty() && shrinking) {
			others.forEach { beats[it] = events[it].beats.toDouble() }
		} else if (others.isNotEmpty()) {
			// Reparto del resto en tiempos enteros si se puede (mayor resto); si no, en proporción exacta.
			val whole = rest >= others.size && rest % 1.0 == 0.0
			if (whole) {
				val share = weights.map { it / weights.sum() * rest }
				val parts = share.map { maxOf(1, kotlin.math.floor(it).toInt()) }.toMutableList()
				while (parts.sum() > rest && parts.any { it > 1 }) parts[parts.indices.filter { parts[it] > 1 }.minBy { share[it] - parts[it] }] -= 1
				val order = share.indices.sortedByDescending { share[it] - kotlin.math.floor(share[it]) }
				var o = 0
				while (parts.sum() < rest) { parts[order[o % order.size]] += 1; o++ }
				others.forEachIndexed { k, idx -> beats[idx] = parts[k].toDouble() }
			} else others.forEachIndexed { k, idx -> beats[idx] = rest * weights[k] / weights.sum() }
		}
		val start = events[a].t ?: return
		val shift = (total - oldTotal) * secPerBeat
		val out = events.toMutableList()
		var t = start
		for (k in members) {
			out[k] = out[k].copy(t = t, beats = beats[k]!!.toFloat(), manual = out[k].manual || k == i)
			t += beats[k]!! * secPerBeat
		}
		for (k in b + 1 until out.size) out[k].t?.let { out[k] = out[k].copy(t = it + shift) }
		apply(out, cursor)
		if (shift > 1e-6) notice = "La línea crece; lo de detrás se retrasa ${fmtBeatsText(total - oldTotal)}"
	}

	/** Índices de los acordes de la misma línea de letra que [i] (consecutivos). */
	fun lineOf(i: Int): IntRange {
		if (events[i].isRest) return i..i
		val line = events[i].line
		var a = i
		while (a > 0 && events[a - 1].line == line && !events[a - 1].isRest) a--
		var b = i
		while (b < events.lastIndex && events[b + 1].line == line && !events[b + 1].isRest) b++
		return a..b
	}

	/** Repartos posibles para una línea de [n] acordes, manteniendo su duración actual. */
	fun linePatterns(i: Int): List<List<Int>> {
		val n = lineOf(i).count()
		if (n !in 1..4) return emptyList()
		val r = lineOf(i)
		val lineTotal = r.sumOf { events[it].beats.toDouble() }
		val target = kotlin.math.round(lineTotal).toInt()
		if (abs(lineTotal - target) > 1e-3 || target !in n..8) return emptyList()
		val values = 1..8
		val out = mutableListOf<List<Int>>()
		fun rec(acc: List<Int>) {
			if (acc.size == n) {
				val sum = acc.sum()
				if (sum == target) out += acc
				return
			}
			for (v in values) rec(acc + v)
		}
		rec(emptyList())
		return out.sortedWith(compareBy({ it.sum() }, { it.joinToString() }))
	}

	/** Pone la línea de [i] con las duraciones [pattern]; crece o encoge y lo de detrás se desplaza. */
	fun setLine(i: Int, pattern: List<Int>) {
		val r = lineOf(i)
		val start = events[r.first].t ?: return
		val oldTotal = r.sumOf { events[it].beats.toDouble() }
		val out = events.toMutableList()
		var t = start
		for ((j, k) in r.withIndex()) {
			out[k] = out[k].copy(t = t, beats = pattern[j].toFloat())
			t += pattern[j] * secPerBeat
		}
		val shift = (pattern.sum() - oldTotal) * secPerBeat
		for (k in r.last + 1 until out.size) out[k].t?.let { out[k] = out[k].copy(t = it + shift) }
		apply(out, cursor)
	}

	/** Deja el bloque en blanco («?»), ocupando su sitio. */
	fun clear(i: Int) {
		apply(events.toMutableList().also { it[i] = it[i].copy(chord = UNKNOWN_CHORD) }, cursor)
	}

	/** Borra el bloque [i]: su tiempo pasa al anterior. */
	fun remove(i: Int) {
		if (events.size <= 1) return
		apply(Timeline.remove(events, i), if (i < cursor) cursor - 1 else cursor)
	}

	fun startFrom(i: Int) {
		val event = events.getOrNull(i) ?: return
		// El acorde tocado fija el punto de entrada; el siguiente queda listo para marcar.
		if (!event.manual) apply(events.toMutableList().also { it[i] = event.copy(manual = true) }, i + 1)
		else cursor = i + 1
		val sec = (event.t ?: offset).coerceAtLeast(0.0).toFloat()
		video.player?.seekTo(sec)
		video.player?.play()
		video.update(sec, force = true)
	}

	fun current() = song.copy(
		events = events, bpm = bpm, videoOffsetMs = ((events.firstOrNull()?.t ?: offset) * 1000).toLong(),
		updatedAt = System.currentTimeMillis(),
	)

	fun save() = onSave(current())


	// Posición del vídeo en pulsos de la secuencia (para iluminar lo que va sonando mientras se marca).
	var playBeat by remember { mutableDoubleStateOf(-1.0) }
	val starts = remember(events) { DoubleArray(events.size + 1).also { a -> events.forEachIndexed { k, e -> a[k + 1] = a[k] + e.beats } } }
	LaunchedEffect(events) {
		while (isActive) withFrameNanos { n ->
			val sec = video.seconds(n)
			val k = events.indexOfLast { (it.t ?: Double.MAX_VALUE) <= sec }
			playBeat = if (k < 0) -1.0 else {
				val t0 = events[k].t!!
				val t1 = events.getOrNull(k + 1)?.t ?: (t0 + events[k].beats * secPerBeat)
				starts[k] + ((sec - t0) / (t1 - t0).coerceAtLeast(1e-3)).coerceIn(0.0, 1.0) * events[k].beats
			}
		}
	}

	val marked = events.count { it.manual }
	// La duración del último acorde marcado se conocerá con el siguiente click.
	val beatInSong = events.take((cursor - 1).coerceAtLeast(0)).sumOf { it.beats.toDouble() }
	val measureNumber = (beatInSong / bar).toInt() + 1
	val beatNumber = (beatInSong % bar).toInt() + 1
	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Column {
						Text("Marcar tiempos")
						Text("${song.title} · $marked de ${events.size} marcados", style = MaterialTheme.typography.bodySmall)
					}
				},
				navigationIcon = {
					IconButton(onClick = { if (changed) confirmExit = true else onBack() }) { Icon(Icons.Filled.Close, "Salir") }
				},
				actions = {
					IconButton(onClick = {
						onSaveStay(current())
						changed = false
						notice = "Guardado. Puedes seguir otro día desde el primer bloque apagado."
					}, enabled = changed) { Icon(Icons.Filled.Check, "Guardar") }
					IconButton(onClick = { confirmReset = true }) { Icon(Icons.Filled.RestartAlt, "Reiniciar") }
				},
			)
		},
	) { pad ->
		Box(Modifier.fillMaxSize().padding(pad)) {
			Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				YouTubeBox(videoId, video, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
				TapTimeline(
					song, events, starts, cursor, playBeat, Modifier.weight(1f),
					onSelect = { i -> startFrom(i); selected = i },
					onLongPress = { i -> picking = i },
					onDrag = { i, d -> drag(i, d) },
					onInsertBar = ::insertBarAfterLine,
					selected = selected,
				)

				// Los dos botones de acorde.
				val next = events.getOrNull(cursor) ?: sourceEvents.getOrNull(sourceCursor)
				Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Box(
						Modifier.weight(1.7f).fillMaxHeight().clip(RoundedCornerShape(24.dp))
							.background(if (next == null) Brush.linearGradient(listOf(Fun.Turquoise, Color(0xFF3DD9C1))) else Fun.current)
							.clickable { markNext() },
						contentAlignment = Alignment.Center,
					) {
						Column(horizontalAlignment = Alignment.CenterHorizontally) {
							Text(
								when { next == null -> "FIN"; cursor < events.size -> "SIGUIENTE"; else -> "AÑADIR" },
								color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 13.sp,
							)
							Text(
								when {
									next == null -> "Pulsa al acabar"
									next.isRest -> "pausa"
									else -> next.chord
								},
								color = Color.White, fontWeight = FontWeight.Black, fontSize = if (next == null) 22.sp else 46.sp, maxLines = 1,
							)
							if (next != null) Text(
								if (cursor < events.size) events.drop(cursor + 1).take(3).joinToString("  ") { if (it.isRest) "pausa" else it.chord }
								else sourceEvents.drop(sourceCursor + 1).take(3).joinToString("  ") { it.chord },
								color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 1,
							)
							Text(
								if (next == null) "Pulsa al terminar" else "Compás $measureNumber · tiempo $beatNumber",
								color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
							)
						}
					}
					Box(
						Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp))
							.background(Brush.linearGradient(listOf(Fun.Purple, Fun.Pink)))
							.clickable { insertBlank() },
						contentAlignment = Alignment.Center,
					) {
						Column(horizontalAlignment = Alignment.CenterHorizontally) {
							Text("VACÍO", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
							Text("?", color = Color.White, fontWeight = FontWeight.Black, fontSize = 46.sp)
							Text("insertar y avanzar", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
						}
					}
				}
				Spacer(Modifier.height(4.dp))
			}
			notice?.let {
				Text(
					it, color = Color.White, fontWeight = FontWeight.Bold,
					modifier = Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp))
						.background(Fun.Ink.copy(alpha = 0.85f)).padding(horizontal = 18.dp, vertical = 10.dp),
				)
			}
		}
	}

	picking?.let { i ->
		ChordPicker(
			events.filter { !it.isRest && it.chord != UNKNOWN_CHORD }.map { it.chord }.distinct(), events.getOrNull(i)?.chord,
			onDismiss = { picking = null },
			onPick = { c ->
				apply(events.toMutableList().also { l -> l.getOrNull(i)?.let { l[i] = it.copy(chord = c) } }, cursor)
				picking = null
			},
			onDelete = if (events.size > 1) ({ picking = null; remove(i) }) else null,
			header = {
				val e = events.getOrNull(i)
				if (e != null) Column(Modifier.padding(bottom = 12.dp)) {
					Text("Duración", style = MaterialTheme.typography.labelLarge)
					val name = NOTE_VALUES.firstOrNull { abs(it.second - e.beats) < 1e-3f }?.first
					val b = if (e.beats % 1f == 0f) e.beats.toInt().toString() else "%.3f".format(e.beats).trimEnd('0')
					Text(
						name?.let { "$it ($b ${if (e.beats == 1f) "tiempo" else "tiempos"})" } ?: "$b tiempos",
						fontWeight = FontWeight.Bold, fontSize = 16.sp,
					)
					FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
						for ((label, beats) in NOTE_VALUES) FilterChip(
							abs(beats - e.beats) < 1e-3f, onClick = { setDuration(i, beats) },
							label = { Text(label) },
						)
					}
					val r = lineOf(i)
					val patterns = linePatterns(i)
					if (patterns.size > 1) {
						Text(
							"Toda la línea (${r.joinToString(" · ") { events[it].chord.ifEmpty { "pausa" } }})",
							style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp),
						)
						val currentPattern = r.map { events[it].beats }
						FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							for (p in patterns) FilterChip(
								p.indices.all { abs(p[it] - currentPattern[it]) < 1e-3f },
								onClick = { setLine(i, p) },
								label = { Text(p.joinToString("-") + "  (${p.sum() / bar.toInt()} c.)") },
							)
						}
					}
					if (e.chord != UNKNOWN_CHORD && !e.isRest) TextButton(onClick = { clear(i); picking = null }) { Text("Vaciar (dejar en blanco)") }
				}
			},
		)
	}

	if (confirmReset) AlertDialog(
		onDismissRequest = { confirmReset = false },
		title = { Text("¿Reiniciar?") },
		text = { Text("Se vaciará toda la línea de tiempo. Después podrás añadir los acordes de la partitura en orden y crear acordes vacíos.") },
		confirmButton = {
			TextButton(onClick = {
				confirmReset = false
				sourceCursor = 0
				selected = null
				apply(emptyList(), 0)
			}) { Text("Reiniciar") }
		},
		dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } },
	)

	if (confirmExit) AlertDialog(
		onDismissRequest = { confirmExit = false },
		title = { Text("¿Guardar lo marcado?") },
		text = { Text("Lo que no hayas marcado se queda donde estaba; puedes seguir otro día.") },
		confirmButton = { TextButton(onClick = { confirmExit = false; save() }) { Text("Guardar") } },
		dismissButton = { TextButton(onClick = { confirmExit = false; onBack() }) { Text("Descartar") } },
	)
}

/** Línea de tiempo de la pantalla de marcar: solo lectura, con las marcas y el siguiente recuadrado. */
@Composable
private fun TapTimeline(
	song: Song, events: List<ChordEvent>, starts: DoubleArray, cursor: Int, playBeat: Double, modifier: Modifier,
	onSelect: (Int) -> Unit, onLongPress: (Int) -> Unit, onDrag: (Int, Float) -> Unit,
	onInsertBar: (Int) -> Unit, selected: Int?,
) {
	val rows = remember(events, song.lines) { timelineDisplayRows(song.lines, events) }
	val listState = rememberLazyListState()
	val cursorIndex = cursor.coerceAtMost(events.lastIndex)
	val cursorRow = rows.indexOfFirst { row -> row.blocks.any { it.index == cursorIndex } }
	LaunchedEffect(cursorRow) { if (cursorRow >= 0) listState.animateScrollToItem((cursorRow - 1).coerceAtLeast(0)) }
	val nowIdx = if (playBeat < 0) null else (0 until events.size).lastOrNull { starts[it] <= playBeat }
	LazyColumn(modifier.fillMaxWidth(), state = listState) {
		items(rows.size) { r ->
			val row = rows[r]
			val li = row.lineIndex
			val blocks = row.blocks
			val line = li?.let { song.lines[it] } ?: SongLine(null, "", emptyList())
			val first = blocks.firstOrNull()?.index
			val rowStart = first?.let { starts[it] } ?: 0.0
			val rowEnd = blocks.lastOrNull()?.let { starts[it.index + 1] } ?: rowStart
			val inRow = playBeat >= rowStart && playBeat < rowEnd
			val t = first?.let { events[it].t }
			Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
				TimelineRow(
					line, blocks, rowStart = rowStart, beatsPerBar = song.beatsPerBar, selected = selected ?: cursor,
					timeLabel = t?.let { "%d:%02d".format((it / 60).toInt(), (it % 60).toInt()) },
					onSelect = onSelect, onMove = onDrag, onInsert = { _, _, _ -> }, dragBlocks = true,
					nowIndex = if (inRow) nowIdx else null, playhead = if (inRow) playBeat else null,
					editable = false, showMarks = true, dimUnmarked = true, onLongPress = onLongPress,
					modifier = Modifier.weight(1f),
				)
				if (li != null && blocks.isNotEmpty()) IconButton(onClick = { onInsertBar(li) }) {
					Text("+", fontSize = 24.sp)
				}
			}
		}
	}
}

/** Figuras y su duración en tiempos (compases de x/4: la negra es un tiempo). */
private val NOTE_VALUES = listOf(
	"1" to 1f, "2" to 2f, "3" to 3f, "4" to 4f,
	"5" to 5f, "6" to 6f, "7" to 7f, "8" to 8f,
)

private fun fmtBeatsText(b: Double): String {
	val r = Math.round(b * 100) / 100.0
	val txt = if (r % 1.0 == 0.0) r.toInt().toString() else "%.2f".format(r).trimEnd('0').trimEnd(',', '.')
	return "$txt ${if (r == 1.0) "tiempo" else "tiempos"}"
}
