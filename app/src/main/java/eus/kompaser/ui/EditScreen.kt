package eus.kompaser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eus.kompaser.model.Song
import eus.kompaser.model.SongLine

private fun fmtSec(s: Double) = "%.3f".format(s).replace(',', '.').trimEnd('0').trimEnd('.')

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditScreen(song: Song, onBack: () -> Unit, onTap: () -> Unit, onSave: (Song) -> Unit) {
	var title by remember { mutableStateOf(song.title) }
	var artist by remember { mutableStateOf(song.artist) }
	var bpm by remember { mutableStateOf(song.bpm.toInt().toString()) }
	// Si la canción no está en afinación estándar, se propone la cejilla equivalente con afinación estándar.
	val suggested = remember(song.id) { standardCapo(song.tuning, song.capo) }
	var tuning by remember { mutableStateOf(if (suggested != null && suggested >= 0) "E A D G B E" else song.tuning) }
	var capo by remember { mutableStateOf((if (suggested != null && suggested >= 0) suggested else song.capo).toString()) }
	var beatsPerBar by remember { mutableStateOf(song.beatsPerBar) }
	var yt by remember { mutableStateOf(song.youtubeId.orEmpty()) }
	var offset by remember { mutableStateOf(fmtSec(song.videoOffsetMs / 1000.0)) }
	// Inicio "conocido" por los eventos; si el usuario cambia el campo a mano, se desplazan todos.
	var baseOffset by remember { mutableDoubleStateOf(song.videoOffsetMs / 1000.0) }
	var events by remember { mutableStateOf(song.events) }
	var picking by remember { mutableStateOf<Int?>(null) }
	var selected by remember { mutableStateOf<Int?>(null) }
	var step by remember { mutableFloatStateOf(1f) }
	val secPerBeat = 60.0 / song.bpm

	fun parsedOffset() = offset.replace(',', '.').toDoubleOrNull() ?: 0.0

	fun result(): Song {
		val o = parsedOffset()
		val evs = song.shiftTimes(events, 0, o - baseOffset)
		return song.copy(
			title = title.trim(), artist = artist.trim(),
			bpm = bpm.toFloatOrNull()?.coerceIn(30f, 260f) ?: song.bpm,
			capo = capo.toIntOrNull()?.coerceIn(0, 12) ?: song.capo,
			tuning = tuning,
			beatsPerBar = beatsPerBar,
			youtubeId = youtubeId(yt),
			videoOffsetMs = (o * 1000).toLong(),
			events = evs,
			updatedAt = System.currentTimeMillis(),
		)
	}

	fun move(i: Int, d: Float) {
		val before = events
		events = Timeline.moveStart(events, i, d, secPerBeat)
		// Mover el primer acorde es mover el inicio de la canción en el vídeo.
		if (i == 0 && events !== before) {
			val o = events[0].t ?: (parsedOffset() + d * secPerBeat)
			offset = fmtSec(o)
			baseOffset = o
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Editar") },
				navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
				actions = { IconButton(onClick = { onSave(result()) }) { Icon(Icons.Filled.Check, "Guardar") } },
			)
		},
		bottomBar = {
			selected?.let { i -> events.getOrNull(i) }?.let { e ->
				val i = selected!!
				TimelineToolbar(
					e, step, onStep = { step = if (step == 1f) 0.5f else 1f }, onMove = { d -> move(i, d) },
					onPick = { picking = i },
					onRemove = if (events.size > 1) ({ events = Timeline.remove(events, i); selected = null }) else null,
					onClose = { selected = null }, modifier = Modifier.navigationBarsPadding(),
				)
			}
		},
	) { pad ->
		val starts = remember(events) {
			DoubleArray(events.size + 1).also { a -> events.forEachIndexed { k, e -> a[k + 1] = a[k] + e.beats } }
		}
		val timelineRows = remember(events, song.lines) { timelineDisplayRows(song.lines, events) }
		LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
			item {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedTextField(title, { title = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth())
					OutlinedTextField(artist, { artist = it }, label = { Text("Artista") }, modifier = Modifier.fillMaxWidth())
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						OutlinedTextField(
							bpm, { bpm = it }, label = { Text("BPM") }, modifier = Modifier.weight(1f),
							keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
						)
						OutlinedTextField(
							capo, { capo = it }, label = { Text("Cejilla") }, modifier = Modifier.weight(1f),
							keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
						)
					}
					if (suggested != null) Text(
						if (suggested >= 0) "Cejilla calculada para tocar con afinación estándar (la original es ${tuningLabel(song.tuning)})."
						else "Afinación ${tuningLabel(song.tuning)}: con cejilla no se puede, baja la guitarra ${semitonesText(-suggested)}.",
						style = MaterialTheme.typography.bodySmall, color = Fun.Purple,
					)
					Text("Compás", style = MaterialTheme.typography.labelLarge)
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						for (n in listOf(2, 3, 4, 6)) FilterChip(beatsPerBar == n, { beatsPerBar = n }, label = { Text("$n/4") })
					}
					OutlinedTextField(
						yt, { yt = it }, label = { Text("Vídeo de YouTube (opcional)") }, singleLine = true,
						modifier = Modifier.fillMaxWidth(), placeholder = { Text("https://youtu.be/…") },
					)
					OutlinedTextField(
						offset, { offset = it }, label = { Text("Primer acorde en el segundo…") }, singleLine = true,
						modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
					)
					Button(
						onClick = { onSave(result()); onTap() }, enabled = youtubeId(yt) != null,
						modifier = Modifier.fillMaxWidth().height(56.dp),
						colors = ButtonDefaults.buttonColors(containerColor = Fun.Coral),
					) {
						Icon(Icons.Filled.TouchApp, null)
						Spacer(Modifier.width(8.dp))
						Text(if (youtubeId(yt) != null) "Marcar tiempos escuchando el vídeo" else "Marcar tiempos (pon antes un vídeo)")
					}
					Text("Línea de tiempo", style = MaterialTheme.typography.titleMedium)
					Text(
						"Cada bloque es un acorde; su ancho es lo que dura (rayas finas = pulsos, gruesas = compases). " +
							"Toca para seleccionar, arrastra el borde izquierdo para moverlo antes o después y mantén pulsado " +
							"en un punto para insertar un acorde o una pausa. Los «?» en rosa están por decidir.",
						style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
					FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						for (bars in listOf(0.5f, 1f, 2f)) AssistChip(
							onClick = { events = events.map { (if (it.isRest) it else it.copy(beats = beatsPerBar * bars)).copy(t = null) } },
							label = { Text("Reiniciar: ${if (bars == 0.5f) "½" else bars.toInt()} compás") },
						)
					}
					HorizontalDivider()
				}
			}
			itemsIndexed(timelineRows) { _, row ->
				val line = row.lineIndex?.let { song.lines[it] } ?: SongLine(null, "", emptyList())
				val blocks = row.blocks
				val first = blocks.firstOrNull()?.index
				val t = first?.let { events[it].t }
				TimelineRow(
					line, blocks, rowStart = first?.let { starts[it] } ?: 0.0, beatsPerBar = beatsPerBar, selected = selected,
					timeLabel = t?.let { "%d:%02d".format((it / 60).toInt(), (it % 60).toInt()) },
					onSelect = { selected = it },
					onMove = ::move,
					showMarks = true,
					onInsert = { i, at, chord ->
						events = Timeline.split(events, i, at, chord, secPerBeat)
						selected = i + 1
						if (chord == UNKNOWN_CHORD) picking = i + 1
					},
				)
			}
			item { Spacer(Modifier.height(96.dp)) }
		}
	}

	picking?.let { i ->
		ChordPicker(
			events.filter { !it.isRest && it.chord != UNKNOWN_CHORD }.map { it.chord }.distinct(), events.getOrNull(i)?.chord,
			onDismiss = { picking = null },
			onPick = { c ->
				events = events.toMutableList().also { l -> l.getOrNull(i)?.let { l[i] = it.copy(chord = c) } }
				picking = null
			},
		)
	}
}
