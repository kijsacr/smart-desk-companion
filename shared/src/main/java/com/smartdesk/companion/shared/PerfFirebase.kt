package com.smartdesk.companion.shared

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.database.FirebaseDatabase

/**
 * Returns the SAME Firebase Realtime Database the rest of the app uses.
 *
 * The bundled google-services.json only contains a placeholder database URL, while the real URL
 * is entered by the user in Settings (stored in each app's SharedPreferences). Performance Mode
 * must read that custom URL, otherwise reads/writes go to a non-existent database and hang forever.
 */
object PerfFirebase {
    private const val PHONE_PREFS = "smart_desk_phone_sender_prefs"
    private const val TABLET_PREFS = "smart_desk_tablet_sync_prefs"
    private const val KEY_CUSTOM_DB_URL = "custom_firebase_db_url"

    fun database(): FirebaseDatabase {
        val ctx = FirebaseApp.getInstance().applicationContext
        val url = listOf(PHONE_PREFS, TABLET_PREFS)
            .map { name ->
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                    .getString(KEY_CUSTOM_DB_URL, "")?.trim().orEmpty()
            }
            .firstOrNull { it.isNotEmpty() }
        return if (url != null) FirebaseDatabase.getInstance(url) else FirebaseDatabase.getInstance()
    }
}
