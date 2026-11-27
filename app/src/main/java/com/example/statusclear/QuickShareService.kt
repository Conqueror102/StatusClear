package com.example.statusclear

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** What the quick share sheet shows. */
sealed interface Quick {
    data object Idle : Quick
    data object Opening : Quick
    data class Working(
        val thumbnail: Bitmap?,
        val isVideo: Boolean,
        val progress: Float = 0f,
        val part: Int = 1,
        val parts: Int = 1
    ) : Quick
    data class Done(val thumbnail: Bitmap?, val clips: List<Clip>) : Quick
    data class Failed(val message: String) : Quick
}

/**
 * The one quick-share job, shared by the sheet (QuickShareActivity) and the
 * foreground service that keeps it alive when the person switches apps.
 */
object QuickJob {
    private val _state = MutableStateFlow<Quick>(Quick.Idle)
    val state: StateFlow<Quick> = _state

    /** The shared file, kept for "More options". */
    @Volatile var sourceUri: Uri? = null; private set
    @Volatile var sourceType: String = ""; private set

    /** Set while the sheet is on screen; when false at the end, we post "ready" instead. */
    @Volatile var sheetVisible = false

    /** Every start/cancel bumps this; a job keeps working only while it holds the latest number. */
    private val generation = AtomicInteger()
    private val handedOff = AtomicBoolean(false)

    val isBusy get() = _state.value is Quick.Opening || _state.value is Quick.Working

    /** True exactly once per job: the first time its clips are handed to WhatsApp. */
    fun markHandedOff() = handedOff.compareAndSet(false, true)

    /** Starts a new job (replacing any running one) in the foreground service. */
    fun start(context: Context, uri: Uri, type: String) {
        generation.incrementAndGet() // stop a previous job, if any
        sourceUri = uri
        sourceType = type
        val intent = Intent(context, QuickShareService::class.java)
            .setAction(QuickShareService.ACTION_START)
            .setDataAndType(uri, type.ifBlank { null })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(null, uri)
        _state.value = Quick.Opening
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancel(context: Context) {
        generation.incrementAndGet()
        if (isBusy) _state.value = Quick.Idle
        context.stopService(Intent(context, QuickShareService::class.java))
    }

    /** Runs the encode on the calling thread. Called by the service. */
    internal fun run(app: Application, uri: Uri, isVideoHint: Boolean?, onProgress: (Quick.Working) -> Unit): Quick {
        val mine = generation.incrementAndGet()
        val stale = { generation.get() != mine }
        handedOff.set(false)
        return try {
            val video = isVideoHint ?: Transcoder.isVideoUri(app, uri)
                ?: throw IllegalArgumentException("That file isn't a video or a photo.")
            val info = Transcoder.probeSource(app, uri, video)
            val thumb = Transcoder.loadThumbnail(app, uri, video, maxDim = 240)
            // Same settings as the app, using the last "Fit to Status screen" choice.
            val ready = Screen.Ready(uri, info, thumb, zoomText = false, fill = Prefs.fill(app))
            if (stale()) return Quick.Idle
            Quick.Working(thumb, video, parts = info.partCount).also { _state.value = it; onProgress(it) }
            val clips = makeStatusClips(app, ready, stale) { p, part, total ->
                if (stale()) return@makeStatusClips
                _state.update { s -> if (s is Quick.Working) s.copy(progress = p, part = part, parts = total) else s }
                (_state.value as? Quick.Working)?.let(onProgress)
            }
            if (stale()) Quick.Idle else Quick.Done(thumb, clips).also { _state.value = it }
        } catch (e: Transcoder.CancelledException) {
            Quick.Idle
        } catch (e: Throwable) {
            if (stale()) return Quick.Idle
            Log.e("StatusClear", "quick share failed", e)
            Quick.Failed(friendlyError(e, "Something went wrong while preparing your Status.")).also { _state.value = it }
        }
    }

    fun reset() {
        if (!isBusy) _state.value = Quick.Idle
    }
}

/**
 * Keeps a quick-share encode running while the person uses other apps, with a progress
 * notification. When it finishes and the sheet isn't on screen, it posts
 * "Your Status is ready — tap to post" (Android doesn't let apps open WhatsApp by
 * themselves from the background, so the tap is the hand-off).
 */
class QuickShareService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastNotifyAt = 0L
    private var lastPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                QuickJob.cancel(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val uri = intent.data ?: run { stopSelf(); return START_NOT_STICKY }
                val type = intent.type.orEmpty()
                ensureChannels(this)
                startInForeground(working(null))
                notificationManager().cancel(NOTIFY_READY)
                scope.launch {
                    val hint = when {
                        type.startsWith("video/") -> true
                        type.startsWith("image/") -> false
                        else -> null
                    }
                    val result = QuickJob.run(application, uri, hint) { w -> updateProgress(w) }
                    if (result is Quick.Idle && QuickJob.isBusy) return@launch // replaced by a newer share
                    ServiceCompat.stopForeground(this@QuickShareService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    if (result is Quick.Done && !QuickJob.sheetVisible) postReady(result)
                    if (result is Quick.Failed && !QuickJob.sheetVisible) postFailed(result.message)
                    stopSelf(startId)
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: android.app.Notification) {
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIFY_WORKING, notification, type)
    }

    private fun updateProgress(w: Quick.Working) {
        val pct = (w.progress * 100).toInt()
        val now = SystemClock.elapsedRealtime()
        if (pct == lastPercent || now - lastNotifyAt < 800) return
        lastPercent = pct
        lastNotifyAt = now
        if (canNotify(this)) notificationManager().notify(NOTIFY_WORKING, working(w))
    }

    private fun working(w: Quick.Working?) = NotificationCompat.Builder(this, CHANNEL_WORKING)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle("Making it HD for Status")
        .setContentText(
            when {
                w == null -> "Getting it ready…"
                w.parts > 1 -> "Clip ${w.part} of ${w.parts} · ${(w.progress * 100).toInt()}%"
                else -> "${(w.progress * 100).toInt()}% · WhatsApp opens when you come back"
            }
        )
        .setProgress(100, ((w?.progress ?: 0f) * 100).toInt(), w == null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(openSheet(this))
        .addAction(0, "Cancel", PendingIntent.getService(
            this, 1, Intent(this, QuickShareService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        ))
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun postReady(done: Quick.Done) {
        if (!canNotify(this)) return
        val n = NotificationCompat.Builder(this, CHANNEL_READY)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Your Status is ready")
            .setContentText(if (done.clips.size > 1) "${done.clips.size} HD clips · tap to post" else "Tap to post it to WhatsApp")
            .setLargeIcon(done.thumbnail)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openSheet(this))
            .build()
        notificationManager().notify(NOTIFY_READY, n)
    }

    private fun postFailed(message: String) {
        if (!canNotify(this)) return
        val n = NotificationCompat.Builder(this, CHANNEL_READY)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Couldn't prepare your Status")
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(openSheet(this))
            .build()
        notificationManager().notify(NOTIFY_READY, n)
    }

    private fun notificationManager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val ACTION_START = "com.example.statusclear.quick.START"
        const val ACTION_CANCEL = "com.example.statusclear.quick.CANCEL"
        const val ACTION_RESUME = "com.example.statusclear.quick.RESUME"
        const val NOTIFY_WORKING = 41
        const val NOTIFY_READY = 42
        private const val CHANNEL_WORKING = "working"
        private const val CHANNEL_READY = "ready"

        fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** Re-opens the sheet for the running/finished job (no new job is started). */
        fun openSheet(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 2,
            Intent(context, QuickShareActivity::class.java)
                .setAction(ACTION_RESUME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun dismissReady(context: Context) {
            (context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFY_READY)
        }

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WORKING, "Preparing", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress while a Status is being prepared" }
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_READY, "Ready to post", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "When your Status is ready to post" }
            )
        }
    }
}
