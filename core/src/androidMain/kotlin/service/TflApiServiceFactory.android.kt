package com.stationly.core.service

import io.ktor.client.HttpClient

// Unchanged Android behaviour — same class, same REST calls as before.
actual fun createTflApiService(httpClient: HttpClient): TflApiService = TflApiServiceImpl(httpClient)
