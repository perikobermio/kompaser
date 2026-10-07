package eus.kompaser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Posición del vídeo. El reproductor solo informa unas pocas veces por segundo, así que
 * entre avisos se interpola con el reloj para que el cambio de acorde sea fluido.
 */
class VideoSync {
	var player: YouTubePlayer? = null
	var playing by mutableStateOf(false)
		private set
	private var sec = 0.0
	private var at = 0L

	fun seconds(now: Long): Double = if (playing) sec + (now - at) / 1e9 else sec

	fun update(s: Float, force: Boolean = false) {
		val now = System.nanoTime()
		val predicted = seconds(now)
		// Corrige el reloj poco a poco: ignorar el error lo acumula; copiar cada aviso lo retrasa.
		sec = if (!force && playing) predicted + (s.toDouble() - predicted).coerceIn(-0.05, 0.05) else s.toDouble()
		at = now
	}

	fun state(isPlaying: Boolean) {
		sec = seconds(System.nanoTime())
		at = System.nanoTime()
		playing = isPlaying
	}
}

@Composable
fun YouTubeBox(videoId: String, sync: VideoSync, modifier: Modifier = Modifier) {
	AndroidView(
		modifier = modifier,
		factory = { ctx ->
			YouTubePlayerView(ctx).apply {
				enableAutomaticInitialization = false
				initialize(
					object : AbstractYouTubePlayerListener() {
						override fun onReady(youTubePlayer: YouTubePlayer) {
							sync.player = youTubePlayer
							youTubePlayer.cueVideo(videoId, 0f)
						}

						override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) = sync.update(second)

						override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) =
							sync.state(state == PlayerConstants.PlayerState.PLAYING)
					},
					IFramePlayerOptions.Builder(ctx).controls(1).rel(0).build(),
				)
			}
		},
		onRelease = {
			sync.player = null
			it.release()
		},
	)
}

private val ytRe = Regex("""(?:v=|youtu\.be/|shorts/|embed/|live/)([A-Za-z0-9_-]{11})""")

/** Acepta una URL de YouTube o directamente el identificador del vídeo. */
fun youtubeId(text: String): String? {
	val t = text.trim()
	if (t.isEmpty()) return null
	ytRe.find(t)?.let { return it.groupValues[1] }
	return t.takeIf { Regex("[A-Za-z0-9_-]{11}").matches(it) }
}
