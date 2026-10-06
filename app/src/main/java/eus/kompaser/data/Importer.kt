package eus.kompaser.data

import android.content.Context
import eus.kompaser.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Elige el proveedor según la URL. Todos devuelven la canción en el mismo formato. */
class Importer(context: Context) {
	private val fetcher = WebFetcher(context)
	private val urlRe = Regex("""https?://[^\s]*(ultimate-guitar\.com|cifraclub\.[a-z.]+)[^\s]*""")

	fun extractUrl(text: String?): String? = text?.let { urlRe.find(it)?.value }

	suspend fun import(text: String): Song {
		val url = extractUrl(text) ?: text.trim()
		return when {
			UgImporter.accepts(url) -> fetcher.html(url, UgImporter::looksValid).let { withContext(Dispatchers.Default) { UgImporter.parse(it, url) } }
			CifraClubImporter.accepts(url) -> {
				val song = fetcher.html(url, CifraClubImporter::looksValid).let { withContext(Dispatchers.Default) { CifraClubImporter.parse(it, url) } }
				if (song.youtubeId != null) song
				else song.copy(youtubeId = withContext(Dispatchers.IO) { CifraClubImporter.findVideo(song.artist, song.title) })
			}
			else -> error("Solo se admiten enlaces de Ultimate Guitar y Cifra Club")
		}
	}
}
