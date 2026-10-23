package cn.zhe.douyinsystemtheme

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference

/** Color the native chat status spacer. It moves and disappears with its own page. */
internal class ChatStatusBackdrop {
    private var target = WeakReference<View>(null)
    private var original: Drawable? = null
    private var applied: ColorDrawable? = null
    fun update(chat: ViewGroup, color: Int) {
        val id = chat.resources.getIdentifier("ud9", "id", "com.ss.android.ugc.aweme")
        val spacer = if (id != 0) chat.findViewById<View>(id) else null
        if (spacer == null || spacer.width < chat.width*3/4 || spacer.height > chat.height/8) { release(); return }
        if (target.get() !== spacer) {
            release(); target = WeakReference(spacer); original = spacer.background
        }
        if (spacer.background !== applied) original = spacer.background
        if (spacer.background === applied && applied?.color == color) return
        val replacement = ColorDrawable(color)
        applied = replacement; spacer.background = replacement
    }
    fun release() {
        target.get()?.let { if (it.background === applied) it.background = original }
        target.clear(); original = null; applied = null
    }
}
