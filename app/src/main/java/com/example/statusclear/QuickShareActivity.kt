package com.example.statusclear

import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.runtime.mutableStateOf
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * The "lazy" path: Gallery → Share → HD Status. A see-through screen with a small
 * sheet that prepares the video/photo with the remembered settings and hands it
 * straight to WhatsApp, then disappears. The full app is one tap away ("More options").
 */
class QuickShareActivity : ComponentActivity() {

    private val askNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifyOffered.value = false
        if (granted) QuickShareService.ensureChannels(this)
    }
    /** Show the one-time "Get a ping when it's ready" row. */
    private val notifyOffered = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Keep the screen on while it works; leaving is fine too (the service keeps going).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (savedInstanceState == null) {
            when (intent?.action) {
                Intent.ACTION_SEND -> {
                    val uri = sharedUri(intent)
                    if (uri == null) {
                        Toast.makeText(this, "Nothing to share was received.", Toast.LENGTH_SHORT).show()
                        finish()
                        return
                    }
                    QuickJob.start(this, uri, intent.type.orEmpty())
                }
                QuickShareService.ACTION_RESUME -> {
                    // Opened from a notification: show the existing job, start nothing new.
                    if (QuickJob.state.value is Quick.Idle) {
                        Toast.makeText(this, "That Status was cancelled. Share it again from your Gallery.", Toast.LENGTH_LONG).show()
                        finish()
                        return
                    }
                }
                else -> { finish(); return }
            }
        }
        notifyOffered.value = Build.VERSION.SDK_INT >= 33 && !QuickShareService.canNotify(this) && !Prefs.notifyAsked(this)

        // Open WhatsApp only while we're in front: Android blocks apps from launching
        // others from the background, so if the person left, the notification (or
        // coming back here) does the hand-off.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                QuickJob.state.collect { s ->
                    if (s is Quick.Done && QuickJob.markHandedOff()) {
                        QuickShareService.dismissReady(this@QuickShareActivity)
                        Sharing.shareClips(this@QuickShareActivity, s.clips, toStatus = true)
                        QuickJob.reset()
                        finish()
                    }
                }
            }
        }

        setContent {
            val state by QuickJob.state.collectAsStateWithLifecycle()
            val offerNotify by notifyOffered
            StatusClearTheme {
                BackHandler { leaveRunning() }
                QuickSheet(
                    state = state,
                    offerNotify = offerNotify && (state is Quick.Opening || state is Quick.Working),
                    onAllowNotify = ::requestNotify,
                    onCancel = ::cancelAndClose,
                    onOptions = ::openFullApp
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        QuickJob.sheetVisible = true
    }

    override fun onPause() {
        QuickJob.sheetVisible = false
        super.onPause()
    }

    private fun requestNotify() {
        Prefs.setNotifyAsked(this)
        if (Build.VERSION.SDK_INT >= 33) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Back = "I'll carry on with something else", not "cancel": the service keeps going and
     * the notification brings the person back. Only the Cancel buttons stop the work.
     */
    private fun leaveRunning() {
        if (!QuickJob.isBusy) { cancelAndClose(); return }
        Toast.makeText(
            this,
            if (QuickShareService.canNotify(this)) "Still making it HD. We'll ping you when it's ready."
            else "Still making it HD. It'll be saved in Gallery › Movies › ${Transcoder.SAVE_FOLDER}.",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    private fun cancelAndClose() {
        if (QuickJob.state.value !is Quick.Done) QuickJob.cancel(this)
        QuickJob.reset()
        finish()
    }

    /** Stop here and continue in the full app with the same video/photo. */
    private fun openFullApp() {
        val uri = QuickJob.sourceUri
        val type = QuickJob.sourceType
        QuickJob.cancel(this)
        uri?.let {
            val intent = Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType(type.ifBlank { contentResolver.getType(it) ?: "*/*" })
                .putExtra(Intent.EXTRA_STREAM, it)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.clipData = ClipData.newRawUri(null, it)
            runCatching { startActivity(intent) }
        }
        finish()
    }

    private fun sharedUri(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_SEND) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
}

// ---------------------------------------------------------------- UI

@Composable
private fun QuickSheet(
    state: Quick,
    offerNotify: Boolean,
    onAllowNotify: () -> Unit,
    onCancel: () -> Unit,
    onOptions: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            // Swallow taps on the dimmed area so a stray touch doesn't cancel the work.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            color = Palette.Card,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 16.dp)) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(Palette.Line)
                )
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(Palette.Green),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.AutoAwesome, null, tint = Color(0xFF04210F), modifier = Modifier.size(14.dp)) }
                    Spacer(Modifier.width(8.dp))
                    Text("StatusClear", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
                }
                Spacer(Modifier.height(14.dp))

                when (state) {
                    is Quick.Failed -> FailedBody(state, onCancel, onOptions)
                    else -> ProgressBody(state, offerNotify, onAllowNotify, onCancel, onOptions)
                }
            }
        }
    }
}

@Composable
private fun ProgressBody(state: Quick, offerNotify: Boolean, onAllowNotify: () -> Unit, onCancel: () -> Unit, onOptions: () -> Unit) {
    val thumb = when (state) {
        is Quick.Working -> state.thumbnail
        is Quick.Done -> state.thumbnail
        else -> null
    }
    val isVideo: Boolean? = (state as? Quick.Working)?.isVideo
    val pct = ((state as? Quick.Working)?.progress ?: 0f).coerceIn(0f, 1f)
    val (title, subtitle) = when (state) {
        is Quick.Opening, is Quick.Idle -> "Getting it ready…" to "Reading your file"
        is Quick.Working -> "Making it HD for Status" to buildString {
            if (state.parts > 1) append("Clip ${state.part} of ${state.parts} · ")
            else append(if (state.isVideo) "Video · " else "Photo · ")
            append("${(pct * 100).toInt()}%")
        }
        is Quick.Done -> "Opening WhatsApp…" to
            if (state.clips.size > 1) "${state.clips.size} clips, in order · saved to Gallery too" else "Saved to Gallery too"
        is Quick.Failed -> "" to ""
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Thumb(thumb, isVideo, done = state is Quick.Done)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = 14.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(16.dp))
    if (state is Quick.Working) {
        LinearProgressIndicator(
            progress = { pct },
            color = Palette.Green,
            trackColor = Palette.Line,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        )
    } else {
        LinearProgressIndicator(
            color = Palette.Green,
            trackColor = Palette.Line,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        )
    }
    Spacer(Modifier.height(10.dp))
    Text(
        if (state is Quick.Done) "Tap send in WhatsApp to post it."
        else "WhatsApp opens by itself when it's ready. You can switch apps; it keeps going.",
        fontSize = 13.sp, color = Palette.Muted
    )
    if (offerNotify) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.CardHi).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.NotificationsActive, null, tint = Palette.Green, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Get a ping when it's ready", fontSize = 14.sp, color = Palette.Text, modifier = Modifier.weight(1f))
            TextButton(onClick = onAllowNotify) { Text("Allow", color = Palette.Green, fontWeight = FontWeight.SemiBold) }
        }
    }
    Spacer(Modifier.height(12.dp))
    if (state !is Quick.Done) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOptions) { Text("More options", color = Palette.Green) }
            OutlinedButton(onClick = onCancel) { Text("Cancel", color = Palette.Text) }
        }
    }
}

@Composable
private fun FailedBody(state: Quick.Failed, onClose: () -> Unit, onOptions: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.ErrorOutline, null, tint = Palette.Danger, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Couldn't prepare this one", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Spacer(Modifier.height(4.dp))
            Text(state.message, fontSize = 14.sp, color = Palette.Muted)
        }
    }
    Spacer(Modifier.height(18.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
        OutlinedButton(onClick = onClose) { Text("Close", color = Palette.Text) }
        Button(
            onClick = onOptions,
            colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color(0xFF04210F))
        ) { Text("Open StatusClear", fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun Thumb(bitmap: Bitmap?, isVideo: Boolean?, done: Boolean) {
    Box(
        Modifier.size(60.dp).clip(RoundedCornerShape(14.dp)).background(Palette.CardHi),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            val icon = when (isVideo) { true -> Icons.Filled.Videocam; false -> Icons.Filled.Image; null -> Icons.Filled.AutoAwesome }
            Icon(icon, null, tint = Palette.Muted)
        }
        if (done) {
            Box(Modifier.fillMaxSize().background(Color(0x8804210F)), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Palette.Green),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.Check, null, tint = Color(0xFF04210F), modifier = Modifier.size(20.dp)) }
            }
        }
    }
}
