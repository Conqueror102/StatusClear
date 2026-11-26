package com.example.statusclear

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One finished clip, plus where it was saved in the Gallery. */
data class Clip(
    val result: Transcoder.PartResult,
    val galleryUri: Uri?,
    val thumbnail: Bitmap?
)

sealed interface Screen {
    data object Home : Screen
    data object Loading : Screen
    data class Ready(
        val uri: Uri,
        val info: Transcoder.SourceInfo,
        val thumbnail: Bitmap?,
        val zoomText: Boolean = false,
        /** Landscape / non-9:16 only: crop to fill the screen instead of black bars. */
        val fill: Boolean = false
    ) : Screen
    data class Working(
        val ready: Ready,
        val progress: Float = 0f,
        val part: Int = 1,
        val parts: Int = 1,
        val cancelling: Boolean = false
    ) : Screen
    data class Done(
        val ready: Ready,
        val clips: List<Clip>
    ) : Screen {
        val allSaved get() = clips.all { it.galleryUri != null }
    }
}

data class UiState(
    val screen: Screen = Screen.Home,
    /** Shown as a dismissable banner; does not replace the current screen. */
    val error: String? = null
)

class StatusViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val cancelFlag = AtomicBoolean(false)
    private var job: Job? = null

    /** [isVideo] null = work it out from the file's type. */
    fun pick(uri: Uri, isVideo: Boolean?) {
        if (_state.value.screen is Screen.Working) return
        val ctx = getApplication<Application>()
        _state.value = UiState(Screen.Loading)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val video = isVideo ?: Transcoder.isVideoUri(ctx, uri)
                        ?: throw IllegalArgumentException("That file isn't a video or a photo.")
                    val info = Transcoder.probeSource(ctx, uri, video)
                    Screen.Ready(uri, info, Transcoder.loadThumbnail(ctx, uri, video))
                }
            }
            result
                .onSuccess { _state.value = UiState(it) }
                .onFailure { e ->
                    Log.w("StatusClear", "pick failed", e)
                    _state.value = UiState(Screen.Home, friendly(e, "We couldn't open that file."))
                }
        }
    }

    fun setZoomText(on: Boolean) = _state.update { s ->
        val r = s.screen as? Screen.Ready ?: return@update s
        s.copy(screen = r.copy(zoomText = on))
    }

    fun setFill(on: Boolean) = _state.update { s ->
        val r = s.screen as? Screen.Ready ?: return@update s
        s.copy(screen = r.copy(fill = on))
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun reset() {
        if (_state.value.screen is Screen.Working) return
        _state.value = UiState()
    }

    fun backToReady() = _state.update { s ->
        when (val sc = s.screen) {
            is Screen.Done -> s.copy(screen = sc.ready)
            else -> s
        }
    }

    fun cancel() {
        cancelFlag.set(true)
        _state.update { s ->
            val w = s.screen as? Screen.Working ?: return@update s
            s.copy(screen = w.copy(cancelling = true))
        }
    }

    fun start() {
        val ready = _state.value.screen as? Screen.Ready ?: return
        val ctx = getApplication<Application>()
        cancelFlag.set(false)
        _state.value = UiState(Screen.Working(ready, parts = ready.info.partCount))
        job = viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching {
                    val outDir = File(ctx.getExternalFilesDir(null), Transcoder.SAVE_FOLDER).apply { mkdirs() }
                    val base = ready.info.name.substringBeforeLast('.').ifBlank { "status" }
                        .replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
                    val parts = if (ready.info.isVideo) {
                        Transcoder.transcodeVideo(ctx, ready.uri, ready.fill, outDir, base, cancelFlag::get) { p, part, total ->
                            _state.update { s ->
                                val w = s.screen as? Screen.Working ?: return@update s
                                s.copy(screen = w.copy(progress = p, part = part, parts = total))
                            }
                        }
                    } else {
                        listOf(
                            Transcoder.transcodeImage(ctx, ready.uri, ready.zoomText, ready.fill, outDir, base, cancelFlag::get) { p ->
                                _state.update { s ->
                                    val w = s.screen as? Screen.Working ?: return@update s
                                    s.copy(screen = w.copy(progress = p))
                                }
                            }
                        )
                    }
                    check(parts.isNotEmpty()) { "No clips were made." }
                    parts.map { part ->
                        Clip(
                            part,
                            runCatching { Transcoder.saveToMediaStore(ctx, part.file, part.file.name) }.getOrNull(),
                            Transcoder.loadFileThumbnail(part.file)
                        )
                    }
                }
            }
            outcome
                .onSuccess { clips -> _state.value = UiState(Screen.Done(ready, clips)) }
                .onFailure { e ->
                    if (e is Transcoder.CancelledException) {
                        _state.value = UiState(ready)
                    } else {
                        Log.e("StatusClear", "encode failed", e)
                        _state.value = UiState(ready, friendly(e, "Something went wrong while preparing your Status."))
                    }
                }
        }
    }

    fun retrySave() {
        val done = _state.value.screen as? Screen.Done ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                done.clips.map { c ->
                    if (c.galleryUri != null || !c.result.file.exists()) c
                    else c.copy(galleryUri = runCatching { Transcoder.saveToMediaStore(ctx, c.result.file, c.result.file.name) }.getOrNull())
                }
            }
            val next = done.copy(clips = updated)
            _state.value = UiState(next, if (next.allSaved) null else "Couldn't save to your Gallery. Check that your phone has free space.")
        }
    }

    private fun friendly(e: Throwable, fallback: String): String {
        val msg = e.message.orEmpty()
        return when {
            e is IllegalArgumentException && msg.isNotBlank() -> msg
            msg.contains("Encoder failed", true) -> "This file couldn't be converted. Try another video or photo."
            e is OutOfMemoryError -> "This file is too large for your phone's memory."
            else -> fallback
        }
    }
}
