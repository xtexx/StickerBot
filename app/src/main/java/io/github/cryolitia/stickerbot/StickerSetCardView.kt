package io.github.cryolitia.stickerbot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import io.github.cryolitia.stickerbot.databinding.ViewStickersetCardBinding

class StickerSetCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {
    val binding = ViewStickersetCardBinding.inflate(LayoutInflater.from(context), this, true)

    fun setImageBitmap(bitmap: Bitmap?) {
        binding.cardHeaderImage.setImageBitmap(bitmap)
    }

    fun setImageDrawable(drawable: Drawable?) {
        binding.cardHeaderImage.setImageDrawable(drawable)
    }

    val cardView = binding.StickerSetCardView

    var text: CharSequence?
        get() {
            return binding.cardTitle.text
        }
        set(value) {
            binding.cardTitle.text = value
        }
}