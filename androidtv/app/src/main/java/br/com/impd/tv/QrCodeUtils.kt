package br.com.impd.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

object QrCodeUtils {

    /**
     * Gera o CRC16 (CCITT-FALSE) para o payload do Pix.
     */
    private fun getCRC16(payload: String): String {
        var crc = 0xFFFF
        for (i in payload.indices) {
            crc = crc xor (payload[i].code shl 8)
            for (j in 0 until 8) {
                if ((crc and 0x8000) != 0) {
                    crc = (crc shl 1) xor 0x1021
                } else {
                    crc = crc shl 1
                }
            }
        }
        crc = crc and 0xFFFF
        return String.format("%04X", crc)
    }

    /**
     * Constrói o payload estático do BR Code para uma chave Pix (email).
     */
    fun generatePixPayload(pixKey: String, name: String = "IMPD", city: String = "SAO PAULO"): String {
        val payloadFormat = "000201"
        
        val gui = "0014br.gov.bcb.pix"
        val key = "01${String.format("%02d", pixKey.length)}$pixKey"
        val merchantAccountInfo = "26${String.format("%02d", gui.length + key.length)}$gui$key"
        
        val merchantCategoryCode = "52040000"
        val transactionCurrency = "5303986"
        val countryCode = "5802BR"
        val merchantName = "59${String.format("%02d", name.length)}$name"
        val merchantCity = "60${String.format("%02d", city.length)}$city"
        
        val txid = "0503***"
        val additionalData = "62${String.format("%02d", txid.length)}$txid"
        
        val payloadBeforeCrc = payloadFormat + merchantAccountInfo + merchantCategoryCode + transactionCurrency + countryCode + merchantName + merchantCity + additionalData + "6304"
        
        return payloadBeforeCrc + getCRC16(payloadBeforeCrc)
    }

    /**
     * Gera um Bitmap de QR Code a partir de um texto, adicionando um ícone no centro.
     */
    fun generateQrCodeWithIcon(context: Context, text: String, width: Int, height: Int, @DrawableRes iconResId: Int): Bitmap? {
        try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.MARGIN] = 1
            // Usar alta correção de erro para garantir que o QR Code seja legível com o ícone no meio
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H

            val bitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height, hints)

            // Filled row by row via setPixels: a per-pixel setPixel loop is ~90k
            // JNI calls per code, which is visible as a stall on weak TV boxes.
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] =
                        if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
            }
            val qrBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            qrBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            val canvas = Canvas(qrBitmap)
            val iconDrawable = ContextCompat.getDrawable(context, iconResId) ?: return qrBitmap
            
            // Desenha o ícone no centro (20% do tamanho total)
            val iconSize = (width * 0.2f).toInt()
            val left = (width - iconSize) / 2
            val top = (height - iconSize) / 2
            
            // Fundo branco para o ícone
            val paint = Paint().apply { color = android.graphics.Color.WHITE }
            canvas.drawRect(left.toFloat(), top.toFloat(), (left + iconSize).toFloat(), (top + iconSize).toFloat(), paint)
            
            iconDrawable.setBounds(left, top, left + iconSize, top + iconSize)
            iconDrawable.draw(canvas)
            
            return qrBitmap
        } catch (e: WriterException) {
            e.printStackTrace()
            return null
        }
    }
}
