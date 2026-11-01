package com.example

import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAdded: Long,
    val size: Long,
    val archiveFile: File? = null
) {
    val isArchive: Boolean
        get() = archiveFile != null

    val uniqueKey: String
        get() = archiveFile?.absolutePath ?: uri.toString()

    fun getFormattedSize(): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var digitGroups = 0
        var s = size.toDouble()
        while (s >= 1024 && digitGroups < units.size - 1) {
            s /= 1024
            digitGroups++
        }
        return String.format(Locale.getDefault(), "%.1f %s", s, units[digitGroups])
    }

    fun getFormattedDate(): String {
        val millis = if (isArchive && archiveFile != null) {
            archiveFile.lastModified()
        } else {
            if (dateAdded > 100000000000L) dateAdded else dateAdded * 1000L
        }
        val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }
}
