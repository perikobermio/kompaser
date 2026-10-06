package eus.kompaser.data

import android.content.Context
import eus.kompaser.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Canciones guardadas en el móvil (un JSON por canción) y sincronizadas con el servidor
 * PostgREST/Postgres si hay uno configurado. Gana la versión con `updated_at` más reciente.
 */
class SongStore(context: Context) {
	private val dir = File(context.filesDir, "songs").apply { mkdirs() }
	private val prefs = context.getSharedPreferences("kompaser", Context.MODE_PRIVATE)

	var serverUrl: String
		get() = prefs.getString("server", "").orEmpty()
		set(v) = prefs.edit().putString("server", v.trim().trimEnd('/')).apply()

	/** En el reproductor, ver la línea de tiempo en vez de las tarjetas y la letra. */
	var timelineMode: Boolean
		get() = prefs.getBoolean("timelineMode", false)
		set(v) = prefs.edit().putBoolean("timelineMode", v).apply()

	var metronome: Boolean
		get() = prefs.getBoolean("metronome", true)
		set(v) = prefs.edit().putBoolean("metronome", v).apply()

	private var deleted: Set<String>
		get() = prefs.getStringSet("deleted", emptySet()).orEmpty()
		set(v) = prefs.edit().putStringSet("deleted", v).apply()

	fun all(): List<Song> = dir.listFiles { f -> f.extension == "json" }.orEmpty()
		.mapNotNull { runCatching { Song.fromJson(JSONObject(it.readText())) }.getOrNull() }
		.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))

	fun save(song: Song) = File(dir, "${song.id}.json").writeText(song.toJson().toString())

	fun delete(song: Song) {
		File(dir, "${song.id}.json").delete()
		deleted = deleted + song.id
	}

	/** Devuelve un resumen ("↓ 2 · ↑ 1"). Lanza excepción si el servidor no responde. */
	fun sync(): String {
		val base = serverUrl.ifEmpty { error("Configura primero la URL del servidor") }
		val gone = deleted
		if (gone.isNotEmpty()) {
			Http.request("DELETE", "$base/songs?id=in.(${gone.joinToString(",")})", null)
			deleted = emptySet()
		}
		val remote = JSONArray(Http.get("$base/songs?select=*")).let { a ->
			List(a.length()) { Song.fromJson(a.getJSONObject(it)) }
		}.associateBy { it.id }
		val local = all().associateBy { it.id }

		val down = remote.values.filter { r -> local[r.id].let { it == null || it.updatedAt < r.updatedAt } }
		down.forEach(::save)
		val up = local.values.filter { l -> remote[l.id].let { it == null || it.updatedAt < l.updatedAt } }
		if (up.isNotEmpty()) {
			Http.request(
				"POST", "$base/songs", JSONArray(up.map { it.toJson() }).toString(),
				mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
			)
		}
		return "↓ ${down.size} · ↑ ${up.size}"
	}
}
