#!/usr/bin/env python3
"""
Calcula los tiempos de una canción de Kompaser a partir de su vídeo de YouTube.

Descarga el audio, detecta los pulsos y alinea la secuencia de acordes de la partitura con lo
que suena: duración real de cada acorde, parones (pausas), BPM y en qué segundo del vídeo
empieza el primer acorde. El resultado se guarda en Postgres (vía PostgREST) y el móvil lo
recibe al sincronizar.

    ./analyze.sh --list                      # canciones del servidor
    ./analyze.sh "Mama Said"                 # analiza y guarda
    ./analyze.sh "Mama Said" --dry-run       # solo muestra el resultado
    ./analyze.sh --json cancion.json --out resultado.json   # sin servidor
"""
import argparse
import json
import math
import os
import re
import sys
import time
from pathlib import Path

import numpy as np

CACHE = Path(__file__).resolve().parent / "cache"
NOTES = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}

# Intervalos (semitonos sobre la raíz) de cada tipo de acorde.
QUALITIES = {
	"": (0, 4, 7), "m": (0, 3, 7), "7": (0, 4, 7, 10), "m7": (0, 3, 7, 10), "maj7": (0, 4, 7, 11),
	"sus2": (0, 2, 7), "sus4": (0, 5, 7), "dim": (0, 3, 6), "dim7": (0, 3, 6, 9), "m7b5": (0, 3, 6, 10),
	"aug": (0, 4, 8), "6": (0, 4, 7, 9), "m6": (0, 3, 7, 9), "9": (0, 4, 7, 10, 2), "add9": (0, 4, 7, 2),
	"m9": (0, 3, 7, 10, 2), "7sus4": (0, 5, 7, 10), "5": (0, 7),
}
ALIASES = {
	"maj": "", "M": "", "min": "m", "-": "m", "M7": "maj7", "Maj7": "maj7", "7M": "maj7", "min7": "m7", "-7": "m7",
	"°": "dim", "o": "dim", "°7": "dim7", "ø": "m7b5", "+": "aug", "2": "sus2", "4": "sus4", "sus": "sus4",
	"add2": "add9", "(9)": "add9", "add11": "sus4",
}


def note(s):
	return (NOTES[s[0]] + (1 if s[1:2] == "#" else -1 if s[1:2] == "b" else 0)) % 12


def chord_template(name, shift):
	"""Vector de 12 notas del acorde tal como suena (forma + cejilla + afinación)."""
	name = name.strip()
	bass = None
	if "/" in name:
		name, b = name.split("/", 1)
		bass = b if b[:1] in NOTES else None
	if not name or name[0] not in NOTES:
		return None
	acc = name[1:2] if name[1:2] in ("#", "b") else ""
	root = note(name[0] + acc)
	suf = name[1 + len(acc):]
	suf = ALIASES.get(suf, suf)
	if suf not in QUALITIES:
		suf = "m7" if suf.startswith("m") and not suf.startswith("maj") and "7" in suf else \
			"m" if suf.startswith("m") and not suf.startswith("maj") else "7" if "7" in suf else ""
	t = np.zeros(12)
	for i, iv in enumerate(QUALITIES[suf]):
		t[(root + iv + shift) % 12] += 1.3 if i == 0 else 1.0
	if bass:
		t[(note(bass) + shift) % 12] += 0.6
	return t / np.linalg.norm(t)


def tuning_shift(tuning):
	"""Semitonos respecto a la afinación estándar según la 1ª cuerda ('Eb' → -1). La 6ª no vale: Drop D."""
	top = (tuning or "E").split()[-1]
	d = (note(top) - 4) % 12
	return d - 12 if d > 6 else d


# --------------------------------------------------------------------------- audio

def download(youtube_id):
	CACHE.mkdir(exist_ok=True)
	wav = CACHE / f"{youtube_id}.wav"
	if wav.exists():
		return wav
	import yt_dlp
	opts = {
		"format": "bestaudio/best",
		"outtmpl": str(CACHE / f"{youtube_id}.%(ext)s"),
		"postprocessors": [{"key": "FFmpegExtractAudio", "preferredcodec": "wav"}],
		"compat_opts": {"no-certifi"},  # usa los certificados del sistema (proxy con inspección SSL)
		"quiet": True,
		"noprogress": True,
	}
	proxy = os.environ.get("https_proxy") or os.environ.get("HTTPS_PROXY")
	if proxy:
		opts["proxy"] = proxy
	print(f"Descargando audio de https://youtu.be/{youtube_id} …", file=sys.stderr)
	with yt_dlp.YoutubeDL(opts) as ydl:
		ydl.download([f"https://www.youtube.com/watch?v={youtube_id}"])
	return wav


def beat_features(wav, half=False, double=False):
	import librosa
	print("Analizando audio …", file=sys.stderr)
	sr, hop = 22050, 512
	y, _ = librosa.load(str(wav), sr=sr, mono=True)
	harm, perc = librosa.effects.hpss(y)
	_, beats = librosa.beat.beat_track(y=perc, sr=sr, hop_length=hop, units="frames")
	if half:
		beats = beats[::2]
	if double:
		mid = ((beats[:-1] + beats[1:]) // 2)
		beats = np.sort(np.concatenate([beats, mid]))
	times = librosa.frames_to_time(beats, sr=sr, hop_length=hop)
	chroma = librosa.feature.chroma_cqt(y=harm, sr=sr, hop_length=hop)
	rms = librosa.feature.rms(y=y, hop_length=hop)[0]
	# Un vector por pulso: lo que suena entre el pulso i y el i+1.
	edges = list(beats) + [chroma.shape[1]]
	C = np.stack([np.median(chroma[:, a:max(a + 1, b)], axis=1) for a, b in zip(edges[:-1], edges[1:])], axis=1)
	E = np.array([rms[a:max(a + 1, b)].mean() for a, b in zip(edges[:-1], edges[1:])])
	C = C / (np.linalg.norm(C, axis=0, keepdims=True) + 1e-9)
	bpm = 60.0 / np.median(np.diff(times))
	return times, C, E, float(bpm)


# --------------------------------------------------------------------------- alineamiento

BREAK_WORDS = ("riff", "solo", "intro", "interlud", "instrumental", "break", "outro", "final")
SECTION_RE = re.compile(r"^\s*\[([^\[\]]+)]\s*$")
CH_RE = re.compile(r"\[ch](.*?)\[/ch]")


def parse_sheet(content):
	"""Réplica de ChordSheet.parse de la app: líneas con su sección, letra y acordes (los índices coinciden)."""
	out, section, pending = [], None, None

	def flush(lyric):
		nonlocal section, pending
		out.append({"section": section, "lyric": lyric, "chords": pending or []})
		section, pending = None, None

	for raw in content.replace("\r", "").split("\n"):
		l = raw.replace("[tab]", "").replace("[/tab]", "")
		if CH_RE.search(l):
			if pending is not None:
				flush("")
			pending = [c.strip() for c in CH_RE.findall(l) if c.strip()]
			continue
		m = SECTION_RE.search(l)
		if m:
			if pending is not None:
				flush("")
			section = m.group(1).strip()
		elif not l.strip():
			if pending is not None:
				flush("")
		else:
			flush(l.rstrip())
	if pending is not None:
		flush("")
	return out


def breaks_after(song, seq_idx):
	"""
	Para cada acorde: ¿la partitura tiene un bloque sin acordes justo después? (tablatura o una
	sección tipo [Riff]/[Solo]). Ahí es donde de verdad hay parones; en mitad de un verso, no.
	"""
	lines = parse_sheet(song["content"])
	ev = song["events"]
	out = []
	for k, i in enumerate(seq_idx):
		if k == len(seq_idx) - 1:
			out.append(True)
			continue
		l1, l2 = ev[i]["l"], ev[seq_idx[k + 1]]["l"]
		mid = lines[l1 + 1:l2 + 1]
		tab = any("|-" in x["lyric"] or "-|" in x["lyric"] for x in mid[:-1])
		sec = any(x["section"] and any(w in x["section"].lower() for w in BREAK_WORDS) for x in mid)
		out.append(tab or sec)
	return out


def align(seq, times, C, E, shift, breaks=None, gap_cost=0.12, gap_open=4.0, gap_open_break=0.3, skip_cost=4.0):
	"""
	Reparte la secuencia de acordes [seq] sobre los pulsos. Estados: antes de empezar, dentro del
	acorde k, o en un hueco tras k (parón o parte sin acordes). Viterbi de izquierda a derecha.
	"""
	names = sorted(set(seq))
	tmpl = {n: chord_template(n, shift) for n in names}
	known = [n for n in names if tmpl[n] is not None]
	T, K = C.shape[1], len(seq)
	S = np.stack([tmpl[n] @ C for n in known])  # similitud coseno: acordes × pulsos
	best = S.max(axis=0)
	silent = E < 0.15 * np.median(E)
	score = {n: (S[i] - best) - np.where(silent, 0.3, 0.0) for i, n in enumerate(known)}
	gapE = np.where(silent, 0.0, -gap_cost)
	emit = np.stack([score.get(n, np.full(T, -0.2)) for n in seq])  # K × T

	NEG = -1e18
	# Estados: 0 = antes, 1..K = acorde k-1, K+1..2K = hueco tras acorde k-1.
	N = 2 * K + 1
	V = np.full(N, NEG)
	back = np.zeros((T, N), dtype=np.int32)
	V[0] = 0.5 * gapE[0]
	V[1] = emit[0, 0]
	back[0, :] = -1
	ci = np.arange(1, K + 1)
	gi = np.arange(K + 1, 2 * K + 1)
	for t in range(1, T):
		W = np.full(N, NEG)
		B = np.zeros(N, dtype=np.int32)
		# antes → antes
		W[0], B[0] = V[0] + 0.5 * gapE[t], 0
		# acorde k: seguir, venir del acorde k-1, del hueco tras k-1, de "antes" (k=0) o saltando uno (k-2)
		stay = V[ci]
		prev_c = np.concatenate([[V[0]], V[ci[:-1]]])
		prev_g = np.concatenate([[NEG], V[gi[:-1]]])
		skip = np.concatenate([[NEG, NEG], V[ci[:-2]] - skip_cost]) if K > 2 else np.full(K, NEG)
		cand = np.stack([stay, prev_c, prev_g, skip])
		src = np.stack([ci, np.concatenate([[0], ci[:-1]]), np.concatenate([[0], gi[:-1]]),
						np.concatenate([[0, 0], ci[:-2]]) if K > 2 else np.zeros(K, dtype=int)])
		arg = cand.argmax(axis=0)
		W[ci] = cand[arg, np.arange(K)] + emit[:, t]
		B[ci] = src[arg, np.arange(K)]
		# hueco tras k: seguir en el hueco o abrirlo desde el acorde k (el hueco final no penaliza)
		open_cost = np.array([gap_open_break if breaks and breaks[k] else gap_open for k in range(K)])
		open_cost[-1] = 0.0
		g_stay, g_open = V[gi], V[ci] - open_cost
		W[gi] = np.maximum(g_stay, g_open) + np.where(np.arange(K) == K - 1, 0.0, gapE[t])
		B[gi] = np.where(g_stay >= g_open, gi, ci)
		V, back[t] = W, B
	state = K if V[K] >= V[2 * K] else 2 * K
	path = [0] * T
	for t in range(T - 1, -1, -1):
		path[t] = state
		state = back[t, state] if t > 0 else state
	return path, S, known


def build_events(song, seq_idx, path, bpb, grid, times):
	"""
	Convierte el camino de Viterbi en la secuencia de la app (acordes + pausas). Cada evento lleva
	además "t": el segundo exacto del vídeo en que empieza, para que la app no acumule desfase
	aunque el tempo de la grabación varíe o las duraciones se hayan redondeado.
	"""
	ev = song["events"]
	K = len(seq_idx)
	counts, gaps, t_chord, t_gap = {}, {}, {}, {}
	first_beat = None
	for t, s in enumerate(path):
		if 1 <= s <= K:
			counts[s - 1] = counts.get(s - 1, 0) + 1
			t_chord.setdefault(s - 1, float(times[t]))
			if first_beat is None:
				first_beat = t
		elif s > K and s - K - 1 < K - 1:
			gaps[s - K - 1] = gaps.get(s - K - 1, 0) + 1
			t_gap.setdefault(s - K - 1, float(times[t]))
	items, skipped = [], 0
	for k, i in enumerate(seq_idx):
		n = counts.get(k, 0)
		if n == 0:
			skipped += 1
			continue
		g = gaps.get(k, 0)
		bars = round(g / bpb)
		base = {k2: v for k2, v in ev[i].items() if k2 not in ("t", "m")}  # tiempos calculados: ya no son marcas a mano
		if bars >= 1:
			items += [({**base, "t": round(t_chord[k], 3)}, n), ({"c": "", "l": ev[i]["l"], "p": -1, "t": round(t_gap[k], 3)}, bars * bpb)]
		else:
			items.append(({**base, "t": round(t_chord[k], 3)}, n + g))  # hueco corto: es el mismo acorde sonando
	durs = quantize([d for _, d in items], grid)
	out = [{**e, "b": float(d)} for (e, _), d in zip(items, durs)]
	return out, first_beat, skipped


def quantize(durs, grid):
	"""
	Ajusta los cambios de acorde a una rejilla de [grid] tiempos (p. ej. medio compás) con la fase que
	más cambios explica: un cambio anticipado un tiempo (3+5) queda como 4+4. Ningún acorde desaparece.
	"""
	if grid <= 1:
		return durs
	pos = np.cumsum(durs)
	phase = max(range(grid), key=lambda p: int(np.sum(pos % grid == p)))
	snapped = np.round((pos - phase) / grid) * grid + phase
	out, prev = [], 0
	for i, b in enumerate(snapped):
		b = max(int(b), prev + 1) if i < len(snapped) - 1 else max(int(pos[-1]), prev + 1)
		out.append(b - prev)
		prev = b
	return out


def accuracy(path, seq, S, known):
	"""% de pulsos en los que el acorde asignado es el que mejor encaja con lo que suena."""
	K = len(seq)
	best = S.argmax(axis=0)
	hits = [known.index(seq[s - 1]) == best[t] for t, s in enumerate(path) if 1 <= s <= K and seq[s - 1] in known]
	return 100.0 * np.mean(hits) if hits else 0.0


def song_line(song, n):
	"""Letra de la línea [n] de la app, para el informe."""
	lines = parse_sheet(song["content"])
	return (lines[n]["lyric"].strip() if n < len(lines) else "") or "·"


# --------------------------------------------------------------------------- servidor

def api_songs(api):
	import requests
	r = requests.get(f"{api}/songs", params={"select": "*"}, timeout=20)
	r.raise_for_status()
	return r.json()


def api_save(api, song):
	import requests
	r = requests.patch(f"{api}/songs", params={"id": f"eq.{song['id']}"}, timeout=20, json={
		k: song[k] for k in ("events", "bpm", "video_offset_ms", "updated_at")
	})
	r.raise_for_status()


def main():
	ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
	ap.add_argument("song", nargs="?", help="id o parte del título de la canción")
	ap.add_argument("--api", default=os.environ.get("KOMPASER_API", "http://localhost:3000"))
	ap.add_argument("--list", action="store_true", help="lista las canciones del servidor")
	ap.add_argument("--all", action="store_true", help="analiza todas las canciones con vídeo")
	ap.add_argument("--json", help="lee la canción de un fichero JSON en lugar del servidor")
	ap.add_argument("--out", help="escribe el resultado en este fichero JSON")
	ap.add_argument("--youtube", help="usa este vídeo en lugar del de la canción")
	ap.add_argument("--dry-run", action="store_true", help="no guarda nada")
	ap.add_argument("--half", action="store_true", help="el tempo detectado es el doble del real")
	ap.add_argument("--double", action="store_true", help="el tempo detectado es la mitad del real")
	ap.add_argument("--grid", type=int, default=2, help="ajusta los cambios a múltiplos de N tiempos (1 = sin ajuste)")
	ap.add_argument("--snap", action="store_true",
		help="no recalcula nada: lleva las marcas hechas a mano (Marcar tiempos) al pulso real más cercano del audio")
	a = ap.parse_args()

	if a.json:
		targets = [json.loads(Path(a.json).read_text())]
	else:
		songs = api_songs(a.api)
		if a.all:
			targets = [s for s in songs if s.get("youtube_id")]
		elif a.list or not a.song:
			for s in songs:
				print(f"{s['id']}  {s['artist']} — {s['title']}  {'▶ ' + s['youtube_id'] if s.get('youtube_id') else '(sin vídeo)'}")
			return
		else:
			targets = None
		q = (a.song or "").lower()
		hits = [s for s in songs if s["id"] == a.song or q in s["title"].lower()]
		if targets is None:
			if len(hits) != 1:
				sys.exit(f"{'Ninguna' if not hits else 'Varias'} canciones coinciden con «{a.song}»")
			targets = hits

	for song in targets:
		snap(song, a) if a.snap else analyze(song, a)


def snap(song, a, tolerance=0.08):
	"""
	Quita la variación de las marcas hechas a mano («me adelanto o atraso unas centésimas»).

	Las marcas suelen ir con un desfase constante respecto al pulso detectado en el audio (el reproductor
	de YouTube informa del tiempo con algo de retraso; al reproducir se usa el mismo reloj, así que ese
	desfase no molesta). Se mide ese desfase medio sobre la rejilla de medios pulsos y cada marca se lleva al
	punto más cercano de la rejilla desplazada, si está a menos de [tolerance] s. Así se respeta el desfase
	y solo se quita la variación de una marca a otra.
	"""
	ev = song["events"]
	if not ev or any(e.get("t") is None for e in ev):
		print(f"«{song['title']}» no tiene tiempos marcados: usa Marcar tiempos en el móvil y sincroniza", file=sys.stderr)
		return
	yt = a.youtube or song.get("youtube_id")
	if not yt:
		print(f"«{song['title']}» no tiene vídeo de YouTube", file=sys.stderr)
		return
	times, _, _, bpm = beat_features(download(yt), a.half, a.double)
	grid = np.sort(np.concatenate([times, (times[:-1] + times[1:]) / 2]))  # pulsos y medios pulsos
	half = float(np.median(np.diff(grid)))
	taps = np.array([e["t"] for e in ev])
	near = grid[np.clip(np.searchsorted(grid, taps), 1, len(grid) - 1) - 1]
	# Desfase de cada marca dentro de su medio pulso, como ángulo; la media circular da el desfase típico.
	ang = 2 * np.pi * ((taps - near) % half) / half
	vec = np.exp(1j * ang).mean()
	phase = (np.angle(vec) % (2 * np.pi)) / (2 * np.pi) * half
	if abs(vec) < 0.3:
		print(f"\n{song['artist']} — {song['title']}: las marcas no siguen el pulso con claridad (concentración {abs(vec):.2f}); no se toca nada.")
		return
	shifted = grid + phase
	new_t, shifts = [], []
	for t in taps:
		g = float(shifted[np.argmin(np.abs(shifted - t))])
		if abs(g - t) <= tolerance and (not new_t or g > new_t[-1] + 0.05):
			shifts.append(g - t)
			new_t.append(g)
		else:
			new_t.append(float(t))
	spb = 60.0 / float(song.get("bpm") or bpm)
	end = new_t[-1] + ev[-1]["b"] * spb
	out = []
	for i, e in enumerate(ev):
		nxt = new_t[i + 1] if i + 1 < len(ev) else end
		out.append({**e, "t": round(new_t[i], 3), "b": float(max(0.5, round((nxt - new_t[i]) / spb * 2) / 2))})
	sh = np.abs(shifts) * 1000 if shifts else np.array([0.0])
	print(f"\n{song['artist']} — {song['title']}")
	print(f"  Tus marcas van {phase * 1000:.0f} ms por detrás de la rejilla de medios pulsos (se respeta); concentración {abs(vec):.2f}")
	print(f"  Ajustadas: {len(shifts)} de {len(ev)}   ·   corrección media {sh.mean():.0f} ms, máxima {sh.max():.0f} ms")
	song = {**song, "events": out, "video_offset_ms": int(out[0]["t"] * 1000), "updated_at": int(time.time() * 1000)}
	if a.out:
		Path(a.out).write_text(json.dumps(song, ensure_ascii=False, indent=1))
	if not a.dry_run and not a.json:
		api_save(a.api, song)
		print("  Guardado en el servidor. Sincroniza en el móvil para recibirlo.")


def analyze(song, a):
	yt = a.youtube or song.get("youtube_id")
	if not yt:
		print(f"«{song['title']}» no tiene vídeo de YouTube (ponlo en la app o usa --youtube)", file=sys.stderr)
		return
	bpb = int(song.get("beats_per_bar") or 4)
	shift = int(song.get("capo") or 0) + tuning_shift(song.get("tuning"))

	times, C, E, bpm = beat_features(download(yt), a.half, a.double)
	seq_idx = [i for i, e in enumerate(song["events"]) if e["c"]]
	seq = [song["events"][i]["c"] for i in seq_idx]
	path, S, known = align(seq, times, C, E, shift, breaks_after(song, seq_idx))
	events, first, skipped = build_events(song, seq_idx, path, bpb, a.grid, times)

	print(f"\n{song['artist']} — {song['title']}")
	print(f"  BPM detectado: {bpm:.1f}   transposición: {shift:+d} semitonos   pulsos: {len(times)}")
	print(f"  Primer acorde en el segundo {times[first]:.2f}" if first is not None else "  No se encontró el primer acorde")
	print(f"  Encaje con el audio: {accuracy(path, seq, S, known):.0f} % de los pulsos"
		  f"   ·   pausas: {sum(1 for e in events if not e['c'])}   ·   acordes saltados: {skipped}\n")
	line = None
	for e in events:
		if e["l"] != line:
			line = e["l"]
			print(f"\n  {song_line(song, line)[:60]}\n    ", end="")
		print(f"[pausa {int(e['b'] // bpb)}c] " if not e["c"] else f"{e['c']}·{int(e['b'])} ", end="")
	print()

	song = {**song, "events": events, "bpm": round(bpm, 1), "updated_at": int(time.time() * 1000)}
	if first is not None:
		song["video_offset_ms"] = int(times[first] * 1000)
	if a.out:
		Path(a.out).write_text(json.dumps(song, ensure_ascii=False, indent=1))
		print(f"\nGuardado en {a.out}")
	if not a.dry_run and not a.json:
		api_save(a.api, song)
		print("\nGuardado en el servidor. Sincroniza en el móvil para recibirlo.")




if __name__ == "__main__":
	main()
