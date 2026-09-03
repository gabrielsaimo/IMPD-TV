/**
 * Ingestão e leitura do monitoramento do IMPD TV.
 *
 * Roda na borda da Cloudflare por um motivo concreto além do preço: o próprio
 * request chega com a UF resolvida (`request.cf.regionCode`), sem base de
 * geo-IP para manter e sem pedir localização ao aparelho. É essa UF que decide
 * qual chave PIX a televisão mostra.
 *
 * Nada aqui identifica pessoa: o aparelho manda um UUID que ele mesmo sorteou
 * na primeira abertura, e o IP é usado para derivar a UF e descartado.
 */

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };

/**
 * Último minuto que este isolate já amostrou. O cron é o caminho certo, mas
 * conta gratuita da Cloudflare não tem cron nenhum — então qualquer requisição
 * que chegue também deixa o ponto do minuto gravado, no máximo uma vez por
 * minuto por isolate. A gravação é `DO NOTHING` em conflito, então dois
 * isolates disputando o mesmo minuto não fazem mal.
 */
let lastSampledMinute = 0;

/** Quanto tempo sem bater até um aparelho deixar de contar como assistindo. */
const OFFLINE_GRACE = 2.5;

const EVENT_TYPES = new Set([
  "open_info", "open_videos", "open_prayer", "open_pix",
  "video_open", "reconnect", "wifi_prompt",
  "update_offered", "update_started", "update_done",
  // Reclamacao vinda da televisao: uma fonte pode responder daqui e nao
  // responder da casa do fiel. E outro dado, nao o mesmo do teste do cron.
  "source_fail"
]);

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "");

    if (request.method === "OPTIONS") return preflight(env);

    try {
      ctx.waitUntil(sampleMinute(env));

      switch (path) {
        case "/v1/hello": return await hello(request, env);
        case "/v1/beat":  return await beat(request, env);
        case "/v1/event": return await event(request, env);
        case "/v1/videos": return await videos(request, env);
        case "/v1/stats": return await stats(request, env, url);
        case "/v1/pix":   return await pixAdmin(request, env);
        case "/v1/admin/state":   return await adminState(request, env);
        case "/v1/admin/config":  return await adminConfig(request, env);
        case "/v1/admin/channel": return await adminChannel(request, env, url);
        default:
          return json({ error: "not_found" }, 404, env);
      }
    } catch (err) {
      // Uma falha aqui nunca pode virar problema na televisão: o aparelho
      // ignora qualquer resposta que não seja 2xx e continua tocando.
      return json({ error: "server_error", detail: String(err && err.message || err) }, 500, env);
    }
  },

  /**
   * Um ponto por minuto na série de audiência, mais a limpeza. É o cron que
   * escreve o histórico — nunca os aparelhos, senão o volume de escrita
   * cresceria junto com o parque.
   */
  async scheduled(controller, env, ctx) {
    const now = Math.floor(Date.now() / 1000);
    const minute = now - (now % 60);
    await sampleMinute(env, true);

    // De cinco em cinco minutos, e não a cada minuto: é estado de fonte, não
    // audiência, e bater no YouTube sessenta vezes por hora não diz mais nada
    // do que bater doze.
    if ((minute / 60) % 5 === 0) await probeSources(env);

    // Retenção: o painel olha 90 dias de série e 180 de evento. O resto sai.
    if (minute % 3600 === 0) {
      await env.DB.batch([
        env.DB.prepare("DELETE FROM audience_minute WHERE ts < ?").bind(now - 90 * 86400),
        env.DB.prepare("DELETE FROM events WHERE ts < ?").bind(now - 180 * 86400),
        env.DB.prepare("DELETE FROM screen_day WHERE day < date('now', '-180 days')")
      ]);
    }
  }
};

/**
 * Vai lá e olha se cada fonte está no ar — a transmissão e cada canal do
 * YouTube. Perguntar direto é diferente de deduzir pelo que as televisões
 * reclamam: uma fonte pode estar de pé e mesmo assim não abrir na casa do
 * fiel, e as duas informações juntas dizem de que lado está o problema.
 */
async function probeSources(env) {
  const now = Math.floor(Date.now() / 1000);

  const channels = await env.DB.prepare(
    "SELECT id, name FROM youtube_channels WHERE enabled = 1"
  ).all();

  const alvos = [{ id: "stream", kind: "stream", label: "Transmissão ao vivo", check: probeStream }];
  for (const c of (channels.results || [])) {
    alvos.push({
      id: "youtube:" + c.id,
      kind: "youtube",
      label: c.name,
      check: () => probeYoutube(c.id)
    });
  }

  const resultados = await Promise.all(alvos.map(async a => {
    try { return { a, r: await a.check() }; }
    catch (e) { return { a, r: { ok: false, detail: String(e && e.message || e).slice(0, 120), items: 0 } }; }
  }));

  await env.DB.batch(resultados.map(({ a, r }) => env.DB.prepare(`
    INSERT INTO sources (id, kind, label, checked_at, ok, last_ok, last_fail, detail, items, ok_count, fail_count)
    VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
    ON CONFLICT(id) DO UPDATE SET
      kind       = ?2,
      label      = ?3,
      checked_at = ?4,
      ok         = ?5,
      last_ok    = CASE WHEN ?5 = 1 THEN ?4 ELSE sources.last_ok END,
      last_fail  = CASE WHEN ?5 = 0 THEN ?4 ELSE sources.last_fail END,
      detail     = ?8,
      items      = COALESCE(?9, sources.items),
      ok_count   = sources.ok_count + ?10,
      fail_count = sources.fail_count + ?11
  `).bind(
    a.id, a.kind, a.label, now, r.ok ? 1 : 0,
    r.ok ? now : null, r.ok ? null : now,
    r.detail || null, r.items == null ? null : r.items,
    r.ok ? 1 : 0, r.ok ? 0 : 1
  )));
}

const STREAM_ENDPOINT = "https://www.inradar.com.br/api/v2/inchurch_channel/home_live/";
const APP_ID = "br.com.inchurch.mundialpoderdeus";

/** O mesmo endereço que o aparelho consulta a cada abertura. */
async function probeStream() {
  const r = await fetch(STREAM_ENDPOINT, {
    headers: {
      "Content-Type": "application/json;charset=UTF-8",
      "Channel": "site",
      "appId": APP_ID,
      "Accept-language": "pt-BR"
    }
  });
  if (!r.ok) return { ok: false, detail: "HTTP " + r.status, items: 0 };
  const body = await r.json();
  const canais = Array.isArray(body.channels) ? body.channels : [];
  const aoVivo = canais.filter(c => c.stream_url && c.channel_type === "hls" && c.is_live);
  const qualquer = canais.filter(c => c.stream_url);
  if (aoVivo.length) return { ok: true, detail: "ao vivo", items: aoVivo.length };
  if (qualquer.length) return { ok: true, detail: "no ar, fora do horário", items: qualquer.length };
  return { ok: false, detail: "nenhum canal com endereço", items: 0 };
}

/**
 * O feed público do canal. Sem parser de XML no Worker: contar `<entry>` diz
 * tudo o que precisa ser sabido — o feed respondeu e trouxe vídeo.
 */
async function probeYoutube(channelId) {
  const r = await fetch("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId);
  if (!r.ok) return { ok: false, detail: "HTTP " + r.status, items: 0 };
  const xml = await r.text();
  const n = (xml.match(/<entry>/g) || []).length;
  if (!n) return { ok: false, detail: "feed sem vídeo", items: 0 };
  return { ok: true, detail: n + " vídeos no feed", items: n };
}

/**
 * Grava quantos aparelhos estão assistindo neste minuto. É o que alimenta o
 * gráfico de audiência — e é escrito uma vez por minuto no total, nunca uma
 * vez por aparelho, senão o volume cresceria junto com o parque.
 */
async function sampleMinute(env, force) {
  const now = Math.floor(Date.now() / 1000);
  const minute = now - (now % 60);
  if (!force && minute === lastSampledMinute) return;
  lastSampledMinute = minute;

  try {
    const grace = (await configNumber(env, "heartbeat_seconds", 300)) * OFFLINE_GRACE;
    const row = await env.DB.prepare(
      "SELECT COUNT(*) AS n FROM devices WHERE last_playing > ?"
    ).bind(now - grace).first();
    await env.DB.prepare(
      "INSERT INTO audience_minute (ts, watching) VALUES (?, ?) ON CONFLICT(ts) DO UPDATE SET watching = excluded.watching"
    ).bind(minute, row ? row.n : 0).run();
  } catch (e) {
    // Amostragem perdida não pode derrubar a resposta que o aparelho espera.
  }
}

/* ------------------------------------------------------------------ */
/* Aparelho                                                            */
/* ------------------------------------------------------------------ */

/**
 * Primeira chamada de cada abertura. O aparelho se apresenta e recebe de volta
 * tudo que ele não deve trazer chumbado: a chave PIX da região onde está, o
 * intervalo de batida e o aviso que estiver no ar.
 */
async function hello(request, env) {
  const body = await readJson(request);
  const id = deviceId(body);
  if (!id) return json({ error: "device_id" }, 400, env);

  const now = Math.floor(Date.now() / 1000);
  const uf = regionOf(request);
  const country = (request.cf && request.cf.country) || null;

  await env.DB.prepare(`
    INSERT INTO devices (id, first_seen, last_seen, uf, country, version_code, version_name, model, android_sdk, sessions)
    VALUES (?1, ?2, ?2, ?3, ?4, ?5, ?6, ?7, ?8, 1)
    ON CONFLICT(id) DO UPDATE SET
      last_seen    = ?2,
      uf           = COALESCE(?3, devices.uf),
      country      = COALESCE(?4, devices.country),
      version_code = ?5,
      version_name = ?6,
      model        = ?7,
      android_sdk  = ?8,
      sessions     = devices.sessions + 1
  `).bind(
    id, now, uf, country,
    intOrNull(body.versionCode), strOrNull(body.versionName),
    strOrNull(body.model), intOrNull(body.androidSdk)
  ).run();

  const [pix, config, channels] = await Promise.all([
    pixFor(env, uf),
    configAll(env),
    env.DB.prepare(
      "SELECT id, name FROM youtube_channels WHERE enabled = 1 ORDER BY position, name"
    ).all()
  ]);

  // Tudo que a tela mostra sai daqui. O APK so guarda copia de reserva, para o
  // caso de a consulta falhar — nunca a fonte da verdade.
  return json({
    uf: uf || "BR",
    country: country || "BR",
    pixKey: pix.pix_key,
    pixLabel: pix.label,
    pixNational: config.pix_national || "pix@impd.org.br",
    channelName: config.channel_name || "IMPD TV",
    prayerPhone: config.prayer_phone || "",
    prayerPhone2: config.prayer_phone_2 || "",
    whatsapp: (config.whatsapp || "").trim(),
    bank: {
      name: config.bank_name || "",
      agency: config.bank_agency || "",
      account: config.bank_account || ""
    },
    channels: (channels.results || []).map(function(c){ return { id: c.id, name: c.name }; }),
    heartbeatSeconds: Number(config.heartbeat_seconds || 300),
    bannerSeconds: Number(config.banner_seconds || 6),
    notice: noticeNow(config, now),
    serverTime: now
  }, 200, env);
}

/**
 * Batida. Uma linha atualizada, nunca uma inserida — é o que segura o custo de
 * escrita constante por mais que o parque cresça.
 */
async function beat(request, env) {
  const body = await readJson(request);
  const id = deviceId(body);
  if (!id) return json({ error: "device_id" }, 400, env);

  const now = Math.floor(Date.now() / 1000);
  const playing = body.playing === true || body.playing === 1;
  // O aparelho manda quanto tempo passou desde a batida anterior; o servidor
  // nunca supõe, porque uma televisão pode ter ficado horas sem rede.
  const delta = clamp(intOrNull(body.screenSeconds) || 0, 0, 3600);

  await env.DB.prepare(`
    UPDATE devices
       SET last_seen      = ?2,
           last_playing   = CASE WHEN ?3 = 1 THEN ?2 ELSE last_playing END,
           screen_seconds = screen_seconds + ?4
     WHERE id = ?1
  `).bind(id, now, playing ? 1 : 0, delta).run();

  if (delta > 0) {
    const day = spDay(now);
    await env.DB.prepare(`
      INSERT INTO screen_day (day, device_id, seconds) VALUES (?1, ?2, ?3)
      ON CONFLICT(day, device_id) DO UPDATE SET seconds = screen_day.seconds + ?3
    `).bind(day, id, delta).run();
  }

  return new Response(null, { status: 204, headers: cors(env) });
}

/** Tecla de painel, vídeo aberto, queda, atualização. Raro por natureza. */
async function event(request, env) {
  const body = await readJson(request);
  const id = deviceId(body);
  const type = strOrNull(body.type);
  if (!id || !type || !EVENT_TYPES.has(type)) return json({ error: "event" }, 400, env);

  const now = Math.floor(Date.now() / 1000);
  const meta = body.meta == null ? null : String(body.meta).slice(0, 400);

  await env.DB.prepare(
    "INSERT INTO events (device_id, ts, type, uf, meta) VALUES (?, ?, ?, ?, ?)"
  ).bind(id, now, type, regionOf(request), meta).run();

  // Um vídeo aberto vira linha com título. Sem isto o painel mostraria hash de
  // onze caracteres e ninguém saberia qual pregação foi assistida.
  if (type === "video_open") {
    const v = parseMeta(meta);
    const vid = strOrNull(v.id);
    if (vid) {
      await env.DB.prepare(`
        INSERT INTO videos (id, title, channel, first_seen, last_opened, opens)
        VALUES (?1, ?2, ?3, ?4, ?4, 1)
        ON CONFLICT(id) DO UPDATE SET
          title       = COALESCE(?2, videos.title),
          channel     = COALESCE(?3, videos.channel),
          last_opened = ?4,
          opens       = videos.opens + 1
      `).bind(vid, strOrNull(v.title), strOrNull(v.channel), now).run();
    }
  }

  // A televisão avisando que não conseguiu ler uma fonte. Vale como sinal
  // independente do teste que o cron faz daqui.
  if (type === "source_fail") {
    const v = parseMeta(meta);
    const sid = strOrNull(v.source);
    if (sid) {
      await env.DB.prepare(`
        INSERT INTO sources (id, kind, label, device_fails, device_last_fail)
        VALUES (?1, ?2, ?3, 1, ?4)
        ON CONFLICT(id) DO UPDATE SET
          device_fails = sources.device_fails + 1,
          device_last_fail = ?4
      `).bind(sid, sid.indexOf("youtube:") === 0 ? "youtube" : "stream",
              strOrNull(v.label), now).run();
    }
  }

  return new Response(null, { status: 204, headers: cors(env) });
}

/* ------------------------------------------------------------------ */
/* Painel                                                              */
/* ------------------------------------------------------------------ */

async function stats(request, env, url) {
  if (!authorized(request, env)) return json({ error: "unauthorized" }, 401, env);

  const now = Math.floor(Date.now() / 1000);
  const grace = (await configNumber(env, "heartbeat_seconds", 300)) * OFFLINE_GRACE;
  const dayStart = spMidnight(now);
  const online = now - grace;

  const [live, today, hours, week, byUf, versions, panels, quiet, totals] = await env.DB.batch([
    env.DB.prepare("SELECT COUNT(*) AS watching FROM devices WHERE last_playing > ?").bind(online),

    env.DB.prepare(
      "SELECT MAX(watching) AS peak, (SELECT ts FROM audience_minute WHERE ts >= ?1 ORDER BY watching DESC, ts ASC LIMIT 1) AS peak_ts FROM audience_minute WHERE ts >= ?1"
    ).bind(dayStart),

    // Uma linha por hora do dia, com a média dos minutos daquela hora.
    env.DB.prepare(`
      SELECT CAST((ts - ?1) / 3600 AS INTEGER) AS hour,
             CAST(ROUND(AVG(watching)) AS INTEGER) AS watching
        FROM audience_minute
       WHERE ts >= ?1
       GROUP BY hour
       ORDER BY hour
    `).bind(dayStart),

    // Sete dias de tela ligada, em média por aparelho que reportou naquele dia.
    env.DB.prepare(`
      SELECT day,
             CAST(ROUND(AVG(seconds)) AS INTEGER) AS avg_seconds,
             COUNT(*) AS devices
        FROM screen_day
       WHERE day >= date('now', '-7 days')
       GROUP BY day
       ORDER BY day
    `),

    // Só o que está no Brasil entra no mapa; o resto tem lugar próprio.
    env.DB.prepare(`
      SELECT uf,
             COUNT(*) AS devices,
             SUM(CASE WHEN last_playing > ?1 THEN 1 ELSE 0 END) AS watching,
             CAST(ROUND(AVG(screen_seconds)) AS INTEGER) AS avg_screen
        FROM devices
       WHERE country = 'BR' AND uf IS NOT NULL
       GROUP BY uf
       ORDER BY devices DESC
    `).bind(online),

    env.DB.prepare(
      "SELECT version_name, version_code, COUNT(*) AS devices FROM devices GROUP BY version_code, version_name ORDER BY version_code DESC"
    ),

    env.DB.prepare(
      "SELECT type, COUNT(*) AS n FROM events WHERE ts >= ? GROUP BY type"
    ).bind(dayStart),

    // Televisão muda: cadastrada, mas sem dar sinal há mais de dois dias.
    env.DB.prepare(`
      SELECT id, uf, model, version_name, last_seen
        FROM devices
       WHERE last_seen < ?1
       ORDER BY last_seen ASC
       LIMIT 50
    `).bind(now - 2 * 86400),

    env.DB.prepare(
      "SELECT COUNT(*) AS devices, SUM(sessions) AS sessions FROM devices"
    )
  ]);

  const [abroad, semUf, videos, sources] = await env.DB.batch([
    // Aparelhos fora do Brasil, agrupados por país.
    env.DB.prepare(`
      SELECT COALESCE(country, '??') AS country,
             COUNT(*) AS devices,
             SUM(CASE WHEN last_playing > ?1 THEN 1 ELSE 0 END) AS watching
        FROM devices
       WHERE country IS NULL OR country <> 'BR'
       GROUP BY COALESCE(country, '??')
       ORDER BY devices DESC
    `).bind(online),

    // No Brasil mas sem UF resolvida — não some do total por causa disso.
    env.DB.prepare(
      "SELECT COUNT(*) AS n FROM devices WHERE country = 'BR' AND uf IS NULL"
    ),

    env.DB.prepare(`
      SELECT id, title, channel, opens, last_opened
        FROM videos
       ORDER BY opens DESC, last_opened DESC
       LIMIT 25
    `),

    env.DB.prepare(
      "SELECT id, kind, label, checked_at, ok, last_ok, last_fail, detail, items, ok_count, fail_count, device_fails, device_last_fail FROM sources ORDER BY kind, id"
    )
  ]);

  const sessionsToday = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM devices WHERE last_seen >= ?"
  ).bind(dayStart).first();

  return json({
    generatedAt: now,
    watchingNow: first(live).watching || 0,
    peakToday: first(today).peak || 0,
    peakAt: first(today).peak_ts || null,
    devicesTotal: first(totals).devices || 0,
    devicesActiveToday: sessionsToday ? sessionsToday.n : 0,
    sessionsTotal: first(totals).sessions || 0,
    dayStart,
    hours: hours.results || [],
    week: week.results || [],
    byUf: byUf.results || [],
    versions: versions.results || [],
    panels: panels.results || [],
    quiet: quiet.results || [],
    abroad: abroad.results || [],
    withoutUf: first(semUf).n || 0,
    videos: videos.results || [],
    sources: sources.results || []
  }, 200, env);
}

/** Cadastro das chaves PIX por gerência, sem publicar aplicativo novo. */
async function pixAdmin(request, env) {
  if (!authorized(request, env)) return json({ error: "unauthorized" }, 401, env);

  if (request.method === "GET") {
    const rows = await env.DB.prepare("SELECT uf, pix_key, label FROM pix_keys ORDER BY uf").all();
    return json({ keys: rows.results || [] }, 200, env);
  }

  if (request.method === "PUT") {
    const body = await readJson(request);
    const uf = String(body.uf || "").toUpperCase().slice(0, 2);
    const key = strOrNull(body.pixKey);
    if (!/^[A-Z]{2}$/.test(uf) || !key) return json({ error: "uf_or_key" }, 400, env);
    await env.DB.prepare(
      "INSERT INTO pix_keys (uf, pix_key, label) VALUES (?1, ?2, ?3) ON CONFLICT(uf) DO UPDATE SET pix_key = ?2, label = ?3"
    ).bind(uf, key, strOrNull(body.label)).run();
    return json({ ok: true }, 200, env);
  }

  if (request.method === "DELETE") {
    const uf = String(new URL(request.url).searchParams.get("uf") || "").toUpperCase();
    // A nacional e o ultimo recurso de toda tela de doacao: apagar ela deixaria
    // aparelho sem chave nenhuma para mostrar.
    if (uf === "BR") return json({ error: "nacional_nao_pode_sair" }, 400, env);
    if (!/^[A-Z]{2}$/.test(uf)) return json({ error: "uf" }, 400, env);
    await env.DB.prepare("DELETE FROM pix_keys WHERE uf = ?").bind(uf).run();
    return json({ ok: true }, 200, env);
  }

  return json({ error: "method" }, 405, env);
}

/** Tudo que o editor do painel precisa, numa chamada só. */
async function adminState(request, env) {
  if (!authorized(request, env)) return json({ error: "unauthorized" }, 401, env);
  const [cfg, pix, chans] = await env.DB.batch([
    env.DB.prepare("SELECT k, v FROM config ORDER BY k"),
    env.DB.prepare("SELECT uf, pix_key, label FROM pix_keys ORDER BY uf"),
    env.DB.prepare("SELECT id, name, enabled, position FROM youtube_channels ORDER BY position, name")
  ]);
  return json({
    config: cfg.results || [],
    pix: pix.results || [],
    channels: chans.results || []
  }, 200, env);
}

/**
 * Só as chaves conhecidas entram. Um painel que aceita qualquer chave vira
 * depósito de lixo, e o aplicativo ignoraria em silêncio o que não sabe ler.
 */
const CONFIG_KEYS = new Set([
  "heartbeat_seconds", "banner_seconds", "notice_text", "notice_until",
  "channel_name", "prayer_phone", "prayer_phone_2", "whatsapp",
  "pix_national", "bank_name", "bank_agency", "bank_account"
]);

async function adminConfig(request, env) {
  if (!authorized(request, env)) return json({ error: "unauthorized" }, 401, env);
  if (request.method !== "PUT") return json({ error: "method" }, 405, env);

  const body = await readJson(request);
  const entries = Array.isArray(body.entries) ? body.entries : [body];
  const writes = [];

  for (const e of entries) {
    const k = String(e.k || "");
    if (!CONFIG_KEYS.has(k)) return json({ error: "chave_desconhecida", k }, 400, env);
    writes.push(env.DB.prepare(
      "INSERT INTO config (k, v) VALUES (?1, ?2) ON CONFLICT(k) DO UPDATE SET v = ?2"
    ).bind(k, String(e.v == null ? "" : e.v).slice(0, 500)));
  }
  if (!writes.length) return json({ error: "vazio" }, 400, env);
  await env.DB.batch(writes);
  return json({ ok: true, saved: writes.length }, 200, env);
}

/** Fontes de vídeo do YouTube: incluir, renomear, ligar, desligar, remover. */
async function adminChannel(request, env, url) {
  if (!authorized(request, env)) return json({ error: "unauthorized" }, 401, env);

  if (request.method === "GET") {
    const rows = await env.DB.prepare(
      "SELECT id, name, enabled, position FROM youtube_channels ORDER BY position, name"
    ).all();
    return json({ channels: rows.results || [] }, 200, env);
  }

  if (request.method === "PUT") {
    const body = await readJson(request);
    const id = String(body.id || "").trim();
    // Um id de canal do YouTube começa com UC e tem 24 caracteres. Aceitar
    // qualquer coisa aqui põe a fileira de vídeos atrás de um feed que não
    // existe, e a televisão mostra "não foi possível carregar" sem motivo.
    if (!/^UC[A-Za-z0-9_-]{22}$/.test(id)) return json({ error: "id_invalido" }, 400, env);
    const name = strOrNull(body.name);
    if (!name) return json({ error: "nome_obrigatorio" }, 400, env);
    await env.DB.prepare(`
      INSERT INTO youtube_channels (id, name, enabled, position, added_at)
      VALUES (?1, ?2, ?3, ?4, ?5)
      ON CONFLICT(id) DO UPDATE SET name = ?2, enabled = ?3, position = ?4
    `).bind(id, name, body.enabled === false ? 0 : 1,
            intOrNull(body.position) || 0, Math.floor(Date.now() / 1000)).run();
    return json({ ok: true }, 200, env);
  }

  if (request.method === "DELETE") {
    const id = String(url.searchParams.get("id") || "").trim();
    if (!id) return json({ error: "id" }, 400, env);
    await env.DB.batch([
      env.DB.prepare("DELETE FROM youtube_channels WHERE id = ?").bind(id),
      env.DB.prepare("DELETE FROM sources WHERE id = ?").bind("youtube:" + id)
    ]);
    return json({ ok: true }, 200, env);
  }

  return json({ error: "method" }, 405, env);
}
/**
 * A fileira de vídeos da televisão.
 *
 * Antes cada aparelho buscava o feed RSS do YouTube por conta própria. Esse
 * endpoint saiu do ar — responde 404 até para o canal oficial do YouTube — e
 * com ele a fileira parou em todas as salas. A busca passou para cá, o que
 * conserta o problema no lugar certo: mil televisões deixam de bater no
 * YouTube mil vezes, a chave de API nunca entra no APK, e a próxima vez que o
 * YouTube mudar de ideia o conserto é um deploy daqui.
 *
 * Com YOUTUBE_API_KEY configurada usa a API oficial, que é estável e
 * documentada. Sem ela devolve o cache — e, não havendo cache, uma lista vazia
 * com o motivo escrito, que a televisão já sabe mostrar como "não foi possível
 * carregar".
 */
const VIDEO_TTL = 1800; // meia hora: a fileira não precisa ser ao vivo

async function videos(request, env) {
  const now = Math.floor(Date.now() / 1000);
  const canais = await env.DB.prepare(
    "SELECT id, name FROM youtube_channels WHERE enabled = 1 ORDER BY position, name"
  ).all();

  const saida = [];
  let motivo = null;

  for (const c of (canais.results || [])) {
    const cache = await env.DB.prepare(
      "SELECT payload, fetched_at, ok, detail FROM video_cache WHERE channel_id = ?"
    ).bind(c.id).first();

    if (cache && now - cache.fetched_at < VIDEO_TTL && cache.ok) {
      saida.push.apply(saida, JSON.parse(cache.payload));
      continue;
    }

    let lista = null, detalhe = null;
    if (env.YOUTUBE_API_KEY) {
      try {
        lista = await buscarNoYoutube(c, env.YOUTUBE_API_KEY);
      } catch (e) {
        detalhe = String(e && e.message || e).slice(0, 120);
      }
    } else {
      detalhe = "sem chave de API configurada";
    }

    if (lista) {
      await env.DB.prepare(`
        INSERT INTO video_cache (channel_id, payload, fetched_at, ok, detail)
        VALUES (?1, ?2, ?3, 1, NULL)
        ON CONFLICT(channel_id) DO UPDATE SET payload = ?2, fetched_at = ?3, ok = 1, detail = NULL
      `).bind(c.id, JSON.stringify(lista), now).run();
      saida.push.apply(saida, lista);
    } else {
      motivo = motivo || detalhe;
      // Cache velho é melhor que fileira vazia: a pregação de ontem continua
      // valendo, e o fiel não vê uma tira preta onde havia vídeos.
      if (cache && cache.payload) saida.push.apply(saida, JSON.parse(cache.payload));
      await env.DB.prepare(`
        INSERT INTO video_cache (channel_id, payload, fetched_at, ok, detail)
        VALUES (?1, ?2, ?3, 0, ?4)
        ON CONFLICT(channel_id) DO UPDATE SET fetched_at = ?3, ok = 0, detail = ?4
      `).bind(c.id, cache ? cache.payload : "[]", now, detalhe).run();
    }
  }

  // Uma transmissão publicada nos dois canais viria duas vezes.
  const vistos = new Set();
  const unicos = saida.filter(v => !vistos.has(v.id) && vistos.add(v.id));
  unicos.sort((a, b) => (b.published || "").localeCompare(a.published || ""));

  return json({ videos: unicos, count: unicos.length, reason: motivo, cachedFor: VIDEO_TTL }, 200, env);
}

/**
 * A lista de envios de um canal é a playlist "UU" + o id sem o "UC" — e ler
 * playlist custa 1 unidade de quota, contra 100 de uma busca. Com meia hora de
 * cache, o parque inteiro cabe de sobra na cota gratuita.
 */
async function buscarNoYoutube(canal, key) {
  const uploads = "UU" + canal.id.slice(2);
  const url = "https://www.googleapis.com/youtube/v3/playlistItems" +
    "?part=snippet&maxResults=20&playlistId=" + uploads + "&key=" + key;

  const r = await fetch(url);
  if (!r.ok) {
    const erro = await r.json().catch(() => ({}));
    throw new Error("HTTP " + r.status + (erro.error ? " " + erro.error.message : ""));
  }
  const body = await r.json();

  return (body.items || []).map(item => {
    const s = item.snippet || {};
    const id = s.resourceId && s.resourceId.videoId;
    if (!id) return null;
    return {
      id: id,
      title: s.title || "",
      // hqdefault e nao maxresdefault: o segundo devolve 404 em video que
      // nunca foi enviado em alta, e a televisao mostra bloco em branco.
      thumbnailUrl: "https://img.youtube.com/vi/" + id + "/hqdefault.jpg",
      published: s.publishedAt || "",
      channel: canal.name,
      dateLabel: rotuloData(s.publishedAt)
    };
  }).filter(Boolean);
}

const MESES = ["jan","fev","mar","abr","mai","jun","jul","ago","set","out","nov","dez"];

/** "16 ago", pronto — a televisão não formata data enquanto a fileira rola. */
function rotuloData(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  if (isNaN(d)) return "";
  return d.getUTCDate() + " " + MESES[d.getUTCMonth()];
}
/* ------------------------------------------------------------------ */
/* Apoio                                                               */
/* ------------------------------------------------------------------ */

function first(result) {
  return (result && result.results && result.results[0]) || {};
}

/** A borda entrega "SP", "RS"… Fora do Brasil não há gerência para escolher. */
function regionOf(request) {
  const cf = request.cf || {};
  if (cf.country !== "BR") return null;
  const code = cf.regionCode;
  return /^[A-Z]{2}$/.test(String(code || "")) ? code : null;
}

async function pixFor(env, uf) {
  if (uf) {
    const row = await env.DB.prepare("SELECT pix_key, label FROM pix_keys WHERE uf = ?").bind(uf).first();
    if (row) return row;
  }
  const national = await env.DB.prepare("SELECT pix_key, label FROM pix_keys WHERE uf = 'BR'").first();
  return national || { pix_key: "pix@impd.org.br", label: "Nacional" };
}

async function configAll(env) {
  const rows = await env.DB.prepare("SELECT k, v FROM config").all();
  const out = {};
  for (const r of rows.results || []) out[r.k] = r.v;
  return out;
}

async function configNumber(env, key, fallback) {
  const row = await env.DB.prepare("SELECT v FROM config WHERE k = ?").bind(key).first();
  const n = row ? Number(row.v) : NaN;
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

function noticeNow(config, now) {
  const text = (config.notice_text || "").trim();
  const until = Number(config.notice_until || 0);
  return text && until > now ? text : null;
}

function authorized(request, env) {
  const header = request.headers.get("authorization") || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : "";
  return Boolean(env.DASHBOARD_TOKEN) && token === env.DASHBOARD_TOKEN;
}

async function readJson(request) {
  try { return await request.json(); } catch (e) { return {}; }
}

/** Aceita só o formato que o aparelho gera: UUID v4 em minúsculas. */
function deviceId(body) {
  const id = String((body && body.deviceId) || "");
  return /^[0-9a-f-]{36}$/.test(id) ? id : null;
}

/** O meta de alguns eventos é JSON; de outros é texto solto. Nunca deve explodir. */
function parseMeta(meta) {
  if (!meta) return {};
  try {
    const v = JSON.parse(meta);
    return v && typeof v === "object" ? v : {};
  } catch (e) {
    return {};
  }
}

const strOrNull = v => (v == null || v === "" ? null : String(v).slice(0, 200));
const intOrNull = v => (Number.isFinite(Number(v)) ? Math.trunc(Number(v)) : null);
const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n));

/**
 * O fuso da igreja é o de São Paulo, e desde 2019 o Brasil não tem horário de
 * verão — a diferença é fixa em três horas, então não é preciso biblioteca.
 */
const SP_OFFSET = -3 * 3600;
const spDay = ts => new Date((ts + SP_OFFSET) * 1000).toISOString().slice(0, 10);
const spMidnight = ts => {
  const local = ts + SP_OFFSET;
  return local - (local % 86400) - SP_OFFSET;
};

function cors(env) {
  return {
    "access-control-allow-origin": env.DASHBOARD_ORIGIN || "*",
    "access-control-allow-headers": "content-type, authorization",
    "access-control-allow-methods": "GET, POST, PUT, OPTIONS"
  };
}

function preflight(env) {
  return new Response(null, { status: 204, headers: cors(env) });
}

function json(body, status, env) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...JSON_HEADERS, ...cors(env) }
  });
}
