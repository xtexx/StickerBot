package io.github.cryolitia.stickerbot

import android.content.Context
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.scale
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import com.google.android.flexbox.FlexboxLayout
import pl.droidsonroids.gif.GifDrawableBuilder
import java.io.File
import java.util.Arrays

class StickerSetGalleryAdapter(
    val context: Context,
    var previewScale: Float,
    fileList: ArrayList<Triple<String, File, File>>,
    val onClick: (File, String) -> Unit
) :
    RecyclerView.Adapter<StickerSetGalleryAdapter.ViewHolderTemplate<StickerSetCardView>>() {

    private val array: Array<Triple<String, File, File>> =
        Arrays.stream(fileList.toTypedArray()).filter { item ->
            val file = item.third
            val extension = file.extension
            extension.equals("png", true)
                    || extension.equals("webp", true)
                    || extension.equals("gif", true)
                    || extension.equals("webm", true)
        }.toList().toTypedArray()

    class ViewHolderTemplate<T : View>(val view: T) : ViewHolder(view)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolderTemplate<StickerSetCardView> {
        return ViewHolderTemplate(StickerSetCardView(context))
    }

    override fun getItemCount(): Int = array.size

    override fun onBindViewHolder(holder: ViewHolderTemplate<StickerSetCardView>, position: Int) {
        val params = holder.view.layoutParams
        if (params is FlexboxLayout.LayoutParams) {
            params.flexGrow = 1.0f
        }

        val file = array[position].third

        val input = file.inputStream()
        try {
            BitmapFactory.decodeStream(file.inputStream())
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            input.close()
        }

        holder.view.text = array[position].first

        val stream = file.inputStream()
        try {
            if (file.extension == "gif") {
                val drawable =
                    GifDrawableBuilder().sampleSize((1 / previewScale).toInt()).from(file).build()
                holder.view.setImageDrawable(drawable)
            } else {
                val bitmap = BitmapFactory.decodeStream(stream)
                holder.view.setImageBitmap(
                    bitmap.scale(
                        (bitmap.width * previewScale).toInt(),
                        (bitmap.height * previewScale).toInt(),
                        false
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            stream.close()
        }

        holder.view.cardView.setOnClickListener {
            onClick(array[position].second, array[position].first)
        }
    }
}