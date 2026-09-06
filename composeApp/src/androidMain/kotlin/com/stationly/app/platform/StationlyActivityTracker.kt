package com.stationly.app.platform

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Starts [AndroidAppContext]'s Activity tracking before there is an Activity to
 * miss.
 *
 * ## Why a content provider, of all things
 * Android instantiates every declared `ContentProvider` after
 * `Application.onCreate` and **before the first Activity is created**. That is
 * the only hook a *library* has at that point without asking its host to call
 * something — and the host contract here is deliberately twenty lines
 * (`setContent { App(...) }`), which is not the place to add an obligation
 * whose omission fails silently. It is the same mechanism `androidx.startup`
 * and Firebase use, minus the dependency.
 *
 * ## What went wrong without it
 * `AndroidAppContext` registered its lifecycle callbacks on first access. The
 * shared `NotificationPermissionEffect` asks for the current Activity from a
 * `LaunchedEffect` during the summary screen's first composition — which runs
 * *after* `onActivityResumed`. The tracker therefore registered too late to
 * hear the only resume that had happened, reported no Activity, and the
 * POST_NOTIFICATIONS prompt never appeared on a fresh install. Nothing threw
 * and nothing logged: "no Activity" is a legitimate answer meaning "cannot
 * ask". Found on hardware in AV2-3.3; see the note on [AndroidAppContext.activity].
 *
 * ## Cost
 * One manifest entry in `:composeApp`, and an object allocation at startup.
 * Nothing is ever queried through it — every operation below is the null answer
 * a provider is allowed to give. It is declared in this library's own manifest,
 * which merges only into builds that include `:composeApp` — so, today, staging
 * only.
 */
class StationlyActivityTracker : ContentProvider() {

    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.let(AndroidAppContext::startTracking)
        // True regardless: a host whose context is somehow not an Application
        // still falls back to lazy registration, and failing to load the
        // provider would be a far louder failure than the one being prevented.
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
