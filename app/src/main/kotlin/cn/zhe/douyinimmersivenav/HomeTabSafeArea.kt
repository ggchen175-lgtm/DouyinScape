package cn.zhe.douyinimmersivenav

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.LinearLayout

/** Owns main tabs only. Chat composers and public detail pages have separate owners. */
internal class HomeTabSafeArea private constructor(
    private val root: View, private val host: ViewGroup,
    private val row: ViewGroup, private val onDetached: () -> Unit
) {
    private val parent = host.parent as? ViewGroup
    private val parentPadding = parent?.paddingBottom ?: 0
    private val parentClip = parent?.clipToPadding ?: true
    private val hostHeight = host.layoutParams.height
    private val nativeHostHeight = host.height
    private val hostMargin = (host.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
    private val rowHeight = row.layoutParams.height
    private val nativeRowHeight = row.height
    private val rowTranslation = row.translationY
    private var appliedTranslation = rowTranslation
    private var appliedPadding = parentPadding
    private var appliedMargin = hostMargin
    private var appliedHeight = hostHeight
    private val background = run {
        var view: View = row
        while (view.parent is View && view.parent !== host) view = view.parent as View
        view.takeIf { it !== row && it.parent === host }
    }
    private val backgroundHeight = background?.layoutParams?.height
    private val rowParent = row.parent as? ViewGroup
    private val rowParentClip = rowParent?.clipChildren ?: true
    private val position = IntArray(2)
    private var dirty = true
    private var feed = false
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { dirty = true }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            release()
            onDetached()
        }
    }

    init {
        rowParent?.clipChildren = false
        root.viewTreeObserver.addOnGlobalLayoutListener(layout)
        row.addOnAttachStateChangeListener(attachment)
    }

    fun sync(): Boolean {
        if (Build.VERSION.SDK_INT < 30 || !row.isShown || host.height == 0) return true
        val insets = root.rootView.rootWindowInsets ?: return true
        if (insets.isVisible(WindowInsets.Type.ime())) return true
        val nav = PageNavigationSafeArea.navigationBottom(root)
        if (dirty) {
            dirty = false
            feed = parent?.let { hasVisibleFeedSpace(it) } == true
        }
        var changed = false
        // Native list tabs already subtract the expanded tab host height.
        // Only feed layouts with their own BottomSpace need viewport reservation.
        parent?.let { view ->
            appliedPadding = parentPadding + if (feed) nav else 0
            if (view.paddingBottom != appliedPadding) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, appliedPadding)
                changed = true
            }
            view.clipToPadding = if (feed) false else parentClip
        }
        val params = host.layoutParams
        appliedHeight = nativeHostHeight + nav
        appliedMargin = hostMargin - if (feed) nav else 0
        if (params.height != appliedHeight ||
            (params is ViewGroup.MarginLayoutParams && params.bottomMargin != appliedMargin)) {
            params.height = appliedHeight
            (params as? ViewGroup.MarginLayoutParams)?.bottomMargin = appliedMargin
            host.layoutParams = params
            changed = true
        }
        if (row.layoutParams.height != nativeRowHeight) {
            row.layoutParams = row.layoutParams.apply { height = nativeRowHeight }
            changed = true
        }
        background?.let { view ->
            if (view.layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                view.layoutParams = view.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                changed = true
            }
        }
        if (changed) return false
        root.rootView.getLocationOnScreen(position)
        val safeBottom = position[1] + root.rootView.height - nav
        row.getLocationOnScreen(position)
        val nativeBottom = position[1] + row.height - (row.translationY - rowTranslation)
        appliedTranslation = rowTranslation + minOf(0f, safeBottom - nativeBottom)
        if (kotlin.math.abs(row.translationY - appliedTranslation) > 0.5f) row.translationY = appliedTranslation
        return true
    }

    private fun hasVisibleFeedSpace(view: View, depth: Int = 0): Boolean {
        if (!view.isShown || depth > 28 || view === host) return false
        if (view.javaClass.name == "com.ss.android.ugc.aweme.feed.ui.bottom.BottomSpace" && view.height > 0) {
            view.getLocationOnScreen(position)
            return position[0] >= 0 && position[0] < root.width && position[1] < root.height
        }
        if (view is ViewGroup) for (i in 0 until view.childCount)
            if (hasVisibleFeedSpace(view.getChildAt(i), depth + 1)) return true
        return false
    }

    private fun release() {
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layout)
        row.removeOnAttachStateChangeListener(attachment)
        if (row.translationY == appliedTranslation) row.translationY = rowTranslation
        rowParent?.clipChildren = rowParentClip
        parent?.let { view ->
            if (view.paddingBottom == appliedPadding)
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, parentPadding)
            view.clipToPadding = parentClip
        }
        host.layoutParams.let { params ->
            if (params.height == appliedHeight) params.height = hostHeight
            (params as? ViewGroup.MarginLayoutParams)?.let { if (it.bottomMargin == appliedMargin) it.bottomMargin = hostMargin }
            host.layoutParams = params
        }
        if (row.layoutParams.height == nativeRowHeight) row.layoutParams = row.layoutParams.apply { height = rowHeight }
        background?.let { view ->
            if (view.layoutParams.height == ViewGroup.LayoutParams.MATCH_PARENT && backgroundHeight != null)
                view.layoutParams = view.layoutParams.apply { height = backgroundHeight }
        }
    }

    companion object {
        fun bind(root: View, host: ViewGroup, onDetached: () -> Unit): HomeTabSafeArea? {
            val id = host.resources.getIdentifier("root_view", "id", "com.ss.android.ugc.aweme")
            val row = if (id != 0) host.findViewById<ViewGroup>(id) else null
            val content = row?.takeIf { it !== host } ?: findRow(host) ?: return null
            if (host.height <= 0 || content.height <= 0) return null
            return HomeTabSafeArea(root, host, content, onDetached)
        }

        private fun findRow(view: ViewGroup): LinearLayout? {
            var best: LinearLayout? = null
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val candidate = if (child is LinearLayout && child.childCount >= 5) child
                    else (child as? ViewGroup)?.let { findRow(it) }
                if (candidate != null && (best == null || candidate.childCount > best.childCount)) best = candidate
            }
            return best
        }
    }
}
