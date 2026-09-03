-- O feed RSS do YouTube (feeds/videos.xml) saiu do ar: responde 404 até para o
-- canal oficial do próprio YouTube. Como era ele que alimentava a fileira de
-- baixo, a busca de vídeos passa a ser feita aqui e não em cada televisão.
--
-- Isso vale por si, independente do RSS: mil aparelhos deixam de bater no
-- YouTube mil vezes, a chave de API (quando existir) nunca entra no APK, e o
-- dia em que o YouTube mudar de novo o conserto é um deploy — não uma
-- atualização em cada sala.
CREATE TABLE IF NOT EXISTS video_cache (
  channel_id TEXT PRIMARY KEY,
  payload    TEXT NOT NULL,      -- JSON já no formato que a televisão espera
  fetched_at INTEGER NOT NULL,
  ok         INTEGER NOT NULL DEFAULT 1,
  detail     TEXT
);
