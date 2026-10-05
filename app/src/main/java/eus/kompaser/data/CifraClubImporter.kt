package eus.kompaser.data

import eus.kompaser.model.ChordSheet
import eus.kompaser.model.ChordShape
import eus.kompaser.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Importa acordes de Cifra Club y los deja en el mismo formato que Ultimate Guitar ([ch]…[/ch]).
 *
 * La letra sale del <pre> (acordes en <b>). Los metadatos y digitaciones salen, según la versión de
 * la web, del payload de Next.js (`self.__next_f.push`) o del antiguo objeto `contentSelector: 'pre'`.
 */
object CifraClubImporter {
	fun accepts(url: String) = "cifraclub." in url

	fun looksValid(html: String) = "<pre" in html && ("data-chord" in html || "contentSelector" in html)

	private data class Meta(
		val title: String?, val artist: String?, val capo: Int?, val tuning: String?, val youtubeId: String?,
		val chords: JSONArray?,
	)

	fun parse(html: String, url: String): Song {
		val pre = Regex("""<pre[^>]*>(.*?)</pre>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
			?: error("La página no contiene acordes")
		val content = toUgMarkup(pre)
		val lines = ChordSheet.parse(content)
		val meta = nextMeta(html) ?: legacyMeta(html)

		val shapes = mutableMapOf<String, ChordShape>()
		meta.chords?.let { arr ->
			for (i in 0 until arr.length()) {
				val c = arr.getJSONObject(i)
				// "alt" / "altName" es el nombre que aparece en la letra (forma del acorde); "chord" / "name" el real.
				val name = c.optString("alt").ifEmpty { c.optString("altName") }.ifEmpty { c.optString("chord") }.ifEmpty { c.optString("name") }
				guitarShapes(c)?.let(::parseFrets)?.let { shapes[name] = ChordLibrary.fromFrets(it) }
			}
		}
		for (name in lines.flatMap { l -> l.chords.map { it.name } }.distinct())
			if (name !in shapes) ChordLibrary.shape(name)?.let { shapes[name] = it }

		// <title>Mama Said (acordes) - Metallica - Cifra Club</title>
		val titleTag = Regex("""<title>(.*?)</title>""").find(html)?.groupValues?.get(1)?.let(Html::unescape)?.split(" - ")
		return Song(
			title = meta.title ?: titleTag?.getOrNull(0)?.replace(Regex("""\s*\([^)]*\)\s*$"""), "") ?: "Sin título",
			artist = meta.artist ?: titleTag?.getOrNull(1).orEmpty(),
			capo = meta.capo ?: 0,
			tuning = meta.tuning ?: "E A D G B E",
			youtubeId = meta.youtubeId,
			sourceUrl = url.substringBefore('#').substringBefore('?'),
			content = content,
			events = ChordSheet.defaultEvents(lines, 4f),
			shapes = shapes,
		)
	}

	/** Web actual (Next.js): el estado va en trozos de texto JSON escapados dentro de self.__next_f.push([1,"…"]). */
	private fun nextMeta(html: String): Meta? {
		val parts = Regex("""self\.__next_f\.push\(\[1,("(?:\\.|[^"\\])*")]\)""").findAll(html)
			.mapNotNull { runCatching { JSONArray("[" + it.groupValues[1] + "]").getString(0) }.getOrNull() }.toList()
		if (parts.isEmpty()) return null
		val rsc = parts.joinToString("")
		val chordsAt = rsc.indexOf("\"chords\":[{\"chord\"").takeIf { it >= 0 } ?: return null
		val config = Regex(""""config":(\{[^{}]*\})""").find(rsc, chordsAt)?.groupValues?.get(1)
			?.let { runCatching { JSONObject(it) }.getOrNull() }
		val artist = Regex(""""artist":\{"id":\d+,"name":("(?:\\.|[^"\\])*")""").findAll(rsc.substring(0, chordsAt)).lastOrNull()
			?.groupValues?.get(1)?.let { JSONArray("[$it]").getString(0) }
		return Meta(
			title = null,
			artist = artist,
			capo = config?.optInt("capo"),
			tuning = config?.optString("tuning")?.ifEmpty { null },
			youtubeId = Regex(""""youtubeID":"([A-Za-z0-9_-]{11})"""").find(rsc)?.groupValues?.get(1),
			chords = balancedArray(rsc, rsc.indexOf('[', chordsAt)),
		)
	}

	/** Versión anterior de la web: objeto JS con `contentSelector: 'pre'`. */
	private fun legacyMeta(html: String): Meta {
		val at = html.indexOf("contentSelector")
		if (at < 0) return Meta(null, null, null, null, null, null)
		val cfg = html.substring((at - 3000).coerceAtLeast(0), at + 200)
		fun str(key: String) = Regex("""\b$key:\s*'((?:\\.|[^'\\])*)'""").findAll(cfg).lastOrNull()
			?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")?.ifEmpty { null }
		val chordsAt = html.indexOf("chords:", at)
		return Meta(
			title = str("name"),
			artist = str("artist"),
			capo = Regex("""\bcapo:\s*(\d+)""").find(cfg)?.groupValues?.get(1)?.toInt(),
			tuning = str("tuning"),
			youtubeId = str("youtubeId"),
			chords = if (chordsAt < 0) null else balancedArray(html, html.indexOf('[', chordsAt)),
		)
	}

	/** Formas de guitarra (la primera es la principal). Formato nuevo: shapes.guitar; antiguo: shapeList con instrumentType 1. */
	private fun guitarShapes(c: JSONObject): String? {
		c.optJSONObject("shapes")?.optJSONArray("guitar")?.optString(0)?.let { return it }
		val list = c.optJSONArray("shapeList") ?: return null
		for (i in 0 until list.length()) {
			val e = list.getJSONObject(i)
			if (e.optInt("instrumentType") != 1) continue
			val tunings = e.optJSONArray("shapeTunings") ?: continue
			for (t in 0 until tunings.length()) tunings.getJSONObject(t).optJSONArray("shapes")?.optString(0)?.let { return it }
		}
		return null
	}

	/** "X 0 2 2 1 0" de 6ª a 1ª cuerda; "P5" = traste 5 con cejilla. */
	private fun parseFrets(s: String): List<Int>? {
		val f = s.trim().split(Regex("\\s+")).map { tok -> if (tok.equals("X", true)) -1 else tok.trimStart('P', 'p').toIntOrNull() }
		return if (f.size == 6 && f.all { it != null }) f.map { it!! } else null
	}

	/** <b>Am</b> → [ch]Am[/ch]; las tablaturas → [tab]…[/tab]; el resto de etiquetas fuera. */
	private fun toUgMarkup(pre: String): String {
		var s = pre
			.replace(Regex("""<span class="tablatura">(.*?)</span>\s*</span>""", RegexOption.DOT_MATCHES_ALL)) { "[tab]${it.groupValues[1]}[/tab]" }
			.replace(Regex("""<span class="tab">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)) { "[tab]${it.groupValues[1]}[/tab]" }
		s = s.replace(Regex("""<b\b[^>]*>(.*?)</b>""")) { "[ch]${it.groupValues[1]}[/ch]" }
		s = s.replace(Regex("""<[^>]+>"""), "")
		return Html.unescape(s).trim('\n')
	}

	/** Array JSON que empieza en [start], recortado por corchetes equilibrados (respetando cadenas). */
	private fun balancedArray(text: String, start: Int): JSONArray? {
		if (start < 0) return null
		var depth = 0
		var inStr = false
		var i = start
		while (i < text.length) {
			val ch = text[i]
			when {
				inStr && ch == '\\' -> i++
				ch == '"' -> inStr = !inStr
				!inStr && ch == '[' -> depth++
				!inStr && ch == ']' -> if (--depth == 0) return runCatching { JSONArray(text.substring(start, i + 1)) }.getOrNull()
			}
			i++
		}
		return null
	}
}
