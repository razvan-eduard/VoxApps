package com.voxapps.commander.ui.screens.splash

import com.voxapps.commander.ui.LocalLanguageManager

import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
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
import com.voxapps.commander.R
import com.voxapps.commander.data.preferences.SettingsRepository
import com.voxapps.commander.data.remote.NativeLibManager
import com.voxapps.commander.data.remote.RemoteModelRegistry
import com.voxapps.commander.domain.intent.registry.AppRegistry
import com.voxapps.commander.domain.localization.LanguageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SplashLoadingScreen(

    settingsRepo: SettingsRepository,
    onFinished: () -> Unit
) {
        val languageManager = LocalLanguageManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadStatus by RemoteModelRegistry.loadStatus.collectAsStateWithLifecycle()
    val scanStatus by AppRegistry.scanStatus.collectAsStateWithLifecycle()
    val nativeStatus by NativeLibManager.status.collectAsStateWithLifecycle()
    val nativeProgress by NativeLibManager.downloadProgress.collectAsStateWithLifecycle()

    // Trigger app scan and native lib init
    LaunchedEffect(Unit) {
        if (scanStatus == AppRegistry.ScanStatus.IDLE) {
            scope.launch {
                AppRegistry.init(context)
                settingsRepo.setAppCache(AppRegistry.toJsonCache())
            }
        }
        // Initialize native libraries (DLC if needed)
        scope.launch {
            NativeLibManager.init(context)
        }
    }

    val assetsReady = loadStatus != RemoteModelRegistry.LoadStatus.LOADING
    val appsReady = scanStatus == AppRegistry.ScanStatus.DONE
    val nativeReady = nativeStatus == NativeLibManager.Status.READY || nativeStatus == NativeLibManager.Status.ERROR

    // Auto-advance when all critical startup components are ready
    LaunchedEffect(assetsReady, appsReady, nativeReady) {
        if (assetsReady && appsReady && nativeReady) {
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
            // Logo
            Image(
                painter = painterResource(id = R.drawable.splash_logo),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(horizontal = 4.dp),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Scanning apps indicator
            AnimatedVisibility(
                visible = scanStatus == AppRegistry.ScanStatus.SCANNING,
                enter = fadeIn()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Scanning installed apps...",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Apps loaded
            AnimatedVisibility(
                visible = scanStatus == AppRegistry.ScanStatus.DONE,
                enter = fadeIn()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Apps ready",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Loading assets indicator
            AnimatedVisibility(
                visible = loadStatus == RemoteModelRegistry.LoadStatus.LOADING,
                enter = fadeIn()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        strokeWidth = 3.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = languageManager.getString("splash_loading_assets"),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Assets loaded from remote
            AnimatedVisibility(
                visible = loadStatus == RemoteModelRegistry.LoadStatus.LOADED_FROM_REMOTE,
                enter = fadeIn()
            ) {
                Text(
                    text = languageManager.getString("splash_loaded_remote"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center
                )
            }

            // Assets loaded from cache
            AnimatedVisibility(
                visible = loadStatus == RemoteModelRegistry.LoadStatus.LOADED_FROM_CACHE,
                enter = fadeIn()
            ) {
                Text(
                    text = languageManager.getString("splash_loaded_cache"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
            }

            // No network at all
            AnimatedVisibility(
                visible = loadStatus == RemoteModelRegistry.LoadStatus.NO_NETWORK,
                enter = fadeIn()
            ) {
                Text(
                    text = languageManager.getString("splash_no_network"),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Downloading essential native libs
            AnimatedVisibility(
                visible = nativeStatus == NativeLibManager.Status.DOWNLOADING,
                enter = fadeIn()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(
                        progress = { nativeProgress },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Downloading core engine components...",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
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
                    text = "Engine ready",
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
                    text = "Failed to load engine. Check internet connection.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
