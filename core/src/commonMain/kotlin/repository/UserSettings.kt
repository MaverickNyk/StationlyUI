package com.stationly.core.repository
import com.stationly.core.session.SessionStore

import com.stationly.core.model.user.BoardConfig
import com.stationly.core.model.user.HomeLayout
import com.stationly.core.model.user.WidgetPlacement
import com.stationly.core.platform.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The one live copy of everything the user has configured — **on this device**.
 *
 * ## None of this is synced, and that is the decision
 * An earlier version pushed every one of these to the backend so that signing in
 * anywhere restored the app exactly as it was left. That is a nicer promise and
 * it costs too much: a settings screen writes on every touch, so dragging the
 * rows slider or flipping Expanded once put a Firestore write on the wire, and
 * the document being written is the one every login reads. Appearance is the
 * highest-frequency, lowest-value state in the app — the worst possible thing to
 * spend a write quota on.
 *
 * So the split is by CONSEQUENCE, not by convenience:
 *
 * | State | Where | Why |
 * |---|---|---|
 * | Boards, lines, directions, filters | backend | The subscription registry is derived from them; losing one loses departures. Rare, and worth a write. |
 * | Expanded, view, rows, pin, order, layout | **here** | Appearance. Frequent, and worth nothing to anyone but this device. |
 *
 * ## Kept per ACCOUNT, and kept across logout
 * Every key is namespaced by uid and written through [StorageManager.saveDurable],
 * which survives the logout wipe of the app's own defaults. Signing back in as
 * the same person on the same device restores the arrangement they left; signing
 * in as somebody else gets their own, and never the previous user's.
 *
 * A brand-new device gets DEFAULTS, and that is the accepted cost. It is
 * recoverable in seconds by the user and invisible to everyone else, which is
 * the opposite of a lost board.
 *
 * ## …and so does a REINSTALL, which is the case that actually happens
 * "New device" undersells it. On iOS the durable store is the App Group suite,
 * and iOS destroys that container with the last app in the group — so deleting
 * and reinstalling the app resets every one of these, on the same phone, for
 * the same person. (`DeviceIdentityStore` exists for the same reason, one level
 * down.)
 *
 * It does not look like the other cases from the user's side. Firebase's session
 * is in the Keychain and outlives the app, so a reinstall comes back silently
 * signed in, restores every board from the cloud, and shows them arranged as
 * though they had never been touched. Nobody signed out; the app forgot. And
 * reinstalling is ordinary — storage pressure, troubleshooting — which makes
 * this the common way an arrangement is lost, rather than the new-phone case
 * the paragraph above imagines.
 *
 * Reviewed and deliberately left as-is on 2026-08-15. What syncing it would
 * actually cost — a debounced blob rather than the per-touch write this class
 * was right to refuse — is in `USER_STATE_AND_ACTIVITY.md` §2b.
 *
 * Nothing kept here identifies anybody. "Three rows per platform, board first"
 * is not personal data, which is what makes it safe to leave behind at logout.
 * The one exception is an account being DELETED rather than signed out, which
 * is never coming back for its arrangement — see [forgetAccount].
 *
 * ## Why an object with state, and why in `core`
 * Several screens edit the same settings — the home screen renders them, the
 * board settings screen and the home settings screen change them — and those are
 * separate destinations with separate ViewModels. Two independently-loaded
 * copies drift the moment one writes: the settings screen flips a switch the
 * card behind it never hears about.
 *
 * It lives in `core` rather than beside the screens that edit it because the iOS
 * widget builder needs the same arrangement the home screen shows. That used to
 * mean a duplicated storage-key string and a duplicated shape, both of which
 * fail SILENTLY when they drift — a renamed key reads as absent and the widget
 * quietly reverts to defaults with nothing logged.
 *
 * ## Reads are served from memory; writes update memory first
 * A toggle must move under the user's finger. A failed write costs the setting
 * at next launch, which is a far better failure than a switch that appears not
 * to respond.
 */
object UserSettings {

    /**
     * Per-board configuration, keyed by board id (the hub).
     *
     * Read by `IosWidgetManager` when it builds the widget's board, which is why
     * the key is declared here and only here.
     */
    private const val CONFIGS_KEY = "board_configs_v2"
    private const val LAYOUT_KEY = "home_layout_v2"

    /** Every namespaced key this store owns — see [forgetAccount]. */
    private val ALL_KEYS = listOf(CONFIGS_KEY, LAYOUT_KEY)

    /** Signed out, or not yet loaded. Keys are namespaced by this. */
    private const val NO_USER = "anon"

    private var uid: String = NO_USER

    /**
     * Namespaced by account, so one device can hold two people's arrangements
     * without either inheriting the other's. `anon` covers the window before
     * sign-in, and is the reason a signed-out app still remembers a layout.
     */
    private fun key(base: String) = "$base::$uid"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val configsSerializer = MapSerializer(String.serializer(), BoardConfig.serializer())

    // ── State ───────────────────────────────────────────────────────────────

    private val _configs = MutableStateFlow<Map<String, BoardConfig>>(emptyMap())
    val configs: StateFlow<Map<String, BoardConfig>> = _configs.asStateFlow()

    /** List or carousel — see [HomeLayout]. Stored by NAME, never by ordinal. */
    private val _layout = MutableStateFlow(HomeLayout.LIST)
    val layout: StateFlow<HomeLayout> = _layout.asStateFlow()

    /**
     * Which boards are showing on a home-screen widget right now.
     *
     * Device-local and never persisted — see [WidgetPlacement]. Re-derived by
     * the platform probe on every foreground, so it cannot go stale; empty until
     * the first probe answers, which is the honest state before anyone has
     * asked.
     */
    private val _widgets = MutableStateFlow<Map<String, WidgetPlacement>>(emptyMap())
    val widgets: StateFlow<Map<String, WidgetPlacement>> = _widgets.asStateFlow()

    /**
     * How many widgets are on the home screen, counting instances.
     *
     * [widgets] cannot answer this and must not be asked to: it is keyed by
     * board and its families are `distinct()`, so two medium widgets on the
     * same station collapse into one entry there. That collapse is right for
     * the question that map exists to answer, "is this board on the home
     * screen", and wrong for a count, which is why the total travels beside it
     * rather than being derived from it.
     *
     * Zero until the first probe of the process, which is also what a device
     * with no widgets reports. The two are indistinguishable here on purpose:
     * a screen that renders a count has nothing useful to say about the
     * difference, and the probe runs on launch.
     */
    private val _widgetTotal = MutableStateFlow(0)
    val widgetTotal: StateFlow<Int> = _widgetTotal.asStateFlow()

    /**
     * Whether the first read has finished, so a screen can tell "the user has no
     * settings" apart from "we have not looked yet".
     *
     * Observable because those two states must not render the same way. Every
     * flow here reports its DEFAULT until the read lands, and a default is
     * indistinguishable from a real value. The home screen paid for that: with
     * [BoardConfig.expanded] defaulting to true, the first frames opened every
     * board and the ones the user had actually collapsed then snapped shut a
     * moment later. A board that appears and retracts reads as a glitch, and it
     * is genuinely wasted work — full boards built and immediately thrown away.
     */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Serialises every board-configuration transition — see [mutateConfigs]. */
    private val writeMutex = Mutex()

    // ── Loading ─────────────────────────────────────────────────────────────

    /**
     * Idempotent — every screen that reads settings may call this on entry.
     *
     * The flag is set AFTER the read, not before. Two screens can enter at once
     * (the home screen's VM and a settings VM created in the same frame), and
     * flagging first would let the second return while the first was still
     * reading, handing it an empty map as though the user had configured
     * nothing. A duplicated read of one small string is the cheaper mistake.
     */
    suspend fun ensureLoaded() {
        if (_loaded.value) return
        load()
    }

    /**
     * Point the store at whoever is signed in now, re-reading from disk.
     *
     * Called at both session boundaries. The uid is resolved HERE rather than
     * passed in, because every caller would otherwise have to know the storage
     * key it is kept under, and a caller that read it a moment too early —
     * before the login path had written it — would silently load the previous
     * account's arrangement and then save it under the new one.
     */
    suspend fun switchUser() {
        _loaded.value = false
        load()
    }

    private suspend fun load() {
        uid = runCatching { Platform.storageManager.loadString(UID_KEY) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: NO_USER
        _configs.value = runCatching {
            Platform.storageManager.loadDurable(key(CONFIGS_KEY))
                ?.let { json.decodeFromString(configsSerializer, it) }
        }.getOrNull().orEmpty()
        _layout.value = runCatching {
            Platform.storageManager.loadDurable(key(LAYOUT_KEY))
                ?.let { name -> HomeLayout.entries.firstOrNull { it.name == name } }
        }.getOrNull() ?: HomeLayout.LIST
        _widgets.value = emptyMap()
        _widgetTotal.value = 0
        _loaded.value = true
    }

    // ── Per-board configuration ─────────────────────────────────────────────

    /**
     * One board's configuration, defaults included.
     *
     * **Always read through this, never off [configs] directly.** Rows equal to
     * the defaults are pruned rather than stored, so a board the user has never
     * configured is simply absent from the map — and an absent row is a
     * configured default, not a missing answer.
     */
    fun configOf(boardId: String): BoardConfig = _configs.value[boardId] ?: BoardConfig()

    suspend fun update(boardId: String, transform: (BoardConfig) -> BoardConfig) =
        mutateConfigs { it + (boardId to transform(it[boardId] ?: BoardConfig())) }

    /**
     * Apply the user's drag order, top to bottom.
     *
     * Ranks are rewritten in full rather than patched, so the stored positions
     * are always a dense 0..n-1 with no gaps or duplicates to break ties over.
     * Boards absent from [ids] keep [BoardConfig.UNPOSITIONED] and sort after
     * the rest, which is what a board added since the last reorder should do.
     */
    suspend fun reorder(ids: List<String>) = mutateConfigs { current ->
        current + ids.mapIndexed { index, id ->
            id to (current[id] ?: BoardConfig()).copy(position = index)
        }
    }

    /** Forget one board's configuration — call when the board itself is deleted. */
    suspend fun forget(boardId: String) = mutateConfigs { it - boardId }

    /** Sort board ids by the user's arrangement; see [BoardConfig.position]. */
    fun ordered(ids: List<String>): List<String> =
        ids.sortedBy { configOf(it).sortKey }

    // ── App-wide preferences ────────────────────────────────────────────────

    suspend fun setLayout(layout: HomeLayout) {
        _layout.value = layout
        runCatching { Platform.storageManager.saveDurable(key(LAYOUT_KEY), layout.name) }
    }

    // ── Widget placement ────────────────────────────────────────────────────

    /**
     * Record what the widget host says is on the home screen right now.
     *
     * Replaces the map wholesale rather than merging, because the probe's answer
     * is the complete truth at that instant and a board missing from it is a
     * board whose widget has gone. Callers must not invoke this when the probe
     * FAILED — see [WidgetPlacement].
     */
    fun widgetsObserved(placements: Map<String, WidgetPlacement>, total: Int) {
        _widgets.value = placements
        _widgetTotal.value = total
    }

    fun widgetOf(boardId: String): WidgetPlacement = _widgets.value[boardId] ?: WidgetPlacement()

    // ── Session boundaries ──────────────────────────────────────────────────

    /**
     * Erase this account's settings from the device, for good.
     *
     * The ONE case that outweighs everything the durable store exists for. A
     * signed-out account is coming back, so its arrangement is kept; a DELETED
     * account is not, and rows namespaced to a uid that no longer exists would
     * otherwise sit on the device forever with nothing able to reach them.
     *
     * Takes the uid rather than using the loaded one, because the caller has it
     * and this store might not: deleting an account without ever having opened a
     * settings screen leaves [uid] at [NO_USER], and wiping the `anon` namespace
     * is both wrong and silent.
     */
    suspend fun forgetAccount(uid: String) {
        if (uid.isBlank()) return
        ALL_KEYS.forEach { base ->
            runCatching { Platform.storageManager.removeDurable("$base::$uid") }
        }
        // Drop to the signed-out namespace WITHOUT re-reading, unlike [reset].
        //
        // `reset()` reloads from `UID_KEY`, and this runs BEFORE the Firebase
        // sign-out clears it (`AuthBridge.signOutForAccountDeletion` tears down
        // and only then logs out — the order is load-bearing and commented as
        // such). So reloading here re-adopts the uid whose rows were just
        // deleted, and the store goes on treating a deleted account as the
        // current namespace: any write before the next sign-in recreates
        // storage for an account that no longer exists.
        //
        // There is nothing to read back in any case — the keys are gone — so
        // the honest end state is the empty one.
        this.uid = NO_USER
        _configs.value = emptyMap()
        _layout.value = HomeLayout.LIST
        _widgets.value = emptyMap()
        _widgetTotal.value = 0
        _loaded.value = false
    }

    /**
     * Let go of the session that is ending, WITHOUT destroying what is on disk.
     *
     * The disk is the point. These settings are namespaced by account and are
     * how the same person, signing back in on this device, gets the app they
     * left — so logout drops the in-memory copy and re-reads for whoever is
     * signed in next.
     *
     * ## The in-memory drop is not optional
     * Every flow here is process-wide state on an `object`, and [ensureLoaded]
     * returns early once [loaded] is set. Signing out and in as somebody else in
     * the SAME process would otherwise leave the previous user's arrangement in
     * memory, with nothing re-reading — and the next user's first change would
     * save THEIR edit on top of the previous user's layout, under their own id.
     */
    suspend fun reset() {
        _configs.value = emptyMap()
        _layout.value = HomeLayout.LIST
        _widgets.value = emptyMap()
        _widgetTotal.value = 0
        _loaded.value = false
        switchUser()
    }

    // ── Internals ───────────────────────────────────────────────────────────

    /**
     * The ONE way board configuration changes: read, transform, prune, publish,
     * persist — all under [writeMutex].
     *
     * ## Why the lock
     * The three public mutators each did their own `_configs.value.toMutableMap()`
     * → mutate → write-back. That is a read-modify-write on shared state with no
     * guard, so two of them overlapping lose one edit entirely: a drag-reorder
     * and a rows-per-platform toggle in the same moment both start from the map
     * as it was, and whichever writes second silently discards the other's work.
     * These are UI-driven and genuinely concurrent — the settings sheet and the
     * card behind it edit the same store, and every one of these is a `suspend`
     * function with a disk write inside it, so there is real time to overlap in.
     *
     * Serialising the whole transition also keeps disk consistent with memory:
     * the value published to [configs] and the value written to storage are the
     * same object, produced and persisted without another writer in between.
     *
     * ## Why the pruning lives here
     * Rows equal to the defaults are dropped rather than written, so boards the
     * user never configured never accumulate storage. [configOf] is what makes
     * that safe, and having exactly one place apply it is what stops a future
     * mutator forgetting to.
     */
    private suspend fun mutateConfigs(
        transform: (Map<String, BoardConfig>) -> Map<String, BoardConfig>,
    ) = writeMutex.withLock {
        val pruned = transform(_configs.value).filterValues { it != BoardConfig() }
        // A transform that changed nothing writes nothing. `forget` of a board
        // that was never configured, a re-drag into the same order, a toggle
        // back to the value already stored — all reach here, and all used to
        // cost a serialise, a disk write and a `StateFlow` emission that
        // recomposed every collector for no change at all. Comparing two small
        // maps is cheaper than any one of those.
        if (pruned == _configs.value) return@withLock
        _configs.value = pruned
        runCatching {
            Platform.storageManager.saveDurable(key(CONFIGS_KEY), json.encodeToString(configsSerializer, pruned))
        }
        Unit
    }

    /**
     * The signed-in account id.
     *
     * ⚠️ NOT its own string. [SessionStore] is the one declaration of every
     * identity key, and this used to be one of TWELVE spellings of the same
     * literal across four modules and two languages — which is how two readers
     * came to disagree about whether anyone was signed in, in one process.
     * Referencing it keeps this file's local name (every call site below reads
     * better for it) without adding a thirteenth place for the string to drift.
     */
    private val UID_KEY = SessionStore.Key.UID.storageKey
}
