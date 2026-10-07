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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
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
import kotlin.math.roundToInt

/**
 * Marca cada cambio de acorde en orden. Con el vídeo en marcha mide los tiempos; pausado usa cuatro
 * tiempos por acorde. «Vacío» añade una pausa en la secuencia de la partitura. Se puede guardar a medias.
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
				if (source == null || event.chord != source.chord || event.pos != source.pos) {
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

	/** Guarda las duraciones en pulsos enteros, conservando los instantes reales marcados en el vídeo. */
	fun applyMarked(evs: List<ChordEvent>, newCursor: Int) {
		history += events to cursor
		events = evs.map { e ->
			val beats = kotlin.math.round(e.beats).toFloat().coerceAtLeast(1f)
			e.copy(beats = beats)
		}
		cursor = newCursor.coerceIn(0, events.size)
		changed = true
		view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
	}

	/** Marca el inicio del evento actual, sin imponer un máximo de tiempos a la línea. */
	fun markAt(t: Double, replacement: ChordEvent? = null) {
		if (cursor >= events.size) {
			val last = events.lastOrNull() ?: return
			val lastStart = last.t ?: return
			val beats = if (video.playing) {
				kotlin.math.round((t - lastStart) / secPerBeat).toInt().coerceAtLeast(1)
			} else 4
			applyMarked(events.dropLast(1) + last.copy(beats = beats.toFloat(), manual = true), events.size)
			selected = null
			notice = "Final marcado · ${beats} tiempos"
			return
		}

		val out = events.toMutableList()
		val original = out[cursor]
		val current = replacement?.let { original.copy(chord = it.chord, line = it.line, pos = it.pos) } ?: original
		val oldStart = current.t ?: t
		val newStart: Double
		if (current.isRest) {
			// Marcar una pausa mueve su inicio y lo que viene después, pero nunca el compás anterior.
			newStart = t
		} else if (cursor > 0) {
			val previous = out[cursor - 1]
			val previousStart = previous.t ?: (t - previous.beats * secPerBeat)
			val elapsed = if (video.playing) {
				kotlin.math.round((t - previousStart) / secPerBeat).toInt().coerceAtLeast(1)
			} else previous.beats.roundToInt().coerceAtLeast(1)
			out[cursor - 1] = previous.copy(beats = elapsed.toFloat())
			// Conserva la rejilla redondeada y la línea indicada por la secuencia de la partitura.
			newStart = previousStart + elapsed * secPerBeat
		} else {
			newStart = t
		}
		val delta = newStart - oldStart
		out[cursor] = current.copy(t = newStart, manual = true)
		for (i in cursor + 1 until out.size) out[i].t?.let { out[i] = out[i].copy(t = it + delta) }
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
		val eventLine = event.line
		val defaultDuration = if (video.playing) lineBeats else 4
		if (prev == null || prevTime == null) {
			start = t
			duration = defaultDuration
		} else {
			if (prev.isRest) {
				start = prevTime + prev.beats * secPerBeat
				duration = defaultDuration
			} else {
				val elapsed = if (video.playing) {
					kotlin.math.round((t - prevTime) / secPerBeat).toInt().coerceAtLeast(1)
				} else prev.beats.roundToInt().coerceAtLeast(1)
				out[prevIndex] = prev.copy(beats = elapsed.toFloat())
				start = prevTime + elapsed * secPerBeat
				duration = defaultDuration
			}
		}
		out += event.copy(beats = duration.toFloat(), line = eventLine, t = start, manual = true)
		applyMarked(out, out.size)
		selected = null
		return true
	}

	fun markNext() {
		val t = now()
		when {
			cursor < events.size -> {
				val source = sourceEvents.getOrNull(sourceCursor)
				markAt(t, source)
				if (source != null) sourceCursor++
			}
			sourceCursor < sourceEvents.size -> {
				if (appendEvent(sourceEvents[sourceCursor], t)) sourceCursor++
			}
			else -> markAt(t)
		}
	}

	/** Inserta una pausa de cuatro tiempos después del bloque seleccionado, en la primera línea con sitio. */
	fun insertBlank() {
		val anchorIndex = selected?.takeIf { it in events.indices } ?: events.lastIndex
		val insertAt = if (anchorIndex >= 0) anchorIndex + 1 else 0
		val anchor = events.getOrNull(anchorIndex)
		val firstLine = anchor?.line ?: sourceEvents.getOrNull(sourceCursor)?.line ?: 0
		val start = anchor?.let { (it.t ?: now()) + it.beats * secPerBeat } ?: now()
		val duration = 4f
		val lineCapacity = maxOf(bar * 2, duration)
		var line = firstLine
		val precedingRests = if (anchor?.isRest == true) {
			var first = anchorIndex
			while (first > 0 && events[first - 1].isRest && events[first - 1].line == firstLine) first--
			(first until insertAt).sumOf { events[it].beats.toDouble() }.toFloat()
		} else 0f
		if (precedingRests + duration > lineCapacity + 1e-6f) line++
		val shift = duration * secPerBeat
		val out = events.toMutableList()
		out.add(insertAt, ChordEvent("", duration, line, -1, start, manual = true))
		for (i in insertAt + 1 until out.size) out[i].t?.let { out[i] = out[i].copy(t = it + shift) }
		// El nuevo bloque vacío es el próximo hueco que debe rellenar «Siguiente».
		apply(out, insertAt)
		selected = insertAt
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

	/** Cambia solo la duración seleccionada y desplaza los eventos posteriores. */
	fun setDuration(i: Int, newBeats: Float) {
		val current = events.getOrNull(i) ?: return
		val deltaBeats = newBeats - current.beats
		val delta = deltaBeats * secPerBeat
		val out = events.toMutableList()
		out[i] = current.copy(beats = newBeats, manual = true)
		if (kotlin.math.abs(deltaBeats) > 1e-6f) {
			for (k in i + 1 until out.size) out[k].t?.let { out[k] = out[k].copy(t = it + delta) }
		}
		applyMarked(out, cursor)
		if (deltaBeats > 1e-6f) notice = "Los siguientes compases se retrasan ${fmtBeatsText(deltaBeats.toDouble())}"
		else if (deltaBeats < -1e-6f) notice = "Los siguientes compases se adelantan ${fmtBeatsText(-deltaBeats.toDouble())}"
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
		if (n !in 2..4) return emptyList()
		return listOf(listOf(4, 4), listOf(2, 2, 4), listOf(4, 2, 2), listOf(2, 2, 2, 2))
			.filter { it.size == n }
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

	/** Borra solo el bloque [i] y adelanta los eventos posteriores por su duración. */
	fun remove(i: Int) {
		if (events.size <= 1) return
		val removed = events[i]
		val out = events.toMutableList().also { it.removeAt(i) }
		val delta = removed.beats * secPerBeat
		for (k in i until out.size) out[k].t?.let { out[k] = out[k].copy(t = it - delta) }
		applyMarked(out, if (i < cursor) cursor - 1 else cursor)
	}

	fun selectForInsertion(i: Int) {
		val event = events.getOrNull(i) ?: return
		selected = i
		val ordinal = events.take(i + 1).count { it.pos >= 0 } - 1
		val expected = sourceEvents.getOrNull(ordinal)
		val exact = if (event.pos >= 0 && expected != null && expected.pos == event.pos && expected.chord == event.chord) ordinal else -1
		if (exact >= 0) {
			// El cursor temporal queda después de lo seleccionado y la secuencia apunta al acorde siguiente.
			cursor = (i + 1).coerceAtMost(events.size)
			sourceCursor = exact + 1
			return
		}

		// Un acorde personalizado o vacío toma como referencia secuencial el acorde
		// secuencial más cercano que aparece antes en la línea temporal.
		val previousSequence = (i - 1 downTo 0).firstOrNull { events[it].pos >= 0 }
		val previousSource = previousSequence?.let { previous ->
			sourceEvents.indexOfFirst { it.line == events[previous].line && it.pos == events[previous].pos }
		} ?: -1
		sourceCursor = if (previousSource >= 0) previousSource + 1 else {
			sourceEvents.indexOfFirst { it.line >= event.line }.let { if (it >= 0) it else sourceEvents.size }
		}
		cursor = (i + 1).coerceAtMost(events.size)
	}

	fun playFrom(i: Int) {
		val event = events.getOrNull(i) ?: return
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
			Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				YouTubeBox(videoId, video, Modifier.fillMaxWidth().padding(horizontal = 12.dp).aspectRatio(16f / 9f))
					TapTimeline(
					song, events, starts, cursor, playBeat, Modifier.weight(1f).padding(horizontal = 8.dp),
						onSelect = { i -> selectForInsertion(i) },
					onBlockTap = { i -> selectForInsertion(i) },
						onBlockDoubleTap = { i -> playFrom(i) },
						onLongPress = { i -> picking = i },
						onDrag = { i, d -> drag(i, d) },
						selected = selected,
				)

				// Controles para marcar, insertar una pausa y controlar el vídeo.
				val next = events.getOrNull(cursor) ?: sourceEvents.getOrNull(sourceCursor)
				Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(112.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Box(
						Modifier.weight(1.35f).fillMaxHeight().clip(RoundedCornerShape(24.dp))
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
							Text("4 tiempos", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
						}
					}
					Box(
						Modifier.width(60.dp).fillMaxHeight().clip(RoundedCornerShape(20.dp))
							.background(Fun.Ink)
							.clickable {
								if (video.playing) video.player?.pause() else video.player?.play()
							},
						contentAlignment = Alignment.Center,
					) {
						Icon(
							if (video.playing) Icons.Filled.PauseCircle else Icons.Filled.PlayArrow,
							contentDescription = if (video.playing) "Pausar vídeo" else "Reproducir vídeo",
							modifier = Modifier.size(34.dp), tint = Color.White,
						)
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
			events.filter { !it.isRest && it.chord != UNKNOWN_CHORD }.map { it.chord }.distinct(),
			events.getOrNull(i)?.let { if (it.isRest) null else it.chord },
			onDismiss = { picking = null },
				onPick = { c ->
					val out = events.toMutableList()
					out.getOrNull(i)?.let { event ->
						out[i] = if (event.isRest) {
							ChordEvent(c, event.beats, event.line, -1, event.t, manual = true)
						} else event.copy(chord = c)
					}
					apply(out, cursor)
					selected = i
					picking = null
				},
			onDelete = if (events.size > 1) ({ picking = null; remove(i) }) else null,
			header = {
				val e = events.getOrNull(i)
				if (e != null) Column(Modifier.padding(bottom = 12.dp)) {
					var durationMenu by remember(i) { mutableStateOf(false) }
					Text("Duración", style = MaterialTheme.typography.labelLarge)
					val b = if (e.beats % 1f == 0f) e.beats.toInt().toString() else "%.3f".format(e.beats).trimEnd('0')
					Text(
						"$b ${if (e.beats == 1f) "tiempo" else "tiempos"}",
						fontWeight = FontWeight.Bold, fontSize = 16.sp,
					)
					Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
						for (beats in 1..4) FilterChip(
							selected = abs(beats - e.beats) < 1e-3f,
							onClick = { setDuration(i, beats.toFloat()) },
							modifier = Modifier.width(44.dp),
							label = { Text(beats.toString()) },
						)
							Box {
								FilterChip(
									selected = false,
								onClick = { durationMenu = true },
									modifier = Modifier.width(44.dp),
									label = {},
								leadingIcon = {
									Icon(
										Icons.Filled.Add,
										contentDescription = "Elegir duración",
										tint = MaterialTheme.colorScheme.onSurfaceVariant,
									)
								},
								)
							DropdownMenu(expanded = durationMenu, onDismissRequest = { durationMenu = false }) {
								for (beats in 1..15) DropdownMenuItem(
									text = { Text("$beats ${if (beats == 1) "tiempo" else "tiempos"}") },
									onClick = { durationMenu = false; setDuration(i, beats.toFloat()) },
								)
							}
						}
					}
					val r = lineOf(i)
					val patterns = linePatterns(i)
					if (patterns.isNotEmpty()) {
						Text(
							"Toda la línea (${r.joinToString(" · ") { events[it].chord.ifEmpty { "pausa" } }})",
							style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp),
						)
						val currentPattern = r.map { events[it].beats }
						FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
							for (p in patterns) FilterChip(
								p.indices.all { abs(p[it] - currentPattern[it]) < 1e-3f },
								onClick = { setLine(i, p) },
								label = { Text(p.joinToString("-")) },
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
	onSelect: (Int) -> Unit, onBlockTap: (Int) -> Unit, onBlockDoubleTap: (Int) -> Unit,
	onLongPress: (Int) -> Unit, onDrag: (Int, Float) -> Unit,
	selected: Int?,
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
			TimelineRow(
					line, blocks, rowStart = rowStart, beatsPerBar = song.beatsPerBar, selected = selected ?: cursor,
					timeLabel = t?.let { "%d:%02d".format((it / 60).toInt(), (it % 60).toInt()) },
					onSelect = onSelect, onMove = onDrag, onInsert = { _, _, _ -> }, dragBlocks = true,
					onBlockTap = onBlockTap,
					onBlockDoubleTap = onBlockDoubleTap,
					nowIndex = if (inRow) nowIdx else null, playhead = if (inRow) playBeat else null,
					editable = false, showMarks = true, dimUnmarked = true, onLongPress = onLongPress,
				)
		}
	}
}

private fun fmtBeatsText(b: Double): String {
	val r = Math.round(b * 100) / 100.0
	val txt = if (r % 1.0 == 0.0) r.toInt().toString() else "%.2f".format(r).trimEnd('0').trimEnd(',', '.')
	return "$txt ${if (r == 1.0) "tiempo" else "tiempos"}"
}
