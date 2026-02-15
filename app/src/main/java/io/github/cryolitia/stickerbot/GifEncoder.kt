package io.github.cryolitia.stickerbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.io.path.createTempDirectory


sealed class GifEncoder(val dstFile: File, val scope: CoroutineScope) {
    abstract fun start()
    abstract fun addFrame(bitmap: Bitmap)
    open suspend fun process() = Unit
    open fun end() = Unit
    var rate: Int = 1
}

class FFmpegEncoder(
    dstFile: File,
    scope: CoroutineScope,
    val context: Context,
    val logCallback: LogCallback? = null,
    val statisticsCallback: StatisticsCallback? = null
) : GifEncoder(dstFile, scope) {
    private val tmpDir = createTempDirectory(context.cacheDir.toPath(), null).toFile()
    private var frame = 0

    override fun start() {
        if (dstFile.exists()) {
            dstFile.delete()
        }
    }

    override fun addFrame(bitmap: Bitmap) {
        val file: File = File(tmpDir, "$frame.png")
        val outStream: OutputStream = FileOutputStream(file)

        bitmap.compress(CompressFormat.PNG, 100, outStream)

        outStream.flush()
        outStream.close()

        frame += 1
    }

    override suspend fun process() {
        val command =
            "-r $rate -i ${tmpDir.absolutePath}/%d.png -vf \"split[v1][v2];[v1]palettegen=reserve_transparent=1[palette];[v2][palette]paletteuse=alpha_threshold=128\" -loop 0 ${dstFile.absolutePath}"
        execute(command)
    }

    suspend fun process(webm: ByteArray, max:Int, preview: suspend (File) -> Unit) {
        val file = File.createTempFile("webm",".webm",tmpDir)
        file.writeBytes(webm)

        val previewFile = File.createTempFile("preview",".png",tmpDir)
        if (previewFile.exists()) {
            previewFile.delete()
        }
        val previewCommand = "-vcodec libvpx-vp9 -i ${file.absolutePath} -vframes 1 -pix_fmt rgba ${previewFile.absolutePath}"
        execute(previewCommand)
        if (previewFile.exists()) {
            preview(previewFile)
        }

        val command = "-vcodec libvpx-vp9 -i ${file.absolutePath} -vf \"format=rgba,scale='min(${max},iw)':'min(${max},ih)':force_original_aspect_ratio=decrease,scale=trunc(iw/2)*2:trunc(ih/2)*2,split[v1][v2];[v1]palettegen=reserve_transparent=1[palette];[v2][palette]paletteuse=alpha_threshold=128\" -loop 0 ${dstFile.absolutePath}"
        execute(command)
    }

    private suspend fun execute(command: String) {
        val channel: Channel<Unit> = Channel(1)
        val session = FFmpegKit.executeAsync(command, {
            scope.launch {
                channel.send(Unit)
                channel.close()
            }
        }, logCallback, statisticsCallback)
        for (ignore in channel) {
            //ignore
        }
        if (!session.returnCode.isValueSuccess) {
            throw RuntimeException(session.logsAsString)
        }
    }
}

fun com.arthenica.ffmpegkit.Log.print() {
    when (this.level) {
        Level.AV_LOG_STDERR, Level.AV_LOG_PANIC, Level.AV_LOG_FATAL, Level.AV_LOG_ERROR -> Log.e(
            "ffmpeg",
            this.message
        )

        Level.AV_LOG_WARNING -> Log.w("ffmpeg", this.message)
        Level.AV_LOG_INFO -> Log.i("ffmpeg", this.message)
        Level.AV_LOG_VERBOSE -> Log.v("ffmpeg", this.message)
        Level.AV_LOG_DEBUG -> Log.d("ffmpeg", this.message)
        Level.AV_LOG_TRACE, Level.AV_LOG_QUIET -> Log.v("ffmpeg", this.message)
    }
}

fun com.arthenica.ffmpegkit.Statistics.toFormatedString(): String {
    val stringBuilder = StringBuilder()

    stringBuilder.append("sessionId=")
    stringBuilder.append(sessionId)
    stringBuilder.append("\nvideoFrameNumber=")
    stringBuilder.append(videoFrameNumber)
    stringBuilder.append("\nvideoFps=")
    stringBuilder.append(videoFps)
    stringBuilder.append("\nvideoQuality=")
    stringBuilder.append(videoQuality)
    stringBuilder.append("\nsize=")
    stringBuilder.append(size)
    stringBuilder.append("\ntime=")
    stringBuilder.append(time)
    stringBuilder.append("\nbitrate=")
    stringBuilder.append(bitrate)
    stringBuilder.append("\nspeed=")
    stringBuilder.append(speed)

    return stringBuilder.toString()
}