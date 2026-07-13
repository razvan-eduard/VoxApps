package com.voxapps.vision.ui.screens.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxapps.vision.R
import com.voxapps.vision.data.NativeLibManager
import com.voxapps.vision.ui.LocalLanguageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SplashLoadingScreen(
    onFinished: () -> Unit
) {
    val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val nativeStatus by NativeLibManager.status.collectAsStateWithLifecycle()
    val nativeProgress by NativeLibManager.downloadProgress.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        scope.launch {
            NativeLibManager.init(context)
        }
    }

    val nativeReady = nativeStatus == NativeLibManager.Status.READY || nativeStatus == NativeLibManager.Status.ERROR

    LaunchedEffect(nativeReady) {
        if (nativeReady) {
            delay(800)
            onFinished()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo (using a placeholder or generic logo if splash_logo missing)
            // Note: vox-vision doesn't have a specific splash_logo.png yet, using ic_launcher
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .aspectRatio(1f)
                    .padding(horizontal = 4.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Downloading essential native libs
            AnimatedVisibility(
                visible = nativeStatus == NativeLibManager.Status.DOWNLOADING || nativeStatus == NativeLibManager.Status.CHECKING,
                enter = fadeIn()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (nativeStatus == NativeLibManager.Status.DOWNLOADING) {
                        LinearProgressIndicator(
                            progress = { nativeProgress },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (nativeStatus == NativeLibManager.Status.DOWNLOADING) 
                            languageManager.getString("splash_loading_engine") 
                            else "Checking components...",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Native libs ready
            AnimatedVisibility(
                visible = nativeStatus == NativeLibManager.Status.READY,
                enter = fadeIn()
            ) {
                Text(
                    text = languageManager.getString("splash_engine_ready"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }

            // Native lib error
            AnimatedVisibility(
                visible = nativeStatus == NativeLibManager.Status.ERROR,
                enter = fadeIn()
            ) {
                Text(
                    text = languageManager.getString("splash_engine_error"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
