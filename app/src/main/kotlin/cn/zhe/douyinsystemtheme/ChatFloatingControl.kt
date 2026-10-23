package cn.zhe.douyinsystemtheme

import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Native shape survives the te0/te1/tez -> te6/te7/te5 ID change. */
object ChatFloatingControl {
    private val cached = WeakHashMap<ViewGroup, WeakReference<ViewGroup>>()
    fun surface(tip: ViewGroup): ViewGroup? = (0 until tip.childCount)
        .map { tip.getChildAt(it) }.filterIsInstance<ViewGroup>()
        .firstOrNull { group -> group.background is LayerDrawable &&
            (0 until group.childCount).any { group.getChildAt(it) is ImageView } }
    fun find(root: ViewGroup): ViewGroup? {
        cached[root]?.get()?.takeIf { it.isAttachedToWindow }?.let { return it }
        fun visit(view: View, depth: Int): ViewGroup? {
            if (depth > 32 || view !is ViewGroup || view.javaClass.name.contains("RecyclerView")) return null
            if (view.javaClass.name == "com.ss.android.ugc.aweme.im.sdk.chat.view.ShadowFrameLayout" && surface(view) != null) return view
            if (!view.isShown) return null
            for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)?.let { return it }
            return null
        }
        return visit(root, 0)?.also { cached[root] = WeakReference(it) }
    }
}
