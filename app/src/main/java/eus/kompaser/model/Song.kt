package eus.kompaser.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Cejilla: traste y rango de cuerdas (índices 0 = 6ª grave … 5 = 1ª aguda). */
data class Barre(val fret: Int, val from: Int, val to: Int)

/** Digitación de guitarra. Cuerdas de la 6ª (Mi grave) a la 1ª (mi agudo); -1 = no se toca, 0 = al aire. */
data class ChordShape(val frets: List<Int>, val fingers: List<Int>, val barre: Barre? = null) {
	fun toJson(): JSONObject = JSONObject()
		.put("frets", JSONArray(frets))
		.put("fingers", JSONArray(fingers))
		.put("barre", barre?.let { JSONObject().put("fret", it.fret).put("from", it.from).put("to", it.to) } ?: JSONObject.NULL)

	companion object {
		fun fromJson(o: JSONObject) = ChordShape(
			frets = o.getJSONArray("frets").ints(),
			fingers = o.getJSONArray("fingers").ints(),
			barre = o.optJSONObject("barre")?.let { Barre(it.getInt("fret"), it.getInt("from"), it.getInt("to")) },
		)
	}
}

/**
 * Un acorde en la secuencia de reproducción: dura [beats] tiempos y apunta a su posición en la letra.
 * Con [chord] vacío es una pausa (parón sin acordes); entonces [pos] es -1.
 * [t] es el segundo del vídeo en que empieza (analizador o marcado a mano); [manual] indica que ese
 * tiempo lo marcó el usuario escuchando el vídeo.
 */
data class ChordEvent(
	val chord: String, val beats: Float, val line: Int, val pos: Int, val t: Double? = null, val manual: Boolean = false,
) {
	val isRest: Boolean get() = chord.isEmpty()

	fun toJson(): JSONObject = JSONObject().put("c", chord).put("b", beats.toDouble()).put("l", line).put("p", pos)
		.also { o -> t?.let { o.put("t", it) } }
		.also { o -> if (manual) o.put("m", true) }

	companion object {
		fun fromJson(o: JSONObject) = ChordEvent(
			o.getString("c"), o.getDouble("b").toFloat(), o.getInt("l"), o.getInt("p"),
			if (o.has("t") && !o.isNull("t")) o.getDouble("t") else null,
			o.optBoolean("m"),
		)
	}
}

data class Song(
	val id: String = UUID.randomUUID().toString(),
	val ugId: Long? = null,
	val title: String,
	val artist: String,
	val capo: Int = 0,
	val tuning: String = "E A D G B E",
	val bpm: Float = 90f,
	val beatsPerBar: Int = 4,
	val youtubeId: String? = null,
	/** Instante del vídeo (ms) en el que empieza el primer acorde. */
	val videoOffsetMs: Long = 0,
	val sourceUrl: String? = null,
	/** Letra con acordes en el formato de Ultimate Guitar ([ch]…[/ch], [tab]…[/tab]). */
	val content: String,
	val events: List<ChordEvent>,
	val shapes: Map<String, ChordShape>,
	val updatedAt: Long = System.currentTimeMillis(),
) {
	val lines: List<SongLine> by lazy { ChordSheet.parse(content) }

	/** Tiempo (en pulsos) en el que empieza cada acorde; el último elemento es la duración total. */
	val starts: DoubleArray by lazy {
		DoubleArray(events.size + 1).also { a -> events.forEachIndexed { i, e -> a[i + 1] = a[i] + e.beats } }
	}

	/**
	 * Segundo del vídeo en que empieza cada evento (y el final del último), si todos lo tienen.
	 * Con esto el vídeo manda: no se acumula desfase aunque el tempo de la grabación varíe.
	 */
	val videoTimes: DoubleArray? by lazy {
		if (events.isEmpty() || events.any { it.t == null }) return@lazy null
		DoubleArray(events.size + 1) { i -> if (i < events.size) events[i].t!! else events.last().t!! + events.last().beats * 60.0 / bpm }
	}

	/** Mueve los tiempos de vídeo de los eventos desde [from] en adelante [seconds] segundos. */
	fun shiftTimes(events: List<ChordEvent>, from: Int, seconds: Double): List<ChordEvent> =
		events.mapIndexed { i, e -> if (i >= from && e.t != null) e.copy(t = e.t + seconds) else e }

	fun toJson(): JSONObject = JSONObject()
		.put("id", id)
		.put("ug_id", ugId ?: JSONObject.NULL)
		.put("title", title)
		.put("artist", artist)
		.put("instrument", "guitar")
		.put("capo", capo)
		.put("tuning", tuning)
		.put("bpm", bpm.toDouble())
		.put("beats_per_bar", beatsPerBar)
		.put("youtube_id", youtubeId ?: JSONObject.NULL)
		.put("video_offset_ms", videoOffsetMs)
		.put("source_url", sourceUrl ?: JSONObject.NULL)
		.put("content", content)
		.put("events", JSONArray(events.map { it.toJson() }))
		.put("shapes", JSONObject().also { o -> shapes.forEach { (k, v) -> o.put(k, v.toJson()) } })
		.put("updated_at", updatedAt)

	companion object {
		fun fromJson(o: JSONObject): Song {
			val shapes = o.getJSONObject("shapes")
			return Song(
				id = o.getString("id"),
				ugId = if (o.isNull("ug_id")) null else o.getLong("ug_id"),
				title = o.getString("title"),
				artist = o.optString("artist"),
				capo = o.optInt("capo"),
				tuning = o.optString("tuning", "E A D G B E"),
				bpm = o.optDouble("bpm", 90.0).toFloat(),
				beatsPerBar = o.optInt("beats_per_bar", 4),
				youtubeId = o.optStringOrNull("youtube_id"),
				videoOffsetMs = o.optLong("video_offset_ms"),
				sourceUrl = o.optStringOrNull("source_url"),
				content = o.getString("content"),
				events = o.getJSONArray("events").let { a -> List(a.length()) { ChordEvent.fromJson(a.getJSONObject(it)) } },
				shapes = shapes.keys().asSequence().associateWith { ChordShape.fromJson(shapes.getJSONObject(it)) },
				updatedAt = o.optLong("updated_at"),
			)
		}
	}
}

data class PlacedChord(val col: Int, val name: String)

/** Una línea de la canción: los acordes (con su columna) y la letra que va debajo. */
data class SongLine(val section: String?, val lyric: String, val chords: List<PlacedChord>)

object ChordSheet {
	private val sectionRe = Regex("""^\s*\[([^\[\]]+)]\s*$""")
	private val chRe = Regex("""\[ch](.*?)\[/ch]""")

	fun parse(content: String): List<SongLine> {
		val out = mutableListOf<SongLine>()
		var section: String? = null
		var pending: List<PlacedChord>? = null
		fun flush(lyric: String) {
			out += SongLine(section, lyric, pending.orEmpty())
			section = null
			pending = null
		}
		for (raw in content.replace("\r", "").split('\n')) {
			val l = raw.replace("[tab]", "").replace("[/tab]", "")
			if (chRe.containsMatchIn(l)) {
				if (pending != null) flush("")
				pending = place(l)
				continue
			}
			val sec = sectionRe.find(l)
			when {
				sec != null -> {
					if (pending != null) flush("")
					section = sec.groupValues[1].trim()
				}
				l.isBlank() -> if (pending != null) flush("")
				else -> flush(l.trimEnd())
			}
		}
		if (pending != null) flush("")
		return out
	}

	private fun place(line: String): List<PlacedChord> {
		var removed = 0
		return chRe.findAll(line).map { m ->
			PlacedChord(m.range.first - removed, m.groupValues[1].trim()).also { removed += m.value.length - m.groupValues[1].length }
		}.filter { it.name.isNotEmpty() }.toList()
	}

	/** Secuencia por defecto: cada acorde dura un compás. */
	fun defaultEvents(lines: List<SongLine>, beats: Float): List<ChordEvent> =
		lines.flatMapIndexed { i, l -> l.chords.mapIndexed { j, c -> ChordEvent(c.name, beats, i, j) } }
}

private fun JSONArray.ints() = List(length()) { getInt(it) }

private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).ifEmpty { null }
