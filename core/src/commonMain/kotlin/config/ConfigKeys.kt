package com.stationly.core.config

/**
 * Storage keys and endpoints shared by everything that touches the SDUI config
 * map.
 *
 * ## Why one file for one string
 * [HOME_CONFIG_CACHE_KEY] had grown three independent copies — one in
 * `composeApp`'s `HomeConfigCache`, one in `core/iosMain`'s widget publisher,
 * and one in [BoardPolicyStore]. Each was correct and each was a literal, so a
 * rename would have compiled cleanly in all three places and silently split the
 * cache in two: the app writing one key, the widget publisher reading another,
 * and neither able to tell.
 *
 * `core` is the lowest module all three can see, so the constant lives here and
 * they point at it. The literal now exists once.
 */
object ConfigKeys {

    /**
     * Where the last successful home-config payload is cached on device.
     *
     * Read by the app on launch (before the network answers), by
     * [BoardPolicyStore] on the ingest path that runs with no UI above it, and
     * by the iOS widget publisher, which must not make a network call from
     * inside a board write.
     */
    const val HOME_CONFIG_CACHE_KEY = "home_config_strings_cache"
}
