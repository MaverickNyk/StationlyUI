package com.stationly.core.service

import com.stationly.core.platform.Platform
import io.ktor.client.plugins.api.*
import io.ktor.http.*

object StationlyAuth {
    val Plugin = createClientPlugin("StationlyAuthPlugin") {
        onRequest { request, _ ->
            // LAYER 1: Global API Key
            request.headers.append("X-Stationly-Key", Platform.getApiKey())

            // LAYER 2: Firebase Auth (Only for User routes)
            if (request.url.encodedPath.contains("/user/")) {
                val token = Platform.getAuthToken()
                if (token != null) {
                    request.headers.append(HttpHeaders.Authorization, "Bearer $token")
                }
            }
        }
    }
}
