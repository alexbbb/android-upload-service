package net.gotev.uploadservice.extensions

import android.webkit.MimeTypeMap
import java.net.URL

/**
 * @author Aleksandar Gotev
 */
internal fun String?.isASCII(): Boolean {
    if (this.isNullOrBlank())
        return false

    for (index in 0 until length) {
        if (this[index].toInt() > 127) {
            return false
        }
    }

    return true
}

internal const val APPLICATION_OCTET_STREAM = "application/octet-stream"
internal const val VIDEO_MP4 = "video/mp4"

/**
 * Tries to auto-detect the content type (MIME type) of a specific file.
 * @param absolutePath absolute path to the file
 * @return content type (MIME type) of the file, or application/octet-stream if no content
 * type could be determined automatically
 */
fun String.autoDetectMimeType(): String {
    val index = lastIndexOf(".")

    return if (index in 0 until lastIndex) {
        val extension = substring(index + 1).toLowerCase()

        if (extension == "mp4") {
            VIDEO_MP4
        } else {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: APPLICATION_OCTET_STREAM
        }
    } else {
        APPLICATION_OCTET_STREAM
    }
}

fun String.isValidHttpUrl(): Boolean {
    if (!startsWith("http://") && !startsWith("https://")) return false

    return try {
        URL(this)
        true
    } catch (exc: Throwable) {
        false
    }
}
