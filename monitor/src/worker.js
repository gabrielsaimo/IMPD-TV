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
  "update_offered", "update_started", "update_done"
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
        case "/v1/stats": return await stats(request, env, url);
        case "/v1/pix":   return await pixAdmin(request, env);
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

  const [pix, config] = await Promise.all([pixFor(env, uf), configAll(env)]);

  return json({
    uf: uf || "BR",
    pixKey: pix.pix_key,
    pixLabel: pix.label,
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

  await env.DB.prepare(
    "INSERT INTO events (device_id, ts, type, uf, meta) VALUES (?, ?, ?, ?, ?)"
  ).bind(
    id, Math.floor(Date.now() / 1000), type, regionOf(request),
    body.meta == null ? null : String(body.meta).slice(0, 200)
  ).run();

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

    env.DB.prepare(`
      SELECT COALESCE(uf, 'BR') AS uf,
             COUNT(*) AS devices,
             SUM(CASE WHEN last_playing > ?1 THEN 1 ELSE 0 END) AS watching
        FROM devices
       GROUP BY COALESCE(uf, 'BR')
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
    quiet: quiet.results || []
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

  return json({ error: "method" }, 405, env);
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

const strOrNull = v => (v == null || v === "" ? null : String(v).slice(0, 120));
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
