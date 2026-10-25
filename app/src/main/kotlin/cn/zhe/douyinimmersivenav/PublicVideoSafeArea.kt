package cn.zhe.douyinimmersivenav

import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/** Shared native feed viewport with an interactive bottom dock, with or without an editor. */
internal class PublicVideoSafeArea(private val root: View) {
    private var host: ViewGroup? = null
    private var editor: View? = null
    private var originalPadding = 0
    private var appliedPadding = 0
    private var lastSearch = 0L
    private val position = IntArray(2)
    private var geometryDirty = true
    private var pendingMeasurement = false

    fun markDirty() { geometryDirty = true }

    /** null means no shared detail layout; false requests a fresh native measurement pass. */
    fun sync(preDraw: Boolean): Boolean? {
        if (Build.VERSION.SDK_INT < 30) return null
        if (host?.isAttachedToWindow != true || editor?.isAttachedToWindow != true) release()
        val now = SystemClock.uptimeMillis()
        if (host == null && (geometryDirty || now - lastSearch >= 150L)) {
            geometryDirty = false
            lastSearch = now
            discover(root)
        }
        val container = host ?: return null
        val field = editor ?: return null
        val insets = root.rootView.rootWindowInsets ?: return true
        val ime = insets.isVisible(WindowInsets.Type.ime())
        if (!field.isShown && !ime) {
            release()
            return null
        }
        root.rootView.getLocationOnScreen(position)
        val safeBottom = position[1] + root.rootView.height - PageNavigationSafeArea.navigationBottom(root)
        container.getLocationOnScreen(position)
        val nativeBottom = position[1] + container.height - originalPadding
        val extra = if (ime) 0 else (nativeBottom - safeBottom)
            .coerceIn(0, PageNavigationSafeArea.navigationBottom(root))
        val wanted = originalPadding + extra
        if (container.paddingBottom != wanted) {
            container.setPadding(container.paddingLeft, container.paddingTop, container.paddingRight, wanted)
            appliedPadding = wanted
            pendingMeasurement = true
            // Keep the native viewport and touch targets aligned in the same
            // measurement pass. Its own background still fills the gesture area.
            return !preDraw
        }
        appliedPadding = wanted
        // Another listener can set padding before this pre-draw listener runs.
        // Matching padding is not proof that native children have been measured.
        if (pendingMeasurement) {
            if (container.isLayoutRequested) return !preDraw
            pendingMeasurement = false
        }
        return true
    }

    private fun discover(view: View, depth: Int = 0) {
        if (host != null || depth > 42 || !view.isShown) return
        // Both comment and private-message composers must reserve space at the
        // shared parent, so progress/collection controls move with the input.
        // Resource IDs and optional caption/search rows vary between builds.
        if (view is android.widget.EditText && view.javaClass.name in setOf(
                "com.ss.android.ugc.aweme.comment.widget.CommentMentionEditText",
                "com.ss.android.ugc.aweme.im.widget.SearchableEditText")) {
            view.getLocationOnScreen(position)
            if (position[0] < 0 || position[0] >= root.width || position[1] < root.height / 2) return
            bindAnchor(view)
        }
        // Non-editor actions (watch-later queue, playlist controls) share the
        // same feed + BottomSpace relationship. Require a visible interactive
        // bottom row; never infer a page from labels, resource IDs or Activity.
        if (host == null && view is ViewGroup && view.width >= root.width * 0.9f &&
            view.height in 1 until root.height / 3 && hasAction(view)) {
            view.getLocationOnScreen(position)
            if (position[0] >= 0 && position[0] < root.width && position[1] >= root.height / 2)
                bindAnchor(view)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) discover(view.getChildAt(i), depth + 1)
    }

    private fun hasAction(view: View, depth: Int = 0): Boolean {
        if (!view.isShown || depth > 8) return false
        if (view.isClickable && view.isEnabled) return true
        if (view is ViewGroup) for (i in 0 until view.childCount)
            if (hasAction(view.getChildAt(i), depth + 1)) return true
        return false
    }

    private fun bindAnchor(view: View) {
        var branch: View = view
        var dock: View = view
        var ancestor = view.parent as? View
        while (ancestor != null && ancestor !== root) {
            if (ancestor.width >= root.width * 0.9f && ancestor.height > 0 &&
                ancestor.height < root.height / 3) dock = ancestor
            if (ancestor is ViewGroup && isSharedViewport(ancestor, branch, dock)) {
                host = ancestor
                editor = view
                originalPadding = ancestor.paddingBottom
                appliedPadding = originalPadding
                return
            }
            branch = ancestor
            ancestor = ancestor.parent as? View
        }
    }

    // Match the native relationship, not an Activity name, resource ID or
    // wrapper class: a short composer branch alongside the feed viewport.
    private fun isSharedViewport(parent: ViewGroup, composer: View, dock: View): Boolean {
        if (parent.width < root.width * 0.9f || parent.height < root.height * 0.65f ||
            dock.width < root.width * 0.9f || dock.height <= 0 || dock.height > root.height / 3) return false
        parent.getLocationOnScreen(position)
        val bottom = position[1] + parent.height - parent.paddingBottom
        dock.getLocationOnScreen(position)
        if (kotlin.math.abs(position[1] + dock.height - bottom) >
            PageNavigationSafeArea.navigationBottom(root) + 2) return false
        for (i in 0 until parent.childCount) {
            val sibling = parent.getChildAt(i)
            if (sibling !== composer && sibling.isShown &&
                containsFeedPart(sibling, "com.ss.android.ugc.aweme.common.widget.RTViewPager") &&
                containsFeedPart(sibling, "com.ss.android.ugc.aweme.feed.ui.bottom.BottomSpace")) return true
        }
        return false
    }

    private fun containsFeedPart(view: View, className: String, depth: Int = 0): Boolean {
        if (!view.isShown || depth > 9) return false
        if (view.javaClass.name == className) return true
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            if (containsFeedPart(view.getChildAt(i), className, depth + 1)) return true
        }
        return false
    }

    fun release() {
        host?.let { view ->
            if (view.paddingBottom == appliedPadding)
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, originalPadding)
        }
        host = null
        editor = null
        pendingMeasurement = false
    }
}
