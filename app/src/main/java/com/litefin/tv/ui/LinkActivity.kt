package com.litefin.tv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.litefin.tv.data.Prefs

/**
 * Entry point of the Android TV home screen rows: litefin://play/{userId}/{itemId} (and litefin://home).
 * Selects the profile the row was made for, then opens Home with the item page on top, playing.
 */
class LinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parts = intent.data?.pathSegments.orEmpty()
        val account = Prefs.accounts.firstOrNull { it.userId == parts.getOrNull(0) }
        val itemId = parts.getOrNull(1)
        val home = Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        when {
            intent.data?.host == "play" && account != null && itemId != null -> {
                Prefs.current = account
                startActivities(arrayOf(home, Intent(this, ItemActivity::class.java).putExtra("id", itemId).putExtra("autoplay", true)))
            }
            Prefs.current != null -> startActivity(home)
            else -> startActivity(Intent(this, UsersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
    }
}
