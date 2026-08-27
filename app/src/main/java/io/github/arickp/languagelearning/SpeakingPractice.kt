package io.github.arickp.languagelearning

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class PracticeSentence(val sentence: String, val translation: String)

object SpeakingPracticeClient {
    suspend fun sentence(term: String, variant: LanguageVariant, languageOverride: String? = null): Result<PracticeSentence> = request("/practice/sentence", JSONObject()
        .put("term", term).put("language", languageOverride ?: variant.language.label).put("region", variant.speechRegion)) { json ->
        PracticeSentence(json.getString("sentence"), json.getString("translation"))
    }

    suspend fun evaluate(file: File, sentence: String, variant: LanguageVariant, languageOverride: String? = null): Result<String> = request("/practice/evaluate", JSONObject()
        .put("sentence", sentence).put("language", languageOverride ?: variant.language.label).put("region", variant.speechRegion)
        .put("audio_base64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))) { it.getString("feedback") }

    private suspend fun <T> request(path: String, body: JSONObject, parse: (JSONObject) -> T): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            require(BuildConfig.SERVER_URL.isNotBlank()) { "SERVER_URL is not configured" }
            val connection = URL("${BuildConfig.SERVER_URL}$path").openConnection() as HttpURLConnection
            try {
                connection.requestMethod="POST"; connection.doOutput=true
                connection.connectTimeout=8_000; connection.readTimeout=45_000
                connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
                if(connection.responseCode !in 200..299) error(connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Server returned ${connection.responseCode}")
                parse(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
            } finally { connection.disconnect() }
        }
    }
}

class WavRecorder(private val context: Context) {
    private var recorder: AudioRecord? = null
    private var job: Job? = null
    private lateinit var pcmFile: File

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(scope: CoroutineScope) {
        check(hasPermission()) { "Microphone permission is required" }
        val rate=16_000
        val minimum=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize=maxOf(minimum,4096)
        pcmFile=File(context.cacheDir,"practice-recording.pcm")
        recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,bufferSize)
        recorder!!.startRecording()
        job=scope.launch(Dispatchers.IO) {
            pcmFile.outputStream().use { output ->
                val buffer=ByteArray(bufferSize)
                while(isActive) { val count=recorder?.read(buffer,0,buffer.size) ?: 0; if(count>0) output.write(buffer,0,count) }
            }
        }
    }

    suspend fun stop(): File {
        recorder?.stop(); job?.cancelAndJoin(); recorder?.release(); recorder=null
        val wav=File(context.cacheDir,"practice-recording.wav")
        pcmToWav(pcmFile,wav,16_000)
        return wav
    }

    private fun pcmToWav(pcm: File, wav: File, sampleRate: Int) {
        val data=pcm.readBytes(); val byteRate=sampleRate*2
        RandomAccessFile(wav,"rw").use { out ->
            out.setLength(0); out.writeBytes("RIFF"); writeIntLE(out,36+data.size); out.writeBytes("WAVEfmt ")
            writeIntLE(out,16); writeShortLE(out,1); writeShortLE(out,1); writeIntLE(out,sampleRate); writeIntLE(out,byteRate)
            writeShortLE(out,2); writeShortLE(out,16); out.writeBytes("data"); writeIntLE(out,data.size); out.write(data)
        }
    }
    private fun writeIntLE(out:RandomAccessFile,value:Int){out.write(value);out.write(value shr 8);out.write(value shr 16);out.write(value shr 24)}
    private fun writeShortLE(out:RandomAccessFile,value:Int){out.write(value);out.write(value shr 8)}
}
