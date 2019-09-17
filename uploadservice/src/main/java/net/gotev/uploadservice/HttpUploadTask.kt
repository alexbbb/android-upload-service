package net.gotev.uploadservice

import android.annotation.SuppressLint
import net.gotev.uploadservice.logger.UploadServiceLogger
import net.gotev.uploadservice.network.BodyWriter
import net.gotev.uploadservice.network.HttpRequest
import net.gotev.uploadservice.network.HttpStack
import java.io.UnsupportedEncodingException

/**
 * Generic HTTP Upload Task.<br></br>
 * Subclass to create your custom upload task.
 *
 * @author cankov
 * @author gotev (Aleksandar Gotev)
 * @author mabdurrahman
 */
abstract class HttpUploadTask : UploadTask(), HttpRequest.RequestBodyDelegate, BodyWriter.OnStreamWriteListener {

    protected val httpParams: HttpUploadTaskParameters
        get() = params.additionalParams as HttpUploadTaskParameters

    /**
     * Implement in subclasses to provide the expected upload in the progress notifications.
     * @return The expected size of the http request body.
     * @throws UnsupportedEncodingException
     */
    abstract val bodyLength: Long

    /**
     * Implementation of the upload logic.<br></br>
     * If you want to take advantage of the automations which Android Upload Service provides,
     * do not override or change the implementation of this method in your subclasses. If you do,
     * you have full control on how the upload is done, so for example you can use your custom
     * http stack, but you have to manually setup the request to the server with everything you
     * set in your [HttpUploadRequest] subclass and to get the response from the server.
     *
     * @throws Exception if an error occurs
     */
    @SuppressLint("NewApi")
    @Throws(Exception::class)
    override fun upload(httpStack: HttpStack) {
        UploadServiceLogger.debug(javaClass.simpleName) { "Starting upload task with ID ${params.id}" }

        val httpParams = httpParams.apply {
            addHeader("User-Agent", if (httpParams.isCustomUserAgentDefined) {
                httpParams.customUserAgent
            } else {
                "AndroidUploadService/" + BuildConfig.VERSION_NAME
            })
        }

        //TODO: clear successfully uploaded files
        totalBytes = bodyLength

        val response = httpStack.newRequest(httpParams.method, params.serverUrl)
                .setHeaders(httpParams.requestHeaders)
                .setTotalBodyBytes(totalBytes, httpParams.usesFixedLengthStreamingMode)
                .getResponse(this, this)

        UploadServiceLogger.debug(javaClass.simpleName) {
            "Server responded with code ${response.code} " +
                    "and body ${response.bodyString} to upload with ID: ${params.id}"
        }

        // Broadcast completion only if the user has not cancelled the operation.
        // It may happen that when the body is not completely written and the client
        // closes the connection, no exception is thrown here, and the server responds
        // with an HTTP status code. Without this, what happened was that completion was
        // broadcasted and then the cancellation. That behaviour was not desirable as the
        // library user couldn't execute code on user cancellation.
        if (shouldContinue) {
            broadcastCompleted(response)
        }
    }

    override fun shouldContinueWriting() = shouldContinue

    final override fun onBytesWritten(bytesWritten: Int) {
        broadcastProgress(bytesWritten.toLong())
    }
}
