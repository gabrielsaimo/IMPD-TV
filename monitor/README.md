# Monitor IMPD TV

O que a igreja vê das televisões, e o que as televisões recebem de volta.

São quatro peças:

| Peça | Onde vive | O que faz |
|---|---|---|
| `Telemetry.kt` | dentro do app | bate de tempos em tempos, reporta painel aberto e queda recuperada |
| `src/worker.js` | Cloudflare Workers | recebe as batidas, resolve a UF pelo IP, devolve a chave PIX daquela gerência |
| `schema.sql` | Cloudflare D1 | guarda os aparelhos, a série de audiência e as chaves PIX |
| `public/index.html` | Cloudflare Pages | o painel |

## Por que Cloudflare, e não outra coisa

O request chega na borda **já com a UF resolvida** (`request.cf.regionCode` = `"SP"`).
É exatamente a peça que o PIX regional precisa, sem base de geo-IP para manter e
sem pedir localização ao aparelho. Em qualquer outro lugar isso vira trabalho
extra justamente na funcionalidade que mais importa.

## O que é gravado, e o que não é

Gravado: um UUID que o **próprio aparelho sorteia** na primeira abertura, a UF, o
modelo, a versão instalada, quanto tempo a tela ficou ligada e quais painéis
foram abertos.

Não gravado, em ponto nenhum: nome, CPF, e-mail, login, telefone, IP (usado só
para derivar a UF e descartado), localização precisa, identificador de
publicidade, identificador de hardware. Sem câmera, sem microfone, sem agenda.

O identificador não é o `ANDROID_ID` de propósito — aquele segue a pessoa entre
aplicativos. Este só existe dentro do IMPD TV e some junto com ele.

## Subir

```bash
npm install -g wrangler
wrangler login
```

```bash
cd monitor && wrangler d1 create impd-tv
```

Copie o `database_id` que ele imprime para o `wrangler.toml`, e então:

```bash
cd monitor && wrangler d1 execute impd-tv --remote --file=schema.sql
```

```bash
cd monitor && wrangler secret put DASHBOARD_TOKEN
```

```bash
cd monitor && wrangler deploy
```

O painel é estático, vai para o Pages:

```bash
cd monitor && wrangler pages deploy public --project-name impd-tv-painel
```

Por último, aponte `BASE_URL` no `Telemetry.kt` e `API` no `public/index.html`
para o domínio que o Worker recebeu.

## Rodar na máquina antes de subir

```bash
cd monitor && wrangler dev
```

```bash
cd monitor && wrangler d1 execute impd-tv --local --file=schema.sql
```

## Quanto custa

O que dita o custo é **quantas batidas por dia**: aparelhos × (86.400 ÷ intervalo).
Com o intervalo de 5 minutos que vem no `config`:

| Parque | Batidas/dia | Cabe no plano grátis? |
|---|---|---|
| 100 televisões | 28.800 | sim |
| 300 televisões | 86.400 | sim, no limite |
| 1.000 televisões | 288.000 | não — Workers pago, US$ 5/mês |
| 3.500 televisões | 1.008.000 | Workers pago |

O plano grátis dá 100.000 requisições e 100.000 escritas por dia. Passou disso,
são US$ 5/mês com folga larga.

**O intervalo é configurável sem publicar aplicativo novo.** Dobrar para 10
minutos corta o custo pela metade e a contagem de "assistindo agora" continua
honesta — o painel considera assistindo quem bateu nos últimos 2,5 intervalos.

```sql
UPDATE config SET v = '600' WHERE k = 'heartbeat_seconds';
```

## Chaves PIX por gerência

```sql
INSERT INTO pix_keys (uf, pix_key, label) VALUES ('AM', 'pixam@impd.org.br', 'Gerência Amazonas')
  ON CONFLICT(uf) DO UPDATE SET pix_key = excluded.pix_key, label = excluded.label;
```

Ou pelo endpoint, que é o que o painel usa:

```bash
curl -X PUT https://monitor.impd.org.br/v1/pix -H "authorization: Bearer $TOKEN" -H 'content-type: application/json' -d '{"uf":"AM","pixKey":"pixam@impd.org.br","label":"Gerência Amazonas"}'
```

Estado sem chave própria cai na chave nacional (`uf = 'BR'`). Nenhuma televisão
fica com a gaveta de doação vazia.

## Endpoints

| Rota | Quem chama | O que faz |
|---|---|---|
| `POST /v1/hello` | aparelho, uma vez por abertura | se apresenta; recebe UF, chave PIX regional, intervalo de batida e aviso no ar |
| `POST /v1/beat` | aparelho, a cada intervalo | atualiza a linha do aparelho e soma o tempo de tela |
| `POST /v1/event` | aparelho, quando é raro | painel aberto, vídeo escolhido, queda recuperada |
| `GET /v1/stats` | painel | tudo que os gráficos leem — exige `Bearer` |
| `GET /v1/pix` · `PUT /v1/pix` | painel | ler e cadastrar chave por gerência — exige `Bearer` |

## Segurança do painel

O `DASHBOARD_TOKEN` é o caminho simples. Para produção de verdade, ponha
**Cloudflare Access** na frente do Pages e do `/v1/stats`: aí o acesso é por
e-mail da liderança, com registro de quem entrou, e não por uma chave que
circula no WhatsApp.
