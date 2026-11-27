package com.example.statusclear

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val vm: StatusViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Opened from another app's Share menu (e.g. Gallery → Share → StatusClear).
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            val state by vm.state.collectAsStateWithLifecycle()

            val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                uri?.let { vm.pick(it, true) }
            }
            val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                uri?.let { vm.pick(it, false) }
            }
            // For files the photo picker hides (Downloads, other folders).
            val browse = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { vm.pick(it, null) }
            }

            var reminder by remember { mutableStateOf(Updates.reminderDue(this)) }
            val actions = UiActions(
                version = remember { Updates.version(this) },
                updateReminder = reminder,
                checkUpdates = { reminder = false; Updates.openSite(this) },
                snoozeUpdates = { reminder = false; Prefs.snoozeUpdate(this) },
                pickVideo = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                pickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                browseFiles = { browse.launch(arrayOf("video/*", "image/*")) },
                shareAll = { Sharing.shareClips(this, it) },
                shareClip = { Sharing.shareClips(this, listOf(it)) },
                playClip = { playClip(this, it) }
            )
            StatusClearTheme { StatusClearApp(state, vm, actions) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (uri == null) return
        val type = intent.type.orEmpty()
        vm.pick(uri, when {
            type.startsWith("video/") -> true
            type.startsWith("image/") -> false
            else -> null
        })
    }

    private fun playClip(context: Context, clip: Clip) {
        val uri = Sharing.shareUri(context, clip) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "No video player found. The clip is in Gallery › Movies › ${Transcoder.SAVE_FOLDER}.", Toast.LENGTH_LONG).show()
        }
    }
}
