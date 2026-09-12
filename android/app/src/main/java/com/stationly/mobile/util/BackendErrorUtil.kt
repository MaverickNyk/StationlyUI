package com.stationly.mobile.util

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

object BackendErrorUtil {
    /**
     * Determines if a Throwable is specifically a failure to reach our backend.
     */
    fun isBackendConnectionError(e: Throwable): Boolean {
        return e is UnknownHostException || 
               e is ConnectException || 
               e is SocketTimeoutException ||
               e.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
               e.message?.contains("timeout", ignoreCase = true) == true ||
               e.message?.contains("failed to connect", ignoreCase = true) == true
    }

    /**
     * Converts technical exceptions into professional, non-technical apologies.
     */
    fun getFriendlyMessage(e: Throwable): String {
        return when {
            isBackendConnectionError(e) ->
                "Can't reach the server. Check your connection and try again."

            e is kotlinx.serialization.SerializationException || e is IllegalArgumentException ->
                "The server sent back something we couldn't read. Try again in a moment."

            else ->
                "Something went wrong. Try again in a moment."
        }
    }
}
