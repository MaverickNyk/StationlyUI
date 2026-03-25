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
                "Apologies for the inconvenience. We're having trouble reaching our servers right now. Please check your connection and try again."
            
            e is kotlinx.serialization.SerializationException || e is IllegalArgumentException ->
                "We encountered a slight issue while processing your data. Our team has been notified. Please try again in a few moments."

            else -> 
                "Something went wrong on our end. We're working to get it fixed as quickly as possible. Thank you for your patience."
        }
    }
}
