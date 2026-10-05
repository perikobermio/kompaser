package eus.kompaser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eus.kompaser.model.Song

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SongListScreen(
	songs: List<Song>,
	busy: Boolean,
	snackbar: SnackbarHostState,
	serverUrl: String,
	onOpen: (Song) -> Unit,
	onEdit: (Song) -> Unit,
	onDelete: (Song) -> Unit,
	onImport: () -> Unit,
	onSync: () -> Unit,
	onServer: (String) -> Unit,
) {
	var serverDialog by remember { mutableStateOf(false) }
	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Text(
						buildAnnotatedString {
							"Kompaser".forEachIndexed { i, ch -> withStyle(SpanStyle(color = Fun.beats[i % Fun.beats.size])) { append(ch) } }
						},
						fontWeight = FontWeight.Black,
					)
				},
				actions = {
					IconButton(onClick = onSync, enabled = !busy) { Icon(Icons.Filled.Sync, "Sincronizar") }
					IconButton(onClick = { serverDialog = true }) { Icon(Icons.Filled.Dns, "Servidor") }
				},
			)
		},
		floatingActionButton = {
			ExtendedFloatingActionButton(onClick = onImport, containerColor = Fun.Coral, contentColor = Color.White, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Importar") })
		},
		snackbarHost = { SnackbarHost(snackbar) },
	) { pad ->
		Box(Modifier.fillMaxSize().padding(pad)) {
			if (songs.isEmpty() && !busy) Text(
				"Aún no hay canciones.\nPulsa «Importar» y pega un enlace de Ultimate Guitar\n(o compártelo desde el navegador).",
				Modifier.align(Alignment.Center).padding(32.dp), style = MaterialTheme.typography.bodyLarge,
			)
			LazyColumn(
				Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				items(songs, key = { it.id }) { s -> SongRow(s, { onOpen(s) }, { onEdit(s) }, { onDelete(s) }) }
			}
			if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
		}
	}
	if (serverDialog) {
		var url by remember { mutableStateOf(serverUrl.ifEmpty { "http://localhost:3000" }) }
		AlertDialog(
			onDismissRequest = { serverDialog = false },
			title = { Text("Servidor") },
			text = {
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text("URL de la API (PostgREST) donde se guardan las canciones en Postgres.", style = MaterialTheme.typography.bodySmall)
					OutlinedTextField(url, { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
				}
			},
			confirmButton = {
				TextButton(onClick = {
					onServer(url)
					serverDialog = false
				}) { Text("Guardar") }
			},
			dismissButton = { TextButton(onClick = { serverDialog = false }) { Text("Cancelar") } },
		)
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(s: Song, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
	var menu by remember { mutableStateOf(false) }
	var confirm by remember { mutableStateOf(false) }
	val accent = Fun.accent(s.id)
	Card(
		Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true }),
		shape = MaterialTheme.shapes.medium,
		colors = CardDefaults.cardColors(containerColor = Color.White),
		elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
	) {
		Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.width(8.dp).fillMaxHeight().background(accent))
			Box(
				Modifier.padding(start = 14.dp).size(48.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
				contentAlignment = Alignment.Center,
			) { Text(s.events.firstOrNull { !it.isRest }?.chord ?: "♪", color = accent, fontWeight = FontWeight.Black) }
			Row(Modifier.padding(14.dp).weight(1f), verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text(s.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
				Text(
					buildString {
						append(s.artist)
						append("  ·  ${s.bpm.toInt()} BPM")
						if (s.capo > 0) append("  ·  Cejilla ${s.capo}")
						if (s.youtubeId != null) append("  ·  ▶ vídeo")
					},
					style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
				Text(
					s.events.filter { !it.isRest }.map { it.chord }.distinct().joinToString("  "),
					style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1,
				)
			}
			DropdownMenu(menu, { menu = false }) {
				DropdownMenuItem(text = { Text("Editar") }, onClick = { menu = false; onEdit() })
				DropdownMenuItem(text = { Text("Borrar") }, onClick = { menu = false; confirm = true })
			}
			}
		}
	}
	if (confirm) AlertDialog(
		onDismissRequest = { confirm = false },
		title = { Text("¿Borrar «${s.title}»?") },
		confirmButton = { TextButton(onClick = { confirm = false; onDelete() }) { Text("Borrar") } },
		dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } },
	)
}

@Composable
fun ImportDialog(initial: String, onDismiss: () -> Unit, onImport: (String) -> Unit) {
	var url by remember { mutableStateOf(initial) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text("Importar de Ultimate Guitar") },
		text = {
			OutlinedTextField(
				url, { url = it }, label = { Text("Enlace") }, modifier = Modifier.fillMaxWidth(),
				placeholder = { Text("https://es.ultimate-guitar.com/tab/…") },
			)
		},
		confirmButton = { TextButton(onClick = { onImport(url.trim()) }, enabled = url.isNotBlank()) { Text("Importar") } },
		dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
	)
}
