package net.gotev.uploadservice.data

import android.os.Parcelable
import kotlinx.android.parcel.IgnoredOnParcel
import kotlinx.android.parcel.Parcelize
import net.gotev.uploadservice.UploadServiceConfig
import net.gotev.uploadservice.schemehandlers.SchemeHandler
import java.util.*

/**
 * Represents a file to upload.
 *
 * @author cankov
 * @author gotev (Aleksandar Gotev)
 */
@Parcelize
data class UploadFile @JvmOverloads constructor(
        val path: String,
        val properties: LinkedHashMap<String, String> = LinkedHashMap()
) : Parcelable {

    companion object {
        private const val successfulUpload = "successful_upload"
    }

    @IgnoredOnParcel
    val handler: SchemeHandler by lazy {
        UploadServiceConfig.getSchemeHandler(path)
    }

    @IgnoredOnParcel
    var successfullyUploaded: Boolean
        get() = properties[successfulUpload]?.toBoolean() ?: false
        set(value) { properties[successfulUpload] = value.toString() }
}
