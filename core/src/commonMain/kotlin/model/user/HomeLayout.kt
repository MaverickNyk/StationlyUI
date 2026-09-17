package com.stationly.core.model.user

import kotlinx.serialization.Serializable

/**
 * How the home screen arranges the user's boards.
 *
 * [LIST] stacks every board down one scrolling page, expanded or collapsed per
 * [BoardConfig.expanded]. [CAROUSEL] gives each a page of its own, swiped left
 * and right, with nothing collapsed.
 *
 * They answer different questions. A list is for "what is happening across my
 * stations" and trades board height for breadth; a carousel is for "what is
 * happening at THIS station" and spends the whole screen on one board. Neither
 * is a better default for everyone, which is why it is a setting.
 *
 * [BoardConfig.position] drives both: it is the top-to-bottom order of the list
 * and the left-to-right order of the pages.
 *
 * Device-local like every other appearance setting — see `UserSettings` for the
 * split between what syncs and what does not.
 */
@Serializable
enum class HomeLayout(val label: String) {
    LIST("List"),
    CAROUSEL("Carousel"),
}
