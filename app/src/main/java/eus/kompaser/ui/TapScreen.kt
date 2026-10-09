package eus.kompaser.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eus.kompaser.model.ChordEvent
import eus.kompaser.model.ChordSheet
import eus.kompaser.model.Song
import eus.kompaser.model.SongLine
import kotlin.math.ceil
import kotlinx.coroutines.launch

private fun compactEmptyRows(events: List<ChordEvent>, beatsPerBar: Int, offset: Double, secondsPerBeat: Double): List<ChordEvent> {
	if (events.isEmpty()) return events
	val bar = beatsPerBar.coerceAtLeast(1).toFloat()
	var compacted = events.sortedBy { it.startBeat ?: 0f }
	var measure = 0
	while (true) {
		val rowStart = measure * bar
		val rowEnd = rowStart + bar
		val hasEvent = compacted.any { event ->
			val start = event.startBeat ?: 0f
			start < rowEnd - 1e-3f && start + event.beats > rowStart + 1e-3f
		}
		if (!hasEvent) {
			if (compacted.none { (it.startBeat ?: 0f) >= rowEnd - 1e-3f }) break
			compacted = compacted.map { event ->
				val start = event.startBeat ?: 0f
				if (start >= rowEnd - 1e-3f) {
					val shifted = (start - bar).coerceAtLeast(0f)
					event.copy(
						line = (shifted / bar).toInt(),
						startBeat = shifted,
						t = offset + shifted * secondsPerBeat,
					)
				} else event
			}
		} else measure++
	}
	return compacted
}

private fun fmtTimelineSecond(seconds: Double) = "%.3f".format(seconds).replace(',', '.').trimEnd('0').trimEnd('.')

private fun fmtTimelineTime(seconds: Double): String {
	val wholeSeconds = seconds.coerceAtLeast(0.0).toInt()
	return "%d:%02d".format(wholeSeconds / 60, wholeSeconds % 60)
}

@Composable
private fun CompactDecimalField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
	val shape = RoundedCornerShape(8.dp)
	Column(modifier) {
		Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
		BasicTextField(
			value = value,
			onValueChange = onValueChange,
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
			textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
			modifier = Modifier.fillMaxWidth().height(34.dp).clip(shape)
				.background(MaterialTheme.colorScheme.surface)
				.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
				.padding(horizontal = 10.dp, vertical = 7.dp),
			decorationBox = { innerTextField ->
				Box(contentAlignment = Alignment.CenterStart) {
					if (value.isEmpty()) Text("—", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
					innerTextField()
				}
			},
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TapScreen(song: Song, onBack: () -> Unit, onSave: (Song) -> Unit, onSaveStay: (Song) -> Unit = onSave) {
	val videoId = song.youtubeId
	if (videoId == null) {
		LaunchedEffect(Unit) { onBack() }
		return
	}
	var bpmText by remember(song.id) { mutableStateOf(song.bpm.toString()) }
	var firstPulseText by remember(song.id) { mutableStateOf(fmtTimelineSecond(song.videoOffsetMs / 1000.0)) }
	val bpm = bpmText.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(30f, 260f) ?: song.bpm
	val offset = firstPulseText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: song.videoOffsetMs / 1000.0
	val secondsPerBeat = 60.0 / bpm.coerceAtLeast(1f)
	val sourceEvents = remember(song.id, song.content) { ChordSheet.defaultEvents(song.lines, song.beatsPerBar.toFloat()) }
	val sourceLineStartPositions = remember(song.id, song.content) {
		sourceEvents.groupBy { it.line }.values.mapNotNull { lineEvents ->
			lineEvents.minByOrNull { it.pos }?.let { it.line to it.pos }
		}.toSet()
	}
	var events by remember(song.id) {
		val starts = song.starts
		val loaded = song.events.mapIndexed { index, event ->
			val start = event.startBeat ?: starts[index].toFloat()
			event.copy(
				line = (start / song.beatsPerBar.coerceAtLeast(1)).toInt(),
				startBeat = start,
				t = event.t ?: offset + start * secondsPerBeat,
			)
		}
		mutableStateOf(compactEmptyRows(loaded, song.beatsPerBar, offset, secondsPerBeat))
	}
	var sourceCursor by remember(song.id) {
		mutableIntStateOf(
			sourceEvents.indexOfFirst { source ->
				song.events.none { it.manual && (it.sourceLine ?: it.line) == source.line && it.pos == source.pos }
			}.let { if (it < 0) sourceEvents.size else it },
		)
	}
	var changed by remember(song.id) { mutableStateOf(false) }
	var showFullScore by remember { mutableStateOf(false) }
	var confirmExit by remember { mutableStateOf(false) }
	var jumpToMeasure by remember(song.id) { mutableIntStateOf(-1) }
	var editingEventIndex by remember { mutableIntStateOf(-1) }
	var selectedEventIndex by remember { mutableIntStateOf(-1) }
	var latestCreatedEventIndex by remember { mutableIntStateOf(-1) }
	var creationToken by remember { mutableIntStateOf(0) }
	val timelineListState = rememberLazyListState()
	val snackbarHostState = remember { SnackbarHostState() }
	val coroutineScope = rememberCoroutineScope()
	val video = remember { VideoSync() }
	val view = LocalView.current
	DisposableEffect(Unit) {
		view.keepScreenOn = true
		onDispose { view.keepScreenOn = false }
	}

	fun currentSong() = song.copy(
		bpm = bpm,
		videoOffsetMs = (offset * 1000.0).toLong(),
		events = events.map { event ->
			val start = event.startBeat ?: 0f
			event.copy(t = offset + start * secondsPerBeat)
		},
		updatedAt = System.currentTimeMillis(),
	)
	fun persistTimeline() {
		onSaveStay(currentSong())
		changed = false
	}
	fun saveAndStay() {
		persistTimeline()
		coroutineScope.launch { snackbarHostState.showSnackbar("Timeline guardado") }
	}
	fun positioned(event: ChordEvent, startBeat: Float, beats: Float = event.beats) = event.copy(
		beats = beats,
		line = (startBeat / song.beatsPerBar.coerceAtLeast(1)).toInt(),
		startBeat = startBeat,
		t = offset + startBeat * secondsPerBeat,
	)
	fun isVerseStart(line: Int, pos: Int): Boolean {
		return (line to pos) in sourceLineStartPositions
	}
	fun isVerseStartEvent(event: ChordEvent): Boolean {
		val sourceLine = event.sourceLine?.takeIf { it >= 0 } ?: event.line
		return event.sectionStart || isVerseStart(sourceLine, event.pos)
	}
	fun nextEventStart(event: ChordEvent, previousEnd: Float): Float {
		if (!isVerseStartEvent(event)) return previousEnd
		val bar = song.beatsPerBar.coerceAtLeast(1).toFloat()
		return (kotlin.math.ceil((previousEnd - 1e-3f) / bar).toInt() * bar).coerceAtLeast(previousEnd)
	}
	fun insertAt(event: ChordEvent, insertionIndex: Int, startBeat: Float): Int {
		val inserted = event.copy(
			line = (startBeat / song.beatsPerBar.coerceAtLeast(1)).toInt(),
			startBeat = startBeat,
			t = offset + startBeat * secondsPerBeat,
		)
		val out = events.take(insertionIndex).toMutableList()
		out += inserted
		var previousEnd = startBeat + inserted.beats
		for (following in events.drop(insertionIndex)) {
			val start = nextEventStart(following, previousEnd)
			out += positioned(following, start)
			previousEnd = start + following.beats
		}
		events = compactEmptyRows(out, song.beatsPerBar, offset, secondsPerBeat)
		latestCreatedEventIndex = insertionIndex
		creationToken++
		val createdStart = events.getOrNull(insertionIndex)?.startBeat ?: startBeat
		jumpToMeasure = (createdStart / song.beatsPerBar.coerceAtLeast(1)).toInt()
		changed = true
		view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
		return insertionIndex
	}
	fun insertAfterSelection(event: ChordEvent): Int {
		val selectedIndex = selectedEventIndex.takeIf { it in events.indices }
		val insertionIndex = selectedIndex?.plus(1) ?: events.size
		val startBeat = selectedIndex?.let { (events[it].startBeat ?: 0f) + events[it].beats }
			?: (events.maxOfOrNull { (it.startBeat ?: 0f) + it.beats } ?: 0f)
		return insertAt(event, insertionIndex, startBeat)
	}
	fun deleteEvent(index: Int) {
		val removed = events.getOrNull(index) ?: return
		val out = events.toMutableList()
		val keepCustomVerseAnchor = isVerseStartEvent(removed) && removed.chord != UNKNOWN_CHORD && removed.pos >= 0
		val placeholderBeats = removed.beats.coerceAtMost(1f)
		if (keepCustomVerseAnchor) {
			out[index] = removed.copy(chord = UNKNOWN_CHORD, beats = placeholderBeats, pos = -1, manual = true, sectionStart = true)
		} else {
			out.removeAt(index)
		}
		var previousEnd = if (keepCustomVerseAnchor) {
			val anchor = out[index]
			(anchor.startBeat ?: 0f) + anchor.beats
		} else if (index > 0) {
			val previous = out[index - 1]
			(previous.startBeat ?: 0f) + previous.beats
		} else 0f
		var followingIndex = if (keepCustomVerseAnchor) index + 1 else index
		while (followingIndex < out.size) {
			val following = out[followingIndex]
			val start = nextEventStart(following, previousEnd)
			out[followingIndex] = positioned(following, start)
			previousEnd = start + following.beats
			followingIndex++
		}
		events = compactEmptyRows(out, song.beatsPerBar, offset, secondsPerBeat)
		selectedEventIndex = -1
		if (!keepCustomVerseAnchor && latestCreatedEventIndex > index) latestCreatedEventIndex--
		if (removed.sourceLine != null && removed.sourceLine >= 0 && removed.pos >= 0) {
			val returnedIndex = sourceEvents.indexOfFirst { it.line == removed.sourceLine && it.pos == removed.pos }
			if (returnedIndex >= 0) sourceCursor = minOf(sourceCursor, returnedIndex)
		}
		changed = true
		view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
	}
	fun advanceSourceCursor(fromIndex: Int) {
		sourceCursor = (fromIndex.coerceAtLeast(0) until sourceEvents.size).firstOrNull { sourceIndex ->
			val source = sourceEvents[sourceIndex]
			events.none { it.manual && !it.isRest && (it.sourceLine ?: it.line) == source.line && it.pos == source.pos }
		} ?: sourceEvents.size
	}
	fun addEmptyTempo() {
		val rest = ChordEvent(
			chord = "", beats = 1f, line = 0, pos = -1,
			manual = true, sourceLine = -1,
		)
		insertAfterSelection(rest)
		sourceCursor = (sourceCursor + 1).coerceAtMost(sourceEvents.size)
	}
	fun addNextChord() {
		val source = sourceEvents.getOrNull(sourceCursor) ?: return
		val insertionIndex = selectedEventIndex.takeIf { it in events.indices }?.plus(1) ?: events.size
		var startBeat = selectedEventIndex.takeIf { it in events.indices }?.let { (events[it].startBeat ?: 0f) + events[it].beats }
			?: (events.maxOfOrNull { (it.startBeat ?: 0f) + it.beats } ?: 0f)
		val startsVerse = isVerseStart(source.line, source.pos)
		if (startsVerse) {
			val bar = song.beatsPerBar.coerceAtLeast(1).toFloat()
			val remainder = startBeat % bar
			val gap = if (remainder < 1e-3f || bar - remainder < 1e-3f) 0f else bar - remainder
			startBeat += gap
		}
		insertAt(source.copy(
			beats = 1f,
			manual = true,
			sourceLine = source.line,
			sectionStart = startsVerse,
		), insertionIndex, startBeat)
		advanceSourceCursor(sourceCursor + 1)
	}
	fun autoFillTimeline() {
		if (sourceCursor >= sourceEvents.size) return
		val out = events.toMutableList()
		var previousEnd = out.maxOfOrNull { (it.startBeat ?: 0f) + it.beats } ?: 0f
		var added = false
		for (sourceIndex in sourceCursor until sourceEvents.size) {
			val source = sourceEvents[sourceIndex]
			val alreadyPresent = out.any {
				it.manual && !it.isRest && (it.sourceLine ?: it.line) == source.line && it.pos == source.pos
			}
			if (alreadyPresent) continue
			val startsVerse = isVerseStart(source.line, source.pos)
			var startBeat = previousEnd
			if (startsVerse) {
				val bar = song.beatsPerBar.coerceAtLeast(1).toFloat()
				startBeat = (ceil((startBeat - 1e-3f) / bar).toInt() * bar).coerceAtLeast(startBeat)
			}
			out += positioned(source.copy(
				beats = 1f,
				manual = true,
				sourceLine = source.line,
				sectionStart = startsVerse,
			), startBeat)
			previousEnd = startBeat + 1f
			added = true
		}
		if (!added) {
			sourceCursor = sourceEvents.size
			return
		}
		events = compactEmptyRows(out, song.beatsPerBar, offset, secondsPerBeat)
		sourceCursor = sourceEvents.indexOfFirst { source ->
			events.none { it.manual && !it.isRest && (it.sourceLine ?: it.line) == source.line && it.pos == source.pos }
		}.let { if (it < 0) sourceEvents.size else it }
		selectedEventIndex = -1
		latestCreatedEventIndex = -1
		changed = true
		view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
	}
	fun setChordDuration(index: Int, duration: Float) {
		val current = events.getOrNull(index) ?: return
		val out = events.toMutableList()
		out[index] = current.copy(beats = duration)
		val ordered = out.indices.sortedBy { out[it].startBeat ?: 0f }
		val currentPosition = ordered.indexOf(index)
		var previousEnd = (current.startBeat ?: 0f) + duration
		for (position in currentPosition + 1 until ordered.size) {
			val followingIndex = ordered[position]
			val following = out[followingIndex]
			val start = nextEventStart(following, previousEnd)
			out[followingIndex] = positioned(following, start)
			previousEnd = start + following.beats
		}
		events = compactEmptyRows(out, song.beatsPerBar, offset, secondsPerBeat)
		changed = true
		view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
	}

	val nextChord = sourceEvents.getOrNull(sourceCursor)?.chord ?: "FIN"
	val measureCount = maxOf(
		1,
		ceil((events.maxOfOrNull { (it.startBeat ?: 0f) + it.beats } ?: 0f) / song.beatsPerBar.coerceAtLeast(1)).toInt(),
	)
	LaunchedEffect(jumpToMeasure, measureCount) {
		if (jumpToMeasure in 0 until measureCount) {
			timelineListState.animateScrollToItem(jumpToMeasure)
			jumpToMeasure = -1
		}
	}
	Scaffold(
		snackbarHost = { SnackbarHost(snackbarHostState) },
		topBar = {
			TopAppBar(
				title = {
					Column {
						Text("Timeline")
						Text(song.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
					}
				},
				navigationIcon = {
					IconButton(onClick = { if (changed) confirmExit = true else onBack() }) {
						Icon(Icons.Filled.Close, contentDescription = "Salir")
					}
				},
					actions = {
						IconButton(onClick = { showFullScore = true }) {
							Icon(Icons.Filled.Description, contentDescription = "Ver partitura")
						}
						IconButton(onClick = ::autoFillTimeline, enabled = sourceCursor < sourceEvents.size) {
							Icon(Icons.AutoMirrored.Filled.PlaylistAddCheck, contentDescription = "Autorrellenar timeline")
						}
						IconButton(onClick = ::saveAndStay) {
						Icon(Icons.Filled.Check, contentDescription = "Guardar línea de tiempo")
					}
				IconButton(onClick = {
					events = emptyList()
					sourceCursor = 0
					selectedEventIndex = -1
					latestCreatedEventIndex = -1
					editingEventIndex = -1
					changed = true
					}) {
						Icon(Icons.Filled.RestartAlt, contentDescription = "Reiniciar línea de tiempo")
					}
				},
			)
		},
	) { insets ->
		Column(
			Modifier.fillMaxSize().padding(insets).padding(horizontal = 12.dp, vertical = 8.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			YouTubeBox(videoId, video, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
			Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				CompactDecimalField(
					bpmText, { bpmText = it; changed = true }, "BPM", Modifier.weight(0.75f),
				)
				CompactDecimalField(
					firstPulseText, { firstPulseText = it; changed = true }, "Inicio pulso (s)", Modifier.weight(1.25f),
				)
			}
			LazyColumn(
				Modifier.fillMaxWidth().weight(1f),
				state = timelineListState,
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				items(measureCount) { measure ->
					val rowStart = measure * song.beatsPerBar.toDouble()
					val rowEnd = rowStart + song.beatsPerBar
					val blocks = events.withIndex().filter { (_, event) ->
						val start = event.startBeat ?: 0f
						start < rowEnd && start + event.beats > rowStart
					}
					val lyricIndex = blocks.firstOrNull { (eventIndex, event) ->
						val sourceLine = event.sourceLine?.takeIf { it >= 0 } ?: event.line
						val lyric = song.lines.getOrNull(sourceLine)?.lyric
						val start = event.startBeat ?: 0f
						!event.isRest && lyric?.isNotBlank() == true && start >= rowStart && start < rowEnd &&
							events.take(eventIndex).none { (it.sourceLine?.takeIf { source -> source >= 0 } ?: it.line) == sourceLine && !it.isRest }
					}?.value?.let { it.sourceLine?.takeIf { source -> source >= 0 } ?: it.line }
					val lyricRow = lyricIndex?.let { song.lines.getOrNull(it) } ?: SongLine(null, "", emptyList())
					TimelineRow(
						line = lyricRow,
						blocks = blocks,
						rowStart = rowStart,
						beatsPerBar = song.beatsPerBar,
					selected = selectedEventIndex.takeIf { it in events.indices },
						createdEventIndex = latestCreatedEventIndex.takeIf { it in events.indices },
						creationToken = creationToken,
						timeLabel = fmtTimelineTime(offset + rowStart * secondsPerBeat),
						onSelect = { index -> selectedEventIndex = if (selectedEventIndex == index) -1 else index },
						onMove = { _, _ -> },
						onInsert = { _, _, _ -> },
						editable = false,
						showMarks = true,
						longPressEnabled = true,
						onLongPress = { index ->
							selectedEventIndex = index
							editingEventIndex = index
						},
						onBlockDoubleTap = { index ->
							val event = events.getOrNull(index)
							if (event != null) {
								val beat = event.startBeat ?: song.starts.getOrNull(index)?.toFloat() ?: 0f
								video.player?.seekTo((offset + beat * secondsPerBeat).coerceAtLeast(0.0).toFloat())
								video.player?.play()
							}
						},
						fixedGrid = true,
					)
				}
			}
			Row(
				Modifier.fillMaxWidth().height(72.dp),
				horizontalArrangement = Arrangement.spacedBy(8.dp),
			) {
				Box(
					Modifier.weight(1.2f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
						.background(Brush.linearGradient(listOf(Fun.Turquoise, Color(0xFF3DD9C1))))
						.clickable { addNextChord() },
					contentAlignment = Alignment.Center,
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text("SIGUIENTE", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 9.sp)
						Text(nextChord, color = Color.White, fontWeight = FontWeight.Black, fontSize = 25.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
						Text("1 tiempo", color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp)
					}
				}
				Box(
					Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
						.background(Brush.linearGradient(listOf(Fun.Purple, Fun.Pink)))
						.clickable { addEmptyTempo() },
					contentAlignment = Alignment.Center,
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text("VACÍO", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Bold, fontSize = 9.sp)
						Text("+", color = Color.White, fontWeight = FontWeight.Black, fontSize = 27.sp)
						Text("1 tiempo", color = Color.White.copy(alpha = 0.85f), fontSize = 9.sp)
					}
				}
				Box(
					Modifier.weight(0.78f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
						.background(Brush.linearGradient(listOf(Fun.Coral, Fun.Pink)))
						.clickable { if (video.playing) video.player?.pause() else video.player?.play() },
					contentAlignment = Alignment.Center,
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
						Icon(
							if (video.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
							contentDescription = if (video.playing) "Pausar vídeo" else "Reproducir vídeo",
							modifier = Modifier.size(24.dp),
							tint = Color.White,
						)
						Text(if (video.playing) "PAUSA" else "PLAY", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
					}
				}
			}
		}
	}

	if (showFullScore) {
		Dialog(
			onDismissRequest = { showFullScore = false },
			properties = DialogProperties(usePlatformDefaultWidth = false),
		) {
			Column(Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(24.dp)).background(Fun.Cream)) {
				TopAppBar(
					title = {
						Column {
							Text("Partitura")
							Text(song.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
						}
					},
					navigationIcon = {
						IconButton(onClick = { showFullScore = false }) {
							Icon(Icons.Filled.Close, contentDescription = "Cerrar partitura")
						}
					},
				)
				LazyColumn(
					Modifier.fillMaxSize().padding(horizontal = 16.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					items(song.lines) { line ->
						Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
							line.section?.takeIf { it.isNotBlank() }?.let {
								Text(it, color = Fun.Purple, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
							}
							Column(Modifier.horizontalScroll(rememberScrollState())) {
								if (line.chords.isNotEmpty()) Text(
									sourceChordLine(line),
									fontFamily = FontFamily.Monospace,
									fontSize = 16.sp,
									fontWeight = FontWeight.Bold,
									color = Fun.Purple,
									softWrap = false,
								)
								if (line.lyric.isNotEmpty()) Text(
									line.lyric,
									fontFamily = FontFamily.Monospace,
									fontSize = 16.sp,
									color = Fun.Ink,
									softWrap = false,
								)
							}
						}
					}
				}
			}
		}
	}

	val editingEvent = events.getOrNull(editingEventIndex)
	if (editingEvent != null) {
		val index = editingEventIndex
		var showMoreDurations by remember(index) { mutableStateOf(false) }
		ChordPicker(
			songChords = sourceEvents.map { it.chord }.distinct(),
			initial = editingEvent.chord,
			onDismiss = { editingEventIndex = -1 },
			onPick = { chord ->
				events = events.toMutableList().also { it[index] = it[index].copy(chord = chord) }
				changed = true
				editingEventIndex = -1
			},
			onDelete = {
				deleteEvent(index)
				editingEventIndex = -1
			},
			header = {
				Column {
					Text("Duración del acorde", style = MaterialTheme.typography.labelLarge)
					FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
						for ((duration, label) in listOf(0.25f to "1/4", 0.5f to "1/2", 1f to "1", 2f to "2", 3f to "3", 4f to "4")) FilterChip(
							selected = kotlin.math.abs(editingEvent.beats - duration) < 1e-3f,
							onClick = { setChordDuration(index, duration); editingEventIndex = -1 },
							label = { Text(label) },
						)
						Box {
							FilterChip(
								selected = editingEvent.beats > 4f,
								onClick = { showMoreDurations = true },
								label = { Text("Más") },
							)
							DropdownMenu(expanded = showMoreDurations, onDismissRequest = { showMoreDurations = false }) {
								for (duration in 5..25) DropdownMenuItem(
									text = { Text("$duration tiempos") },
									onClick = {
										showMoreDurations = false
										setChordDuration(index, duration.toFloat())
										editingEventIndex = -1
									},
								)
							}
						}
					}
					HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 8.dp))
					Text("Seleccionar acorde", style = MaterialTheme.typography.labelLarge)
				}
			},
		)
	}

	if (confirmExit) AlertDialog(
		onDismissRequest = { confirmExit = false },
		title = { Text("¿Guardar la línea de tiempo?") },
		text = { Text("Puedes guardar los cambios antes de salir.") },
		confirmButton = {
		TextButton(onClick = {
			confirmExit = false
			persistTimeline()
			coroutineScope.launch {
				snackbarHostState.showSnackbar("Timeline guardado")
				onBack()
			}
		}) { Text("Guardar") }
		},
		dismissButton = { TextButton(onClick = { confirmExit = false; onBack() }) { Text("Salir sin guardar") } },
	)
}

private fun sourceChordLine(line: SongLine): String = buildString {
	var col = 0
	line.chords.forEachIndexed { index, chord ->
		val padding = (chord.col - col).coerceAtLeast(if (index > 0) 1 else 0)
		append(" ".repeat(padding))
		append(chord.name)
		col += padding + chord.name.length
	}
}
