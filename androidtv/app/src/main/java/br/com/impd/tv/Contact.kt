package br.com.impd.tv

/**
 * How a viewer reaches the church.
 *
 * impd.org.br publishes no WhatsApp number: its prayer request page is a web
 * form posted to the platform API, and the only per-event WhatsApp field comes
 * back empty. What the church does publish, through the same API the site uses
 * (`/api/v1/tertiary_group/33296/`), are the two Central de Oração lines at the
 * head office in Brás — so those are what the panel shows.
 *
 * If the church later runs an official WhatsApp line, put it in [WHATSAPP] and
 * the panel switches to it on its own. Leave it null otherwise: a wrong number
 * on a television sends an elderly viewer to a stranger.
 */
object Contact {
    const val PRAYER_PHONE = "+551135773800"
    const val PRAYER_PHONE_2 = "+551134883050"

    /** Set to something like "+5511999998888" only once the church confirms it. */
    val WHATSAPP: String? = null

    const val PRAYER_MESSAGE = "Olá, gostaria de fazer um pedido de oração"

    /**
     * Chaves PIX da igreja, no mesmo lugar que o resto dos dados de contato.
     *
     * Estavam escritas dentro da MainActivity, no meio da montagem de tela, e
     * uma delas não conferia com o que a igreja usa. Errar isto manda oferta de
     * fiel para a conta errada, então moram aqui, num arquivo só de contato,
     * onde dá para achar e conferir sem ler layout nem tela.
     *
     * A gaveta mostra um QR por chave, na ordem desta lista.
     */
    val PIX_KEYS = listOf(
        "pix@impd.org.br",
        "pixrs@impd.org.br"
    )

    /*
     * Daqui para baixo: o painel manda, o APK guarda cópia de reserva.
     *
     * Trocar a Central de Oração ou uma chave PIX era publicar aplicativo novo
     * e esperar milhares de televisões atualizarem. Agora é uma linha no
     * painel. As constantes acima continuam valendo para o primeiro instante
     * depois de ligar e para o dia em que o painel não responder — um número
     * desatualizado ainda atende; um campo vazio na televisão, não.
     */

    fun prayerPhone(): String = Telemetry.prayerPhone ?: PRAYER_PHONE

    fun prayerPhone2(): String = Telemetry.prayerPhone2 ?: PRAYER_PHONE_2

    fun whatsapp(): String? = Telemetry.whatsapp ?: WHATSAPP

    /**
     * As chaves que a gaveta mostra, na ordem: a da gerência onde o aparelho
     * está primeiro, a nacional ao lado. Sem resposta do painel, valem as duas
     * gravadas aqui.
     */
    fun pixKeys(): List<String> {
        val nacional = Telemetry.nationalPixKey ?: PIX_KEYS.firstOrNull() ?: return PIX_KEYS
        val regional = Telemetry.regionalPixKey
        return when {
            regional == null -> PIX_KEYS
            regional == nacional -> listOf(nacional)
            else -> listOf(regional, nacional)
        }
    }

    /** "+551135773800" reads as "(11) 3577-3800" on screen. */
    fun formatBr(raw: String): String {
        val digits = raw.filter { it.isDigit() }.removePrefix("55")
        if (digits.length < 10) return raw
        val area = digits.substring(0, 2)
        val rest = digits.substring(2)
        val split = rest.length - 4
        return "($area) ${rest.substring(0, split)}-${rest.substring(split)}"
    }

    /**
     * What a phone camera should open: a WhatsApp conversation when there is a
     * number for it, otherwise a dial-ready link to the Central de Oração.
     */
    fun prayerQrPayload(): String {
        val whatsapp = whatsapp()
        return if (whatsapp != null) {
            val digits = whatsapp.filter { it.isDigit() }
            val text = java.net.URLEncoder.encode(PRAYER_MESSAGE, "UTF-8")
            "https://wa.me/$digits?text=$text"
        } else {
            "tel:${prayerPhone()}"
        }
    }
}
