package com.stationly.core.config

import com.stationly.core.platform.AppEnvironment
import com.stationly.core.platform.Platform

object AppConfig {
    const val PROD_API_URL    = "https://api.stationly.co.uk"
    const val STAGING_API_URL = "https://staging-api.stationly.co.uk"
    const val PROD_WEB_URL    = "https://stationly.co.uk"
    const val STAGING_WEB_URL = "https://staging.stationly.co.uk"

    val isStaging: Boolean  get() = Platform.getEnvironment() == AppEnvironment.STAGING
    val apiBaseUrl: String  get() = if (isStaging) STAGING_API_URL else PROD_API_URL
    val webBaseUrl: String  get() = if (isStaging) STAGING_WEB_URL else PROD_WEB_URL
}
