package eus.kompaser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.ViewTimeline
import eus.kompaser.model.ChordEvent
import kotlin.math.abs
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eus.kompaser.data.ChordLibrary
import eus.kompaser.data.SongStore
import eus.kompaser.model.Song
import eus.kompaser.model.SongLine
import kotlinx.coroutines.isActive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private class Clock {
	var beat = 0.0
	var nanos = 0L
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
	song: Song, store: SongStore, onBack: () -> Unit, onEdit: () -> Unit, onTap: () -> Unit, onSave: (Song) -> Unit,
) {
	val events = song.events
	val starts = song.starts
	val total = starts.last()
	val bpb = song.beatsPerBar

	val bpm = song.bpm
	var playing by remember { mutableStateOf(false) }
	var beat by remember { mutableDoubleStateOf(0.0) }
	var metronomeOn by remember { mutableStateOf(store.metronome) }
	var videoOn by remember { mutableStateOf(song.youtubeId != null) }
	val clock = remember { Clock() }
	val video = remember { VideoSync() }
	val metronome = remember { Metronome() }
	var videoClockStarted by remember { mutableStateOf(false) }
	var notice by remember { mutableStateOf<String?>(null) }
	var timelineMode by remember { mutableStateOf(store.timelineMode) }
	var selected by remember { mutableStateOf<Int?>(null) }
	var step by remember { mutableFloatStateOf(1f) }
	var picking by remember { mutableStateOf<Int?>(null) }
	LaunchedEffect(notice) {
		if (notice != null) {
			delay(1500)
			notice = null
		}
	}

	val view = LocalView.current
	DisposableEffect(Unit) {
		view.keepScreenOn = true
		onDispose {
			view.keepScreenOn = false
			metronome.release()
		}
	}

	fun beatNow(now: Long): Double = if (playing) clock.beat + (now - clock.nanos) / 60e9 * bpm else beat

	fun play() {
		if (videoOn) {
			video.player?.play()
			return
		}
		if (beat >= total) beat = 0.0
		clock.beat = if (beat <= 0.0) -bpb.toDouble() else beat // un compás de entrada
		clock.nanos = System.nanoTime()
		beat = clock.beat
		playing = true
		if (metronomeOn) metronome.click(true)
	}

	fun pause() {
		beat = beatNow(System.nanoTime())
		playing = false
		if (videoOn) {
			video.player?.pause()
			return
		}
	}

	fun seek(i: Int) {
		if (events.isEmpty()) return
		val index = i.coerceIn(0, events.size - 1)
		val b = starts[index]
		if (videoOn) {
			// El reloj del reproductor es el BPM: los tiempos individuales marcados no deben desplazar el vídeo.
			val sec = (song.videoOffsetMs / 1000.0 + b * 60.0 / bpm).toFloat().coerceAtLeast(0f)
			video.player?.seekTo(sec)
			video.update(sec, force = true)
			videoClockStarted = true
		}
		clock.beat = b
		clock.nanos = System.nanoTime()
		beat = b
	}

	// El vídeo controla play/pause, pero el metrónomo y los cambios de acorde avanzan con el reloj BPM.
	LaunchedEffect(videoOn, video.playing) {
		if (!videoOn) return@LaunchedEffect
		val now = System.nanoTime()
		if (video.playing) {
			if (!videoClockStarted) {
				val firstChord = song.videoOffsetMs / 1000.0
				beat = (video.seconds(now) - firstChord) / 60.0 * bpm
				videoClockStarted = true
			}
			if (!playing) {
				clock.beat = beat
				clock.nanos = now
				playing = true
				if (metronomeOn) metronome.click(Math.floorMod(floor(beat).toInt(), bpb) == 0)
			}
		} else if (playing) {
			beat = beatNow(now)
			playing = false
		}
	}

	/** Doble toque en un acorde: empieza un tiempo antes; triple: un tiempo después. Se guarda al momento. */
	fun nudge(i: Int, d: Float) {
		val e = events.getOrNull(i) ?: return
		val moved = Timeline.moveStart(events, i, d, 60.0 / bpm)
		val name = if (e.isRest) "Pausa" else e.chord
		if (moved === events) {
			notice = "$name: no se puede mover más"
			return
		}
		val offsetMs = if (i == 0) ((moved[0].t ?: (song.videoOffsetMs / 1000.0 + d * 60.0 / bpm)) * 1000).toLong() else song.videoOffsetMs
		onSave(song.copy(events = moved, videoOffsetMs = offsetMs, updatedAt = System.currentTimeMillis()))
		val amount = if (abs(d) == 1f) "1 tiempo" else if (abs(d) == 0.5f) "½ tiempo" else "${abs(d)} tiempos"
		notice = "$name · $amount ${if (d < 0) "antes" else "después"}"
	}

	/** Cambios hechos desde la línea de tiempo: se guardan al momento. */
	fun saveEvents(evs: List<ChordEvent>) {
		if (evs === events) return
		onSave(song.copy(events = evs, updatedAt = System.currentTimeMillis()))
	}

	val running = if (videoOn) video.playing else playing
	LaunchedEffect(playing) {
		if (!playing) return@LaunchedEffect
		var last = floor(beatNow(System.nanoTime())).toInt()
		while (isActive) withFrameNanos { now ->
			val b = beatNow(now)
			val bi = floor(b).toInt()
			if (bi != last) {
				if (metronomeOn && b < total) metronome.click(Math.floorMod(bi, bpb) == 0)
				last = bi
			}
			if (b >= total) {
				playing = false
				beat = total
				if (videoOn) video.player?.pause()
			} else beat = b
		}
	}

	val idx = currentIndex(starts, events.size, beat)
	val shapeOf = { name: String -> song.shapes[name] ?: ChordLibrary.shape(name) }

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Column {
						Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
						Text(
							maxLines = 1, overflow = TextOverflow.Ellipsis,
							text = buildString {
								append(song.artist)
								if (song.capo > 0) append("  ·  Cejilla ${song.capo}")
							},
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				},
				navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
				actions = {
					if (song.youtubeId != null) IconButton(onClick = {
						pause()
						playing = false
						videoOn = !videoOn
					}) {
						Icon(
							Icons.Filled.SmartDisplay, "Vídeo",
							tint = if (videoOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					IconButton(onClick = {
						metronomeOn = !metronomeOn
						store.metronome = metronomeOn
					}) { Icon(if (metronomeOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "Metrónomo") }
					IconButton(onClick = {
						timelineMode = !timelineMode
						store.timelineMode = timelineMode
						selected = null
					}) {
						Icon(
							Icons.Filled.ViewTimeline, "Línea de tiempo",
							tint = if (timelineMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
					if (song.youtubeId != null) IconButton(onClick = {
						pause()
						onTap()
					}) { Icon(Icons.Filled.TouchApp, "Marcar tiempos") }
					IconButton(onClick = {
						pause()
						onEdit()
					}) { Icon(Icons.Filled.Edit, "Editar") }
				},
			)
		},
	) { pad ->
		if (events.isEmpty()) {
			Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
				Text("Esta canción no tiene acordes")
			}
			return@Scaffold
		}
		val ev = events[idx]
		val inChord = (beat - starts[idx]).coerceAtLeast(0.0)
		val countIn = beat < 0 && beat >= -bpb

		// En grande, el acorde que suena (o el primero, durante la entrada); a la derecha, los dos siguientes.
		val started = beat >= starts[0]
		val left = if (started) ev.beats - inChord else starts[0] - beat
		val chords: @Composable (Modifier) -> Unit = { m ->
			Row(m, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				ChordCard(
					ev.chord, shapeOf(ev.chord), big = true,
					Modifier.weight(1.55f).fillMaxSize().then(rememberMultiTap({ idx }) { c, i -> if (c == 2) nudge(i, -1f) else if (c >= 3) nudge(i, 1f) }),
					caption = if (started) "AHORA" else "EMPIEZA",
					// Aviso de cambio: en el último compás aparece una barra que se vacía hasta el cambio.
					// Solo en acordes de más de un tiempo (en los cortos sería un parpadeo).
					warn = if (started && ev.beats <= 1f) 0f else (1f - (left / bpb).toFloat()).coerceIn(0f, 1f),
					restBars = if (ev.isRest) ceil((ev.beats - inChord) / bpb - 1e-6).toInt().coerceAtLeast(1) else null,
				)
				Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					listOf("SIGUIENTE", "LUEGO").forEachIndexed { k, label ->
						val i = idx + k + 1
						val n = events.getOrNull(i)
						if (n != null) ChordCard(
							n.chord, shapeOf(n.chord), big = false,
							Modifier.weight(1f).fillMaxWidth().then(
								rememberMultiTap({ i }) { c, j -> if (c == 2) nudge(j, -1f) else if (c >= 3) nudge(j, 1f) },
							),
							caption = label, variant = k,
							restBars = if (n.isRest) ceil(n.beats / bpb).toInt().coerceAtLeast(1) else null,
						)
						else Spacer(Modifier.weight(1f))
					}
				}
			}
		}
		val beats: @Composable () -> Unit = {
			// En una pausa larga cada segmento es un compás (así se ve cuánto falta para volver a entrar).
			val byBar = ev.isRest && ev.beats > bpb
			BeatBar(
				count = when {
					countIn -> bpb
					byBar -> ceil(ev.beats / bpb).toInt().coerceIn(1, 16)
					else -> ceil(ev.beats).toInt().coerceIn(1, 16)
				},
				active = when {
					countIn -> floor(beat).toInt() + bpb
					byBar -> floor(inChord / bpb).toInt()
					else -> floor(inChord).toInt()
				},
				progress = if (countIn) 0f else (inChord / ev.beats).toFloat().coerceIn(0f, 1f),
			)
		}
		// Con el vídeo a la vista caben 3 líneas de letra; sin él, 4.
		val lyrics: @Composable () -> Unit = { Lyrics(song.lines, ev.line, ev.pos, count = if (videoOn && song.youtubeId != null) 3 else 4) }
		val tuning: @Composable () -> Unit = {
			Text(
				tuningNotes(song.tuning),
				style = MaterialTheme.typography.labelMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp),
			)
		}
		val timeline: @Composable (Modifier) -> Unit = { m ->
			TimelinePlayer(
				song, beat, if (started) idx else null, selected, m,
				onSelect = { selected = it },
				onSeek = ::seek,
				onMove = { i, d -> nudge(i, d) },
				onInsert = { i, at, chord ->
					saveEvents(Timeline.split(events, i, at, chord, 60.0 / bpm))
					selected = i + 1
					if (chord == UNKNOWN_CHORD) picking = i + 1
				},
			)
		}
		val toolbar: @Composable () -> Unit = {
			val sel = selected
			val e = sel?.let { events.getOrNull(it) }
			if (timelineMode && sel != null && e != null) TimelineToolbar(
				e, step, onStep = { step = if (step == 1f) 0.5f else 1f }, onMove = { d -> nudge(sel, d) },
				onPick = { picking = sel },
				onRemove = if (events.size > 1) ({ saveEvents(Timeline.remove(events, sel)); selected = null }) else null,
				onClose = { selected = null },
			)
		}
		val controls: @Composable () -> Unit = {
			Controls(
				running = running, onPlay = { if (running) pause() else play() },
				onRestart = {
					pause()
					seek(0)
					if (!videoOn) beat = 0.0
				},
				onPrev = { seek(idx - 1) }, onNext = { seek(idx + 1) },
			)
		}
		val videoBox: @Composable () -> Unit = {
			if (videoOn && song.youtubeId != null) {
				YouTubeBox(song.youtubeId, video, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
				Spacer(Modifier.height(14.dp))
			}
		}

		Box(Modifier.fillMaxSize()) {
		BoxWithConstraints(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp)) {
			if (maxWidth > maxHeight) {
				Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Column(Modifier.weight(1.2f).fillMaxSize().padding(bottom = 8.dp)) {
						tuning()
						if (timelineMode) timeline(Modifier.weight(1f)) else {
							chords(Modifier.weight(1f))
							Spacer(Modifier.height(6.dp))
							beats()
						}
					}
					Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
						videoBox()
						if (!timelineMode) lyrics()
						toolbar()
						controls()
					}
				}
			} else {
				Column(Modifier.fillMaxSize()) {
					videoBox()
					tuning()
					if (timelineMode) {
						timeline(Modifier.weight(1f))
						toolbar()
					} else {
						chords(Modifier.weight(1f))
						Spacer(Modifier.height(6.dp))
						beats()
						Spacer(Modifier.height(6.dp))
						lyrics()
					}
					controls()
				}
			}
		}
		picking?.let { i ->
			ChordPicker(
				events.filter { !it.isRest && it.chord != UNKNOWN_CHORD }.map { it.chord }.distinct(), events.getOrNull(i)?.chord,
				onDismiss = { picking = null },
				onPick = { c ->
					saveEvents(events.toMutableList().also { l -> l.getOrNull(i)?.let { l[i] = it.copy(chord = c) } })
					picking = null
				},
			)
		}
		notice?.let {
			Text(
				it, color = Color.White, fontWeight = FontWeight.Bold,
				modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(Fun.Ink.copy(alpha = 0.85f))
					.padding(horizontal = 18.dp, vertical = 10.dp),
			)
		}
		}
	}
}

/**
 * Cuenta toques seguidos (hasta ~0,35 s entre uno y otro) y avisa con el total y el acorde que había
 * en el primer toque: aunque la canción avance mientras tanto, se mueve el acorde que se tocó.
 */
@Composable
private fun rememberMultiTap(target: () -> Int, onCount: (count: Int, index: Int) -> Unit): Modifier {
	val currentTarget by rememberUpdatedState(target)
	val callback by rememberUpdatedState(onCount)
	return Modifier.pointerInput(Unit) {
		coroutineScope {
			var count = 0
			var index = 0
			var job: Job? = null
			detectTapGestures(onTap = {
				if (count == 0) index = currentTarget()
				count++
				job?.cancel()
				job = launch {
					delay(350)
					val c = count
					count = 0
					callback(c, index)
				}
			})
		}
	}
}

private fun currentIndex(starts: DoubleArray, n: Int, beat: Double): Int {
	var lo = 0
	var hi = n - 1
	while (lo < hi) {
		val mid = (lo + hi + 1) / 2
		if (starts[mid] <= beat) lo = mid else hi = mid - 1
	}
	return lo.coerceAtLeast(0)
}

@Composable
private fun ChordCard(
	name: String, shape: eus.kompaser.model.ChordShape?, big: Boolean, modifier: Modifier, badge: String? = null, variant: Int = 0,
	warn: Float = 0f,
	restBars: Int? = null, caption: String? = null,
) {
	if (restBars != null) return RestCard(restBars, big, modifier, caption, warn)
	val bg = if (big) Fun.current else Fun.next[variant % Fun.next.size]
	val fg = if (big) Color.White else Fun.nextInk[variant % Fun.nextInk.size]
	Card(
		modifier,
		shape = MaterialTheme.shapes.large,
		colors = CardDefaults.cardColors(containerColor = Color.Transparent),
		elevation = CardDefaults.cardElevation(defaultElevation = if (big) 6.dp else 2.dp),
	) {
		BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
			val wide = maxWidth > maxHeight * 1.15f
			val cardWidth = maxWidth
			val nameSize = if (big) (if (wide) 52.sp else 60.sp) else (if (wide) 24.sp else 28.sp)
			val diagram: @Composable (Modifier) -> Unit = { m ->
				if (shape != null) ChordDiagram(
					shape, m, color = fg, fitHeight = wide,
					dotColor = if (big) Color.White else fg, dotText = if (big) Fun.Coral else Color.White,
				)
			}
			if (wide) {
				// Tarjeta apaisada (p. ej. con el vídeo abierto): nombre a la izquierda y diagrama a la derecha.
				Row(
					// Con etiqueta, se baja el contenido para que no la pise el diagrama.
					Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = if (caption != null) 24.dp else 8.dp, bottom = 8.dp),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceEvenly,
				) {
					ChordName(name, nameSize, fg, Modifier.weight(1f).padding(end = 8.dp))
					diagram(Modifier.fillMaxHeight().widthIn(max = cardWidth * 0.5f))
				}
			} else {
				Column(
					// En la grande se reserva abajo el hueco de la barra del último compás.
					Modifier.fillMaxSize().padding(start = if (big) 12.dp else 8.dp, end = if (big) 12.dp else 8.dp,
						top = if (caption != null) 26.dp else if (big) 12.dp else 8.dp, bottom = if (big) 30.dp else 8.dp),
					horizontalAlignment = Alignment.CenterHorizontally,
				) {
					ChordName(name, nameSize, fg, Modifier.fillMaxWidth())
					diagram(Modifier.weight(1f).padding(top = 4.dp))
				}
			}
			caption?.let { Caption(it, fg, Modifier.align(Alignment.TopStart)) }
			if (big) EndBar(warn, Modifier.align(Alignment.BottomCenter))
			if (badge != null) {
				Box(
					Modifier.align(Alignment.TopEnd).padding(10.dp).size(48.dp).clip(CircleShape).background(Fun.Sun),
					contentAlignment = Alignment.Center,
				) { Text(badge, color = Fun.Ink, fontSize = 26.sp, fontWeight = FontWeight.Black) }
			}
		}
	}
}

/** Nombre del acorde: se reduce lo justo para caber en una línea («D4/A», «F#m7b5»…). */
@Composable
private fun ChordName(name: String, size: TextUnit, color: Color, modifier: Modifier) {
	BasicText(
		name, modifier,
		style = TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Black, textAlign = TextAlign.Center),
		maxLines = 1, softWrap = false,
		autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = size, stepSize = 2.sp),
	)
}

/** Tarjeta de pausa: sin acorde, con los compases que faltan para volver a entrar. */
@Composable
private fun RestCard(bars: Int, big: Boolean, modifier: Modifier, caption: String?, warn: Float = 0f) {
	Card(
		modifier, shape = MaterialTheme.shapes.large,
		colors = CardDefaults.cardColors(containerColor = Color.Transparent),
		elevation = CardDefaults.cardElevation(defaultElevation = if (big) 6.dp else 2.dp),
	) {
		val bg = if (big) Brush.linearGradient(listOf(Fun.Purple, Fun.Pink)) else Brush.linearGradient(listOf(Color(0xFFECE8FF), Color(0xFFDCD5FF)))
		val fg = if (big) Color.White else Fun.Purple
		Column(
			Modifier.fillMaxSize().background(bg).padding(12.dp),
			horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
		) {
			Text("Pausa", fontSize = if (big) 44.sp else 22.sp, fontWeight = FontWeight.Black, color = fg)
			Text("$bars", fontSize = if (big) 96.sp else 30.sp, fontWeight = FontWeight.Black, color = fg)
			Text(if (bars == 1) "compás" else "compases", fontSize = if (big) 20.sp else 13.sp, color = fg)
		}
		Box(Modifier.fillMaxSize()) {
			caption?.let { Caption(it, fg, Modifier.align(Alignment.TopStart)) }
			if (big) EndBar(warn, Modifier.align(Alignment.BottomCenter))
		}
	}
}

/** Barra del último compás: aparece al empezar el compás final ([warn] 0 → 1) y se vacía hasta el cambio. */
@Composable
private fun EndBar(warn: Float, modifier: Modifier) {
	if (warn <= 0f) return
	Box(
		modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).height(8.dp)
			.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.28f)),
	) {
		Box(Modifier.fillMaxHeight().fillMaxWidth(1f - warn).clip(RoundedCornerShape(4.dp)).background(Fun.Sun))
	}
}

@Composable
private fun Caption(text: String, color: Color, modifier: Modifier) {
	Text(
		text, modifier.padding(start = 12.dp, top = 8.dp), color = color.copy(alpha = 0.75f),
		fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
	)
}

@Composable
private fun BeatBar(count: Int, active: Int, progress: Float) {
	val c = MaterialTheme.colorScheme
	Column {
		Row(Modifier.fillMaxWidth().height(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
			repeat(count) { i ->
				Box(
					Modifier.weight(1f).height(if (i == active) 12.dp else 8.dp).clip(RoundedCornerShape(6.dp))
						.background(Fun.beats[i % Fun.beats.size].let { if (i == active) it else if (i < active) it.copy(alpha = 0.4f) else c.surfaceVariant }),
				)
			}
		}
	}
}

@Composable
private fun Lyrics(lines: List<SongLine>, current: Int, pos: Int, count: Int) {
	val c = MaterialTheme.colorScheme
	val section = (current downTo 0).firstNotNullOfOrNull { lines.getOrNull(it)?.section }
	Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
		Text(section.orEmpty(), style = MaterialTheme.typography.labelLarge, color = Fun.Purple, fontWeight = FontWeight.Bold)
		// La línea actual y las siguientes; las tablaturas sueltas (E|--3--…) no cuentan ni se muestran.
		val shown = (current until lines.size).filter { it == current || lines[it].chords.isNotEmpty() || !isTabLine(lines[it].lyric) }.take(count)
		for ((k, li) in shown.withIndex()) {
			val l = lines[li]
			val color = if (k == 0) c.onSurface else c.onSurfaceVariant.copy(alpha = 0.6f)
			Column(Modifier.horizontalScroll(rememberScrollState())) {
				if (l.chords.isNotEmpty()) Text(
					chordLine(l, if (k == 0) pos else -1, c.primary), fontFamily = FontFamily.Monospace,
					fontSize = 16.sp, color = color, fontWeight = FontWeight.Bold, softWrap = false,
				)
				if (l.lyric.isNotEmpty()) Text(l.lyric, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = color, softWrap = false)
			}
			Spacer(Modifier.height(2.dp))
		}
	}
}

private fun chordLine(l: SongLine, hi: Int, hiColor: Color): AnnotatedString = buildAnnotatedString {
	var col = 0
	l.chords.forEachIndexed { j, ch ->
		val pad = (ch.col - col).coerceAtLeast(if (j > 0) 1 else 0)
		append(" ".repeat(pad))
		if (j == hi) withStyle(SpanStyle(color = Color.White, background = hiColor)) { append(ch.name) } else append(ch.name)
		col += pad + ch.name.length
	}
}

@Composable
private fun Controls(running: Boolean, onPlay: () -> Unit, onRestart: () -> Unit, onPrev: () -> Unit, onNext: () -> Unit) {
	Row(
		Modifier.fillMaxWidth().padding(vertical = 4.dp),
		horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
	) {
		IconButton(onClick = onRestart, Modifier.size(48.dp)) { Icon(Icons.Filled.Replay, "Desde el principio") }
		IconButton(onClick = onPrev, Modifier.size(48.dp)) { Icon(Icons.Filled.SkipPrevious, "Anterior", Modifier.size(30.dp)) }
		FilledIconButton(
			onClick = onPlay, Modifier.size(64.dp).clip(CircleShape).background(if (running) Fun.current else Brush.linearGradient(listOf(Fun.Turquoise, Color(0xFF3DD9C1)))),
			colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Transparent, contentColor = Color.White),
		) {
			Icon(if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pausa", Modifier.size(40.dp))
		}
		IconButton(onClick = onNext, Modifier.size(48.dp)) { Icon(Icons.Filled.SkipNext, "Siguiente", Modifier.size(30.dp)) }
	}
}

private val NOTE_NAMES = mapOf(
	"C" to 0, "C#" to 1, "Db" to 1, "D" to 2, "D#" to 3, "Eb" to 3, "E" to 4, "F" to 5, "F#" to 6, "Gb" to 6,
	"G" to 7, "G#" to 8, "Ab" to 8, "A" to 9, "A#" to 10, "Bb" to 10, "B" to 11,
)

/** Semitonos respecto a la afinación estándar según la 1ª cuerda (Eb → -1). Null si no se reconoce. */
fun tuningShift(tuning: String): Int? {
	val top = NOTE_NAMES[tuning.trim().split(Regex("\\s+")).lastOrNull()] ?: return null
	return ((top - 4 + 18) % 12) - 6
}

/** Lo que hay que bajar la guitarra, en texto («½ tono»…), para un desplazamiento negativo. */
fun semitonesText(n: Int) = when (n) {
	1 -> "½ tono"
	2 -> "1 tono"
	3 -> "1 tono y ½"
	else -> "$n semitonos"
}

/**
 * Cejilla con la que, con la guitarra en afinación estándar, se suena igual que el disco (afinación +
 * cejilla originales). Null si no hace falta cambiar nada; negativo si sería más grave (no hay cejilla posible).
 */
fun standardCapo(tuning: String, capo: Int): Int? {
	val shift = tuningShift(tuning) ?: return null
	if (shift == 0) return null
	return shift + capo
}

/** Notas de cada cuerda (6ª a 1ª) con nombres en español: «Mi♭ La♭ Re♭ Sol♭ Si♭ Mi♭». */
fun tuningNotes(tuning: String): String {
	val names = mapOf('C' to "Do", 'D' to "Re", 'E' to "Mi", 'F' to "Fa", 'G' to "Sol", 'A' to "La", 'B' to "Si")
	return tuning.trim().split(Regex("\\s+")).joinToString("  ") { n ->
		val base = names[n.firstOrNull()?.uppercaseChar()] ?: return@joinToString n
		base + when (n.drop(1)) { "#" -> "♯"; "b" -> "♭"; else -> "" }
	}
}

/** Texto corto de la afinación: «estándar», «½ tono abajo (Eb)», «Drop D»… */
fun tuningLabel(tuning: String): String {
	val notes = tuning.trim().split(Regex("\\s+"))
	if (notes.joinToString(" ") == "E A D G B E") return "estándar"
	if (notes.size == 6 && notes.drop(1).joinToString(" ") == "A D G B E") return "Drop ${notes[0]}"
	val d = tuningShift(tuning) ?: return tuning
	val what = when (d) {
		-1 -> "½ tono abajo"
		-2 -> "1 tono abajo"
		-3 -> "1 tono y ½ abajo"
		1 -> "½ tono arriba"
		2 -> "1 tono arriba"
		else -> return tuning
	}
	return "$what (${notes.joinToString(" ")})"
}

/**
 * La línea de tiempo del editor dentro del reproductor: el bloque que suena se resalta, un cabezal
 * avanza con la canción y la lista se desplaza sola hasta la línea que suena.
 */
@Composable
private fun TimelinePlayer(
	song: Song, beat: Double, nowIndex: Int?, selected: Int?, modifier: Modifier,
	onSelect: (Int) -> Unit, onSeek: (Int) -> Unit, onMove: (Int, Float) -> Unit, onInsert: (Int, Float, String) -> Unit,
) {
	val events = song.events
	val starts = song.starts
	val rows = remember(events, song.lines) { timelineDisplayRows(song.lines, events) }
	val listState = rememberLazyListState()
	val currentRow = nowIndex?.let { i -> rows.indexOfFirst { row -> row.blocks.any { it.index == i } } } ?: -1
	LaunchedEffect(currentRow) {
		if (currentRow >= 0) listState.animateScrollToItem((currentRow - 1).coerceAtLeast(0))
	}
	LazyColumn(modifier.fillMaxWidth(), state = listState) {
		items(rows.size) { r ->
			val row = rows[r]
			val li = row.lineIndex
			val blocks = row.blocks
			val line = li?.let { song.lines[it] } ?: SongLine(null, "", emptyList())
			val first = blocks.firstOrNull()?.index
			val rowStart = first?.let { starts[it] } ?: 0.0
			val rowEnd = blocks.lastOrNull()?.let { starts[it.index + 1] } ?: rowStart
			val inRow = beat >= rowStart && beat < rowEnd
			val t = first?.takeIf { song.youtubeId != null }?.let { song.videoOffsetMs / 1000.0 + starts[it] * 60.0 / song.bpm }
			TimelineRow(
				line, blocks, rowStart = rowStart, beatsPerBar = song.beatsPerBar, selected = selected,
				timeLabel = t?.let { "%d:%02d".format((it / 60).toInt(), (it % 60).toInt()) },
				onSelect = onSelect, onMove = onMove, onInsert = onInsert,
				onBlockTap = onSeek, longPressEnabled = false,
				nowIndex = if (inRow) nowIndex else null, playhead = if (inRow) beat else null,
				// Todo lo anterior al acorde que suena queda iluminado.
				playedBefore = nowIndex,
			)
		}
	}
}
