-- Banco do monitoramento do IMPD TV (Cloudflare D1).
--
-- Duas ideias governam o desenho:
--
-- 1. O batimento do aparelho ATUALIZA uma linha, nunca insere uma nova. Um
--    parque de 3.500 aparelhos batendo de 5 em 5 minutos geraria um milhão de
--    linhas por dia se cada batida virasse registro; em vez disso `devices`
--    tem exatamente uma linha por aparelho, para sempre.
--
-- 2. O histórico dos gráficos é escrito por um cron do Worker, uma linha por
--    minuto, e não pelos aparelhos. São 1.440 linhas por dia no total,
--    independentemente de quantas televisões existam.

CREATE TABLE IF NOT EXISTS devices (
  id             TEXT PRIMARY KEY,          -- UUID sorteado no aparelho, sem relação com hardware
  first_seen     INTEGER NOT NULL,          -- epoch em segundos
  last_seen      INTEGER NOT NULL,
  last_playing   INTEGER NOT NULL DEFAULT 0,-- epoch da última batida com vídeo tocando
  uf             TEXT,                      -- resolvida na borda pelo IP, nunca por GPS
  country        TEXT,
  version_code   INTEGER,
  version_name   TEXT,
  model          TEXT,
  android_sdk    INTEGER,
  sessions       INTEGER NOT NULL DEFAULT 0,
  screen_seconds INTEGER NOT NULL DEFAULT 0 -- acumulado de tela ligada, desde sempre
);
CREATE INDEX IF NOT EXISTS devices_last_playing ON devices(last_playing);
CREATE INDEX IF NOT EXISTS devices_uf           ON devices(uf);
CREATE INDEX IF NOT EXISTS devices_version      ON devices(version_code);

-- Só o que é raro entra aqui: tecla de painel, vídeo aberto, queda, atualização.
-- Batimento NÃO é evento, ou a tabela cresce como se fosse.
CREATE TABLE IF NOT EXISTS events (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  device_id TEXT NOT NULL,
  ts        INTEGER NOT NULL,
  type      TEXT NOT NULL,
  uf        TEXT,
  meta      TEXT
);
CREATE INDEX IF NOT EXISTS events_ts      ON events(ts);
CREATE INDEX IF NOT EXISTS events_type_ts ON events(type, ts);

-- A série que o gráfico de audiência lê. Escrita pelo cron, um ponto por minuto.
CREATE TABLE IF NOT EXISTS audience_minute (
  ts       INTEGER PRIMARY KEY,  -- epoch truncado no minuto
  watching INTEGER NOT NULL
);

-- Tela ligada por aparelho e por dia, para a média semanal.
CREATE TABLE IF NOT EXISTS screen_day (
  day       TEXT    NOT NULL,    -- YYYY-MM-DD no fuso de São Paulo
  device_id TEXT    NOT NULL,
  seconds   INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, device_id)
);
CREATE INDEX IF NOT EXISTS screen_day_day ON screen_day(day);

-- A chave PIX que cada gerência recebe. Editável sem publicar aplicativo novo.
CREATE TABLE IF NOT EXISTS pix_keys (
  uf      TEXT PRIMARY KEY,      -- 'BR' é a chave nacional, usada quando a UF não resolve
  pix_key TEXT NOT NULL,
  label   TEXT
);

-- Tudo que a televisão lê do painel em vez de trazer chumbado no APK.
CREATE TABLE IF NOT EXISTS config (
  k TEXT PRIMARY KEY,
  v TEXT NOT NULL
);

INSERT OR IGNORE INTO config (k, v) VALUES
  ('heartbeat_seconds', '300'),
  ('banner_seconds',    '6'),
  ('notice_text',       ''),
  ('notice_until',      '0');

INSERT OR IGNORE INTO pix_keys (uf, pix_key, label) VALUES
  ('BR', 'pix@impd.org.br',   'Nacional'),
  ('RS', 'pixrs@impd.org.br', 'Gerência Rio Grande do Sul');
