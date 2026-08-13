package com.stationly.core.repository

import com.stationly.core.model.UserSelection
import com.stationly.core.platform.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Selection Repository
 * 
 * Manages user selections (saved stations) with local persistence.
 * This is shared across all platforms.
 * 
 * Mirrors the functionality of MindTheTimeAndroid's SummaryViewModel
 * but extracted as a reusable repository.
 */
class SelectionRepository(
    private val storageManager: StorageManager,
    private val sqlStorage: SqlStorage
) {
    
    /**
     * In-memory cache for fast access — **shared by every instance**.
     *
     * Six places construct a `SelectionRepository` (login, home, selection,
     * station settings, profile, the iOS sync bridge) and each used to get its
     * own cache over the same SQLite table. That is two records of one fact, and
     * they diverged exactly as `UserSettings` describes: one instance writes, the
     * others never hear about it.
     *
     * It showed up as the worst possible symptom. Signing in restored the boards
     * through the LOGIN repository — SQLite correct, Firestore correct — while
     * the home screen collected a different instance's flow, which still held the
     * empty list it was constructed with. The user landed on "Your board is
     * empty" with their boards sitting on disk, and it fixed itself on a trip to
     * the profile screen and back, because that rebuilt the ViewModel and its
     * repository re-read SQLite.
     *
     * Process-wide rather than injected, because the storage underneath is a
     * process-wide singleton too: these instances are already the same
     * repository, and this makes them behave like it. `initialize()` re-seeds it
     * from SQLite at a session boundary.
     */
    private val _selections get() = shared
    val selections: StateFlow<List<UserSelection>> = shared.asStateFlow()
    
    /**
     * Initialize repository by loading from storage
     */
    suspend fun initialize() {
        val savedSelections = sqlStorage.getAllSelections()
        _selections.value = savedSelections
    }
    
    /**
     * Save a new selection or update existing one
     * @param newSelection The selection to save
     * @param oldSelection Optional old selection to replace (for edits)
     */
    suspend fun saveSelection(newSelection: UserSelection, oldSelection: UserSelection?) {
        val currentSelections = _selections.value.toMutableList()
        
        // Remove old selection if provided
        oldSelection?.let {
            currentSelections.removeAll { s -> 
                s.station == it.station && 
                s.line == it.line && 
                s.direction == it.direction 
            }
        }
        
        // Add new selection to the top (primary position)
        currentSelections.add(0, newSelection)
        
        // Update state and persist
        _selections.value = currentSelections
        
        // Persist to SQL
        sqlStorage.clearSelections()
        currentSelections.forEach { sqlStorage.saveSelection(it) }
    }
    
    /**
     * Replace a board's stored settings WITHOUT moving it in the list.
     *
     * [saveSelection] always inserts at index 0, which is right for a new board
     * but wrong for editing one: changing a filter on an existing board would
     * jump it to the top of its card and hand it the widget's primary slot. This
     * matches on (station, line, direction) — a board's identity — and writes
     * over it in place.
     *
     * No-op if the board is not tracked, so a stale edit cannot resurrect a
     * board that was deleted meanwhile.
     */
    suspend fun updateSelectionInPlace(selection: UserSelection) {
        val current = _selections.value.toMutableList()
        val idx = current.indexOfFirst {
            it.station == selection.station &&
                it.line == selection.line &&
                it.direction == selection.direction
        }
        if (idx < 0) return
        current[idx] = selection
        _selections.value = current

        sqlStorage.clearSelections()
        current.forEach { sqlStorage.saveSelection(it) }
    }

    /**
     * Delete a selection
     * @param selection The selection to delete
     */
    suspend fun deleteSelection(selection: UserSelection) {
        val currentSelections = _selections.value.toMutableList()
        // Direction is part of a board's identity: both directions of one line
        // can be tracked at the same station, and removing one must leave the
        // other alone.
        currentSelections.removeAll {
            it.station == selection.station &&
                it.line == selection.line &&
                it.direction == selection.direction
        }
        _selections.value = currentSelections
        // Always delete from SQLite — in-memory cache may not be initialized
        // (e.g. ProfileViewModel never calls initialize()), but SQLite is always authoritative.
        sqlStorage.deleteSelection(selection.station, selection.line, selection.direction)
    }
    
    /**
     * Get primary selection (first in list)
     * This is the one shown in the widget
     */
    fun getPrimarySelection(): UserSelection? {
        return _selections.value.firstOrNull()
    }
    
    /**
     * Clear all selections
     */
    suspend fun clearAll() {
        _selections.value = emptyList()
        sqlStorage.clearSelections()
        storageManager.clearCache()
    }

    companion object {
        /** The one cache. See the note on [_selections]. */
        private val shared = MutableStateFlow<List<UserSelection>>(emptyList())
    }
}