import com.stationly.app.ui.selection.MODE_NAME_CELL_DP
import com.stationly.app.ui.selection.modeColumnCount
import kotlin.test.Test
import kotlin.test.assertEquals

/** The mode step: two cards a row on a phone, more on a tablet. Widths in dp. */
class ModeColumnsTest {

    /** 20dp of padding each side and 12dp between cards, as the screen has. */
    private fun columns(screen: Int, fontScale: Float = 1f) = modeColumnCount(
        screen - 40, spacing = 12, minCell = 170, minHalf = (MODE_NAME_CELL_DP * fontScale).toInt(),
    )

    @Test
    fun everyPhoneShowsTwo() {
        for (screen in listOf(320, 360, 375, 384, 390, 393, 411, 430, 440)) assertEquals(2, columns(screen), "$screen dp")
    }

    @Test
    fun oneWhenTheNameWouldNotFitHalfTheWidth() {
        assertEquals(2, columns(360, 1.15f))
        assertEquals(1, columns(360, 1.3f))
        assertEquals(1, columns(320, 1.15f))
    }

    /** What these screens showed before, whatever the text size. */
    @Test
    fun widerScreensAreUnchanged() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            assertEquals(2, columns(393, scale))
            assertEquals(2, columns(430, scale))
            assertEquals(3, columns(600, scale))
            assertEquals(4, columns(820, scale))
        }
    }
}
