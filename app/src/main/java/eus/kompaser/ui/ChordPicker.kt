package eus.kompaser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Marcador de un acorde aún sin decidir (se inserta con «Otro acorde» y se elige después). */
const val UNKNOWN_CHORD = "?"

private val ROOTS = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
private val TYPES = listOf("" to "mayor", "m" to "m", "7" to "7", "m7" to "m7", "maj7" to "maj7", "sus2" to "sus2", "sus4" to "sus4", "dim" to "dim", "add9" to "add9", "5" to "5")

/**
 * Elegir un acorde: primero los de la canción y, si no está, cualquiera montando nota + tipo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChordPicker(songChords: List<String>, initial: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
	var root by remember { mutableStateOf<String?>(null) }
	var type by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(if (initial == null || initial == UNKNOWN_CHORD) "¿Qué acorde es?" else "Cambiar $initial por…") },
		text = {
			Column(Modifier.verticalScroll(rememberScrollState())) {
				Text("De esta canción", style = MaterialTheme.typography.labelLarge)
				FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					for (c in songChords) SuggestionChip(onClick = { onPick(c) }, label = { Text(c, fontWeight = FontWeight.Bold, fontSize = 18.sp) })
				}
				Text("Otro", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
				FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					for (r in ROOTS) FilterChip(root == r, { root = r }, label = { Text(r) })
				}
				FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
					for ((t, label) in TYPES) FilterChip(type == t, { type = t }, label = { Text(label) })
				}
			}
		},
		confirmButton = {
			TextButton(onClick = { root?.let { onPick(it + type) } }, enabled = root != null) {
				Text(root?.let { "Usar ${it + type}" } ?: "Elige nota")
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
	)
}
