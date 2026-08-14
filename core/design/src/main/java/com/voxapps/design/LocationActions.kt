package com.voxapps.design

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Opens [query] (free text — "Ploiești, PH, RO", a street, a venue name) as a place search in
 * whatever maps/navigation app the user picks. The `geo:` URI is the platform-standard,
 * vendor-neutral scheme every maps app registers for (OsmAnd, Organic Maps, Waze, Magic Earth,
 * Google Maps alike — no Google dependency here), and the explicit chooser means the SELECTION is
 * the user's every time rather than a remembered default silently winning.
 */
fun openLocationInMaps(context: Context, query: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: ActivityNotFoundException) {
        // No maps-capable app installed — nothing sane to open; the field stays a plain value.
    }
}
