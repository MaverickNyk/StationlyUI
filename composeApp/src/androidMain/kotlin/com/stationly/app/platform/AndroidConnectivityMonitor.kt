package com.stationly.app.platform

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

actual fun getConnectivityFlow(): Flow<Boolean> = flowOf(true)
