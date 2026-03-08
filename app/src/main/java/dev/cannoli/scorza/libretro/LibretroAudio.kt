package dev.cannoli.scorza.libretro

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

class LibretroAudio(sampleRate: Int) {

    private val bufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 4

    @Suppress("DEPRECATION")
    private val track = AudioTrack(
        AudioManager.STREAM_MUSIC,
        sampleRate,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT,
        bufferSize,
        AudioTrack.MODE_STREAM
    )

    fun start() {
        track.play()
    }

    fun stop() {
        track.stop()
        track.release()
    }

    @Suppress("unused") // Called from JNI
    fun writeSamples(samples: ShortArray, count: Int) {
        track.write(samples, 0, count)
    }
}
