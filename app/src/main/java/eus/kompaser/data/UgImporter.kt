package eus.kompaser.data

import eus.kompaser.model.Barre
import eus.kompaser.model.ChordSheet
import eus.kompaser.model.ChordShape
import eus.kompaser.model.Song
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Importa una tablatura de acordes de Ultimate Guitar a partir de su URL (vale la de impresión o la normal). */
object UgImporter {
	private val storeRe = Regex("""class="js-store" data-content="([^"]*)"""")

	fun accepts(url: String) = "ultimate-guitar.com" in url

	fun looksValid(html: String) = "js-store" in html

	fun parse(html: String, url: String): Song {
		val raw = storeRe.find(html)?.groupValues?.get(1) ?: error("La página no contiene ninguna tablatura")
		val data = JSONObject(Html.unescape(raw)).getJSONObject("store").getJSONObject("page").getJSONObject("data")
		val tab = data.getJSONObject("tab")
		val view = data.getJSONObject("tab_view")
		val content = view.getJSONObject("wiki_tab").getString("content")
		val meta = view.optJSONObject("meta")
		val bpm = view.optJSONArray("strummings")?.optJSONObject(0)?.optDouble("bpm")?.takeIf { it > 0 } ?: 90.0
		val lines = ChordSheet.parse(content)

		val shapes = mutableMapOf<String, ChordShape>()
		view.optJSONObject("applicature")?.let { app ->
			for (name in app.keys()) app.optJSONArray(name)?.optJSONObject(0)?.let { shapes[name] = ugShape(it) }
		}
		for (name in lines.flatMap { l -> l.chords.map { it.name } }.distinct())
			if (name !in shapes) ChordLibrary.shape(name)?.let { shapes[name] = it }

		return Song(
			ugId = tab.optLong("id"),
			title = tab.optString("song_name"),
			artist = tab.optString("artist_name"),
			capo = meta?.optInt("capo") ?: 0,
			tuning = meta?.optJSONObject("tuning")?.optString("value")?.ifEmpty { null } ?: "E A D G B E",
			bpm = bpm.toFloat(),
			sourceUrl = tab.optString("tab_url").ifEmpty { url },
			content = content,
			events = ChordSheet.defaultEvents(lines, 4f),
			shapes = shapes,
		)
	}

	/** UG da las cuerdas de aguda a grave; las invertimos. */
	private fun ugShape(o: JSONObject): ChordShape {
		val f = o.getJSONArray("frets")
		val d = o.getJSONArray("fingers")
		val frets = List(6) { f.getInt(5 - it) }
		val fingers = List(6) { d.getInt(5 - it) }
		val barre = o.optJSONArray("listCapos")?.optJSONObject(0)?.let {
			Barre(it.getInt("fret"), 5 - it.getInt("lastString"), 5 - it.getInt("startString"))
		}
		return ChordShape(frets, fingers.mapIndexed { i, x -> if (barre != null && x == 0 && frets[i] == barre.fret && i in barre.from..barre.to) 1 else x }, barre)
	}
}

object Http {
	private const val UA = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

	fun get(url: String, headers: Map<String, String> = emptyMap()): String = request("GET", url, null, headers)

	fun request(method: String, url: String, body: String?, headers: Map<String, String> = emptyMap()): String {
		val c = URL(url).openConnection() as HttpURLConnection
		try {
			c.requestMethod = method
			c.connectTimeout = 15_000
			c.readTimeout = 30_000
			c.instanceFollowRedirects = true
			c.setRequestProperty("User-Agent", UA)
			c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
			headers.forEach(c::setRequestProperty)
			if (body != null) {
				c.doOutput = true
				c.setRequestProperty("Content-Type", "application/json")
				c.outputStream.use { it.write(body.toByteArray()) }
			}
			val code = c.responseCode
			val text = (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
			if (code >= 400) error("HTTP $code: ${text.take(200)}")
			return text
		} finally {
			c.disconnect()
		}
	}
}

object Html {
	private val entityRe = Regex("""&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);""")
	private val named = mapOf("quot" to "\"", "amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'", "nbsp" to " ")

	fun unescape(s: String) = entityRe.replace(s) { m ->
		val e = m.groupValues[1]
		when {
			e.startsWith("#x") -> String(Character.toChars(e.substring(2).toInt(16)))
			e.startsWith("#") -> String(Character.toChars(e.substring(1).toInt()))
			else -> named[e] ?: m.value
		}
	}
}
