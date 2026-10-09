package eus.kompaser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eus.kompaser.model.Song

private fun fmtSec(s: Double) = "%.3f".format(s).replace(',', '.').trimEnd('0').trimEnd('.')
private fun fmtBpm(bpm: Float) = if (bpm % 1f == 0f) bpm.toInt().toString() else bpm.toString().trimEnd('0').trimEnd('.')

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(song: Song, onBack: () -> Unit, onSave: (Song) -> Unit) {
	var title by remember { mutableStateOf(song.title) }
	var artist by remember { mutableStateOf(song.artist) }
	var bpm by remember { mutableStateOf(fmtBpm(song.bpm)) }
	val suggested = remember(song.id) { standardCapo(song.tuning, song.capo) }
	val tuning = if (suggested != null && suggested >= 0) "E A D G B E" else song.tuning
	var capo by remember { mutableStateOf((if (suggested != null && suggested >= 0) suggested else song.capo).toString()) }
	var beatsPerBar by remember { mutableStateOf(song.beatsPerBar) }
	var yt by remember { mutableStateOf(song.youtubeId.orEmpty()) }
	var offset by remember { mutableStateOf(fmtSec(song.videoOffsetMs / 1000.0)) }
	val originalOffset = song.videoOffsetMs / 1000.0

	fun result(): Song {
		val o = offset.replace(',', '.').toDoubleOrNull() ?: originalOffset
		val newBpm = bpm.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(30f, 260f) ?: song.bpm
		val newBar = beatsPerBar.toFloat()
		val events = if (song.events.isNotEmpty() && song.events.all { it.startBeat != null }) {
			song.events.map { event ->
				val local = ((event.startBeat!! - event.line * song.beatsPerBar) / song.beatsPerBar).coerceIn(0f, 1f) * newBar
				val start = event.line * newBar + local
				event.copy(startBeat = start, t = o + start * 60.0 / newBpm)
			}
		} else song.shiftTimes(song.events, 0, o - originalOffset)
		return song.copy(
			title = title.trim(),
			artist = artist.trim(),
			bpm = newBpm,
			capo = capo.toIntOrNull()?.coerceIn(0, 12) ?: song.capo,
			tuning = tuning,
			beatsPerBar = beatsPerBar,
			youtubeId = youtubeId(yt),
			videoOffsetMs = (o * 1000).toLong(),
			events = events,
			updatedAt = System.currentTimeMillis(),
		)
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text("Editar") },
				navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
				actions = { IconButton(onClick = { onSave(result()) }) { Icon(Icons.Filled.Check, "Guardar") } },
			)
		},
	) { pad ->
		LazyColumn(
			Modifier.padding(pad).padding(horizontal = 16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			item {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					OutlinedTextField(title, { title = it }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth())
					OutlinedTextField(artist, { artist = it }, label = { Text("Artista") }, modifier = Modifier.fillMaxWidth())
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						OutlinedTextField(
							bpm, { bpm = it }, label = { Text("BPM") }, modifier = Modifier.weight(1f),
							keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
						)
						OutlinedTextField(
							capo, { capo = it }, label = { Text("Cejilla") }, modifier = Modifier.weight(1f),
							keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
						)
					}
					if (suggested != null) Text(
						if (suggested >= 0) "Cejilla calculada para tocar con afinación estándar (la original es ${tuningLabel(song.tuning)})."
						else "Afinación ${tuningLabel(song.tuning)}: con cejilla no se puede, baja la guitarra ${semitonesText(-suggested)}.",
						style = MaterialTheme.typography.bodySmall,
						color = Fun.Purple,
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
						offset, { offset = it }, label = { Text("Primer pulso en el segundo…") }, singleLine = true,
						modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
					)
				}
			}
		}
	}
}
