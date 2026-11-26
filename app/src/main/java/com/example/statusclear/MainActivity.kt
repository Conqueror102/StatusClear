package com.example.statusclear

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.content.FileProvider
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

            val actions = UiActions(
                pickVideo = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                pickPhoto = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                browseFiles = { browse.launch(arrayOf("video/*", "image/*")) },
                shareAll = { shareClips(this, it) },
                shareClip = { shareClips(this, listOf(it)) },
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

    // ------------------------------------------------------------ sharing

    /** Gallery copy first; the app's own file via FileProvider as a fallback. */
    private fun shareUri(context: Context, clip: Clip): Uri? =
        clip.galleryUri ?: runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", clip.result.file)
        }.getOrNull()

    private fun shareClips(context: Context, clips: List<Clip>) {
        val uris = clips.mapNotNull { shareUri(context, it) }
        if (uris.isEmpty()) {
            Toast.makeText(context, "Couldn't find the clips to share. Try making them again.", Toast.LENGTH_LONG).show()
            return
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
        try {
            if (whatsapp != null) {
                // Go straight to WhatsApp — no chooser in the way.
                context.startActivity(Intent(intent).setPackage(whatsapp))
            } else {
                context.startActivity(Intent.createChooser(intent, "Share to"))
            }
        } catch (_: Exception) {
            try {
                context.startActivity(Intent.createChooser(intent, "Share to"))
            } catch (_: Exception) {
                Toast.makeText(context, "No app found to share with.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playClip(context: Context, clip: Clip) {
        val uri = shareUri(context, clip) ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "No video player found. The clip is in Gallery › Movies › ${Transcoder.SAVE_FOLDER}.", Toast.LENGTH_LONG).show()
        }
    }

    private fun isInstalled(context: Context, pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
