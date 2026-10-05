package eus.kompaser.data

import eus.kompaser.model.Barre
import eus.kompaser.model.ChordShape

/**
 * Digitaciones de guitarra para cuando la tablatura no trae la suya: primero acordes abiertos
 * habituales y, si no, una forma con cejilla (de Mi en la 6ª o de La en la 5ª) calculada a partir de la raíz.
 */
object ChordLibrary {
	private val NOTES = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

	// Trastes y dedos de 6ª a 1ª cuerda; 'x' = no se toca.
	private val OPEN = mapOf(
		"C" to ("x32010" to "032010"), "C7" to ("x32310" to "032410"), "Cmaj7" to ("x32000" to "032000"),
		"Cadd9" to ("x32030" to "021030"),
		"D" to ("xx0232" to "000132"), "Dm" to ("xx0231" to "000231"), "D7" to ("xx0212" to "000213"),
		"Dmaj7" to ("xx0222" to "000123"), "Dm7" to ("xx0211" to "000211"), "Dsus2" to ("xx0230" to "000130"),
		"Dsus4" to ("xx0233" to "000134"),
		"E" to ("022100" to "023100"), "Em" to ("022000" to "023000"), "E7" to ("020100" to "020100"),
		"Em7" to ("022030" to "023040"), "Esus4" to ("022200" to "023400"),
		"G" to ("320003" to "210003"), "G7" to ("320001" to "320001"),
		"A" to ("x02220" to "001230"), "Am" to ("x02210" to "002310"), "A7" to ("x02020" to "002030"),
		"Am7" to ("x02010" to "002010"), "Amaj7" to ("x02120" to "002130"), "Asus2" to ("x02200" to "001200"),
		"Asus4" to ("x02230" to "001230"),
		"B7" to ("x21202" to "021304"), "Fmaj7" to ("xx3210" to "003210"),
	)

	// Desplazamientos respecto al traste de la raíz.
	private val E_SHAPE = mapOf(
		"" to "0 2 2 1 0 0", "m" to "0 2 2 0 0 0", "7" to "0 2 0 1 0 0", "m7" to "0 2 0 0 0 0",
		"sus4" to "0 2 2 2 0 0", "5" to "0 2 2 x x x", "maj7" to "0 x 1 1 0 x", "m6" to "0 2 2 0 2 0",
	)
	private val A_SHAPE = mapOf(
		"" to "x 0 2 2 2 0", "m" to "x 0 2 2 1 0", "7" to "x 0 2 0 2 0", "m7" to "x 0 2 0 1 0",
		"maj7" to "x 0 2 1 2 0", "sus2" to "x 0 2 2 0 0", "sus4" to "x 0 2 2 3 0", "dim" to "x 0 1 2 1 x",
		"dim7" to "x 0 1 2 1 2", "m7b5" to "x 0 1 0 1 x", "aug" to "x 0 3 2 2 1", "6" to "x 0 2 2 2 2",
		"m6" to "x 0 2 2 1 2", "9" to "x 0 2 4 2 3", "add9" to "x 0 2 4 2 0", "7sus4" to "x 0 2 0 3 0",
		"5" to "x 0 2 2 x x",
	)

	private val ALIASES = mapOf(
		"maj" to "", "M" to "", "min" to "m", "-" to "m", "M7" to "maj7", "Maj7" to "maj7", "Δ" to "maj7",
		"Δ7" to "maj7", "min7" to "m7", "-7" to "m7", "°" to "dim", "o" to "dim", "°7" to "dim7", "o7" to "dim7",
		"ø" to "m7b5", "+" to "aug", "2" to "sus2", "sus" to "sus4", "add2" to "add9",
	)

	private val nameRe = Regex("""^([A-G])([#b]?)([^/]*)(/.*)?$""")

	fun shape(name: String): ChordShape? {
		val m = nameRe.find(name.trim()) ?: return null
		val (letter, acc, rawSuffix) = m.destructured
		val suffix = normalize(rawSuffix)
		OPEN[letter + acc + suffix]?.let { (f, d) -> return ChordShape(parse(f), d.map { it.digitToInt() }) }
		val root = (NOTES.getValue(letter[0]) + when (acc) { "#" -> 1; "b" -> 11; else -> 0 }) % 12
		val candidates = listOfNotNull(
			E_SHAPE[suffix]?.let { build(it, (root - 4 + 12) % 12) },
			A_SHAPE[suffix]?.let { build(it, (root - 9 + 12) % 12) },
		)
		return candidates.minByOrNull { s -> s.frets.max() }
	}

	private fun normalize(s: String): String {
		ALIASES[s]?.let { return it }
		if (s in A_SHAPE || s in E_SHAPE) return s
		return when {
			s.startsWith("m") && !s.startsWith("maj") -> if ("7" in s) "m7" else "m"
			"7" in s -> "7"
			else -> ""
		}
	}

	private fun parse(f: String) = f.map { if (it == 'x') -1 else it.digitToInt() }

	/** Coloca la plantilla en el traste [root] (0 = posición abierta) con cejilla con el dedo 1. */
	private fun build(template: String, root: Int): ChordShape {
		val offs = template.split(' ').map { if (it == "x") null else it.toInt() }
		return fromFrets(offs.map { o -> if (o == null) -1 else root + o }, barre = root > 0)
	}

	/**
	 * Asigna dedos: cejilla en el traste más bajo (si procede) y el resto por traste y cuerda.
	 * Por defecto hay cejilla cuando no suena ninguna cuerda al aire.
	 */
	fun fromFrets(frets: List<Int>, barre: Boolean = frets.none { it == 0 }): ChordShape {
		val fingers = MutableList(6) { 0 }
		val fretted = frets.indices.filter { frets[it] > 0 }
		if (fretted.isEmpty()) return ChordShape(frets, fingers)
		val minF = fretted.minOf { frets[it] }
		val atMin = fretted.filter { frets[it] == minF }
		var b: Barre? = null
		if (barre && atMin.size >= 2 && (atMin.first()..atMin.last()).none { frets[it] == 0 }) {
			b = Barre(minF, atMin.first(), atMin.last())
			atMin.forEach { fingers[it] = 1 }
		}
		var next = if (b != null) 2 else 1
		fretted.filter { fingers[it] == 0 }.sortedWith(compareBy({ frets[it] }, { it })).forEach {
			fingers[it] = next.coerceAtMost(4)
			next++
		}
		return ChordShape(frets, fingers, b)
	}
}
