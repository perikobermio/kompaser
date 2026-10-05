-- Canciones con acordes. Pensado para ampliarse a otros instrumentos (bajo, batería…)
-- con la columna instrument y el contenido en formato Ultimate Guitar.
CREATE TABLE songs (
	id              uuid PRIMARY KEY,
	ug_id           bigint,
	title           text NOT NULL,
	artist          text NOT NULL DEFAULT '',
	instrument      text NOT NULL DEFAULT 'guitar',
	capo            int NOT NULL DEFAULT 0,
	tuning          text NOT NULL DEFAULT 'E A D G B E',
	bpm             real NOT NULL DEFAULT 90,
	beats_per_bar   int NOT NULL DEFAULT 4,
	youtube_id      text,
	video_offset_ms bigint NOT NULL DEFAULT 0,
	source_url      text,
	content         text NOT NULL,           -- letra con [ch]acordes[/ch]
	events          jsonb NOT NULL,          -- [{c: acorde, b: tiempos, l: línea, p: posición}]
	shapes          jsonb NOT NULL,          -- {acorde: {frets, fingers, barre}} de 6ª a 1ª cuerda
	updated_at      bigint NOT NULL,         -- epoch ms, para sincronizar
	created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX songs_artist_title ON songs (lower(artist), lower(title));

CREATE ROLE web_anon NOLOGIN;
GRANT USAGE ON SCHEMA public TO web_anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON songs TO web_anon;
GRANT web_anon TO kompaser;
