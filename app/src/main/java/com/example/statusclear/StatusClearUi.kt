package com.example.statusclear

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- theme

object Palette {
    val Bg = Color(0xFF0E1116)
    val Card = Color(0xFF171C23)
    val CardHi = Color(0xFF1F262F)
    val Line = Color(0xFF2A323C)
    val Green = Color(0xFF25D366)
    val GreenDim = Color(0xFF123322)
    val Text = Color(0xFFF1F4F7)
    val Muted = Color(0xFF8B95A1)
    val Danger = Color(0xFFFF6B6B)
}

@Composable
fun StatusClearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Green,
            onPrimary = Color(0xFF04210F),
            secondary = Palette.Green,
            background = Palette.Bg,
            onBackground = Palette.Text,
            surface = Palette.Card,
            onSurface = Palette.Text,
            surfaceVariant = Palette.CardHi,
            onSurfaceVariant = Palette.Muted,
            outline = Palette.Line,
            error = Palette.Danger
        ),
        content = content
    )
}

/** Everything the screen asks the Activity to do (pickers, intents). */
class UiActions(
    val pickVideo: () -> Unit,
    val pickPhoto: () -> Unit,
    val browseFiles: () -> Unit,
    val shareAll: (List<Clip>) -> Unit,
    val shareClip: (Clip) -> Unit,
    val playClip: (Clip) -> Unit
)

// ---------------------------------------------------------------- root

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusClearApp(state: UiState, vm: StatusViewModel, actions: UiActions) {
    val screen = state.screen
    var confirmCancel by remember { mutableStateOf(false) }

    BackHandler(enabled = screen !is Screen.Home && screen !is Screen.Loading) {
        when (screen) {
            is Screen.Working -> confirmCancel = true
            else -> vm.reset()
        }
    }
    if (screen is Screen.Working) KeepScreenOn()

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            TopAppBar(
                title = {
                    if (screen is Screen.Home) BrandTitle() else Text(
                        when (screen) {
                            is Screen.Ready -> if (screen.info.isVideo) "Your video" else "Your photo"
                            is Screen.Working -> "Preparing"
                            is Screen.Done -> "Ready to post"
                            else -> ""
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    if (screen is Screen.Ready || screen is Screen.Done) {
                        IconButton(onClick = vm::reset) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Start over") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Bg)
            )
        },
        bottomBar = {
            when (screen) {
                is Screen.Ready -> BottomAction {
                    PrimaryButton(
                        text = if (screen.info.isVideo && screen.info.partCount > 1) "Make ${screen.info.partCount} HD clips" else "Make HD Status",
                        icon = Icons.Filled.AutoAwesome,
                        onClick = vm::start
                    )
                }
                is Screen.Done -> BottomAction {
                    PrimaryButton(
                        text = if (screen.clips.size > 1) "Share all ${screen.clips.size} to WhatsApp" else "Share to WhatsApp",
                        icon = Icons.Filled.Share,
                        onClick = { actions.shareAll(screen.clips) }
                    )
                }
                else -> {}
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(state.error != null) {
                ErrorBanner(state.error.orEmpty(), vm::dismissError)
            }
            AnimatedContent(
                targetState = screen,
                contentKey = { it::class },
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen"
            ) { s ->
                when (s) {
                    Screen.Home -> HomeScreen(actions)
                    Screen.Loading -> CenterBox { CircularProgressIndicator() }
                    is Screen.Ready -> ReadyScreen(s, vm::setZoomText, vm::setFill, actions)
                    is Screen.Working -> WorkingScreen(s) { confirmCancel = true }
                    is Screen.Done -> DoneScreen(s, actions, vm::retrySave, vm::reset)
                }
            }
        }
    }

    if (confirmCancel && screen is Screen.Working) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Stop preparing?") },
            text = { Text("The clips made so far will be thrown away.") },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; vm.cancel() }) { Text("Stop", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep going") } },
            containerColor = Palette.CardHi
        )
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun BrandTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Palette.Green),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.AutoAwesome, null, tint = Color(0xFF04210F), modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(10.dp))
        Text("StatusClear", fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun HomeScreen(actions: UiActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text("Post a sharp Status,\nnot a blurry one.", fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
        Text(
            "WhatsApp squeezes anything that doesn't fit its limits. We resize it to exactly what it expects, so it stays clear.",
            color = Palette.Muted, fontSize = 15.sp, lineHeight = 21.sp
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PickTile(
                Modifier.weight(1f), Icons.Filled.Videocam, "Video",
                "Long videos are split into 30-second clips", actions.pickVideo
            )
            PickTile(
                Modifier.weight(1f), Icons.Filled.Image, "Photo",
                "Pictures and screenshots", actions.pickPhoto
            )
        }
        TextButton(
            onClick = actions.browseFiles,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Can't see it? Browse all files")
        }
        SectionCard {
            Text("How it works", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            StepRow(1, "Pick a video or photo", "From your gallery, or shared from any app.")
            StepRow(2, "We prepare it", "Right on your phone — nothing is uploaded.")
            StepRow(3, "Share to WhatsApp", "Post to My status. It stays sharp.", last = true)
        }
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Lock, null, tint = Palette.Muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Private: your files never leave this phone", color = Palette.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PickTile(modifier: Modifier, icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(176.dp),
        shape = RoundedCornerShape(22.dp),
        color = Palette.Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Palette.GreenDim),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = Palette.Green, modifier = Modifier.size(28.dp)) }
            Column {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(2.dp))
                // Always reserve two lines so both tiles' titles line up.
                Text(subtitle, color = Palette.Muted, fontSize = 13.sp, lineHeight = 17.sp, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun StepRow(n: Int, title: String, body: String, last: Boolean = false) {
    Row(Modifier.padding(bottom = if (last) 0.dp else 14.dp)) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(Palette.GreenDim),
            contentAlignment = Alignment.Center
        ) { Text("$n", color = Palette.Green, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            Text(body, color = Palette.Muted, fontSize = 13.sp)
        }
    }
}

// ---------------------------------------------------------------- ready

@Composable
private fun ReadyScreen(r: Screen.Ready, onZoom: (Boolean) -> Unit, onFill: (Boolean) -> Unit, actions: UiActions) {
    val preview = remember(r.thumbnail, r.zoomText) {
        val t = r.thumbnail
        if (t != null && r.zoomText && !r.info.isVideo) {
            val c = Transcoder.cropRect(t.width, t.height)
            runCatching { Bitmap.createBitmap(t, c.left, c.top, c.width(), c.height()) }.getOrDefault(t)
        } else t
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Status is portrait 9:16. Anything else gets framed: whole picture with black
        // bars, or cropped to fill. (Landscape posted as-is was cut to 640x360 by Status.)
        val needsFrame = (r.zoomText && !r.info.isVideo) || Transcoder.needsFraming(r.info.width, r.info.height)
        val fill = needsFrame && r.fill
        PhonePreview(preview, fill, Modifier.widthIn(max = 220.dp).fillMaxWidth(0.62f))
        Text(
            "Preview of how it will look on Status",
            color = Palette.Muted, fontSize = 12.sp
        )
        SectionCard {
            Text(r.info.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.info.isVideo) Chip(formatDuration(r.info.durationMs))
                Chip("${r.info.width}×${r.info.height}")
                if (r.info.sizeBytes > 0) Chip(formatSize(r.info.sizeBytes))
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = Palette.Green, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    if (r.info.isVideo) {
                        if (r.info.partCount > 1) "Becomes ${r.info.partCount} HD clips of up to 30 s — WhatsApp's Status limit."
                        else "Becomes 1 HD clip, ready for Status."
                    } else "Becomes a sharp 5-second HD clip — photos stay crisper this way than as images.",
                    fontSize = 14.sp, lineHeight = 19.sp
                )
            }
        }
        if (needsFrame) {
            SectionCard {
                Text("Fit to Status screen", fontWeight = FontWeight.Medium)
                Text(
                    "Status is a tall screen. WhatsApp shrinks wide pictures, so we place yours in a tall frame.",
                    color = Palette.Muted, fontSize = 13.sp, lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FrameOption(Modifier.weight(1f), "Whole picture", "Black bars above and below", !r.fill) { onFill(false) }
                    FrameOption(Modifier.weight(1f), "Fill screen", "Crops the sides", r.fill) { onFill(true) }
                }
            }
        }
        if (!r.info.isVideo) {
            SectionCard(Modifier.clickable { onZoom(!r.zoomText) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ZoomIn, null, tint = Palette.Muted)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Zoom into text", fontWeight = FontWeight.Medium)
                        Text("For screenshots with small writing", color = Palette.Muted, fontSize = 13.sp)
                    }
                    Switch(
                        checked = r.zoomText,
                        onCheckedChange = onZoom,
                        colors = SwitchDefaults.colors(checkedTrackColor = Palette.Green)
                    )
                }
            }
        }
        TextButton(onClick = if (r.info.isVideo) actions.pickVideo else actions.pickPhoto) {
            Text("Choose a different ${if (r.info.isVideo) "video" else "photo"}")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FrameOption(modifier: Modifier, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Palette.GreenDim else Palette.CardHi)
            .border(1.5.dp, if (selected) Palette.Green else Palette.Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (selected) Palette.Green else Palette.Text)
        Text(subtitle, color = Palette.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun PhonePreview(bitmap: Bitmap?, fill: Boolean, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)
            .border(1.dp, Palette.Line, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(), null, Modifier.fillMaxSize(),
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit
            )
        } else {
            Icon(Icons.Filled.Image, null, tint = Palette.Muted, modifier = Modifier.size(40.dp))
        }
    }
}

// ---------------------------------------------------------------- working

@Composable
private fun WorkingScreen(w: Screen.Working, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { w.progress },
                modifier = Modifier.size(168.dp),
                strokeWidth = 10.dp,
                trackColor = Palette.CardHi
            )
            Text("${(w.progress * 100).toInt()}%", fontSize = 36.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        Text(
            when {
                w.cancelling -> "Stopping…"
                w.parts > 1 -> "Preparing clip ${w.part} of ${w.parts}"
                else -> "Preparing your Status"
            },
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Keep StatusClear open. Everything happens on your phone, so it can take a minute for long videos.",
            color = Palette.Muted, textAlign = TextAlign.Center, fontSize = 14.sp, lineHeight = 20.sp
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel, enabled = !w.cancelling) {
            Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Cancel")
        }
    }
}

// ---------------------------------------------------------------- done

@Composable
private fun DoneScreen(d: Screen.Done, actions: UiActions, onRetrySave: () -> Unit, onNew: () -> Unit) {
    var showHelp by rememberSaveable { mutableStateOf(true) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Palette.Green),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Check, null, tint = Color(0xFF04210F), modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    if (d.clips.size > 1) "${d.clips.size} clips ready" else "Your clip is ready",
                    fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
                Text(
                    if (d.allSaved) "Saved to Gallery › Movies › ${Transcoder.SAVE_FOLDER}" else "Not saved to Gallery yet",
                    color = if (d.allSaved) Palette.Muted else Palette.Danger, fontSize = 13.sp
                )
            }
        }
        if (!d.allSaved) {
            OutlinedButton(onClick = onRetrySave, modifier = Modifier.fillMaxWidth()) { Text("Try saving to Gallery again") }
        }

        if (d.clips.size > 1) {
            Text("Post them in this order", color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            d.clips.forEachIndexed { i, clip -> ClipRow(i, d.clips.size, clip, actions) }
        }

        SectionCard {
            Row(
                Modifier.fillMaxWidth().clickable { showHelp = !showHelp },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("How to post without losing quality", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(if (showHelp) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = Palette.Muted)
            }
            AnimatedVisibility(showHelp) {
                Column(Modifier.padding(top = 14.dp)) {
                    StepRow(1, "Tap Share to WhatsApp", "Pick My status, or send it to yourself first.")
                    StepRow(2, "Turn on HD", "If WhatsApp shows an HD button, tap it before sending.")
                    StepRow(3, "Don't trim or edit in WhatsApp", "Any edit makes WhatsApp compress it again.")
                    StepRow(4, "Post from your phone", "WhatsApp on a computer can't post HD Status.", last = true)
                }
            }
        }
        TextButton(onClick = onNew, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Make another")
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ClipRow(i: Int, total: Int, clip: Clip, actions: UiActions) {
    Surface(
        onClick = { actions.playClip(clip) },
        shape = RoundedCornerShape(18.dp),
        color = Palette.Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Line)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(54.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(10.dp)).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                clip.thumbnail?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(18.dp)) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (total > 1) "Clip ${i + 1} of $total" else "Status clip", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${formatDuration(clip.result.durationMs)} · ${formatSize(clip.result.sizeBytes)}",
                    color = Palette.Muted, fontSize = 13.sp
                )
                Text(
                    clip.result.details,
                    color = if (clip.result.verified) Palette.Green else Palette.Muted, fontSize = 12.sp
                )
            }
            FilledTonalIconButton(
                onClick = { actions.shareClip(clip) },
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Palette.GreenDim, contentColor = Palette.Green)
            ) { Icon(Icons.Filled.Share, "Share clip ${i + 1}") }
        }
    }
}

// ---------------------------------------------------------------- bits

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF3A1D20))
            .padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.ErrorOutline, null, tint = Palette.Danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(message, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 19.sp)
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss", tint = Palette.Muted) }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Card)
            .border(1.dp, Palette.Line, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) { content() }
}

@Composable
private fun Chip(text: String) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.CardHi).padding(horizontal = 10.dp, vertical = 5.dp),
        fontSize = 13.sp, color = Palette.Text
    )
}

@Composable
private fun BottomAction(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Palette.Bg)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) { content() }
}

@Composable
private fun PrimaryButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Green, contentColor = Color(0xFF04210F)),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1048576.0)
}

fun formatDuration(ms: Long): String {
    val s = ((ms + 500) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
