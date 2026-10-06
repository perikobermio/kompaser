package eus.kompaser.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eus.kompaser.model.ChordEvent
import eus.kompaser.model.ChordSheet
import eus.kompaser.model.Song
import kotlin.math.roundToInt

/** Una marca: el acorde (o pausa) que empieza en el segundo [t] del vídeo. */
private data class Mark(val event: ChordEvent, val t: Double, val advances: Boolean)

/**
 * Marcar tiempos escuchando la canción. Con el vídeo sonando:
 *  - «Siguiente» empieza el siguiente acorde de la partitura (la secuencia avanza).
 *  - «Otro» mete en ese instante un acorde aún sin decidir («?») sin que la secuencia avance;
 *    luego se elige cuál es tocándolo en la tira de marcas (o desde el editor).
 *  - «Pausa» marca un parón y «Deshacer» quita la última marca.
 * Cada marca guarda el segundo exacto del vídeo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapScreen(song: Song, onBack: () -> Unit, onSave: (Song) -> Unit) {
	val videoId = song.youtubeId
	if (videoId == null) {
		LaunchedEffect(Unit) { onBack() }
		return
	}
	// La secuencia es la de la partitura, en orden (sin las repeticiones o pausas añadidas a mano).
	val sequence = remember(song.id) { ChordSheet.defaultEvents(song.lines, song.beatsPerBar.toFloat()) }
	val songChords = remember(song.id) { sequence.map { it.chord }.distinct() }
	val marks = remember { mutableStateListOf<Mark>() }
	var end by remember { mutableStateOf<Double?>(null) }
	var confirmExit by remember { mutableStateOf(false) }
	var picking by remember { mutableStateOf<Int?>(null) }
	val video = remember { VideoSync() }
	val view = LocalView.current
	DisposableEffect(Unit) {
		view.keepScreenOn = true
		onDispose { view.keepScreenOn = false }
	}

	val next = marks.count { it.advances }
	val current = marks.lastOrNull()?.event
	val done = next >= sequence.size
	// Línea de la letra por la que vamos: la del próximo acorde (o la del último marcado al terminar).
	val lyricLine = sequence.getOrNull(next)?.line ?: current?.line ?: 0

	fun now() = video.seconds(System.nanoTime())
	fun mark(e: ChordEvent, advances: Boolean) {
		view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
		end = null
		marks += Mark(e, now(), advances)
	}

	fun result(): Song {
		val secPerBeat = 60.0 / song.bpm
		val endT = end ?: marks.lastOrNull()?.let { maxOf(now(), it.t + song.beatsPerBar * secPerBeat) } ?: 0.0
		val out = marks.mapIndexed { i, m ->
			val until = marks.getOrNull(i + 1)?.t ?: endT
			m.event.copy(beats = ((until - m.t) / secPerBeat).roundToInt().coerceAtLeast(1).toFloat(), t = m.t)
		}.toMutableList()
		// Lo que no se llegó a marcar se queda a continuación, a un compás por acorde.
		var t = endT
		for (e in sequence.drop(next)) {
			out += e.copy(t = t)
			t += e.beats * secPerBeat
		}
		return song.copy(
			events = out, videoOffsetMs = ((marks.firstOrNull()?.t ?: 0.0) * 1000).toLong(),
			updatedAt = System.currentTimeMillis(),
		)
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Column {
						Text("Marcar tiempos")
						Text("${song.title} · $next de ${sequence.size}", style = MaterialTheme.typography.bodySmall)
					}
				},
				navigationIcon = {
					IconButton(onClick = { if (marks.isEmpty()) onBack() else confirmExit = true }) { Icon(Icons.Filled.Close, "Salir") }
				},
				actions = {
					IconButton(onClick = { onSave(result()) }, enabled = marks.isNotEmpty()) { Icon(Icons.Filled.Check, "Guardar") }
				},
			)
		},
	) { pad ->
		Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			YouTubeBox(videoId, video, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
			MarksTrail(marks, onPick = { picking = it })

			// Letra: la línea del próximo acorde y las dos siguientes.
			Box(Modifier.fillMaxWidth().weight(1f)) {
				LyricLines(song, lyricLine, sequence.getOrNull(next), count = 3)
			}

			// Los dos botones de acorde.
			Row(Modifier.fillMaxWidth().height(132.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Box(
					Modifier.weight(1.7f).fillMaxHeight().clip(RoundedCornerShape(24.dp))
						.background(if (done) Brush.linearGradient(listOf(Fun.Turquoise, Color(0xFF3DD9C1))) else Fun.current)
						.clickable {
							if (!done) mark(sequence[next], advances = true)
							else {
								view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
								end = now()
							}
						},
					contentAlignment = Alignment.Center,
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text(if (done) "FIN" else "SIGUIENTE", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
						Text(
							if (done) (if (end == null) "Pulsa al acabar" else "Marcado") else sequence[next].chord,
							color = Color.White, fontWeight = FontWeight.Black, fontSize = if (done) 22.sp else 52.sp, maxLines = 1,
						)
						if (!done) Text(
							sequence.drop(next + 1).take(3).joinToString("  ") { it.chord },
							color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, maxLines = 1,
						)
					}
				}
				Box(
					Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(24.dp))
						.background(Brush.linearGradient(listOf(Fun.Purple, Fun.Pink)))
						.clickable {
							mark(ChordEvent(UNKNOWN_CHORD, song.beatsPerBar.toFloat(), current?.line ?: lyricLine, -1), advances = false)
						},
					contentAlignment = Alignment.Center,
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text("OTRO", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
						Text("?", color = Color.White, fontWeight = FontWeight.Black, fontSize = 52.sp)
						Text("sin avanzar", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
					}
				}
			}

			Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				FilledTonalButton(
					onClick = { mark(ChordEvent("", song.beatsPerBar.toFloat(), current?.line ?: 0, -1), advances = false) },
					enabled = current?.isRest != true, modifier = Modifier.weight(1f).height(52.dp),
				) {
					Icon(Icons.Filled.PauseCircle, null)
					Spacer(Modifier.width(6.dp))
					Text("Pausa")
				}
				FilledTonalButton(
					onClick = { if (end != null) end = null else if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) },
					enabled = marks.isNotEmpty(), modifier = Modifier.weight(1f).height(52.dp),
				) {
					Icon(Icons.AutoMirrored.Filled.Undo, null)
					Spacer(Modifier.width(6.dp))
					Text("Deshacer")
				}
			}
		}
	}

	picking?.let { i ->
		ChordPicker(songChords, marks.getOrNull(i)?.event?.chord, onDismiss = { picking = null }, onPick = { c ->
			marks.getOrNull(i)?.let { marks[i] = it.copy(event = it.event.copy(chord = c)) }
			picking = null
		})
	}

	if (confirmExit) AlertDialog(
		onDismissRequest = { confirmExit = false },
		title = { Text("¿Guardar lo marcado?") },
		text = { Text("Llevas ${marks.size} marcas. Lo que falte se queda a un compás por acorde detrás de la última.") },
		confirmButton = { TextButton(onClick = { confirmExit = false; onSave(result()) }) { Text("Guardar") } },
		dismissButton = { TextButton(onClick = { confirmExit = false; onBack() }) { Text("Descartar") } },
	)
}

/** Últimas marcas; los «?» (en rosa) se tocan para elegir qué acorde son. */
@Composable
private fun MarksTrail(marks: List<Mark>, onPick: (Int) -> Unit) {
	if (marks.isEmpty()) {
		Text(
			"Dale al play y pulsa «Siguiente» justo cuando entre cada acorde.",
			style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		return
	}
	val from = (marks.size - 10).coerceAtLeast(0)
	Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
		if (from > 0) Text("…", color = MaterialTheme.colorScheme.onSurfaceVariant)
		for (i in from until marks.size) {
			val e = marks[i].event
			val unknown = e.chord == UNKNOWN_CHORD
			Box(
				Modifier.clip(RoundedCornerShape(10.dp))
					.background(
						when {
							unknown -> Fun.Pink
							e.isRest || !marks[i].advances -> Fun.Purple.copy(alpha = 0.18f)
							else -> MaterialTheme.colorScheme.surfaceVariant
						},
					)
					.clickable(enabled = !e.isRest) { onPick(i) }
					.padding(horizontal = 10.dp, vertical = 6.dp),
			) {
				Text(
					if (e.isRest) "pausa" else e.chord, fontWeight = FontWeight.Bold,
					color = if (unknown) Color.White else MaterialTheme.colorScheme.onSurface,
				)
			}
		}
	}
}

/** [count] líneas de la letra desde [from], con el próximo acorde ([next]) resaltado. */
@Composable
private fun LyricLines(song: Song, from: Int, next: ChordEvent?, count: Int) {
	Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
		val section = (from downTo 0).firstNotNullOfOrNull { song.lines.getOrNull(it)?.section }
		if (section != null) Text(section, color = Fun.Purple, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
		for (n in from until (from + count).coerceAtMost(song.lines.size)) {
			val line = song.lines[n]
			if (n > from && line.section != null) {
				Text(line.section, color = Fun.Purple, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
			}
			val color = if (n == from) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
			Column(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
				if (line.chords.isNotEmpty()) Row {
					var col = 0
					line.chords.forEachIndexed { j, ch ->
						val pad = (ch.col - col).coerceAtLeast(if (j > 0) 1 else 0)
						Text(" ".repeat(pad), fontFamily = FontFamily.Monospace, fontSize = 16.sp)
						val hi = n == next?.line && j == next.pos
						Text(
							ch.name, fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Bold,
							color = if (hi) Color.White else color,
							modifier = if (hi) Modifier.background(Fun.Coral) else Modifier,
						)
						col += pad + ch.name.length
					}
				}
				if (line.lyric.isNotEmpty()) {
					Text(line.lyric, fontFamily = FontFamily.Monospace, fontSize = 16.sp, softWrap = false, overflow = TextOverflow.Clip, color = color)
				}
			}
		}
	}
}
