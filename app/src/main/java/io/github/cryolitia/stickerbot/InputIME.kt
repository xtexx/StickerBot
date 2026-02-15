package io.github.cryolitia.stickerbot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.ClipDescription
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.recyclerview.widget.LinearLayoutManager
import io.github.cryolitia.stickerbot.databinding.ViewInputMethodBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class InputIME : InputMethodService() {
    lateinit var binding: ViewInputMethodBinding
    lateinit var stickerSetAdapter: StickerSetGalleryAdapter

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    var shortAnimationDuration: Int = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        binding = ViewInputMethodBinding.inflate(layoutInflater)

        val previewScale = 0.5f
        shortAnimationDuration = resources.getInteger(android.R.integer.config_shortAnimTime)

        scope.launch {
            getPreference(
                stringPreferencesKey(STICKER_PER_LINE), "0.5"
            ).toFloatOrNull() ?: 0.5f
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            binding.switchLanguage.visibility =
                if (shouldOfferSwitchingToNextInputMethod()) View.VISIBLE else View.GONE
            binding.switchLanguage.setOnClickListener {
                switchToNextInputMethod(false)
            }
        } else {
            binding.switchLanguage.visibility = View.GONE
        }

        binding.notSupportSendingMedia.setOnTouchListener { view, _ ->
            view.performClick()
            return@setOnTouchListener true
        }

        binding.gallery.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.stickersGallery.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        val stickerSetArray = iterateStickerSet(this)
        val fileArray = ArrayList<Triple<String, File, File>>()
        for (stickerSet in stickerSetArray) {
            if (stickerSet.third != null) {
                val title = stickerSet.second?.title ?: stickerSet.first.first.name
                fileArray.add(Triple(title, stickerSet.first.first, stickerSet.third!!))
            }
        }
        stickerSetAdapter =
            StickerSetGalleryAdapter(this, previewScale, fileArray) { directory, title ->
                scope.launch {
                    withContext(Dispatchers.Main) {
                        binding.stickersGallery.adapter = GalleryAdapter(
                            this@InputIME,
                            getPreviewScale(this@InputIME),
                            directory.safetyListFiles(),
                        ) { file ->
                            val contentUri = FileProvider.getUriForFile(
                                this@InputIME,
                                "io.github.cryolitia.stickerbot.stickerprovider",
                                file
                            )
                            val inputContentInfo = InputContentInfoCompat(
                                contentUri,
                                ClipDescription(
                                    title, arrayOf(
                                        MimeTypeMap.getSingleton()
                                            .getMimeTypeFromExtension(file.extension)
                                    )
                                ),
                                null
                            )
                            val inputConnection = currentInputConnection
                            val editorInfo = currentInputEditorInfo
                            var flags = 0
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                                flags =
                                    flags or InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
                            }
                            InputConnectionCompat.commitContent(
                                inputConnection,
                                editorInfo,
                                inputContentInfo,
                                flags,
                                null
                            )
                        }
                        binding.returnIconButton.visibility = View.VISIBLE

                        binding.gallery.animate()
                            .alpha(0f)
                            .setDuration(shortAnimationDuration.toLong())
                            .setListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    binding.gallery.visibility = View.INVISIBLE
                                }
                            })

                        binding.stickersGallery.apply {
                            alpha = 0f
                            visibility = View.VISIBLE
                            animate()
                                .alpha(1f)
                                .setDuration(shortAnimationDuration.toLong())
                                .setListener(null)
                        }
                    }
                }
            }
        binding.gallery.adapter = stickerSetAdapter
        binding.returnIconButton.setOnClickListener {
            binding.gallery.adapter = stickerSetAdapter
            binding.returnIconButton.visibility = View.GONE

            binding.gallery.apply {
                alpha = 0f
                visibility = View.VISIBLE
                animate()
                    .alpha(1f)
                    .setDuration(shortAnimationDuration.toLong())
                    .setListener(null)
            }

            binding.stickersGallery.animate()
                .alpha(0f)
                .setDuration(shortAnimationDuration.toLong())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        binding.stickersGallery.visibility = View.GONE
                    }
                })
        }

        return binding.root
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        val mimeTypes: Array<String> = EditorInfoCompat.getContentMimeTypes(editorInfo)

        val gifSupported: Boolean = mimeTypes.any {
            ClipDescription.compareMimeTypes(it, "image/*")
        }

        if (gifSupported) {
            binding.notSupportSendingMedia.visibility = View.GONE
        } else {
            binding.notSupportSendingMedia.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
