package eus.kompaser.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Clic de metrónomo con dos tonos precalculados (acentuado en el primer tiempo del compás). */
class Metronome {
	private val rate = 44_100
	private val accent = tone(1760.0, 45)
	private val normal = tone(1175.0, 35)

	fun click(strong: Boolean) {
		val t = if (strong) accent else normal
		runCatching {
			t.stop()
			t.reloadStaticData()
			t.play()
		}
	}

	fun release() {
		accent.release()
		normal.release()
	}

	private fun tone(freq: Double, ms: Int): AudioTrack {
		val n = rate * ms / 1000
		val pcm = ShortArray(n) { i ->
			(sin(2 * PI * freq * i / rate) * exp(-i / (n / 5.0)) * 0.9 * Short.MAX_VALUE).toInt().toShort()
		}
		return AudioTrack.Builder()
			.setAudioAttributes(
				AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_MEDIA)
					.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
					.build(),
			)
			.setAudioFormat(
				AudioFormat.Builder()
					.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
					.setSampleRate(rate)
					.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
					.build(),
			)
			.setTransferMode(AudioTrack.MODE_STATIC)
			.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
			.setBufferSizeInBytes(n * 2)
			.build()
			.apply { write(pcm, 0, n) }
	}
}
