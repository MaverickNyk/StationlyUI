import com.stationly.app.ui.selection.interpolate
import kotlin.test.Test
import kotlin.test.assertEquals

/** "Set up a Board": the served templates say "a {mode}". */
class SetupWordingTest {

    private fun title(mode: String?, stop: String = "station") =
        interpolate("Find a {mode} {stop}", mapOf("mode" to mode, "stop" to stop))

    @Test
    fun theArticleFitsTheMode() {
        assertEquals("Find an Overground station", title("Overground"))
        assertEquals("Find an Elizabeth Line station", title("Elizabeth Line"))
        assertEquals("Find a Tube station", title("Tube"))
        assertEquals("Find a DLR station", title("DLR"))
        assertEquals("Find a Bus stop", title("Bus", "stop"))
        // By sound, not by letter.
        assertEquals("Find a Uber Boat pier", title("Uber Boat", "pier"))
    }

    @Test
    fun onlyTheModeGetsTheArticleFixed() {
        assertEquals("Take a Overground train", interpolate("Take a {line} train", mapOf("line" to "Overground")))
    }

    @Test
    fun nothingElseChanges() {
        assertEquals("Find a station", title(null))
        assertEquals("Lines from Epping", interpolate("{lines} from {station}", mapOf("lines" to "Lines", "station" to "Epping")))
    }
}
