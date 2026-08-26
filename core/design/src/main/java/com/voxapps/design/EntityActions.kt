package com.voxapps.design

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * What tapping a piece of recognised text does: the sibling of [openLocationInMaps] for the other
 * kinds of thing a line can be. Same shape throughout — an implicit intent, the user's own apps,
 * and a quiet nothing when no app answers, because a missing dialer is not this text's failure.
 *
 * Every action takes an optional [packageName]: null is the system default — the chooser, or
 * whatever the platform resolves — and a package is the app the person picked for this kind of
 * thing in settings. A picked app that stopped existing falls back to the default path rather
 * than failing the tap: the choice was a preference, not a dependency.
 *
 * Nothing here acts on the user's behalf: dialing prefills and stops, mail opens a draft, search
 * opens results. The tap is an offer accepted, and what happens next stays in the app it opened.
 */
object EntityActions {

    /** Opens the dialer with [number] filled in. ACTION_DIAL by design — it needs no permission
     *  and places no call; the green button stays the person's to press. */
    fun dial(context: Context, number: String, packageName: String? = null) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
        launchPreferring(context, intent, packageName)
    }

    /** Opens the messaging app with [number] as the recipient — the SENDTO twin of [dial], and
     *  like it nothing is sent. */
    fun composeSms(context: Context, number: String, packageName: String? = null) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}"))
        launchPreferring(context, intent, packageName)
    }

    /**
     * Hands [number] to one specific app, whatever kind of app it is, most specific carrier first:
     * a dialer takes the tel:, a messenger its own published chat scheme (a handful are known — a
     * plain smsto: opens some of them on a compose-SMS screen rather than the chat) or the
     * standard smsto:, and anything else still receives the number as shared text. A number
     * without its country code may be refused by the app itself: the chip is an offer, not a
     * promise.
     */
    fun phoneToApp(context: Context, number: String, packageName: String) {
        if (tryLaunch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")).setPackage(packageName))) return
        val digits = number.filter { it.isDigit() }
        val ownScheme = when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "https://wa.me/$digits"
            "org.thoughtcrime.securesms" -> "https://signal.me/#p/+$digits"
            "com.viber.voip" -> "viber://chat?number=%2B$digits"
            "org.telegram.messenger", "org.telegram.messenger.web" -> "tg://resolve?phone=$digits"
            else -> null
        }
        if (ownScheme != null &&
            tryLaunch(context, Intent(Intent.ACTION_VIEW, Uri.parse(ownScheme)).setPackage(packageName))
        ) {
            return
        }
        if (tryLaunch(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).setPackage(packageName))) return
        textToApp(context, number, packageName)
    }

    /** [address] to one specific app: as a mail draft if it takes mailto:, as shared text if not. */
    fun emailToApp(context: Context, address: String, packageName: String) {
        if (tryLaunch(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(address)}")).setPackage(packageName))) return
        textToApp(context, address, packageName)
    }

    /** [url] to one specific app: opened if it takes links, shared text if not. */
    fun urlToApp(context: Context, url: String, packageName: String) {
        if (tryLaunch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(packageName))) return
        textToApp(context, url, packageName)
    }

    /** [query] as a place to one specific app: the geo: search if it takes one, shared text if not. */
    fun placeToApp(context: Context, query: String, packageName: String) {
        if (tryLaunch(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}")).setPackage(packageName))) return
        textToApp(context, query, packageName)
    }

    /** [text] to one specific app as plain shared text — the carrier of last resort every other
     *  *ToApp falls back to, and the whole carrier for text only its target understands. */
    fun textToApp(context: Context, text: String, packageName: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (tryLaunch(context, Intent(send).setPackage(packageName))) return
        // The picked app is gone or takes nothing — the person still gets their chooser.
        launch(context, Intent.createChooser(send, null))
    }

    /**
     * Turn-by-turn to [query] rather than a pin on it: the waze scheme when Waze is the pick or
     * answers, the plain geo: search otherwise — a navigation app treats that as a destination
     * anyway, and no vendor is hardcoded in preference to another.
     */
    fun navigate(context: Context, query: String, packageName: String? = null) {
        val waze = Intent(Intent.ACTION_VIEW, Uri.parse("waze://?q=${Uri.encode(query)}&navigate=yes"))
        if (packageName != null) {
            if (tryLaunch(context, Intent(waze).setPackage(packageName))) return
            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
            if (tryLaunch(context, geo.setPackage(packageName))) return
        }
        if (tryLaunch(context, waze)) return
        openLocationInMaps(context, query)
    }

    /** Opens a mail draft to [address] in the picked client, or lets the user choose. ACTION_SENDTO
     *  with a mailto: keeps it to actual mail apps — ACTION_SEND would offer every share target
     *  there is. */
    fun composeEmail(context: Context, address: String, packageName: String? = null) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(address)}"))
        if (packageName != null) {
            launchPreferring(context, intent, packageName)
        } else {
            launch(context, Intent.createChooser(intent, null))
        }
    }

    /** Opens [url] in the picked browser, or the app that owns the link. */
    fun openUrl(context: Context, url: String, packageName: String? = null) {
        launchPreferring(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), packageName)
    }

    /**
     * Searches the web for [query] — in the picked app, else the user's own search app with the
     * user's own engine.
     *
     * The fallback for devices with no search activity is a plain browser search on DuckDuckGo —
     * an engine with no account to leak the query into, matching how the rest of this codebase
     * treats what leaves the phone.
     */
    fun searchWeb(context: Context, query: String, packageName: String? = null) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=${Uri.encode(query)}"))
        if (packageName != null) {
            // The picked app takes the search however it can: as a search intent if it answers
            // one, as a plain results page if it is a browser.
            if (tryLaunch(context, Intent(search).setPackage(packageName))) return
            if (tryLaunch(context, Intent(fallback).setPackage(packageName))) return
        }
        if (tryLaunch(context, search)) return
        launch(context, fallback)
    }

    /**
     * Searches for [query] as a place, in the picked maps app — or through the vendor-neutral
     * chooser [openLocationInMaps] already is, which stays the null-package path unchanged.
     */
    fun openMaps(context: Context, query: String, packageName: String? = null) {
        if (packageName == null) {
            openLocationInMaps(context, query)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
        if (!tryLaunch(context, Intent(intent).setPackage(packageName))) {
            openLocationInMaps(context, query)
        }
    }

    /** Hands [text] to the picked app as plain text, or to whichever the person chooses — the
     *  carrier for a custom category, whose meaning only its target app knows. */
    fun sendText(context: Context, text: String, packageName: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (packageName != null) {
            if (tryLaunch(context, Intent(intent).setPackage(packageName))) return
        }
        launch(context, Intent.createChooser(intent, null))
    }

    /** Sends [intent] to [packageName] when one was picked, falling back to the bare intent when
     *  the pick no longer resolves; plain launch otherwise. */
    private fun launchPreferring(context: Context, intent: Intent, packageName: String?) {
        if (packageName != null && tryLaunch(context, Intent(intent).setPackage(packageName))) return
        launch(context, intent)
    }

    private fun tryLaunch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun launch(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No app answers this kind of thing — the text stays text.
        }
    }
}
