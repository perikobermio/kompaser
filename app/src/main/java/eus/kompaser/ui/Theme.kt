package eus.kompaser.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Paleta alegre: coral, turquesa, amarillo sol y morado sobre fondo crema. */
object Fun {
	val Coral = Color(0xFFFF5E5B)
	val Orange = Color(0xFFFF9F43)
	val Turquoise = Color(0xFF00B8A9)
	val Sun = Color(0xFFFFC93C)
	val Purple = Color(0xFF8E7DFF)
	val Pink = Color(0xFFFF6FB5)
	val Cream = Color(0xFFFFF8EE)
	val Ink = Color(0xFF2D2A32)

	val current = Brush.linearGradient(listOf(Coral, Orange))
	val next = listOf(
		Brush.linearGradient(listOf(Color(0xFFD7F7F2), Color(0xFFB8EEE6))),
		Brush.linearGradient(listOf(Color(0xFFFFF1C9), Color(0xFFFFE39A))),
	)
	val nextInk = listOf(Color(0xFF00695F), Color(0xFF8A5A00))

	/** Colores de los tiempos del compás, uno por pulso. */
	val beats = listOf(Coral, Sun, Turquoise, Purple, Pink, Orange)

	/** Color de acento estable para cada canción de la lista. */
	fun accent(key: String) = beats[Math.floorMod(key.hashCode(), beats.size)]
}

private val Scheme = lightColorScheme(
	primary = Fun.Coral,
	onPrimary = Color.White,
	primaryContainer = Color(0xFFFFDAD6),
	onPrimaryContainer = Color(0xFF5C1110),
	secondary = Fun.Turquoise,
	onSecondary = Color.White,
	tertiary = Fun.Purple,
	onTertiary = Color.White,
	background = Fun.Cream,
	onBackground = Fun.Ink,
	surface = Fun.Cream,
	onSurface = Fun.Ink,
	surfaceVariant = Color(0xFFF3E9DC),
	onSurfaceVariant = Color(0xFF6B6370),
	surfaceContainer = Color(0xFFFFFFFF),
	surfaceContainerHigh = Color(0xFFFFFFFF),
	surfaceContainerHighest = Color(0xFFFFFFFF),
	outline = Color(0xFFD9CFC2),
)

@Composable
fun KompaserTheme(content: @Composable () -> Unit) = MaterialTheme(
	colorScheme = Scheme,
	shapes = Shapes(
		small = RoundedCornerShape(12.dp),
		medium = RoundedCornerShape(20.dp),
		large = RoundedCornerShape(28.dp),
	),
	content = content,
)
