package br.com.impd.tv

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * O que a igreja consegue ver das televisões, e o que as televisões recebem
 * de volta.
 *
 * Duas regras mandam neste arquivo:
 *
 * 1. **Nada aqui pode atrapalhar a transmissão.** Toda chamada roda em linha
 *    separada, toda falha é engolida e nenhum retorno é esperado. Uma
 *    televisão sem rede continua se comportando exatamente como antes: o
 *    canal é a razão do app existir, o monitoramento não é.
 *
 * 2. **Não se identifica ninguém.** O aparelho sorteia um UUID na primeira
 *    abertura e guarda em disco. Não é o identificador do aparelho, não é
 *    conta, não é publicidade — é um número que só existe dentro deste app e
 *    que some junto com ele. A UF vem resolvida do outro lado, pelo IP da
 *    conexão, e nunca por localização pedida ao sistema.
 *
 * O [hello] devolve o que a tela não deve trazer chumbada no APK: a chave PIX
 * da gerência onde o aparelho está, o intervalo de batida e o aviso que
 * estiver no ar. Mudar qualquer um deles é mexer no painel, não publicar
 * versão nova.
 */
object Telemetry {

    /**
     * Endpoint do monitoramento. Vazio desliga tudo — é assim que uma build
     * de teste não polui a contagem do parque de verdade.
     */
    private const val BASE_URL = "https://impd-tv-monitor.gabrielsaimo68.workers.dev/v1"

    private const val PREFS = "impdtv"
    private const val KEY_DEVICE_ID = "device_id"

    private val handler = Handler(Looper.getMainLooper())

    /** Configuração vinda do painel; até o primeiro [hello] valem os padrões. */
    @Volatile var heartbeatSeconds: Int = 300; private set
    @Volatile var regionalPixKey: String? = null; private set
    @Volatile var nationalPixKey: String? = null; private set
    @Volatile var uf: String? = null; private set
    @Volatile var country: String? = null; private set
    @Volatile var notice: String? = null; private set
    @Volatile var prayerPhone: String? = null; private set
    @Volatile var prayerPhone2: String? = null; private set
    @Volatile var whatsapp: String? = null; private set

    /**
     * As fontes de vídeo da fileira de baixo. Vazio significa "o painel ainda
     * não respondeu" — e aí vale a lista de reserva do [YoutubeFetcher], nunca
     * uma fileira vazia na televisão.
     */
    @Volatile var channels: List<Pair<String, String>> = emptyList(); private set

    private var deviceId: String? = null
    private var playing = false
    private var startedAt = 0L
    private var reportedUpTo = 0L
    private var beating = false

    private val beat = object : Runnable {
        override fun run() {
            sendBeat()
            handler.postDelayed(this, heartbeatSeconds * 1000L)
        }
    }

    private fun enabled() = BASE_URL.isNotBlank()

    /** Onde o painel mora. Vazio significa monitoramento desligado. */
    fun baseUrl(): String = BASE_URL

    /**
     * O identificador é sorteado uma vez e guardado. Não se usa ANDROID_ID nem
     * nada preso ao aparelho: aquilo segue a pessoa entre aplicativos, isto
     * não sai daqui.
     */
    private fun deviceId(context: Context): String {
        deviceId?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        deviceId = id
        return id
    }

    /**
     * Uma vez por abertura. [onConfig] chega na linha principal, já com a
     * chave regional pronta para a gaveta de doação redesenhar o QR.
     */
    fun hello(context: Context, onConfig: (() -> Unit)? = null) {
        if (!enabled()) return
        val app = context.applicationContext
        val id = deviceId(app)

        Thread {
            val body = JSONObject().apply {
                put("deviceId", id)
                put("versionCode", versionCode(app))
                put("versionName", versionName(app))
                put("model", "${Build.MANUFACTURER} ${Build.MODEL}".take(80))
                put("androidSdk", Build.VERSION.SDK_INT)
            }
            val answer = post("$BASE_URL/hello", body, readBack = true)
            if (answer != null) {
                heartbeatSeconds = answer.optInt("heartbeatSeconds", heartbeatSeconds)
                    .coerceIn(60, 3600)
                regionalPixKey = answer.text("pixKey")
                nationalPixKey = answer.text("pixNational")
                uf = answer.text("uf")
                country = answer.text("country")
                notice = answer.text("notice")
                prayerPhone = answer.text("prayerPhone")
                prayerPhone2 = answer.text("prayerPhone2")
                whatsapp = answer.text("whatsapp")

                // Uma lista vazia nunca substitui a de reserva: painel fora do
                // ar não pode apagar a fileira de vídeos da televisão.
                val lista = mutableListOf<Pair<String, String>>()
                val arr = answer.optJSONArray("channels")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val id = c.optString("id", "")
                        val nome = c.optString("name", "")
                        if (id.isNotBlank() && nome.isNotBlank()) lista.add(id to nome)
                    }
                }
                if (lista.isNotEmpty()) channels = lista

                onConfig?.let { handler.post(it) }
            }
        }.start()
    }

    /** Chamado do onResume: começa a bater enquanto a televisão estiver acordada. */
    fun start(context: Context) {
        if (!enabled()) return
        deviceId(context)
        if (beating) return
        beating = true
        handler.postDelayed(beat, heartbeatSeconds * 1000L)
    }

    /** Chamado do onPause: fecha a conta do tempo de tela e para de bater. */
    fun stop() {
        if (!beating) return
        beating = false
        handler.removeCallbacks(beat)
        sendBeat()
        playing = false
        startedAt = 0L
        reportedUpTo = 0L
    }

    /** O player entrou em STATE_READY: daqui para a frente conta como tela ligada. */
    fun playbackStarted() {
        if (playing) return
        playing = true
        startedAt = System.currentTimeMillis()
        reportedUpTo = startedAt
    }

    /** Queda, pausa ou saída: o relógio de tela ligada para. */
    fun playbackStopped() {
        playing = false
    }

    /**
     * Painel aberto, vídeo escolhido, queda recuperada. Só o que é raro:
     * batida não é evento, ou a tabela cresceria junto com o parque.
     */
    fun event(type: String, meta: String? = null) {
        if (!enabled()) return
        val id = deviceId ?: return
        Thread {
            val body = JSONObject().apply {
                put("deviceId", id)
                put("type", type)
                if (meta != null) put("meta", meta)
            }
            post("$BASE_URL/event", body, readBack = false)
        }.start()
    }

    /**
     * Um vídeo escolhido na fileira. Vai com título e canal junto: sem isso o
     * painel mostraria um código de onze caracteres e ninguém saberia qual
     * pregação foi assistida.
     */
    fun videoOpened(id: String, title: String, channel: String) {
        event("video_open", JSONObject().apply {
            put("id", id)
            put("title", title.take(160))
            put("channel", channel.take(80))
        }.toString())
    }

    /**
     * A televisão avisando que não conseguiu ler uma fonte. Vale como sinal
     * separado do teste que o painel faz por conta própria: uma fonte pode
     * estar de pé e mesmo assim não abrir na casa do fiel, e as duas
     * informações juntas dizem de que lado está o problema.
     */
    fun sourceFailed(source: String, label: String?) {
        event("source_fail", JSONObject().apply {
            put("source", source)
            if (label != null) put("label", label.take(80))
        }.toString())
    }

    /**
     * Manda quanto tempo de tela passou desde a batida anterior. O aparelho é
     * quem sabe: o servidor não pode supor, porque uma televisão pode ficar
     * horas sem rede e voltar com uma batida só.
     */
    private fun sendBeat() {
        val id = deviceId ?: return
        val now = System.currentTimeMillis()

        val elapsed = if (playing && reportedUpTo > 0L) {
            ((now - reportedUpTo) / 1000L).coerceIn(0L, 3600L)
        } else {
            0L
        }
        if (playing) reportedUpTo = now

        val wasPlaying = playing
        Thread {
            val body = JSONObject().apply {
                put("deviceId", id)
                put("playing", wasPlaying)
                put("screenSeconds", elapsed)
            }
            post("$BASE_URL/beat", body, readBack = false)
        }.start()
    }


    /**
     * Texto de um campo, ou nulo.
     *
     * Não dá para usar `optString` aqui: quando o JSON traz `null` de verdade,
     * o `optString` do Android devolve a **string** "null", com quatro letras,
     * em vez do padrão. O aviso da igreja vem nulo na maior parte do tempo, e
     * sem esta checagem a televisão mostraria uma tarja escrita "null" no
     * meio do culto.
     */
    private fun JSONObject.text(nome: String): String? {
        if (isNull(nome)) return null
        return optString(nome, "").takeIf { it.isNotBlank() }
    }

    private fun post(url: String, body: JSONObject, readBack: Boolean): JSONObject? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            }
            OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(body.toString()) }

            val code = connection.responseCode
            if (readBack && code in 200..299) {
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } else {
                null
            }
        } catch (e: Exception) {
            // De propósito: monitoramento que derruba televisão não serve.
            null
        } finally {
            connection?.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun versionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else info.versionCode.toLong()
    } catch (e: Exception) {
        0L
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }
}
