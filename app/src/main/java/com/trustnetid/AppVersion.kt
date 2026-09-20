package com.trustnetid

import com.trustnetid.app.BuildConfig

/**
 * Centralized version information for TrustNet app
 * Provides access to app version from BuildConfig (set via build.gradle.kts)
 */
object AppVersion {
    /**
     * Get the full version string (e.g., "0.1.0-dev" for debug, "0.1.0" for release)
     */
    fun getVersionString(): String {
        return "v${BuildConfig.APP_VERSION}"
    }
    
    /**
     * Get just the version number without the "v" prefix
     */
    fun getVersion(): String {
        return BuildConfig.APP_VERSION
    }
    
    /**
     * VERSION_NAME constant - the actual version string (e.g., "0.1.0-dev" or "0.1.0")
     */
    const val VERSION_NAME = BuildConfig.VERSION_NAME
}
