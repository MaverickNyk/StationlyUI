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
class SelectionRepository(private val storageManager: StorageManager) {
    
    // In-memory cache for fast access
    private val _selections = MutableStateFlow<List<UserSelection>>(emptyList())
    val selections: StateFlow<List<UserSelection>> = _selections.asStateFlow()
    
    /**
     * Initialize repository by loading from storage
     */
    suspend fun initialize() {
        val savedSelections = storageManager.loadSelections()
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
        storageManager.saveSelections(currentSelections)
    }
    
    /**
     * Delete a selection
     * @param selection The selection to delete
     */
    suspend fun deleteSelection(selection: UserSelection) {
        val currentSelections = _selections.value.toMutableList()
        val wasRemoved = currentSelections.remove(selection)
        
        if (wasRemoved) {
            _selections.value = currentSelections
            storageManager.saveSelections(currentSelections)
        }
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
        storageManager.clearCache()
    }
}