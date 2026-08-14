package com.voxapps.location.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.voxapps.location.AndroidLiveLocationProvider
import com.voxapps.location.VOX_NOMINATIM_USER_AGENT
import com.voxapps.location.VoxNominatimGeocoder
import com.voxapps.location.VoxPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Nominatim's usage policy allows one request per second — the debounce keeps a fast typist to
 *  one in-flight search, not one per keystroke. */
private const val SEARCH_DEBOUNCE_MILLIS = 1100L
private const val MIN_QUERY_LENGTH = 3

/**
 * The suite's location input: type to SEARCH places (OpenStreetMap Nominatim — free, keyless, no
 * Google) instead of spelling them from memory, pick a suggestion, or tap the GPS-lock icon to
 * stamp wherever you are right now. Manual text stays legal throughout — the value is the text,
 * and a caller that also wants the picked place's coordinates listens on [onPlacePicked].
 *
 * [gpsLock] is the host's own "current location as text" — each app wires its caching rules into
 * it (expenses caches like commander; calendar resolves fresh every time) so this component stays
 * policy-free. Null hides the icon. Tapping it with no location permission granted asks for it
 * first (coarse+fine), then resolves on grant.
 */
@Composable
fun VoxLocationPickerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onPlacePicked: ((VoxPlace) -> Unit)? = null,
    gpsLock: (suspend () -> String?)? = null,
    userAgent: String = VOX_NOMINATIM_USER_AGENT
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val geocoder = remember(userAgent) { VoxNominatimGeocoder(userAgent) }
    var suggestions by remember { mutableStateOf<List<VoxPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var gpsBusy by remember { mutableStateOf(false) }
    // Suppresses the search that the value-change of PICKING a suggestion (or a GPS stamp) would
    // otherwise fire — those values are already resolved places, not queries.
    var suppressSearchFor by remember { mutableStateOf<String?>(null) }

    fun runGpsLock() {
        val lock = gpsLock ?: return
        scope.launch {
            gpsBusy = true
            val resolved = lock()
            gpsBusy = false
            if (resolved != null) {
                suppressSearchFor = resolved
                suggestions = emptyList()
                onValueChange(resolved)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) runGpsLock()
    }

    LaunchedEffect(value) {
        if (value == suppressSearchFor) return@LaunchedEffect
        suppressSearchFor = null
        if (value.trim().length < MIN_QUERY_LENGTH) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(SEARCH_DEBOUNCE_MILLIS)
        searching = true
        suggestions = withContext(Dispatchers.IO) { geocoder.search(value.trim()) }
        searching = false
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it) },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                when {
                    searching || gpsBusy -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp
                    )
                    gpsLock != null -> IconButton(onClick = {
                        if (AndroidLiveLocationProvider.hasLocationPermission(context)) {
                            runGpsLock()
                        } else {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                )
                            )
                        }
                    }) {
                        Icon(
                            Icons.Filled.MyLocation,
                            contentDescription = label,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        )
        suggestions.forEach { place ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        suppressSearchFor = place.shortName
                        suggestions = emptyList()
                        onValueChange(place.shortName)
                        onPlacePicked?.invoke(place)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(place.shortName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    place.fullName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
