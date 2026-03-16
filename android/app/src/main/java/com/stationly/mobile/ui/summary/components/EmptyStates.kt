package com.stationly.mobile.ui.summary.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stationly.mobile.R
import com.stationly.mobile.ui.theme.TflAmber

@Composable
fun EmptyStationsState(onAction: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.AddCircle,
            contentDescription = null,
            tint = TflAmber,
            modifier = Modifier
                .size(72.dp)
                .padding(bottom = 24.dp)
        )
        
        Text(
            text = "Your Board is Empty",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
            color = Color.White
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Connect your first London station to see live departure signals.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = TflAmber),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(56.dp).fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Design Your Board", color = Color.Black, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun WelcomeEmptyState(onAddSelection: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.stationly_logo),
                contentDescription = null,
                modifier = Modifier.size(80.dp).graphicsLayer { alpha = 0.8f },
                contentScale = ContentScale.Fit
            )
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Welcome to Stationly",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Your digital window to London's transit.",
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp
                )
            }
            
            Button(
                onClick = onAddSelection,
                colors = ButtonDefaults.buttonColors(containerColor = TflAmber, contentColor = Color.Black),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(56.dp).fillMaxWidth(0.8f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text("Setup New Board", fontWeight = FontWeight.Bold)
            }
        }
    }
}
