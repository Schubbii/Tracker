package de.securitycam

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Speicherort der Aufnahmen und automatisches Löschen alter Dateien (Endlosaufnahme). */
object Storage {
    private const val MIN_FREE_BYTES = 500L * 1024 * 1024

    fun dir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "Movies")
        return File(base, "Aufnahmen").apply { mkdirs() }
    }

    fun newFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY).format(Date())
        return File(dir(context), "cam_$stamp.mp4")
    }

    /** Neueste Aufnahme zuerst. */
    fun list(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension == "mp4" }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /**
     * Löscht die ältesten Aufnahmen, bis das Speicherlimit eingehalten wird
     * und mindestens [MIN_FREE_BYTES] frei sind. Nur aufrufen, wenn gerade
     * keine Datei geschrieben wird.
     */
    fun enforceLimit(context: Context, maxBytes: Long) {
        val dir = dir(context)
        val oldestFirst = list(context).reversed()
        var total = oldestFirst.sumOf { it.length() }
        for (file in oldestFirst) {
            if (total <= maxBytes && dir.usableSpace >= MIN_FREE_BYTES) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }
}
