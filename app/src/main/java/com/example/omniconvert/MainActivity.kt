package com.example.omniconvert

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConverterScreen()
                }
            }
        }
    }
}

@Composable
fun ConverterScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFormat by remember { mutableStateOf(TargetFormat.JPEG) }
    var isProcessing by remember { mutableStateOf(false) }

    // Modern Android Photo Picker Launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        selectedImageUri = uri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Local Image Converter",
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "100% Offline • On-Device Processing",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Image Preview Box
        if (selectedImageUri != null) {
            AsyncImage(
                model = selectedImageUri,
                contentDescription = "Selected Image",
                modifier = Modifier
                    .size(200.dp)
                    .padding(8.dp)
            )
        } else {
            OutlinedCard(
                modifier = Modifier
                    .size(200.dp)
                    .padding(8.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text("No file selected", color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }) {
            Text("Select Image")
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Target Format Selector
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TargetFormat.values().forEach { format ->
                FilterChip(
                    selected = selectedFormat == format,
                    onClick = { selectedFormat = format },
                    label = { Text(format.name) }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Convert Button
        Button(
            enabled = selectedImageUri != null && !isProcessing,
            onClick = {
                val uri = selectedImageUri ?: return@Button
                isProcessing = true

                coroutineScope.launch {
                    val result = ImageConverter.convertAndSaveImage(
                        context = context,
                        inputUri = uri,
                        targetFormat = selectedFormat
                    )

                    isProcessing = false
                    if (result.isSuccess) {
                        Toast.makeText(context, "Saved to Pictures/LocalConvert!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Error: ${result.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isProcessing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Convert & Save to Device")
            }
        }
    }
}