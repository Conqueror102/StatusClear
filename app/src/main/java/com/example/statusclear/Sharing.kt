package com.example.statusclear

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider

/** Handing finished clips to WhatsApp (or any app), shared by both screens. */
object Sharing {

    /** Gallery copy first; the app's own file via FileProvider as a fallback. */
    fun shareUri(context: Context, clip: Clip): Uri? =
        clip.galleryUri ?: runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", clip.result.file)
        }.getOrNull()

    /**
     * Opens WhatsApp (or a chooser if it isn't installed). Returns true if something opened.
     * [toStatus]: ask WhatsApp to open its Status composer directly instead of the "Send to"
     * list. This relies on WhatsApp honouring the (unofficial) "jid" extra; if it ever
     * stops, WhatsApp simply shows the normal "Send to" list instead.
     */
    fun shareClips(context: Context, clips: List<Clip>, toStatus: Boolean = false): Boolean {
        val uris = clips.mapNotNull { shareUri(context, it) }
        if (uris.isEmpty()) {
            Toast.makeText(context, "Couldn't find the clips to share. Try making them again.", Toast.LENGTH_LONG).show()
            return false
        }
        val base = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val intent = base.apply {
            type = "video/mp4"
            // ClipData is what actually carries the read grant on modern Android.
            clipData = ClipData.newRawUri(null, uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val whatsapp = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isInstalled(context, it) }
        return try {
            if (whatsapp != null) {
                // Go straight to WhatsApp — no chooser in the way.
                val direct = Intent(intent).setPackage(whatsapp)
                if (toStatus) direct.putExtra("jid", "status@broadcast")
                context.startActivity(direct)
            } else {
                context.startActivity(chooser(context, intent))
            }
            true
        } catch (_: Exception) {
            try {
                context.startActivity(chooser(context, intent))
                true
            } catch (_: Exception) {
                Toast.makeText(context, "No app found to share with.", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    /** System share list, without our own "HD Status" entry (it would loop). */
    private fun chooser(context: Context, intent: Intent): Intent =
        Intent.createChooser(intent, "Share to").putExtra(
            Intent.EXTRA_EXCLUDE_COMPONENTS,
            arrayOf(ComponentName(context, QuickShareActivity::class.java))
        )

    fun isInstalled(context: Context, pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/** Small remembered choices. */
object Prefs {
    private fun prefs(context: Context) = context.getSharedPreferences("statusclear", Context.MODE_PRIVATE)

    /** Last "Fit to Status screen" choice for wide pictures: true = Fill screen. */
    fun fill(context: Context): Boolean = prefs(context).getBoolean("fill", false)
    fun setFill(context: Context, on: Boolean) = prefs(context).edit().putBoolean("fill", on).apply()

    fun updateSnoozedAt(context: Context): Long = prefs(context).getLong("update_snoozed_at", 0L)
    fun snoozeUpdate(context: Context) = prefs(context).edit().putLong("update_snoozed_at", System.currentTimeMillis()).apply()

    /** We ask for the notification permission from the sheet at most once, and only when the person taps. */
    fun notifyAsked(context: Context): Boolean = prefs(context).getBoolean("notify_asked", false)
    fun setNotifyAsked(context: Context) = prefs(context).edit().putBoolean("notify_asked", true).apply()
}

/**
 * Update checks without internet access: the app never goes online, so it can't know
 * a new version exists. Instead it shows its version, opens the website on request,
 * and after a few weeks gently suggests checking.
 */
object Updates {
    const val SITE = "https://statusclear.hub2.workers.dev/"
    private const val REMIND_AFTER_MS = 42L * 24 * 60 * 60 * 1000 // 6 weeks

    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: ""

    /** True once this version has been installed for 6 weeks (and not snoozed since). */
    fun reminderDue(context: Context): Boolean {
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(System.currentTimeMillis())
        val since = maxOf(installed, Prefs.updateSnoozedAt(context))
        return System.currentTimeMillis() - since > REMIND_AFTER_MS
    }

    fun openSite(context: Context) {
        Prefs.snoozeUpdate(context)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SITE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(context, "Open $SITE in your browser to get the latest version.", Toast.LENGTH_LONG).show()
        }
    }
}
