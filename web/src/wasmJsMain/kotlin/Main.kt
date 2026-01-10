import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.stationly.mobile.ui.theme.StationlyTheme

@JsName("console")
external object console {
    fun log(message: String)
    fun error(message: String)
}

@Composable
fun App() {
    console.log("App composable rendering")
    
    StationlyTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1F1F1F)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .wrapContentHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Logo/Title
                Text(
                    text = "Stationly",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0066FF),
                    modifier = Modifier.padding(bottom = 16.dp),
                    textAlign = TextAlign.Center
                )
                
                // Subtitle
                Text(
                    text = "London Underground Departure Board",
                    fontSize = 18.sp,
                    color = Color(0xFFCCCCCC),
                    modifier = Modifier.padding(bottom = 40.dp),
                    textAlign = TextAlign.Center
                )
                
                // Coming Soon Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = Color(0xFF0066FF),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "🚀 Coming Soon",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        
                        Text(
                            text = "Download the Stationly app very soon to get real-time London Underground departure information at your fingertips.",
                            fontSize = 16.sp,
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                
                // Features hint
                Spacer(modifier = Modifier.height(40.dp))
                
                Text(
                    text = "✓ Real-time departures\n✓ Line status updates\n✓ Saved stations\n✓ Available on iOS, Android & Web",
                    fontSize = 14.sp,
                    color = Color(0xFFAAAAAA),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

sealed class Screen {
    object Summary : Screen()
    object Selection : Screen()
}

fun main() {
    console.log("Stationly main() called - initializing app")
}
