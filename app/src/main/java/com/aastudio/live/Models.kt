package com.aastudio.live

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Sistem koordinat kanvas tetap 1920x1080 (16:9). Semua posisi/ukuran elemen memakai satuan ini. */
const val CANVAS_W = 1920
const val CANVAS_H = 1080
const val MIN_SIZE = 40
const val MAX_SIZE = 6000

data class SourceItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Web Source",
    var url: String = "",
    var x: Int = 0,
    var y: Int = 0,
    var w: Int = 640,
    var h: Int = 360,
    var visible: Boolean = true,
    var locked: Boolean = false,
    var muted: Boolean = false,
    var volume: Int = 100
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("x", x)
        .put("y", y)
        .put("w", w)
        .put("h", h)
        .put("visible", visible)
        .put("locked", locked)
        .put("muted", muted)
        .put("volume", volume)

    companion object {
        fun fromJson(o: JSONObject): SourceItem = SourceItem(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name", "Web Source"),
            url = o.optString("url", ""),
            x = o.optInt("x", 0),
            y = o.optInt("y", 0),
            w = o.optInt("w", 640).coerceIn(MIN_SIZE, MAX_SIZE),
            h = o.optInt("h", 360).coerceIn(MIN_SIZE, MAX_SIZE),
            visible = o.optBoolean("visible", true),
            locked = o.optBoolean("locked", false),
            muted = o.optBoolean("muted", false),
            volume = o.optInt("volume", 100).coerceIn(0, 100)
        )
    }
}

data class AudioSettings(
    var volume: Int = 100,
    var muted: Boolean = false,
    var autoplay: Boolean = true,
    var soundUri: String = "",
    var soundName: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("volume", volume)
        .put("muted", muted)
        .put("autoplay", autoplay)
        .put("soundUri", soundUri)
        .put("soundName", soundName)

    companion object {
        fun fromJson(o: JSONObject?): AudioSettings {
            if (o == null) return AudioSettings()
            return AudioSettings(
                volume = o.optInt("volume", 100).coerceIn(0, 100),
                muted = o.optBoolean("muted", false),
                autoplay = o.optBoolean("autoplay", true),
                soundUri = o.optString("soundUri", ""),
                soundName = o.optString("soundName", "")
            )
        }
    }
}

data class OverlayPrefs(
    var widthPercent: Int = 60,
    var touchEnabled: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("widthPercent", widthPercent)
        .put("touchEnabled", touchEnabled)

    companion object {
        fun fromJson(o: JSONObject?): OverlayPrefs {
            if (o == null) return OverlayPrefs()
            return OverlayPrefs(
                widthPercent = o.optInt("widthPercent", 60).coerceIn(30, 100),
                touchEnabled = o.optBoolean("touchEnabled", false)
            )
        }
    }
}

class Project(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Proyek",
    var items: MutableList<SourceItem> = mutableListOf(),
    var audio: AudioSettings = AudioSettings(),
    var overlay: OverlayPrefs = OverlayPrefs(),
    var updated: Long = System.currentTimeMillis()
) {
    /** Urutan list = urutan layer: indeks 0 paling belakang, indeks terakhir paling depan. */
    fun itemsJson(): String {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("version", 2)
        .put("id", id)
        .put("name", name)
        .put("updated", updated)
        .put("items", JSONArray(itemsJson()))
        .put("audio", audio.toJson())
        .put("overlay", overlay.toJson())

    companion object {
        fun parseItems(json: String): MutableList<SourceItem> {
            val list = mutableListOf<SourceItem>()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(SourceItem.fromJson(o))
            }
            return list
        }

        fun fromJson(o: JSONObject): Project {
            val items = mutableListOf<SourceItem>()
            val arr = o.optJSONArray("items")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val io = arr.optJSONObject(i) ?: continue
                    items.add(SourceItem.fromJson(io))
                }
            }
            return Project(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name", "Proyek").ifBlank { "Proyek" },
                items = items,
                audio = AudioSettings.fromJson(o.optJSONObject("audio")),
                overlay = OverlayPrefs.fromJson(o.optJSONObject("overlay")),
                updated = o.optLong("updated", System.currentTimeMillis())
            )
        }
    }
}
