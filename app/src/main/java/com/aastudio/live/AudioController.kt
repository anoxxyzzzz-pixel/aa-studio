package com.aastudio.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Pemutar suara lokal (MP3/OGG/WAV) dengan audio focus, volume, mute, dan pembersihan resource. */
class AudioController(context: Context) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var paused = false
    private var pausedByFocus = false
    private var enhancer: LoudnessEnhancer? = null

    /** Penguat 0-15 dB di atas volume maksimum pemutar. */
    var boostDb: Int = 6
        set(value) {
            field = value.coerceIn(0, 15)
            applyBoost()
        }

    var volume: Int = 100
        set(value) {
            field = value.coerceIn(0, 100)
            applyVolume()
        }
    var muted: Boolean = false
        set(value) {
            field = value
            applyVolume()
        }

    var onState: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    val isPlaying: Boolean get() = player != null && !paused
    val hasPlayer: Boolean get() = player != null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying) {
                    pause()
                    pausedByFocus = true
                    onState?.invoke("Dijeda: aplikasi lain memakai audio")
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByFocus) {
                    pausedByFocus = false
                    resume()
                }
            }
        }
    }

    private fun applyVolume() {
        val v = if (muted) 0f else volume / 100f
        try {
            player?.setVolume(v, v)
        } catch (e: IllegalStateException) {
            // player sedang dilepas; abaikan
        }
    }

    private fun applyBoost() {
        try {
            enhancer?.setTargetGain(boostDb * 100)
            enhancer?.enabled = boostDb > 0
        } catch (e: RuntimeException) {
            // efek audio tidak didukung perangkat; abaikan
        }
    }

    private fun attachBoost(mp: MediaPlayer) {
        releaseBoost()
        try {
            enhancer = LoudnessEnhancer(mp.audioSessionId)
            applyBoost()
        } catch (e: RuntimeException) {
            enhancer = null
        }
    }

    private fun releaseBoost() {
        try {
            enhancer?.release()
        } catch (e: RuntimeException) {
            // abaikan
        }
        enhancer = null
    }

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun play(uri: Uri) {
        stop()
        if (!requestFocus()) {
            onError?.invoke("Tidak bisa memutar: audio sedang dipakai aplikasi lain")
            return
        }
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(ctx, uri)
            mp.setOnPreparedListener {
                if (player === it) {
                    applyVolume()
                    attachBoost(it)
                    it.start()
                    paused = false
                    onState?.invoke("Memutar")
                }
            }
            mp.setOnCompletionListener {
                if (player === it) {
                    stop()
                    onState?.invoke("Selesai")
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                stop()
                onError?.invoke("Gagal memutar: file rusak atau format tidak didukung")
                true
            }
            player = mp
            paused = false
            onState?.invoke("Menyiapkan…")
            mp.prepareAsync()
        } catch (e: SecurityException) {
            mp.release()
            player = null
            abandonFocus()
            onError?.invoke("File tidak bisa diakses. Impor ulang file suaranya.")
        } catch (e: Exception) {
            mp.release()
            player = null
            abandonFocus()
            onError?.invoke("Gagal memutar: file rusak atau format tidak didukung")
        }
    }

    fun pause() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                paused = true
                onState?.invoke("Dijeda")
            }
        } catch (e: IllegalStateException) {
            // belum siap
        }
    }

    fun resume() {
        val p = player ?: return
        try {
            if (paused) {
                p.start()
                paused = false
                onState?.invoke("Memutar")
            }
        } catch (e: IllegalStateException) {
            // belum siap
        }
    }

    fun togglePause() {
        if (paused) resume() else pause()
    }

    fun stop() {
        val p = player
        player = null
        paused = false
        pausedByFocus = false
        if (p != null) {
            try {
                p.setOnPreparedListener(null)
                p.setOnCompletionListener(null)
                p.setOnErrorListener(null)
                p.release()
            } catch (e: Exception) {
                // abaikan
            }
        }
        releaseBoost()
        abandonFocus()
    }

    fun release() {
        stop()
        onState = null
        onError = null
    }
}

/** Membuat suara uji bawaan (dua nada, keras dan jelas) sebagai file WAV. Dibuat dari kode, bebas hak cipta. */
object ToneWav {
    fun write(file: File) {
        val rate = 22050
        val n = (rate * 1.6).toInt()
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val t = i / rate.toDouble()
            val first = t < 0.7
            val freq = if (first) 659.25 else 880.0
            val local = if (first) t else t - 0.7
            // Atak cepat, peluruhan lembut: energi rata-rata jauh lebih besar dari versi lama.
            val env = exp(-1.6 * local) * min(1.0, local * 300.0)
            val wave = sin(2.0 * PI * freq * t) * 0.8 + sin(2.0 * PI * freq * 2 * t) * 0.2
            // Soft clip agar puncak penuh (hampir 0 dBFS) tanpa distorsi kasar.
            val v = (Math.tanh(wave * env * 1.6) * 0.97 * Short.MAX_VALUE).toInt()
            data[2 * i] = (v and 0xFF).toByte()
            data[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + data.size)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(rate)
        header.putInt(rate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(data.size)
        val out = ByteArrayOutputStream()
        out.write(header.array())
        out.write(data)
        file.writeBytes(out.toByteArray())
    }
}
