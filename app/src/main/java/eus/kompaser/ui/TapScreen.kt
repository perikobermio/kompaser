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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Straighten
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * Marcar tiempos escuchando la canción, a trozos. Trabaja sobre la secuencia que ya tiene la canción:
 *  - «Siguiente» marca, en el instante actual del vídeo, el acorde recuadrado y pasa al siguiente.
 *  - Tocar un bloque de la línea de tiempo hace que la secuencia siga desde él (y el vídeo salta un poco antes).
 *  - «Otro» y «Pausa» insertan ahí un acorde por decidir o un parón, sin que la secuencia avance.
 * Los acordes marcados a mano llevan un punto y se ven en color; los demás, apagados, se reparten entre las
 * marcas. Se puede guardar a medias y seguir otro día.
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
	var tempoDialog by remember { mutableStateOf(false) }
	val bar = song.beatsPerBar.toFloat()
	val offset = song.videoOffsetMs / 1000.0
	var events by remember(song.id) { mutableStateOf(Timeline.normalize(song.events, secPerBeat, offset)) }
	// Siguiente acorde por marcar: el primero que no está marcado a mano.
	var cursor by remember(song.id) { mutableIntStateOf(events.indexOfFirst { !it.manual }.let { if (it < 0) events.size else it }) }
	val history = remember { mutableStateListOf<Pair<List<ChordEvent>, Int>>() }
	var changed by remember { mutableStateOf(false) }
	var confirmExit by remember { mutableStateOf(false) }
	var confirmReset by remember { mutableStateOf(false) }
	var menu by remember { mutableStateOf(false) }
	var picking by remember { mutableStateOf<Int?>(null) }
	// Bloque elegido con un toque (para «Otro» justo después). Se olvida cuando la reproducción ya lo ha dejado atrás.
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

	/** El instante actual del vídeo tiene que ir después del acorde anterior; si no, se avisa. */
	fun timeOk(t: Double): Boolean {
		val prev = events.getOrNull(cursor - 1)?.t
		if (prev != null && t <= prev + 0.05) {
			notice = "El vídeo va por detrás del acorde anterior: toca el bloque desde el que quieres seguir"
			return false
		}
		return true
	}

	/**
	 * «Siguiente»: marca el cambio de acorde en este instante. Normalmente es el acorde recuadrado (que
	 * se adelanta hasta aquí); si el recuadro acaba de pasar al siguiente porque el anterior estaba
	 * colocado antes de tiempo, se corrige ese anterior: se marca el cambio más cercano a la pulsación.
	 */
	fun markNext() {
		val t = now()
		if (cursor >= events.size) {
			val last = events.lastOrNull() ?: return
			val lt = last.t ?: return
			if (!last.manual && t > lt && t - lt < last.beats * secPerBeat / 2) {
				// Acaba de empezar el último y está sin marcar: se corrige su inicio.
				apply(events.dropLast(1) + last.copy(t = t, manual = true), events.size)
				return
			}
			if (t <= lt) return
			apply(events.dropLast(1) + last.copy(beats = ((t - lt) / secPerBeat).toFloat().coerceAtLeast(0.5f)), cursor)
			notice = "Final marcado"
			return
		}
		val prev = cursor - 1
		val pt = events.getOrNull(prev)?.t
		val nt = events[cursor].t ?: Double.MAX_VALUE
		val usePrev = prev >= 0 && !events[prev].manual && pt != null && (t - pt) < (nt - t) &&
			(events.getOrNull(prev - 1)?.t ?: Double.NEGATIVE_INFINITY) < t - 0.05
		if (usePrev) {
			apply(events.toMutableList().also { it[prev] = it[prev].copy(t = t, manual = true) }, cursor)
			return
		}
		if (!timeOk(t)) return
		apply(events.toMutableList().also { it[cursor] = it[cursor].copy(t = t, manual = true) }, cursor + 1)
	}

	/**
	 * «Otro»: el acorde nuevo va donde le toca por tiempo, partiendo el bloque que suena en este instante
	 * del vídeo (no en la posición del recuadro). La secuencia no avanza.
	 */
	fun insertAtTime(chord: String) {
		val t = now()
		val k = events.indexOfLast { (it.t ?: Double.MAX_VALUE) <= t }
		if (k >= 0 && t <= events[k].t!! + 0.05) {
			notice = "Ya hay un acorde en este instante"
			return
		}
		val at = k + 1
		val line = events.getOrNull(k.coerceAtLeast(0))?.line ?: 0
		val out = events.toMutableList()
		out.add(at, ChordEvent(chord, bar, line, -1, t, manual = true))
		// El acorde que viene detrás: si el nuevo (un compás) se le echa encima, empieza más tarde y termina
		// donde terminaba (se acorta). Lo demás se queda donde estaba.
		out.getOrNull(at + 1)?.let { nb ->
			val start = nb.t ?: return@let
			val end = start + nb.beats * secPerBeat
			val newStart = maxOf(start, minOf(t + bar * secPerBeat, end - secPerBeat))
			if (newStart > start) out[at + 1] = nb.copy(t = newStart)
		}
		apply(out, if (at <= cursor) cursor + 1 else cursor)
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

	/**
	 * Duración del acorde [i] = [newBeats], respetando el compás: su línea ocupa siempre compases enteros. Los
	 * demás acordes de la línea se reparten lo que queda (en proporción a lo que duraban); si no hay sitio, la
	 * línea crece lo justo, en compases, y todo lo que viene detrás se desplaza (nada se pisa).
	 */
	fun setDuration(i: Int, newBeats: Float) {
		val line = events[i].line
		var a = i
		while (a > 0 && events[a - 1].line == line) a--
		var b = i
		while (b < events.lastIndex && events[b + 1].line == line) b++
		val members = (a..b).toList()
		val others = members.filter { it != i }
		val oldTotal = members.sumOf { events[it].beats.toDouble() }
		val minOthers = others.size * Timeline.FINE
		var total = maxOf(1.0, kotlin.math.ceil(oldTotal / bar - 1e-6)) * bar
		while (total < newBeats + minOthers - 1e-6 || (others.isNotEmpty() && total - newBeats < 0.5 - 1e-6 && newBeats >= bar)) total += bar
		val rest = total - newBeats
		val weights = others.map { events[it].beats.toDouble().coerceAtLeast(0.01) }
		val beats = HashMap<Int, Double>()
		beats[i] = newBeats.toDouble()
		if (others.isNotEmpty()) {
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
		val line = events[i].line
		var a = i
		while (a > 0 && events[a - 1].line == line) a--
		var b = i
		while (b < events.lastIndex && events[b + 1].line == line) b++
		return a..b
	}

	/** Repartos posibles para una línea de [n] acordes: cada uno 1, 2 o 4 tiempos (u 8), total en compases enteros. */
	fun linePatterns(n: Int): List<List<Int>> {
		if (n !in 1..4) return emptyList()
		val values = if (n <= 2) listOf(1, 2, 4, 8) else listOf(1, 2, 4)
		val out = mutableListOf<List<Int>>()
		fun rec(acc: List<Int>) {
			if (acc.size == n) {
				val sum = acc.sum()
				if (sum % bar.toInt() == 0 && sum <= 3 * bar) out += acc
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

	/**
	 * «Otro» con un bloque elegido: el acorde nuevo entra justo después. El elegido se parte por la mitad
	 * (en pulsos enteros); si es demasiado corto, el nuevo coge un pulso del de detrás.
	 */
	fun insertAfter(i: Int) {
		val e = events[i]
		val et = e.t ?: return
		val next = events.getOrNull(i + 1)
		val (start, out) = when {
			e.beats >= 2f -> {
				val keep = kotlin.math.ceil(e.beats / 2.0).toFloat()
				et + keep * secPerBeat to events
			}
			next != null && next.beats >= 2f -> {
				val nt = next.t ?: return
				nt to (shiftStart(events, i + 1, 1f) ?: return)
			}
			else -> {
				notice = "No hay sitio en el compás"
				return
			}
		}
		val list = out.toMutableList()
		list.add(i + 1, ChordEvent(UNKNOWN_CHORD, 1f, e.line, -1, start, manual = true))
		apply(list, if (i + 1 <= cursor) cursor + 1 else cursor)
		selected = i + 1
	}

	/** Borra el bloque [i]: su tiempo pasa al anterior. */
	fun remove(i: Int) {
		if (events.size <= 1) return
		apply(Timeline.remove(events, i), if (i < cursor) cursor - 1 else cursor)
	}

	fun startFrom(i: Int) {
		cursor = i
		// El vídeo va al instante de ese acorde para seguir desde ahí.
		events.getOrNull(i)?.t?.let { t ->
			val sec = t.coerceAtLeast(0.0).toFloat()
			video.player?.seekTo(sec)
			video.update(sec, force = true)
		}
	}

	fun current() = song.copy(
		events = events, bpm = bpm, videoOffsetMs = ((events.firstOrNull()?.t ?: offset) * 1000).toLong(),
		updatedAt = System.currentTimeMillis(),
	)

	fun save() = onSave(current())

	/** Duración típica de un acorde (s), a partir de las marcas: la mediana, sin contar los muy cortos. */
	fun typicalChord(): Double? {
		val d = (0 until events.size - 1).filter { events[it].manual && events[it + 1].t != null && events[it].t != null }
			.map { events[it + 1].t!! - events[it].t!! }.sorted()
		if (d.size < 3) return null
		val med = d[d.size / 2]
		val body = d.filter { it >= med * 0.6 && it <= med * 1.6 }
		return body.getOrNull(body.size / 2) ?: med
	}

	/**
	 * Lleva cada acorde a un múltiplo de corchea con el tempo [newBpm]. La rejilla se recoloca en cada acorde
	 * (no acumula error): cada inicio se mueve como mucho media corchea respecto a la marca.
	 */
	fun quantize(newBpm: Float): List<ChordEvent> {
		val u = 0.5 * 60.0 / newBpm
		val q = DoubleArray(events.size)
		// Lo que va detrás de la última marca no tiene tiempos reales: un compás por acorde con el tempo nuevo.
		val lastManual = events.indexOfLast { it.manual }
		for (k in events.indices) {
			q[k] = when {
				k == 0 -> events[0].t ?: offset
				k > lastManual -> q[k - 1] + bar * 2 * u
				else -> {
					val t = events[k].t ?: (q[k - 1] + events[k - 1].beats * 60.0 / bpm)
					q[k - 1] + maxOf(1, Math.round((t - q[k - 1]) / u).toInt()) * u
				}
			}
		}
		return events.mapIndexed { k, e ->
			val beats = if (k < events.size - 1) ((q[k + 1] - q[k]) / (2 * u)).toFloat() else bar
			e.copy(t = q[k], beats = beats)
		}
	}

	/**
	 * Ajuste por compases. Se coloca una rejilla de compases para toda la canción ajustada (mínimos cuadrados)
	 * a los inicios de línea marcados; cada línea ocupa un número entero de compases y su espacio se reparte
	 * entre sus acordes en tiempos enteros, en proporción a lo marcado. Devuelve los eventos, el tempo
	 * ajustado y el desfase máximo (s) entre la rejilla y tus marcas de inicio de línea.
	 */
	fun fitBars(guessBpm: Float): Triple<List<ChordEvent>, Float, Double>? {
		// Grupos consecutivos por línea de letra.
		val groups = mutableListOf<MutableList<Int>>()
		for (k in events.indices) {
			if (groups.isEmpty() || events[groups.last().last()].line != events[k].line) groups += mutableListOf(k)
			else groups.last() += k
		}
		val anchors = groups.withIndex().filter { (_, g) -> events[g.first()].manual && events[g.first()].t != null }
		if (anchors.size < 3) return null
		val s0 = events[anchors.first().value.first()].t!!
		var barSec = bar * 60.0 / guessBpm
		var a = s0
		var n = IntArray(0)
		repeat(4) {
			n = IntArray(anchors.size) { j -> Math.round((events[anchors[j].value.first()].t!! - a) / barSec).toInt() }
			val xs = n.map { it.toDouble() }
			val ys = anchors.map { events[it.value.first()].t!! }
			val mx = xs.average()
			val my = ys.average()
			val sxx = xs.sumOf { (it - mx) * (it - mx) }
			if (sxx > 0) {
				barSec = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
				a = my - barSec * mx
			}
		}
		val maxErr = anchors.indices.maxOf { j -> abs(events[anchors[j].value.first()].t!! - (a + barSec * n[j])) }
		val spb = barSec / bar
		// Compás de inicio de cada línea: los marcados, por la rejilla; los demás, siguiendo a la anterior.
		val startBar = DoubleArray(groups.size)
		val anchorBar = anchors.withIndex().associate { (j, iv) -> iv.index to n[j] }
		for (gi in groups.indices) {
			startBar[gi] = anchorBar[gi]?.toDouble() ?: if (gi == 0) Math.round((((events[0].t ?: a) - a) / barSec)).toDouble() else {
				val prev = groups[gi - 1]
				val measured = prev.sumOf { events[it].beats.toDouble() } * 60.0 / bpm / barSec
				startBar[gi - 1] + maxOf(1.0, Math.round(measured).toDouble())
			}
		}
		val out = events.toMutableList()
		for ((gi, g) in groups.withIndex()) {
			val bars = if (gi < groups.size - 1) startBar[gi + 1] - startBar[gi]
			else maxOf(1.0, Math.round(g.sumOf { events[it].beats.toDouble() } * 60.0 / bpm / barSec).toDouble())
			if (bars <= 0) return null
			val total = (bars * bar).toInt()
			// Reparto en tiempos enteros proporcional a lo marcado (cada acorde, al menos uno).
			val w = g.map { events[it].beats.toDouble().coerceAtLeast(0.01) }
			val raw = w.map { it / w.sum() * total }
			// En medios compases (blancas en 4/4) salvo que algún acorde sea de verdad corto; si no, en tiempos.
			val step = if (bar % 2 == 0f && raw.all { it >= 1.5 } && total % 2 == 0) 2 else 1
			// Mayor resto: cada uno recibe la parte entera y lo que falta va a los de mayor resto (empates, en orden).
			val units = total / step
			val share = raw.map { it / step }
			val beats = share.map { maxOf(1, kotlin.math.floor(it).toInt()) }.toMutableList()
			while (beats.sum() > units && beats.any { it > 1 }) beats[beats.indices.filter { beats[it] > 1 }.minBy { share[it] - beats[it] }] -= 1
			val order = share.indices.sortedByDescending { share[it] - kotlin.math.floor(share[it]) }
			var o = 0
			while (beats.sum() < units) { beats[order[o % order.size]] += 1; o++ }
			for (j in beats.indices) beats[j] *= step
			var t = a + startBar[gi] * barSec
			for ((j, k) in g.withIndex()) {
				out[k] = out[k].copy(t = t, beats = beats[j].toFloat())
				t += beats[j] * spb
			}
		}
		return Triple(out, (bar * 60.0 / barSec).toFloat(), maxErr)
	}

	fun adjustTo(newBpm: Float) {
		val fit = fitBars(newBpm)
		history += events to cursor
		if (fit == null) {
			events = quantize(newBpm)
			bpm = newBpm
			notice = "Ajustado a ${newBpm.toInt()} BPM (marca más inicios de línea para ajustar por compases)"
		} else {
			val (evs, fitted, err) = fit
			events = evs
			bpm = Math.round(fitted * 10) / 10f
			notice = "Ajustado por compases a %.1f BPM · tus marcas, a %.2f s como mucho".format(bpm, err)
		}
		onSaveStay(current())
		changed = false
	}


	// Posición del vídeo en pulsos de la secuencia (para iluminar lo que va sonando mientras se marca).
	var playBeat by remember { mutableDoubleStateOf(-1.0) }
	val starts = remember(events) { DoubleArray(events.size + 1).also { a -> events.forEachIndexed { k, e -> a[k + 1] = a[k] + e.beats } } }
	LaunchedEffect(events) {
		while (isActive) withFrameNanos { n ->
			val sec = video.seconds(n)
			// Con el vídeo sonando, el recuadro pasa al siguiente a medida que van entrando los acordes colocados.
			if (video.playing) while (cursor < events.size && (events[cursor].t ?: Double.MAX_VALUE) <= sec) cursor++
			selected?.let { sel -> if (video.playing && cursor > sel + 2) selected = null }
			val k = events.indexOfLast { (it.t ?: Double.MAX_VALUE) <= sec }
			playBeat = if (k < 0) -1.0 else {
				val t0 = events[k].t!!
				val t1 = events.getOrNull(k + 1)?.t ?: (t0 + events[k].beats * secPerBeat)
				starts[k] + ((sec - t0) / (t1 - t0).coerceAtLeast(1e-3)).coerceIn(0.0, 1.0) * events[k].beats
			}
		}
	}

	val marked = events.count { it.manual }
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
					IconButton(onClick = { tempoDialog = true }) { Icon(Icons.Filled.Straighten, "Ajustar al compás") }
					IconButton(onClick = { confirmReset = true }) { Icon(Icons.Filled.RestartAlt, "Reiniciar") }
				},
			)
		},
	) { pad ->
		Box(Modifier.fillMaxSize().padding(pad)) {
			Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				YouTubeBox(videoId, video, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
				Legend()
				TapTimeline(
					song, events, starts, cursor, playBeat, Modifier.weight(1f),
					onSelect = { i -> startFrom(i); selected = i },
					onLongPress = { i -> picking = i },
					onDrag = { i, d -> drag(i, d) },
					selected = selected,
				)

				// Los dos botones de acorde.
				val next = events.getOrNull(cursor)
				Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Box(
						Modifier.weight(1.7f).fillMaxHeight().clip(RoundedCornerShape(24.dp))
							.background(if (next == null) Brush.linearGradient(listOf(Fun.Turquoise, Color(0xFF3DD9C1))) else Fun.current)
							.clickable { markNext() },
						contentAlignment = Alignment.Center,
					) {
						Column(horizontalAlignment = Alignment.CenterHorizontally) {
							Text(if (next == null) "FIN" else "SIGUIENTE", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
							Text(
								when {
									next == null -> "Pulsa al acabar"
									next.isRest -> "pausa"
									else -> next.chord
								},
								color = Color.White, fontWeight = FontWeight.Black, fontSize = if (next == null) 22.sp else 46.sp, maxLines = 1,
							)
							if (next != null) Text(
								events.drop(cursor + 1).take(3).joinToString("  ") { if (it.isRest) "pausa" else it.chord },
								color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, maxLines = 1,
							)
						}
					}
					Box(
						Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp))
							.background(Brush.linearGradient(listOf(Fun.Purple, Fun.Pink)))
							.clickable { selected?.let { insertAfter(it) } ?: insertAtTime(UNKNOWN_CHORD) },
						contentAlignment = Alignment.Center,
					) {
						Column(horizontalAlignment = Alignment.CenterHorizontally) {
							Text("OTRO", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
							Text("?", color = Color.White, fontWeight = FontWeight.Black, fontSize = 46.sp)
							Text("sin avanzar", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
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
					val patterns = linePatterns(r.count())
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

	if (tempoDialog) {
		val typ = typicalChord()
		AlertDialog(
			onDismissRequest = { tempoDialog = false },
			title = { Text("Ajustar al compás") },
			text = {
				Column {
					if (typ == null) Text("Marca antes unos cuantos acordes para poder calcular el tempo.")
					else {
						Text(
							"Tus acordes duran normalmente %.2f s. ¿Cuánto es eso?".format(typ),
							style = MaterialTheme.typography.bodyMedium,
						)
						Spacer(Modifier.height(8.dp))
						for ((label, bars) in listOf("Un compás" to 1.0, "Medio compás" to 0.5, "Dos compases" to 2.0)) {
							val b = (bar * bars * 60.0 / typ).toFloat().let { Math.round(it).toFloat() }
							if (b in 30f..260f) TextButton(onClick = { tempoDialog = false; adjustTo(b) }) {
								Text("$label  →  ${b.toInt()} BPM")
							}
						}
					}
					TextButton(onClick = { tempoDialog = false; adjustTo(bpm) }) { Text("Solo redondear (${bpm.toInt()} BPM)") }
					Text(
						"Cada línea ocupa compases enteros y sus acordes se reparten en tiempos enteros.",
						style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			},
			confirmButton = {},
			dismissButton = { TextButton(onClick = { tempoDialog = false }) { Text("Cancelar") } },
		)
	}

	if (confirmReset) AlertDialog(
		onDismissRequest = { confirmReset = false },
		title = { Text("¿Reiniciar?") },
		text = { Text("Se vuelve a la información original de la partitura: sin marcas, sin los acordes añadidos y sin pausas.") },
		confirmButton = {
			TextButton(onClick = {
				confirmReset = false
				apply(ChordSheet.defaultEvents(song.lines, bar), 0)
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

@Composable
private fun Legend() {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Box(Modifier.size(8.dp).clip(CircleShape).background(Fun.Purple))
		Text(
			"  marcado a mano  ·  apagado: sin marcar  ·  recuadro: el siguiente  ·  toca un bloque para ir a él; mantén pulsado para cambiarlo o suprimirlo",
			style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

/** Línea de tiempo de la pantalla de marcar: solo lectura, con las marcas y el siguiente recuadrado. */
@Composable
private fun TapTimeline(
	song: Song, events: List<ChordEvent>, starts: DoubleArray, cursor: Int, playBeat: Double, modifier: Modifier,
	onSelect: (Int) -> Unit, onLongPress: (Int) -> Unit, onDrag: (Int, Float) -> Unit, selected: Int?,
) {
	val byLine = remember(events) { events.withIndex().groupBy { it.value.line } }
	val rows = remember(events, song.lines) { song.lines.indices.filter { li -> byLine[li] != null || song.lines[li].section != null } }
	val listState = rememberLazyListState()
	val cursorRow = events.getOrNull(cursor.coerceAtMost(events.size - 1))?.let { rows.indexOf(it.line) } ?: -1
	LaunchedEffect(cursorRow) { if (cursorRow >= 0) listState.animateScrollToItem((cursorRow - 1).coerceAtLeast(0)) }
	val nowIdx = if (playBeat < 0) null else (0 until events.size).lastOrNull { starts[it] <= playBeat }
	LazyColumn(modifier.fillMaxWidth(), state = listState) {
		items(rows.size) { r ->
			val li = rows[r]
			val blocks = byLine[li].orEmpty()
			val first = blocks.firstOrNull()?.index
			val rowStart = first?.let { starts[it] } ?: 0.0
			val rowEnd = blocks.lastOrNull()?.let { starts[it.index + 1] } ?: rowStart
			val inRow = playBeat >= rowStart && playBeat < rowEnd
			val t = first?.let { events[it].t }
			TimelineRow(
				song.lines[li], blocks, rowStart = rowStart, beatsPerBar = song.beatsPerBar, selected = selected ?: cursor,
				timeLabel = t?.let { "%d:%02d".format((it / 60).toInt(), (it % 60).toInt()) },
				onSelect = onSelect, onMove = onDrag, onInsert = { _, _, _ -> }, dragBlocks = true,
				nowIndex = if (inRow) nowIdx else null, playhead = if (inRow) playBeat else null,
				editable = false, showMarks = true, dimUnmarked = true, onLongPress = onLongPress,
			)
		}
	}
}

/** Figuras y su duración en tiempos (compases de x/4: la negra es un tiempo). */
private val NOTE_VALUES = listOf(
	"𝅝 Redonda" to 4f, "𝅗𝅥 Blanca" to 2f, "♩ Negra" to 1f, "♪ Corchea" to 0.5f,
	"♬ Semicorchea" to 0.25f, "Fusa" to 0.125f, "Semifusa" to 0.0625f,
)

private fun fmtBeatsText(b: Double): String {
	val r = Math.round(b * 100) / 100.0
	val txt = if (r % 1.0 == 0.0) r.toInt().toString() else "%.2f".format(r).trimEnd('0').trimEnd(',', '.')
	return "$txt ${if (r == 1.0) "tiempo" else "tiempos"}"
}
