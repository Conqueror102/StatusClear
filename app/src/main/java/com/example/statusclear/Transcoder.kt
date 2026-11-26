package com.example.statusclear

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit

import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * On-device WhatsApp-Status encoding with FFmpeg + x264.
 *
 * Measured on a Galaxy A32, Nov 2026 (WhatsApp's own copies in Media/WhatsApp Video/Sent):
 *  - CHAT passes x264 1080p clips at ~1.5-1.7 Mbps untouched, in either orientation.
 *  - STATUS is stricter: the same 1920x1080 clips (1.53 / 1.70 Mbps) were re-encoded to
 *    640x360. Portrait 1080x1920 clips at 1.27 / 1.39 Mbps were posted untouched.
 *  - Status is a portrait screen, so clips are built as a portrait 1080x1920 frame,
 *    either "fit" (scale down + black bars) or "fill" (scale up + crop).
 *  - The phone's hardware encoder can't look good at ~1 Mbps; x264 can (a text
 *    screenshot: 0.31 Mbps at 48 dB with x264 vs 2.85 Mbps at 42 dB with MediaCodec).
 *
 *  - Controlled test (6 labelled clips, sent to self-chat then forwarded to Status):
 *      chat:   1.1 / 1.6 / 2.1 Mbps untouched, 3.0 / 4.0 Mbps -> 720x1280
 *      Status: 1.12 (portrait) and 1.15 (landscape) Mbps untouched,
 *              1.56 / 2.1 / 3.0 / 4.0 Mbps -> crushed to 360x640
 *    Together with earlier posts (1.27 and 1.39 passed, 1.53 crushed) the Status limit
 *    is ~1.4 Mbps for the WHOLE file (video + audio), whatever the shape.
 *
 * So every clip is a portrait 1080x1920 frame encoded with x264 (CRF 23 video / 20 photo,
 * mbtree off, preset medium) under a VBV cap that keeps the file average below
 * [STATUS_LIMIT_KBPS]. After encoding we measure the real average and re-encode with a
 * lower cap if needed. Videos: at most 30 fps, 30 s parts.
 * Always start from the original file — never re-encode a WhatsApp file.
 */
object Transcoder {
    private const val TAG = "StatusClear"
    /** WhatsApp Status is portrait: every clip is exactly this size. */
    const val OUT_W = 1080
    const val OUT_H = 1920
    const val PART_US = 30_000_000L
    const val IMAGE_SECONDS = 5
    const val MAX_PART_BYTES = 16L * 1024 * 1024
    const val SAVE_FOLDER = "StatusClear"
    private const val MAX_FPS = 30.0
    private const val VIDEO_CRF = 23
    private const val PHOTO_CRF = 20

    /**
     * Status re-encodes anything above ~1.4 Mbps (measured) down to 360x640. We aim for
     * a file average (video + audio) at or under this, leaving a safety margin.
     */
    const val STATUS_LIMIT_KBPS = 1300
    private const val AUDIO_KBPS = 96
    /** Video cap for clips: limit minus audio, minus a little for the VBV buffer. */
    private const val VIDEO_MAXRATE_KBPS = 1100
    /**
     * Photos are one sharp keyframe followed by near-empty frames, so the buffer (how
     * big that keyframe may be) matters more than the rate: 3000 kbit = a 375 KB frame.
     */
    private const val PHOTO_MAXRATE_KBPS = 700
    private const val PHOTO_BUFSIZE_KBPS = 3000

    /** x264 settings (CRF 23 for video, 20 for photos), tested against WhatsApp. */
    private fun x264Args(crf: Int, maxrateKbps: Int, bufsizeKbps: Int) = listOf(
        "-c:v", "libx264",
        "-preset", "medium",
        "-crf", "$crf",
        "-x264-params", "no-mbtree=1",
        "-maxrate", "${maxrateKbps}k",
        "-bufsize", "${bufsizeKbps}k",
        "-profile:v", "high",
        "-pix_fmt", "yuv420p"
    )
    private val AUDIO_ARGS = listOf("-c:a", "aac", "-b:a", "${AUDIO_KBPS}k", "-ar", "44100", "-ac", "2")

    class CancelledException : Exception("Cancelled")

    data class SourceInfo(
        val name: String,
        val durationMs: Long,
        /** Display size, i.e. after rotation is applied. */
        val width: Int,
        val height: Int,
        val sizeBytes: Long,
        val isVideo: Boolean,
        val rotation: Int = 0,
        val frameRate: Double = 0.0,
        val isHdr: Boolean = false
    ) {
        /** How many 30-second parts this video will be split into. */
        val partCount: Int
            get() = if (!isVideo) 1 else partStarts(durationMs * 1000L).size.coerceAtLeast(1)
    }

    data class PartResult(
        val file: File,
        val index: Int,
        val durationMs: Long,
        val sizeBytes: Long,
        val verified: Boolean,
        val hasAudio: Boolean,
        val details: String
    )

    /** Start times of each 30 s part; a last sliver under 0.5 s is dropped. */
    private fun partStarts(totalUs: Long): List<Long> {
        val starts = mutableListOf<Long>()
        var s = 0L
        while (s < totalUs - 500_000L) { starts += s; s += PART_US }
        return starts
    }

    /** True when a picture of [w]x[h] doesn't already have the 9:16 Status shape. */
    fun needsFraming(w: Int, h: Int): Boolean = kotlin.math.abs(w.toDouble() / h - OUT_W.toDouble() / OUT_H) > 0.01

    /**
     * Builds the portrait 1080x1920 Status frame.
     * [fill] = scale up and crop the sides to cover the screen; otherwise scale down to
     * show the whole picture, with black bars.
     */
    private fun frameFilter(fill: Boolean): String = if (fill) {
        "scale=$OUT_W:$OUT_H:force_original_aspect_ratio=increase:flags=lanczos,crop=$OUT_W:$OUT_H,setsar=1"
    } else {
        "scale=$OUT_W:$OUT_H:force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos," +
            "pad=$OUT_W:$OUT_H:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1"
    }

    /** HDR (HLG / PQ) phone videos look washed out unless tone-mapped to normal range. */
    private const val HDR_TONEMAP =
        "zscale=t=linear:npl=100,format=gbrpf32le,zscale=p=bt709," +
            "tonemap=tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=tv"

    fun isVideoUri(context: Context, uri: Uri): Boolean? {
        val type = context.contentResolver.getType(uri) ?: return null
        return when {
            type.startsWith("video/") -> true
            type.startsWith("image/") -> false
            else -> null
        }
    }

    fun probeSource(context: Context, uri: Uri, isVideo: Boolean): SourceInfo {
        var name = if (isVideo) "video" else "photo"
        var size = 0L
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0) name = c.getString(ni) ?: name
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0) size = c.getLong(si)
            }
        }
        if (!isVideo) {
            // Still images: never use MediaMetadataRetriever (it throws on
            // stills on many devices). Bounds from the header, rotation from EXIF.
            val rotation = context.contentResolver.openInputStream(uri)?.use { exifRotationDegrees(it) } ?: 0
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Exception) {
                throw IllegalArgumentException("This photo can't be opened.", e)
            }
            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                throw IllegalArgumentException("This file isn't a photo we can read.")
            }
            val swap = rotation == 90 || rotation == 270
            return SourceInfo(
                name, 0L,
                if (swap) opts.outHeight else opts.outWidth,
                if (swap) opts.outWidth else opts.outHeight,
                size, false, rotation
            )
        }
        var durationMs = 0L
        var w = 0
        var h = 0
        var rotation = 0
        try {
            withRetriever { r ->
                r.setDataSource(context, uri)
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            }
        } catch (_: Exception) {
            // Fall through to MediaExtractor below.
        }
        var fps = 0.0
        var hdr = false
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val t = findTrack(ex) { it.startsWith("video/") }
            if (t >= 0) {
                val f = ex.getTrackFormat(t)
                if (w <= 0 || h <= 0) {
                    w = f.getInteger(MediaFormat.KEY_WIDTH)
                    h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    rotation = f.intOrZero(MediaFormat.KEY_ROTATION)
                }
                if (durationMs <= 0 && f.containsKey(MediaFormat.KEY_DURATION)) {
                    durationMs = f.getLong(MediaFormat.KEY_DURATION) / 1000
                }
                // KEY_FRAME_RATE is an int in some files and a float in others.
                fps = if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) 0.0 else try {
                    f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                } catch (_: Exception) {
                    try { f.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() } catch (_: Exception) { 0.0 }
                }
                val transfer = f.intOrZero(MediaFormat.KEY_COLOR_TRANSFER)
                hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
            }
        } catch (e: Exception) {
            if (w <= 0 || h <= 0) throw IllegalArgumentException("This video can't be opened.", e)
        } finally {
            ex.release()
        }
        if (w <= 0 || h <= 0) throw IllegalArgumentException("This file has no video picture in it.")
        if (durationMs < 1000) throw IllegalArgumentException("This video is shorter than 1 second.")
        val swap = rotation == 90 || rotation == 270
        return SourceInfo(name, durationMs, if (swap) h else w, if (swap) w else h, size, true, rotation, fps, hdr)
    }

    /** MediaMetadataRetriever is only AutoCloseable from API 29; minSdk is 26. */
    private inline fun <T> withRetriever(block: (MediaMetadataRetriever) -> T): T {
        val r = MediaMetadataRetriever()
        try {
            return block(r)
        } finally {
            try { r.release() } catch (_: Exception) { }
        }
    }

    private fun MediaFormat.intOrZero(key: String): Int =
        try { if (containsKey(key)) getInteger(key) else 0 } catch (_: Exception) { 0 }

    private fun exifRotationDegrees(stream: java.io.InputStream): Int = try {
        when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    // ---------------------------------------------------------------- ffmpeg

    /**
     * Runs one FFmpeg command, reporting progress as seconds of output written, and
     * cancelling it as soon as [isCancelled] turns true.
     */
    private fun runFfmpeg(args: List<String>, isCancelled: () -> Boolean, onSeconds: (Double) -> Unit) {
        Log.i(TAG, "ffmpeg " + args.joinToString(" "))
        val done = CountDownLatch(1)
        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { done.countDown() },
            null,
            { stats -> onSeconds(stats.time / 1000.0) }
        )
        while (!done.await(200, TimeUnit.MILLISECONDS)) {
            if (isCancelled()) {
                FFmpegKit.cancel(session.sessionId)
                done.await(5, TimeUnit.SECONDS)
                throw CancelledException()
            }
        }
        val rc = session.returnCode
        when {
            ReturnCode.isSuccess(rc) -> return
            ReturnCode.isCancel(rc) -> throw CancelledException()
            else -> {
                val log = session.allLogsAsString.orEmpty().takeLast(1500)
                Log.e(TAG, "ffmpeg failed rc=${rc?.value}\n$log")
                throw IllegalStateException("Encoder failed (code ${rc?.value})")
            }
        }
    }

    /**
     * Runs [encode] with a video cap, then measures the file's real average bitrate.
     * If it's over [STATUS_LIMIT_KBPS] (Status would crush it to 360x640), encodes again
     * with a lower cap — up to 3 more tries.
     */
    private fun encodeUnderStatusLimit(outFile: File, durationSec: Double, startMaxrate: Int, encode: (Int) -> Unit) {
        var maxrate = startMaxrate
        repeat(4) { attempt ->
            try {
                encode(maxrate)
            } catch (e: Exception) {
                outFile.delete()
                throw e
            }
            val kbps = outFile.length() * 8.0 / durationSec / 1000.0
            if (kbps <= STATUS_LIMIT_KBPS) return
            Log.w(TAG, "attempt ${attempt + 1}: ${"%.0f".format(kbps)} kbps > $STATUS_LIMIT_KBPS, retrying")
            maxrate = (maxrate * STATUS_LIMIT_KBPS / kbps * 0.9).toInt().coerceAtLeast(200)
        }
        Log.w(TAG, "still over the Status limit after retries: ${outFile.name}")
    }

    // ---------------------------------------------------------------- video

    /**
     * @param onProgress overall 0..1, current part (1-based), total parts
     */
    fun transcodeVideo(
        context: Context,
        uri: Uri,
        fill: Boolean,
        outDir: File,
        baseName: String,
        isCancelled: () -> Boolean,
        onProgress: (Float, Int, Int) -> Unit
    ): List<PartResult> {
        val info = probeSource(context, uri, true)
        val totalUs = info.durationMs * 1000L
        val starts = partStarts(totalUs)
        // FFmpeg can't read MP4s through the photo picker's content URIs (they often
        // aren't seekable: "moov atom not found"), so work from a private copy.
        val input = copyToCache(context, uri, isCancelled)
        try {
            return encodeVideoParts(info, input.absolutePath, fill, starts, totalUs, outDir, baseName, isCancelled, onProgress)
        } finally {
            input.delete()
        }
    }

    private fun copyToCache(context: Context, uri: Uri, isCancelled: () -> Boolean): File {
        val dir = File(context.cacheDir, "input").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // leftovers from a crash
        val file = File(dir, "source-${System.nanoTime()}.mp4")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("This video can't be opened.")
            input.use { src ->
                file.outputStream().use { dst ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        if (isCancelled()) throw CancelledException()
                        val n = src.read(buf)
                        if (n < 0) break
                        dst.write(buf, 0, n)
                    }
                }
            }
        } catch (e: java.io.IOException) {
            file.delete()
            throw IllegalArgumentException("Not enough free space on your phone to prepare this video.", e)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    private fun encodeVideoParts(
        info: SourceInfo,
        input: String,
        fill: Boolean,
        starts: List<Long>,
        totalUs: Long,
        outDir: File,
        baseName: String,
        isCancelled: () -> Boolean,
        onProgress: (Float, Int, Int) -> Unit
    ): List<PartResult> {
        val filters = buildList {
            if (info.isHdr) add(HDR_TONEMAP)
            add(frameFilter(fill))
            // Cap at 30 fps (60 fps phone video wastes bits); keep 24/25/29.97 as they are.
            if (info.frameRate == 0.0 || info.frameRate > MAX_FPS + 0.5) add("fps=30")
            add("format=yuv420p")
        }.joinToString(",")

        val parts = mutableListOf<PartResult>()
        starts.forEachIndexed { i, startUs ->
            val index = i + 1
            val lenUs = minOf(PART_US, totalUs - startUs)
            val outFile = File(outDir, if (starts.size == 1) "$baseName-status.mp4" else "$baseName-status-part$index.mp4")
            outFile.delete()
            val lenSec = lenUs / 1e6
            encodeUnderStatusLimit(outFile, lenSec, VIDEO_MAXRATE_KBPS) { maxrate ->
                // The VBV buffer lets the average run over the cap by bufsize/duration,
                // so short clips (e.g. a 4 s last part) get a smaller buffer.
                val bufsize = minOf(maxrate * 2, (maxrate * lenSec / 4).toInt().coerceAtLeast(maxrate / 2))
                val args = buildList {
                    addAll(listOf("-hide_banner", "-y"))
                    // Seeking before -i is fast, and frame-accurate when re-encoding.
                    if (startUs > 0) addAll(listOf("-ss", "%.3f".format(java.util.Locale.US, startUs / 1e6)))
                    addAll(listOf("-t", "%.3f".format(java.util.Locale.US, lenSec)))
                    addAll(listOf("-i", input))
                    addAll(listOf("-map", "0:v:0", "-map", "0:a:0?"))
                    addAll(listOf("-vf", filters))
                    addAll(x264Args(VIDEO_CRF, maxrate, bufsize))
                    addAll(AUDIO_ARGS)
                    addAll(listOf("-map_metadata", "-1", "-movflags", "+faststart"))
                    add(outFile.absolutePath)
                }
                runFfmpeg(args, isCancelled) { sec ->
                    val p = (sec * 1e6 / lenUs).toFloat().coerceIn(0f, 1f)
                    onProgress(((startUs + p * lenUs) / totalUs).coerceIn(0f, 1f), index, starts.size)
                }
            }
            parts += verifyPart(outFile, index, lenUs / 1000)
        }
        onProgress(1f, starts.size, starts.size)
        return parts
    }

    // ---------------------------------------------------------------- image

    fun transcodeImage(
        context: Context,
        uri: Uri,
        cropZoom: Boolean,
        fill: Boolean,
        outDir: File,
        baseName: String,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit
    ): PartResult {
        // Decode ourselves so EXIF rotation and the "zoom into text" crop apply, then
        // hand FFmpeg a lossless PNG.
        val bitmap = decodeBitmap(context, uri, cropZoom, 4096)
        val png = File(context.cacheDir, "still-${System.nanoTime()}.png")
        val outFile = File(outDir, "$baseName-status.mp4")
        try {
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            outFile.delete()
            encodeUnderStatusLimit(outFile, IMAGE_SECONDS.toDouble(), PHOTO_MAXRATE_KBPS) { maxrate ->
                val bufsize = PHOTO_BUFSIZE_KBPS * maxrate / PHOTO_MAXRATE_KBPS
                val args = buildList {
                    addAll(listOf("-hide_banner", "-y"))
                    addAll(listOf("-loop", "1", "-framerate", "30", "-t", "$IMAGE_SECONDS", "-i", png.absolutePath))
                    addAll(listOf("-vf", frameFilter(fill) + ",format=yuv420p"))
                    addAll(x264Args(PHOTO_CRF, maxrate, bufsize))
                    addAll(listOf("-an", "-map_metadata", "-1", "-movflags", "+faststart"))
                    add(outFile.absolutePath)
                }
                runFfmpeg(args, isCancelled) { sec -> onProgress((sec / IMAGE_SECONDS).toFloat().coerceIn(0f, 1f)) }
            }
        } catch (e: Exception) {
            outFile.delete()
            throw e
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
            png.delete()
        }
        onProgress(1f)
        return verifyPart(outFile, 1, IMAGE_SECONDS * 1000L)
    }

    /** Decodes an image upright (EXIF applied), optionally cropped to the text band. */
    fun decodeBitmap(context: Context, uri: Uri, cropZoom: Boolean, maxDim: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("This photo can't be opened.")
        val rotation = context.contentResolver.openInputStream(uri)?.use { exifRotationDegrees(it) } ?: 0
        if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }
        if (cropZoom && bmp.width > 200 && bmp.height > 200) {
            val rect = cropRect(bmp.width, bmp.height)
            val cropped = Bitmap.createBitmap(bmp, rect.left, rect.top, rect.width(), rect.height())
            if (cropped != bmp) bmp.recycle()
            bmp = cropped
        }
        return bmp
    }

    /** The dense middle band "Zoom into text" keeps. Shared with the preview. */
    fun cropRect(w: Int, h: Int) = Rect((w * 0.06).toInt(), (h * 0.18).toInt(), (w * 0.94).toInt(), (h * 0.62).toInt())

    /** Small upright preview image for the UI. */
    fun loadThumbnail(context: Context, uri: Uri, isVideo: Boolean, maxDim: Int = 720): Bitmap? = try {
        if (isVideo) {
            withRetriever { r ->
                r.setDataSource(context, uri)
                r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it, maxDim) }
            }
        } else {
            decodeBitmap(context, uri, false, maxDim * 2).let { scaleDown(it, maxDim) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "thumbnail failed", e)
        null
    }

    fun loadFileThumbnail(file: File, maxDim: Int = 360): Bitmap? = try {
        withRetriever { r ->
            r.setDataSource(file.absolutePath)
            r.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scaleDown(it, maxDim) }
        }
    } catch (_: Exception) {
        null
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val big = max(src.width, src.height)
        if (big <= maxDim) return src
        val s = maxDim.toFloat() / big
        val out = Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true)
        if (out != src) src.recycle()
        return out
    }

    fun estimatePartsLabel(info: SourceInfo): String {
        if (!info.isVideo) return "1 clip · $IMAGE_SECONDS s"
        val n = info.partCount
        return if (n == 1) "1 clip · ${ceil(info.durationMs / 1000.0).toInt()} s" else "$n clips · 30 s each"
    }

    // ---------------------------------------------------------------- helpers

    private fun findTrack(extractor: MediaExtractor, pred: (String) -> Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (pred(mime)) return i
        }
        return -1
    }

    private fun verifyPart(file: File, index: Int, expectedMs: Long): PartResult {
        try {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(file.absolutePath)
                var vw = 0
                var vh = 0
                var vMime = ""
                var hasAudio = false
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        vMime = mime
                        vw = f.getInteger(MediaFormat.KEY_WIDTH)
                        vh = f.getInteger(MediaFormat.KEY_HEIGHT)
                    } else if (mime.startsWith("audio/")) {
                        hasAudio = true
                    }
                }
                val durMs = withRetriever { r ->
                    r.setDataSource(file.absolutePath)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                }
                val sizeOk = vw == OUT_W && vh == OUT_H
                val ms0 = if (durMs > 0) durMs else expectedMs
                val kbps = if (ms0 > 0) file.length() * 8.0 / ms0 else 0.0
                val verified = file.length() < MAX_PART_BYTES && sizeOk && vMime == MediaFormat.MIMETYPE_VIDEO_AVC &&
                    kbps <= STATUS_LIMIT_KBPS
                val ms = if (durMs > 0) durMs else expectedMs
                val mbps = if (ms > 0) file.length() * 8.0 / ms / 1000.0 else 0.0
                val safe = if (kbps <= STATUS_LIMIT_KBPS) "Status-safe" else "may be compressed"
                val details = "$vw×$vh · %.2f Mbps · $safe".format(mbps)
                return PartResult(file, index, ms, file.length(), verified, hasAudio, details)
            } finally {
                ex.release()
            }
        } catch (e: Exception) {
            return PartResult(file, index, expectedMs, file.length(), false, false, "Could not double-check this clip")
        }
    }

    fun saveToMediaStore(context: Context, file: File, displayName: String): Uri? {
        // RELATIVE_PATH / IS_PENDING need Android 10+. On older phones the
        // clip stays in app storage and is shared through the FileProvider.
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + SAVE_FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Could not open MediaStore output stream")
        } catch (e: Exception) {
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
            return null
        }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return uri
    }
}
