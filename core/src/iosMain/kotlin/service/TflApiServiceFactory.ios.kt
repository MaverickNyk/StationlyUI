package com.stationly.core.service

import io.ktor.client.HttpClient

actual fun createTflApiService(httpClient: HttpClient): TflApiService =
    StreamBackedTflApiService(TflApiServiceImpl(httpClient))
