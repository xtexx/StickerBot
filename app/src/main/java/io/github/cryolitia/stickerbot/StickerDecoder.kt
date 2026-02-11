package io.github.cryolitia.stickerbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.LinkedList

sealed class StickerDecoder(val data: ByteArray) {
    abstract suspend fun start()
    abstract suspend fun getFrames(callback: suspend (bitmap: Bitmap, index: Int, delay: Int) -> Unit)
    abstract fun end()

    protected var mWidth: Int = 0
    protected var mHeight: Int = 0
    protected var mDuration: Int = 0

    fun getWidth(): Int = mWidth
    fun getHeight(): Int = mHeight
    fun getDuration(): Int = mDuration
}

class LottieStickerDecoder(data: ByteArray, val context: Context, val fileUniqueId: String) :
    StickerDecoder(data) {

    var mDelay: Int = 0

    override suspend fun start() {
        val jsonString = String(
            data,
            StandardCharsets.UTF_8
        )
        val lottieComposition =
            LottieCompositionFactory.fromJsonStringSync(
                jsonString, fileUniqueId
            ).value!!
        lottieDrawable = LottieDrawable()
        lottieDrawable.callback = View(context)
        lottieDrawable.composition = lottieComposition
        mDelay = (1000.0 / lottieComposition.frameRate).toInt()
        mWidth = lottieDrawable.intrinsicWidth
        mHeight = lottieDrawable.intrinsicHeight
        mDuration = lottieDrawable.composition.durationFrames.toInt()
        lottieDrawable.setBounds(0, 0, mWidth, mHeight)
    }

    override suspend fun getFrames(callback: suspend (bitmap: Bitmap, index: Int, delay: Int) -> Unit) {
        withContext(Dispatchers.IO) {
            for (index in 0..mDuration) {
                lottieDrawable.frame = index
                val bitmap = createBitmap(mWidth, mHeight)
                val canvas = Canvas(bitmap)
                lottieDrawable.draw(canvas)
                callback(bitmap, index, mDelay)
            }
        }
    }

    lateinit var lottieDrawable: LottieDrawable

    override fun end() = Unit
}

class WebMDecoder(data: ByteArray, val context: Context) : StickerDecoder(data) {

    val retriever = MediaMetadataRetriever()
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    lateinit var mime: String
    lateinit var format: MediaFormat
    var transparentize = false

    override suspend fun start() {
        val dataSource = ByteArrayMediaDataSource(data)
        retriever.setDataSource(dataSource)
        extractor.setDataSource(dataSource)

        transparentize = context.getPreference(booleanPreferencesKey(TRANSPARENTIZE_WEBM), true)

        mWidth =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
        mHeight =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt()
                ?: 0
        val durationMs =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L

        var videoTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            format = extractor.getTrackFormat(i)
            mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                break
            }
        }

        if (videoTrackIndex < 0) {
            throw IllegalStateException("Can not find video track in media")
        }

        extractor.selectTrack(videoTrackIndex)
        mDuration = durationMs.toInt()
    }

    override suspend fun getFrames(callback: suspend (bitmap: Bitmap, index: Int, delay: Int) -> Unit) {
        decoder = MediaCodec.createDecoderByType(mime)
        decoder!!.configure(format, null, null, 0)
        decoder!!.start()

        val bufferInfo = MediaCodec.BufferInfo()
        var frameIndex = 0
        var isInputEOS = false
        var isOutputEOS = false

        val timeout = 10_000L // 10ms

        while (!isOutputEOS) {
            if (!isInputEOS) {
                val inputBufferId = decoder!!.dequeueInputBuffer(timeout)
                if (inputBufferId >= 0) {
                    val inputBuffer = decoder!!.getInputBuffer(inputBufferId)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)

                    if (sampleSize < 0) {
                        decoder!!.queueInputBuffer(
                            inputBufferId, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        isInputEOS = true
                    } else {
                        val presentationTimeUs = extractor.sampleTime
                        decoder!!.queueInputBuffer(
                            inputBufferId, 0, sampleSize,
                            presentationTimeUs, 0
                        )
                        extractor.advance()
                    }
                }
            }

            val outputBufferId = decoder!!.dequeueOutputBuffer(bufferInfo, timeout)
            if (outputBufferId >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    isOutputEOS = true
                }

                if (bufferInfo.size > 0) {
                    // 获取解码后的图像
                    val image = decoder!!.getOutputImage(outputBufferId)
                    if (image != null) {
                        val width = image.width
                        val height = image.height
                        val bitmap: Bitmap = when (image.format) {
                            ImageFormat.YUV_420_888 -> {
                                val bitmap: Bitmap = createBitmap(width, height)
                                YuvToRgbConverter(context).yuvToRgb(image, bitmap)
                                if (transparentize) AlphaProcessor.floodFillEdgeBlack(
                                    bitmap,
                                    0
                                ) else bitmap
                            }

                            ImageFormat.JPEG -> {
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)

                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }

                            ImageFormat.FLEX_RGBA_8888 -> {
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                val pixelStride = planes[0].pixelStride
                                val rowStride = planes[0].rowStride
                                val rowPadding = rowStride - pixelStride * width

                                var bitmap = createBitmap(width + rowPadding / pixelStride, height)
                                bitmap.copyPixelsFromBuffer(buffer)

                                if (rowPadding != 0) {
                                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                                }

                                bitmap
                            }

                            else -> throw IllegalStateException("Unexpected IMAGE Format: ${image.format}")
                        }

                        callback(
                            bitmap,
                            (bufferInfo.presentationTimeUs / 1000).toInt(),
                            (bufferInfo.presentationTimeUs / 1000).toInt() - frameIndex
                        )
                        frameIndex = (bufferInfo.presentationTimeUs / 1000).toInt()
                        image.close()
                    }
                }

                decoder!!.releaseOutputBuffer(outputBufferId, false)
            }
        }
    }

    override fun end() {
        retriever.release()
        extractor.release()
        decoder?.release()
    }

    class ByteArrayMediaDataSource(val data: ByteArray) : MediaDataSource() {
        override fun getSize(): Long = data.size.toLong()

        override fun readAt(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            size: Int
        ): Int {
            var size = size
            if (position >= data.size) {
                return -1
            }

            if (position + size > data.size) {
                size = data.size - position.toInt()
            }

            System.arraycopy(data, position.toInt(), buffer, offset, size)
            return size
        }

        override fun close() = Unit
    }
}


object AlphaProcessor {

    /**
     * 使用洪水填充算法，只替换从边缘开始的连续黑色区域
     * @param threshold 黑色阈值
     */
    fun floodFillEdgeBlack(bitmap: Bitmap, threshold: Int = 10): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)

        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 标记哪些像素应该变透明
        val visited = BooleanArray(width * height)
        val toTransparent = BooleanArray(width * height)

        // 从四条边开始洪水填充
        val queue = LinkedList<Pair<Int, Int>>()

        // 添加四条边的黑色像素到队列
        for (x in 0 until width) {
            // 上边
            if (isBlack(pixels[x], threshold)) {
                queue.offer(Pair(x, 0))
            }
            // 下边
            val bottomIdx = (height - 1) * width + x
            if (isBlack(pixels[bottomIdx], threshold)) {
                queue.offer(Pair(x, height - 1))
            }
        }

        for (y in 0 until height) {
            // 左边
            if (isBlack(pixels[y * width], threshold)) {
                queue.offer(Pair(0, y))
            }
            // 右边
            val rightIdx = y * width + (width - 1)
            if (isBlack(pixels[rightIdx], threshold)) {
                queue.offer(Pair(width - 1, y))
            }
        }

        // 洪水填充
        val directions = arrayOf(
            Pair(-1, 0), Pair(1, 0),  // 左右
            Pair(0, -1), Pair(0, 1)   // 上下
        )

        while (queue.isNotEmpty()) {
            val (x, y) = queue.poll()!!
            val idx = y * width + x

            if (x !in 0..<width || y < 0 || y >= height) continue
            if (visited[idx]) continue
            if (!isBlack(pixels[idx], threshold)) continue

            visited[idx] = true
            toTransparent[idx] = true

            // 检查四个方向
            for ((dx, dy) in directions) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0..<width && ny >= 0 && ny < height) {
                    queue.offer(Pair(nx, ny))
                }
            }
        }

        // 应用透明
        for (i in pixels.indices) {
            if (toTransparent[i]) {
                pixels[i] = Color.TRANSPARENT
            }
        }

        val result = createBitmap(width, height)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun isBlack(pixel: Int, threshold: Int): Boolean {
        val r = (pixel shr 16) and 0xff
        val g = (pixel shr 8) and 0xff
        val b = pixel and 0xff
        return r <= threshold && g <= threshold && b <= threshold
    }
}