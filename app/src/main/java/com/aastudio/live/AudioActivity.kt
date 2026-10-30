package com.aastudio.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.io.File

/**
 * Pengaturan audio proyek + pengujian suara lokal.
 * - Volume 0-100% dan mute berlaku untuk pemutar lokal DAN sebagai master untuk Web Source.
 * - Autoplay mengatur apakah WebView boleh memutar media tanpa sentuhan pengguna.
 */
class AudioActivity : Activity() {

    private lateinit var store: ProjectStore
    private lateinit var project: Project
    private lateinit var audio: AudioController

    private lateinit var status: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var radioTone: RadioButton
    private lateinit var radioFile: RadioButton
    private lateinit var importedLabel: TextView
    private lateinit var pauseBtn: TextView
    private val reqImport = 7001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        val id = intent.getStringExtra(EXTRA_PROJECT)
        val loaded = if (id != null) store.load(id) else null
        if (loaded == null) {
            Ui.toast(this, "Proyek tidak ditemukan")
            finish()
            return
        }
        project = loaded
        audio = AudioController(this)
        audio.volume = project.audio.volume
        audio.muted = project.audio.muted
        audio.boostDb = project.audio.boost
        audio.onState = { runOnUiThread { setStatus(it, false) } }
        audio.onError = { runOnUiThread { setStatus(it, true) } }
        buildUi()
        refreshImported()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.WHITE)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 24))
        scroll.addView(col, Ui.lp(Ui.MATCH, Ui.WRAP))

        col.addView(Ui.text(this, "Audio", 22f, true))
        col.addView(
            Ui.text(this, "Proyek: ${project.name}", 13f, false, Ui.MUTED),
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 2), 0, Ui.dp(this, 12))
        )

        col.addView(Ui.text(this, "Uji suara lokal", 15f, true))
        val group = RadioGroup(this)
        radioTone = RadioButton(this)
        radioTone.text = "Suara uji bawaan (nada dua not)"
        radioTone.id = View.generateViewId()
        radioFile = RadioButton(this)
        radioFile.text = "File impor (MP3/OGG)"
        radioFile.id = View.generateViewId()
        group.addView(radioTone)
        group.addView(radioFile)
        radioTone.isChecked = true
        col.addView(group)

        importedLabel = Ui.text(this, "", 12f, false, Ui.MUTED)
        col.addView(importedLabel)
        col.addView(
            Ui.button(this, "Impor file MP3/OGG") { pickFile() },
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, Ui.dp(this, 8))
        )

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val gap = Ui.dp(this, 6)
        row.addView(Ui.button(this, "Play", true) { play() }, Ui.lpM(0, Ui.WRAP, 1f, 0, 0, gap, 0))
        val pause = Ui.button(this, "Jeda / Lanjut") { audio.togglePause() }
        pauseBtn = pause
        row.addView(pause, Ui.lpM(0, Ui.WRAP, 1.3f, 0, 0, gap, 0))
        row.addView(Ui.button(this, "Stop") { audio.stop(); setStatus("Dihentikan", false) }, Ui.lp(0, Ui.WRAP, 1f))
        col.addView(row)

        status = Ui.text(this, "Siap", 13f, false, Ui.MUTED)
        col.addView(status, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, Ui.dp(this, 12)))

        col.addView(Ui.text(this, "Volume master & mute", 15f, true))
        volumeLabel = Ui.text(this, "Volume: ${project.audio.volume}%", 13f)
        col.addView(volumeLabel, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 8), 0, 0))
        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = project.audio.volume
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeLabel.text = "Volume: $progress%"
                if (fromUser) {
                    project.audio.volume = progress
                    audio.volume = progress
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                store.save(project)
            }
        })
        col.addView(seek, Ui.lp(Ui.MATCH, Ui.WRAP))

        // --- Volume media perangkat (penyebab paling umum suara terasa kecil)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val sysLabel = Ui.text(this, "", 13f)
        fun refreshSys() {
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            sysLabel.text = "Volume media perangkat: $cur / $maxVol"
        }
        refreshSys()
        col.addView(sysLabel, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 14), 0, 0))
        val sysSeek = SeekBar(this)
        sysSeek.max = maxVol
        sysSeek.progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        sysSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    refreshSys()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        col.addView(sysSeek, Ui.lp(Ui.MATCH, Ui.WRAP))
        col.addView(
            Ui.button(this, "Maksimalkan volume media") {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
                sysSeek.progress = maxVol
                refreshSys()
            },
            Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 6), 0, 0)
        )

        // --- Boost suara uji lokal
        val boostLabel = Ui.text(this, "Boost suara uji: +${project.audio.boost} dB", 13f)
        col.addView(boostLabel, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 14), 0, 0))
        val boostSeek = SeekBar(this)
        boostSeek.max = 15
        boostSeek.progress = project.audio.boost
        boostSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                boostLabel.text = "Boost suara uji: +$progress dB"
                if (fromUser) {
                    project.audio.boost = progress
                    audio.boostDb = progress
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                store.save(project)
            }
        })
        col.addView(boostSeek, Ui.lp(Ui.MATCH, Ui.WRAP))

        // --- Penguat suara Web Source
        val gainLabel = Ui.text(this, "", 13f)
        fun refreshGain() {
            val g = project.audio.webGain
            gainLabel.text = if (g <= 1) "Penguat suara Web Source: mati (x1)" else "Penguat suara Web Source: x$g"
        }
        refreshGain()
        col.addView(gainLabel, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 14), 0, 0))
        val gainSeek = SeekBar(this)
        gainSeek.max = 3
        gainSeek.progress = project.audio.webGain - 1
        gainSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    project.audio.webGain = progress + 1
                    refreshGain()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                store.save(project)
            }
        })
        col.addView(gainSeek, Ui.lp(Ui.MATCH, Ui.WRAP))
        col.addView(
            Ui.text(
                this,
                "Penguat Web Source memperkeras elemen audio/video di halaman. Berlaku saat halaman dimuat " +
                    "ulang. Bila suara halaman malah hilang, kembalikan ke x1 (sebagian situs melarang penguatan).",
                11f, false, Ui.MUTED
            )
        )

        val mute = Switch(this)
        mute.text = "Mute (suara uji dan Web Source)"
        mute.isChecked = project.audio.muted
        mute.setOnCheckedChangeListener { _, checked ->
            project.audio.muted = checked
            audio.muted = checked
            store.save(project)
        }
        col.addView(mute, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 10), 0, 0))

        val autoplay = Switch(this)
        autoplay.text = "Izinkan autoplay media di Web Source"
        autoplay.isChecked = project.audio.autoplay
        autoplay.setOnCheckedChangeListener { _, checked ->
            project.audio.autoplay = checked
            store.save(project)
        }
        col.addView(autoplay, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 10), 0, 0))

        val note = Ui.text(
            this,
            "Catatan: suara dari halaman web bergantung pada situs itu sendiri. Mute dan volume " +
                "bekerja pada elemen <audio>/<video> di halaman; audio yang dibuat lewat Web Audio API " +
                "tidak bisa dikendalikan. Sebagian situs hanya memutar suara setelah disentuh, " +
                "meskipun autoplay diizinkan.",
            12f, false, Ui.MUTED
        )
        col.addView(note, Ui.lpM(Ui.MATCH, Ui.WRAP, 0f, 0, Ui.dp(this, 14), 0, Ui.dp(this, 14)))

        val back = Ui.button(this, "Selesai") { finish() }
        col.addView(back, Ui.lp(Ui.MATCH, Ui.WRAP))
        back.gravity = Gravity.CENTER

        setContentView(scroll)
    }

    private fun setStatus(msg: String, error: Boolean) {
        status.text = msg
        status.setTextColor(if (error) Ui.BAD else Ui.MUTED)
    }

    private fun refreshImported() {
        if (project.audio.soundUri.isBlank()) {
            importedLabel.text = "Belum ada file diimpor"
        } else {
            importedLabel.text = "File: ${project.audio.soundName.ifBlank { "(tanpa nama)" }}"
            radioFile.isChecked = true
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "audio/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/mpeg", "audio/ogg", "application/ogg", "audio/x-wav"))
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            startActivityForResult(i, reqImport)
        } catch (e: Exception) {
            setStatus("Pemilih file tidak tersedia di perangkat ini", true)
        }
    }

    @Deprecated("Deprecated in Android API; dipakai agar tetap sederhana tanpa dependensi")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqImport || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // tidak semua penyedia mendukung izin persisten; tetap dicoba
        }
        project.audio.soundUri = uri.toString()
        project.audio.soundName = queryName(uri)
        store.save(project)
        refreshImported()
        setStatus("File diimpor. Tekan Play untuk menguji.", false)
    }

    private fun queryName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (e: Exception) {
            // abaikan
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun play() {
        audio.volume = project.audio.volume
        audio.muted = project.audio.muted
        audio.boostDb = project.audio.boost
        if (radioFile.isChecked) {
            if (project.audio.soundUri.isBlank()) {
                setStatus("Belum ada file. Tekan Impor dulu.", true)
                return
            }
            audio.play(Uri.parse(project.audio.soundUri))
        } else {
            val f = File(cacheDir, "aa_test_tone_v2.wav")
            try {
                if (!f.exists()) ToneWav.write(f)
            } catch (e: Exception) {
                setStatus("Gagal membuat suara uji", true)
                return
            }
            audio.play(Uri.fromFile(f))
        }
    }

    override fun onStop() {
        super.onStop()
        if (::audio.isInitialized) audio.stop()
    }

    override fun onDestroy() {
        if (::audio.isInitialized) audio.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROJECT = "project_id"
    }
}
