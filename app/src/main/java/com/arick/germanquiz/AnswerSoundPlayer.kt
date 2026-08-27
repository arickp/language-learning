package com.arick.languagelearning

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/** Warm answer feedback synthesized locally, with no audio assets or permissions. */
object AnswerSoundPlayer {
    private const val SAMPLE_RATE = 44_100

    fun correct() = playAsync(
        notes = listOf(
            Note(523.25, 0.00, 0.22, 0.26),
            Note(659.25, 0.09, 0.24, 0.22),
            Note(783.99, 0.18, 0.34, 0.19)
        ),
        durationSeconds = 0.58
    )

    fun incorrect() = playAsync(
        notes = listOf(
            Note(329.63, 0.00, 0.25, 0.20),
            Note(246.94, 0.24, 0.34, 0.18)
        ),
        durationSeconds = 0.62
    )

    private fun playAsync(notes: List<Note>, durationSeconds: Double) {
        thread(name = "answer-sound", isDaemon = true) {
            val samples = ShortArray((SAMPLE_RATE * durationSeconds).toInt())
            for (sampleIndex in samples.indices) {
                val time = sampleIndex.toDouble() / SAMPLE_RATE
                var value = 0.0
                for (note in notes) {
                    val localTime = time - note.startSeconds
                    if (localTime in 0.0..note.durationSeconds) {
                        val envelope = softEnvelope(localTime, note.durationSeconds)
                        val fundamental = sin(2.0 * PI * note.frequency * localTime)
                        val overtone = sin(2.0 * PI * note.frequency * 2.0 * localTime) * 0.12
                        value += (fundamental + overtone) * envelope * note.volume
                    }
                }
                samples[sampleIndex] =
                    (value.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 2)
                .build()

            try {
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep((durationSeconds * 1_000).toLong() + 40)
            } finally {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                track.release()
            }
        }
    }

    private fun softEnvelope(time: Double, duration: Double): Double {
        val attack = (time / 0.035).coerceIn(0.0, 1.0)
        val release = ((duration - time) / 0.16).coerceIn(0.0, 1.0)
        return attack * release
    }

    private data class Note(
        val frequency: Double,
        val startSeconds: Double,
        val durationSeconds: Double,
        val volume: Double
    )
}
