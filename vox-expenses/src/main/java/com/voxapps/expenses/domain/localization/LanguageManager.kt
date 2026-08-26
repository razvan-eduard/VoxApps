package com.voxapps.expenses.domain.localization

/**
 * The implementation lives in :core:i18n — one loader, one plural convention, six apps. The alias
 * keeps this app's historical import path alive so call sites did not have to move.
 */
typealias LanguageManager = com.voxapps.i18n.LanguageManager
