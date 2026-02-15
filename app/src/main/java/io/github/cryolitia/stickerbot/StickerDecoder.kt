package io.github.cryolitia.stickerbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.core.graphics.createBitmap
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

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

    var mRate: Int = 1

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
        mRate = lottieComposition.frameRate.toInt()
        mWidth = lottieDrawable.intrinsicWidth
        mHeight = lottieDrawable.intrinsicHeight
        mDuration = lottieDrawable.composition.durationFrames.toInt()
        lottieDrawable.setBounds(0, 0, mWidth, mHeight)
    }

    override suspend fun getFrames(callback: suspend (bitmap: Bitmap, index: Int, rate: Int) -> Unit) {
        withContext(Dispatchers.IO) {
            for (index in 0..mDuration) {
                lottieDrawable.frame = index
                val bitmap = createBitmap(mWidth, mHeight)
                val canvas = Canvas(bitmap)
                lottieDrawable.draw(canvas)
                callback(bitmap, index, mRate)
            }
        }
    }

    lateinit var lottieDrawable: LottieDrawable

    override fun end() = Unit
}