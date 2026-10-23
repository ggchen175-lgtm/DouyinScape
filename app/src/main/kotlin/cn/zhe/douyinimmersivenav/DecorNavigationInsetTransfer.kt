package cn.zhe.douyinimmersivenav

import android.view.View
import android.view.ViewGroup

/** Transfer a window's bottom reserve to its native interactive-content viewport. */
internal class DecorNavigationInsetTransfer(private val root: View, private val contentClass: String) {
    private data class Background(val view: View, val scale: Float = view.scaleY, val pivot: Float = view.pivotY)
    private data class Content(val view: ViewGroup, val padding: Int = view.paddingBottom,
        val clip: Boolean = view.clipToPadding, var applied: Int = view.paddingBottom,
        val backgrounds: MutableList<Background> = ArrayList())
    private val contents = ArrayList<Content>()
    private var dirty = true
    private var decor: View? = null
    private var originalDecorPadding = 0
    private var appliedDecorPadding = 0
    fun markDirty() { dirty = true }

    fun sync(): Boolean {
        if (!root.isAttachedToWindow || root.height == 0) return false
        if (dirty || contents.isEmpty()) {
            dirty = false
            find(root)
        }
        if (contents.isEmpty()) return false
        if (decor == null) {
            decor = root.rootView
            originalDecorPadding = decor!!.paddingBottom
            appliedDecorPadding = originalDecorPadding
        }
        val windowRoot = decor ?: return false
        val transferred = minOf(originalDecorPadding, PageNavigationSafeArea.navigationBottom(root))
        val outer = originalDecorPadding - transferred
        var changed = false
        if (windowRoot.paddingBottom != outer) {
            windowRoot.setPadding(windowRoot.paddingLeft, windowRoot.paddingTop, windowRoot.paddingRight, outer)
            changed = true
        }
        val iterator = contents.iterator()
        while (iterator.hasNext()) {
            val saved = iterator.next()
            val viewport = saved.view
            if (!viewport.isAttachedToWindow) {
                restore(saved)
                iterator.remove()
                continue
            }
            val inner = saved.padding + transferred
            if (viewport.paddingBottom != inner) {
                viewport.setPadding(viewport.paddingLeft, viewport.paddingTop, viewport.paddingRight, inner)
                changed = true
            }
            saved.applied = inner
            viewport.clipToPadding = false
            // These are full-page background-only branches, preceding the
            // cover and controls. Extend their rendering, never the controls.
            for (background in saved.backgrounds) if (background.view.height > 0) {
                val view = background.view
                view.pivotY = 0f
                val scale = background.scale * viewport.height.toFloat() / view.height
                if (view.scaleY != scale) view.scaleY = scale
            }
        }
        appliedDecorPadding = outer
        return changed
    }

    private fun find(view: View, depth: Int = 0) {
        if (!view.isShown || depth > 36 || view !is ViewGroup) return
        if (view.javaClass.name == contentClass && contents.none { it.view === view }) {
            val saved = Content(view)
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.top == 0 && child.width >= view.width * 9 / 10 &&
                    child.height >= view.height * 9 / 10) saved.backgrounds.add(Background(child))
            }
            contents.add(saved)
            return
        }
        for (i in 0 until view.childCount) find(view.getChildAt(i), depth + 1)
    }

    private fun restore(saved: Content) {
        val view = saved.view
        if (view.paddingBottom == saved.applied)
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, saved.padding)
        view.clipToPadding = saved.clip
        for (background in saved.backgrounds) {
            background.view.scaleY = background.scale
            background.view.pivotY = background.pivot
        }
    }

    fun release() {
        contents.forEach(::restore)
        contents.clear()
        decor?.let { view ->
            if (view.paddingBottom == appliedDecorPadding)
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, originalDecorPadding)
        }
        decor = null
    }
}
