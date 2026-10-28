package com.aastudio.live

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Penyimpanan proyek lokal: satu file JSON per proyek di filesDir/projects. */
class ProjectStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "projects").also { it.mkdirs() }

    private fun fileFor(id: String): File? {
        if (!id.matches(Regex("[A-Za-z0-9-]{1,64}"))) return null
        return File(dir, "$id.json")
    }

    private fun readFile(f: File): Project? = try {
        Project.fromJson(JSONObject(f.readText()))
    } catch (e: Exception) {
        null
    }

    fun list(): List<Project> {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { readFile(it) }.sortedByDescending { it.updated }
    }

    fun load(id: String): Project? {
        val f = fileFor(id) ?: return null
        if (!f.exists()) return null
        return readFile(f)
    }

    fun save(p: Project): Boolean {
        val f = fileFor(p.id) ?: return false
        p.updated = System.currentTimeMillis()
        return try {
            val tmp = File(dir, "${p.id}.tmp")
            tmp.writeText(p.toJson().toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(id: String): Boolean {
        val f = fileFor(id) ?: return false
        return f.delete()
    }

    fun create(name: String): Project {
        val p = Project(name = name.trim().ifBlank { "Proyek" })
        save(p)
        return p
    }
}
