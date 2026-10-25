package cn.zhe.douyinimmersivenav

import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import java.util.IdentityHashMap

/** Fixed Lynx footer actions: preserve the full background, move only missing clearance. */
internal class LynxFooterSafeArea(private val root: View) {
    private data class Offset(var original: Float, var applied: Float)
    private var footer: ViewGroup? = null
    private val buttons = IdentityHashMap<View, Offset>()
    private var dirty = true
    private val xy = IntArray(2)
    fun markDirty() { dirty = true }
    fun sync() {
        if (root.height == 0) return
        if (footer != null && (footer?.isAttachedToWindow != true || footer?.isShown != true)) { release(); dirty = true }
        root.rootView.getLocationOnScreen(xy)
        val bottom = xy[1] + root.rootView.height
        val nav = PageNavigationSafeArea.navigationBottom(root)
        if (dirty) {
            dirty = false
            if (footer == null) find(root, false, bottom)
        }
        val row = footer ?: return
        if (root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) { release(); return }
        var nativeBottom = 0f
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (!child.isShown || child.height == 0) continue
            val offset = buttons.getOrPut(child) { Offset(child.translationY, child.translationY) }
            if (child.translationY != offset.applied) offset.original = child.translationY
            child.getLocationOnScreen(xy)
            nativeBottom = maxOf(nativeBottom, xy[1] + child.height - (child.translationY - offset.original))
        }
        val overlap = (nativeBottom - (bottom - nav)).coerceIn(0f, nav.toFloat())
        row.getLocationOnScreen(xy)
        val rowTop = xy[1]
        buttons.forEach { (view, offset) ->
            if (view.parent !== row) return@forEach
            view.getLocationOnScreen(xy)
            val nativeTop = xy[1] - (view.translationY - offset.original)
            // Do not cut the button at its own footer's top edge.
            offset.applied = offset.original - minOf(overlap, maxOf(0f, nativeTop - rowTop))
            if (view.translationY != offset.applied) view.translationY = offset.applied
        }
    }
    private fun find(view: View, lynx: Boolean, bottom: Int, depth: Int = 0) {
        if (footer != null || !view.isShown || view !is ViewGroup || depth > 32) return
        val name = view.javaClass.name
        // Scroll content is not a fixed footer, even when an item touches the bottom.
        if (name.contains("ScrollView") || name.contains("RecyclerView")) return
        val inside = lynx || name == "com.lynx.tasm.LynxView"
        if (inside && name == "com.lynx.tasm.behavior.ui.view.AndroidView" &&
            view.width >= root.width * 0.9f && view.height in 1 until root.height / 4 &&
            view.childCount > 0) {
            view.getLocationOnScreen(xy)
            if (kotlin.math.abs(xy[1] + view.height - bottom) <= 2 &&
                (0 until view.childCount).all { i ->
                    val child = view.getChildAt(i)
                    child.isShown && child.height in 1 until view.height && child.background != null
                }) { footer = view; return }
        }
        for (i in 0 until view.childCount) find(view.getChildAt(i), inside, bottom, depth + 1)
    }
    fun release() {
        buttons.forEach { (view, offset) -> if (view.translationY == offset.applied) view.translationY = offset.original }
        buttons.clear(); footer = null
    }
}
