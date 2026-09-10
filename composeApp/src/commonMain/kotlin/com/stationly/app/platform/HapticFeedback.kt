package com.stationly.app.platform

enum class HapticType {
    /** A discrete thing happened: a button was pressed, a choice was made. */
    TAP,

    /**
     * A value moved to the next stop in a continuous control — a slider detent,
     * a picker rolling past an entry.
     *
     * Deliberately not [TAP]. iOS has a separate generator for this and it is
     * noticeably lighter and drier, because the two are answering different
     * questions: a tap says "that registered", a selection says "you are on
     * three now". Firing an impact per detent through a drag feels like the
     * control is being struck rather than turned.
     */
    SELECTION,

    SUCCESS,
    ERROR,
}

expect fun performHaptic(type: HapticType)
