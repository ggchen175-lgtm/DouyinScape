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
    /** Bottom anchor of the current feed page: its bottom edge marks where the tab bar starts. */
    private var anchor: View? = null
    /** Padding this module added on top of the host's own padding. */
    private var ourExtra = 0
    private var nativePadding = parentPadding
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
        val fullscreen = PageNavigationSafeArea.isPinchPlayback(root)
        if (Build.VERSION.SDK_INT < 30 || (!row.isShown && !fullscreen) || host.height == 0) return true
        val insets = root.rootView.rootWindowInsets ?: return true
        if (insets.isVisible(WindowInsets.Type.ime())) return true
        val nav = PageNavigationSafeArea.navigationBottom(root)
        if (dirty) {
            dirty = false
            anchor = parent?.let { findFeedBottomAnchor(it) }
            feed = anchor != null
        }
        var changed = false
        // Only top up the part of the gesture band the page has not already
        // reserved. A ROM or the app itself may have lifted its own bottom
        // controls already; adding the full inset again would double-shift it.
        // The fullscreen player owns its controls; release only our home-feed reserve.
        val reserve = if (fullscreen) 0 else if (feed) bottomDeficit(nav) else 0
        parent?.let { view ->
            nativePadding = (view.paddingBottom - ourExtra).coerceAtLeast(0)
            ourExtra = reserve
            appliedPadding = nativePadding + reserve
            if (view.paddingBottom != appliedPadding) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, appliedPadding)
                changed = true
            }
            view.clipToPadding = if (feed) false else parentClip
        }
        val params = host.layoutParams
        // The host must stay tall enough to contain the lifted tab row, so it
        // always grows by the full inset. Only the parent padding follows the
        // measured deficit; the margin compensates whatever the parent now pads
        // so the host keeps sitting on the screen edge.
        appliedHeight = nativeHostHeight + nav
        appliedMargin = hostMargin - (nativePadding + reserve)
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

    /**
     * How much of the gesture band the page still covers on its own.
     * Zero means the page (or its ROM) already lifted its bottom controls,
     * so the module must leave the layout alone.
     */
    private fun bottomDeficit(nav: Int): Int {
        val target = anchor ?: return nav
        if (!target.isShown || target.height <= 0) return nav
        root.rootView.getLocationOnScreen(position)
        val safeBottom = position[1] + root.rootView.height - nav
        // Undo our own padding so the next pass reads the page's native position
        // instead of chasing the shift it just applied.
        val nativeBottom = bottomOf(target) + ourExtra
        return (nativeBottom - safeBottom).coerceIn(0, nav)
    }

    /**
     * Feed pages reserve their own bottom space; message and profile tabs do not.
     * Prefer the native BottomSpace, and fall back to the full-width seekbar host
     * so a renamed container still yields an anchor instead of silently opting out.
     */
    private fun findFeedBottomAnchor(container: ViewGroup): View? =
        findBottomSpace(container) ?: findSeekBarHost(container)

    private fun findBottomSpace(view: View, depth: Int = 0): View? {
        if (!view.isShown || depth > 28 || view === host) return null
        if (view.javaClass.name == FEED_BOTTOM_SPACE && view.height > 0 && isOnScreen(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount)
            findBottomSpace(view.getChildAt(i), depth + 1)?.let { return it }
        return null
    }

    private fun findSeekBarHost(view: View, depth: Int = 0, best: View? = null): View? {
        var found = best
        if (!view.isShown || depth > 28 || view === host) return found
        if (view.javaClass.name.contains("SeekBar") && view.height > 0 &&
            view.width >= root.width * 0.8f && isOnScreen(view)) {
            // Keep the candidate with the lowest bottom edge: the outer host
            // spans the page, the thin bar inside it does not.
            if (found == null || bottomOf(view) > bottomOf(found)) found = view
        }
        if (view is ViewGroup) for (i in 0 until view.childCount)
            found = findSeekBarHost(view.getChildAt(i), depth + 1, found)
        return found
    }

    private fun bottomOf(view: View): Int {
        view.getLocationOnScreen(position)
        return position[1] + view.height
    }

    private fun isOnScreen(view: View): Boolean {
        view.getLocationOnScreen(position)
        return position[0] >= 0 && position[0] < root.width && position[1] < root.height
    }

    private fun release() {
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layout)
        row.removeOnAttachStateChangeListener(attachment)
        if (row.translationY == appliedTranslation) row.translationY = rowTranslation
        rowParent?.clipChildren = rowParentClip
        parent?.let { view ->
            if (view.paddingBottom == appliedPadding)
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight,
                    (view.paddingBottom - ourExtra).coerceAtLeast(0))
            view.clipToPadding = parentClip
        }
        ourExtra = 0
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
        private const val FEED_BOTTOM_SPACE = "com.ss.android.ugc.aweme.feed.ui.bottom.BottomSpace"

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
