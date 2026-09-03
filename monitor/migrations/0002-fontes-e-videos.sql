-- Segunda leva: o painel deixa de ser só leitura e passa a mandar no app.
--
-- Três coisas entram:
--   1. as fontes de vídeo do YouTube saem de dentro do APK e vêm para cá;
--   2. cada vídeo aberto vira linha com título, para o painel não mostrar hash;
--   3. o estado de cada fonte é medido pelo próprio Worker, de minuto em
--      minuto, e não estimado a partir do que os aparelhos reclamam.

CREATE TABLE IF NOT EXISTS youtube_channels (
  id       TEXT PRIMARY KEY,            -- UCxxxxxxxx
  name     TEXT NOT NULL,
  enabled  INTEGER NOT NULL DEFAULT 1,
  position INTEGER NOT NULL DEFAULT 0,  -- ordem na fileira
  added_at INTEGER NOT NULL
);

-- Dimensão dos vídeos. O evento guarda o instante; esta tabela guarda quem é.
CREATE TABLE IF NOT EXISTS videos (
  id          TEXT PRIMARY KEY,
  title       TEXT,
  channel     TEXT,
  first_seen  INTEGER NOT NULL,
  last_opened INTEGER NOT NULL,
  opens       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS videos_opens ON videos(opens DESC);

-- Estado atual de cada fonte: a transmissão e cada canal do YouTube.
-- Uma linha por fonte, atualizada no lugar — é "está no ar agora", não histórico.
CREATE TABLE IF NOT EXISTS sources (
  id          TEXT PRIMARY KEY,         -- 'stream' | 'youtube:UCxxxx'
  kind        TEXT NOT NULL,            -- 'stream' | 'youtube'
  label       TEXT,
  checked_at  INTEGER,                  -- última vez que o Worker olhou
  ok          INTEGER,                  -- resultado dessa última olhada
  last_ok     INTEGER,                  -- último instante em que estava no ar
  last_fail   INTEGER,
  detail      TEXT,                     -- código HTTP ou motivo da falha
  items       INTEGER,                  -- quantos vídeos o feed devolveu
  ok_count    INTEGER NOT NULL DEFAULT 0,
  fail_count  INTEGER NOT NULL DEFAULT 0,
  -- Reclamações vindas das televisões. Diferente do teste do Worker: uma fonte
  -- pode responder daqui e não responder da casa do fiel.
  device_fails INTEGER NOT NULL DEFAULT 0,
  device_last_fail INTEGER
);

-- Os dois canais que estavam escritos dentro do YoutubeFetcher.
INSERT OR IGNORE INTO youtube_channels (id, name, enabled, position, added_at) VALUES
  ('UCfb8GIF7etM7HaMmBJ150qg', 'Bispo Roberto Santana', 1, 0, unixepoch()),
  ('UCHxVJ4kWtbDbzAwzIJ-_QpA', 'Igreja Mundial Ao Vivo', 1, 1, unixepoch());

-- O resto do que estava chumbado na tela.
INSERT OR IGNORE INTO config (k, v) VALUES
  ('channel_name',    'IMPD TV'),
  ('prayer_phone',    '+551135773800'),
  ('prayer_phone_2',  '+551134883050'),
  ('whatsapp',        ''),
  ('pix_national',    'pix@impd.org.br'),
  ('bank_name',       '237'),
  ('bank_agency',     '3395'),
  ('bank_account',    '200048-2');
