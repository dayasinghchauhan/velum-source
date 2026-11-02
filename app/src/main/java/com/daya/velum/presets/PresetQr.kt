package com.daya.velum.presets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import com.daya.velum.imaging.LookParams
import com.daya.velum.imaging.LookRepository
import com.daya.velum.settings.CameraSettings
import com.daya.velum.settings.DataStoreSettingsRepository
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Presets as QR codes. Payload: "VELUM1:" + base64url(deflate(JSON)) where JSON holds the preset name, its settings
 * and, when it uses a custom look, that look's parameters (an imported .cube file cannot travel in a QR code).
 */
object PresetQr {
    private const val PREFIX = "VELUM1:"

    data class Decoded(val name: String, val settings: CameraSettings, val lookName: String?, val lookParams: LookParams?, val lookId: String?)

    fun encode(name: String, settings: CameraSettings, customLookName: String?, customLookId: String?, customLook: LookParams?): String {
        val j = JSONObject().put("n", name).put("s", DataStoreSettingsRepository.toJson(settings))
        if (customLook != null) j.put("look", JSONObject().put("id", customLookId).put("name", customLookName).put("p", LookRepository.paramsTo(customLook)))
        val raw = j.toString().toByteArray(Charsets.UTF_8)
        val def = Deflater(Deflater.BEST_COMPRESSION).apply { setInput(raw); finish() }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) out.write(buf, 0, def.deflate(buf))
        def.end()
        return PREFIX + Base64.encodeToString(out.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decode(text: String): Decoded {
        require(text.startsWith(PREFIX)) { "This QR code is not a Velum preset." }
        val bytes = Base64.decode(text.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val inf = Inflater().apply { setInput(bytes) }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            require(out.size() < 256 * 1024) { "Preset data too large." }
        }
        inf.end()
        val j = JSONObject(out.toString(Charsets.UTF_8.name()))
        val look = j.optJSONObject("look")
        return Decoded(j.optString("n", "Imported preset").take(40), DataStoreSettingsRepository.fromJson(j.getJSONObject("s")),
            look?.optString("name"), look?.optJSONObject("p")?.let { LookRepository.paramsFrom(it) }, look?.optString("id"))
    }

    fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /** A shareable card: preset name, a one-line summary and the code, on a quiet dark background. */
    fun card(name: String, summary: String, payload: String): Bitmap {
        val w = 1080; val h = 1350
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(20, 20, 22))
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(236, 230, 218); textSize = 64f; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(160, 156, 148); textSize = 34f }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 163, 61); textSize = 30f }
        c.drawText(name.take(28), 80f, 150f, title)
        c.drawText(summary.take(52), 80f, 210f, small)
        val q = qr(payload, 840)
        val p = Paint().apply { isFilterBitmap = false }
        c.drawRoundRect(110f, 270f, 970f, 1130f, 24f, 24f, Paint().apply { color = Color.WHITE })
        c.drawBitmap(q, 120f, 280f, p)
        c.drawText("Velum preset. In Velum: Recipes, Import from QR image.", 80f, 1220f, accent)
        return b
    }

    /** Finds and reads a QR code in a photo or screenshot. */
    fun read(bmp: Bitmap): String? {
        val scaled = if (maxOf(bmp.width, bmp.height) > 1600) {
            val k = 1600f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        } else bmp
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val src = RGBLuminanceSource(scaled.width, scaled.height, px)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        return runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(src)), hints).text }.getOrNull()
    }
}
