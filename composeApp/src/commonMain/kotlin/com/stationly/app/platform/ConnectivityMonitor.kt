package com.stationly.app.platform

import kotlinx.coroutines.flow.Flow

expect fun getConnectivityFlow(): Flow<Boolean>
