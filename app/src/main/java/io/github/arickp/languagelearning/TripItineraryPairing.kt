package io.github.arickp.languagelearning

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class ItineraryPairingSession(
    val token: String,
    val expiresInSeconds: Long
)

internal fun showRemoteItineraryInputByDefault(isTv: Boolean): Boolean = !isTv

internal fun quizPaneCount(isTv: Boolean): Int = if (isTv) 2 else 1

internal fun initialQuizAnswerIndex(isTv: Boolean, optionCount: Int): Int? =
    if (isTv && optionCount > 0) 0 else null

internal fun shouldAutoRevealQuizFeedback(isTv: Boolean, hasAnswer: Boolean): Boolean =
    !isTv && hasAnswer

internal fun shouldAutoStartTripQuiz(itinerary: String?): Boolean = !itinerary.isNullOrBlank()

internal object TripItineraryPairing {
    fun formUrl(serverUrl: String, token: String): String =
        "${serverUrl.trimEnd('/')}/trip/itinerary/$token"

    suspend fun createSession(): ItineraryPairingSession = withContext(Dispatchers.IO) {
        val connection = openConnection("${configuredServerUrl()}/trip/itinerary-sessions")
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(0)
        connection.connect()
        try {
            requireSuccess(connection, "Could not create an itinerary QR session")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            ItineraryPairingSession(
                token = json.getString("token"),
                expiresInSeconds = json.getLong("expires_in_seconds")
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun pollItinerary(token: String): String? = withContext(Dispatchers.IO) {
        val connection = openConnection(
            "${configuredServerUrl()}/trip/itinerary-sessions/$token"
        )
        try {
            requireSuccess(connection, "The itinerary QR session expired")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            if (json.optString("status") == "submitted") json.optString("itinerary") else null
        } finally {
            connection.disconnect()
        }
    }

    fun qrBitmap(contents: String, size: Int = 512): Bitmap {
        val matrix = MultiFormatWriter().encode(
            contents,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
            )
        )
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }

    private fun configuredServerUrl(): String = BuildConfig.SERVER_URL
        .trimEnd('/')
        .takeIf { it.isNotBlank() }
        ?: throw IOException("SERVER_URL is not configured for this build")

    private fun requireSuccess(connection: HttpURLConnection, message: String) {
        if (connection.responseCode !in 200..299) {
            throw IOException("$message (HTTP ${connection.responseCode})")
        }
    }
}
