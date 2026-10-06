package eus.kompaser.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/** Miniatura del vídeo de YouTube; se descarga una vez y se guarda en la caché de la app. */
@Composable
fun rememberThumbnail(videoId: String?): ImageBitmap? {
	val context = LocalContext.current
	val thumb by produceState<ImageBitmap?>(null, videoId) {
		if (videoId == null) return@produceState
		value = withContext(Dispatchers.IO) {
			runCatching {
				val f = File(context.cacheDir, "thumbs/$videoId.jpg")
				if (!f.exists()) {
					f.parentFile?.mkdirs()
					val tmp = File(f.path + ".part")
					URL("https://img.youtube.com/vi/$videoId/hqdefault.jpg").openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
					tmp.renameTo(f)
				}
				BitmapFactory.decodeFile(f.path)?.asImageBitmap()
			}.getOrNull()
		}
	}
	return thumb
}
