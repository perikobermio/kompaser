package eus.kompaser.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import eus.kompaser.model.ChordShape

/** Diagrama de traste: cuerdas en vertical (6ª a la izquierda), 5 trastes, puntos con el número de dedo. */
@Composable
fun ChordDiagram(
	shape: ChordShape, modifier: Modifier = Modifier, color: Color, dotColor: Color, dotText: Color, fitHeight: Boolean = false,
) {
	val measurer = rememberTextMeasurer()
	val fretted = shape.frets.filter { it > 0 }
	val maxF = fretted.maxOrNull() ?: 0
	val base = if (maxF <= 5) 1 else fretted.min()
	Canvas(modifier.aspectRatio(0.8f, matchHeightConstraintsFirst = fitHeight)) {
		val padL = size.width * 0.16f
		val padR = size.width * 0.06f
		val padT = size.height * 0.14f
		val padB = size.height * 0.03f
		val w = size.width - padL - padR
		val h = size.height - padT - padB
		val sx = w / 5
		val fy = h / 5
		val line = (size.width / 90f).coerceAtLeast(1.5f)
		val r = sx * 0.36f
		val textSize = (r * 1.25f / density).sp

		for (s in 0..5) drawLine(color, Offset(padL + s * sx, padT), Offset(padL + s * sx, padT + h), line)
		for (f in 0..5) drawLine(color, Offset(padL, padT + f * fy), Offset(padL + w, padT + f * fy), line)
		if (base == 1) drawRect(color, Offset(padL - line, padT - line * 3), Size(w + 2 * line, line * 4))
		else drawText(
			measurer, "${base}", Offset(0f, padT + fy * 0.15f),
			TextStyle(color = color, fontSize = (fy * 0.5f / density).sp, fontWeight = FontWeight.Bold),
		)

		// x / o encima de la cejuela
		shape.frets.forEachIndexed { s, f ->
			val cx = padL + s * sx
			val cy = padT * 0.45f
			if (f == 0) drawCircle(color, r * 0.6f, Offset(cx, cy), style = Stroke(line))
			if (f < 0) {
				val d = r * 0.55f
				drawLine(color, Offset(cx - d, cy - d), Offset(cx + d, cy + d), line * 1.3f)
				drawLine(color, Offset(cx - d, cy + d), Offset(cx + d, cy - d), line * 1.3f)
			}
		}

		shape.barre?.let { b ->
			val y = padT + (b.fret - base + 0.5f) * fy
			drawRoundRect(
				dotColor, Offset(padL + b.from * sx - r, y - r * 0.8f), Size((b.to - b.from) * sx + 2 * r, r * 1.6f),
				CornerRadius(r, r),
			)
		}
		shape.frets.forEachIndexed { s, f ->
			if (f <= 0) return@forEachIndexed
			val c = Offset(padL + s * sx, padT + (f - base + 0.5f) * fy)
			val inBarre = shape.barre?.let { f == it.fret && s in it.from..it.to } == true
			if (!inBarre) drawCircle(dotColor, r, c)
			val finger = shape.fingers.getOrNull(s) ?: 0
			if (finger > 0 && (!inBarre || s == shape.barre?.from)) {
				val t = measurer.measure("$finger", TextStyle(color = dotText, fontSize = textSize, fontWeight = FontWeight.Bold))
				drawText(t, topLeft = Offset(c.x - t.size.width / 2f, c.y - t.size.height / 2f))
			}
		}
	}
}
