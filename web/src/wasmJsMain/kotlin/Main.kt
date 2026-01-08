import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.CanvasBasedWindow
import com.stationly.mobile.ui.summary.SummaryScreen
import com.stationly.mobile.ui.theme.StationlyTheme

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    CanvasBasedWindow(title = "Stationly") {
        // We will switch between screens here later
        StationlyTheme {
             SummaryScreen(
                 onNavigateToSelection = {
                     println("Navigate to selection")
                 }
             )
        }
    }
}
