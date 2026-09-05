-- Três coisas nesta leva:
--
--   1. Canal do YouTube ganha alcance: nacional, ou de um estado só. Uma
--      pregação regional não precisa aparecer na televisão do país inteiro.
--   2. O aparelho passa a guardar o município aproximado da conexão, que a
--      borda já entrega junto da UF. Aproximado mesmo: às vezes é a cidade do
--      POP da operadora, e não a de quem assiste. O painel diz isso.
--   3. Índice para o gráfico conseguir varrer 30 dias de série sem sofrer.

ALTER TABLE youtube_channels ADD COLUMN scope TEXT NOT NULL DEFAULT 'BR';

ALTER TABLE devices ADD COLUMN city TEXT;
CREATE INDEX IF NOT EXISTS devices_city ON devices(country, uf, city);

-- audience_minute já tem ts como chave primária, que serve de índice para o
-- recorte por período. Nada a fazer aqui — a nota fica para quem vier depois.
