package eus.kompaser

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import eus.kompaser.data.SongStore
import eus.kompaser.data.Importer
import eus.kompaser.model.Song
import eus.kompaser.ui.EditScreen
import eus.kompaser.ui.ImportDialog
import eus.kompaser.ui.KompaserTheme
import eus.kompaser.ui.PlayerScreen
import eus.kompaser.ui.SongListScreen
import eus.kompaser.ui.TapScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Screen {
	data object List : Screen
	data class Play(val id: String) : Screen
	data class Edit(val id: String, val back: Screen) : Screen
	data class Tap(val id: String, val back: Screen) : Screen
}

class MainActivity : ComponentActivity() {
	private val sharedUrl = mutableStateOf<String?>(null)
	private val importer by lazy { Importer(this) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		sharedUrl.value = urlFrom(intent)
		val store = SongStore(applicationContext)
		setContent {
			KompaserTheme {
				val scope = rememberCoroutineScope()
				val snackbar = remember { SnackbarHostState() }
				var songs by remember { mutableStateOf(store.all()) }
				var screen by remember { mutableStateOf<Screen>(Screen.List) }
				var busy by remember { mutableStateOf(false) }
				var importing by remember { mutableStateOf<String?>(null) }
				fun reload() {
					songs = store.all()
				}

				fun pushQuietly() {
					if (store.serverUrl.isEmpty()) return
					scope.launch(Dispatchers.IO) { runCatching { store.sync() } }
				}

				fun save(s: Song) {
					store.save(s)
					reload()
					pushQuietly()
				}

				fun sync() = scope.launch {
					busy = true
					val msg = withContext(Dispatchers.IO) { runCatching { store.sync() } }
						.fold({ "Sincronizado $it" }, { "Error al sincronizar: ${it.message}" })
					busy = false
					reload()
					snackbar.showSnackbar(msg)
				}

				fun import(url: String) = scope.launch {
					importing = null
					busy = true
					val r = runCatching { importer.import(url) }
					busy = false
					r.onSuccess { s ->
						val existing = songs.firstOrNull { (it.ugId != null && it.ugId == s.ugId) || it.sourceUrl == s.sourceUrl }
						val song = existing?.let {
							s.copy(id = it.id, youtubeId = it.youtubeId ?: s.youtubeId, videoOffsetMs = it.videoOffsetMs, bpm = it.bpm, events = if (it.content == s.content) it.events else s.events)
						} ?: s
						save(song)
						screen = Screen.Play(song.id)
					}.onFailure { snackbar.showSnackbar("No se pudo importar: ${it.message}") }
				}

				val shared = sharedUrl.value
				LaunchedEffect(shared) {
					if (shared != null) {
						sharedUrl.value = null
						import(shared)
					}
				}

				BackHandler(screen != Screen.List) {
					screen = when (val s = screen) {
						is Screen.Edit -> s.back
						is Screen.Tap -> s.back
						else -> Screen.List
					}
				}

				when (val s = screen) {
					Screen.List -> SongListScreen(
						songs, busy, snackbar, store.serverUrl,
						onOpen = { screen = Screen.Play(it.id) },
						onEdit = { screen = Screen.Edit(it.id, Screen.List) },
						onDelete = {
							store.delete(it)
							reload()
							pushQuietly()
						},
						onImport = { importing = clipboardUrl().orEmpty() },
						onSync = { sync() },
						onServer = {
							store.serverUrl = it
							sync()
						},
					)
					is Screen.Play -> songs.firstOrNull { it.id == s.id }?.let { song ->
						PlayerScreen(
							song, store, onBack = { screen = Screen.List }, onEdit = { screen = Screen.Edit(song.id, s) },
							onTap = { screen = Screen.Tap(song.id, s) },
							onSave = ::save,
						)
					} ?: LaunchedEffect(s) { screen = Screen.List }
					is Screen.Edit -> songs.firstOrNull { it.id == s.id }?.let { song ->
						EditScreen(song, onBack = { screen = s.back }, onTap = { screen = Screen.Tap(song.id, s) }, onSave = {
							save(it)
							screen = s.back
						})
					} ?: LaunchedEffect(s) { screen = Screen.List }
					is Screen.Tap -> songs.firstOrNull { it.id == s.id }?.let { song ->
						TapScreen(song, onBack = { screen = s.back }, onSave = {
							save(it)
							screen = s.back
						}, onSaveStay = ::save)
					} ?: LaunchedEffect(s) { screen = Screen.List }
				}

				importing?.let { ImportDialog(it, onDismiss = { importing = null }, onImport = ::import) }
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		urlFrom(intent)?.let { sharedUrl.value = it }
	}

	private fun urlFrom(i: Intent?): String? = when (i?.action) {
		Intent.ACTION_SEND -> importer.extractUrl(i.getStringExtra(Intent.EXTRA_TEXT))
		Intent.ACTION_VIEW -> i.dataString
		else -> null
	}

	private fun clipboardUrl(): String? = runCatching {
		val cm = getSystemService(ClipboardManager::class.java)
		importer.extractUrl(cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString())
	}.getOrNull()
}
