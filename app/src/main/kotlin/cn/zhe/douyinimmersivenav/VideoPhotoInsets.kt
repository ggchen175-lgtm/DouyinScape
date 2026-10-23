package cn.zhe.douyinimmersivenav

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import de.robv.android.xposed.XposedBridge

/** Content-only inset for photo posts. Never touches the video renderer or page dimensions. */
internal class VideoPhotoInsets(private val root: View) {
    private var content: ViewGroup? = null
    private var photoPager: View? = null
    private var originalBottom = 0
    private var appliedBottom = 0
    private var loggedInset = -1
    private val position = IntArray(2)
    var appliedInset = 0
        private set

    fun sync(page: ViewGroup, views: List<View>, inputTop: Int) {
        // The photo pager is nested inside FeedPhotosGestureDispatchLayout/root_view.
        // progress_bar alone is not sufficient: video seekbars and photo indicators coexist.
        val pager = photoPager?.takeIf { it.isAttachedToWindow && it.isShown } ?: views.firstOrNull { view ->
            view.javaClass.simpleName == "HorizonViewPager" && view.isShown &&
                view.width > 0 && view.height > 0 && hasPhotoAncestor(view, page)
        }
        val candidate = (pager?.parent as? ViewGroup)?.takeIf {
            it !== page && it.isShown && it.width > 0 && it.height > 0 &&
                it.javaClass.simpleName != "MonitorScrollFrameLayout" &&
                it.javaClass.simpleName != "VideoViewHolderRootView"
        }
        if (candidate !== content) {
            release()
            photoPager = pager
            content = candidate
            if (candidate != null) {
                originalBottom = candidate.paddingBottom
                appliedBottom = originalBottom
            }
        }
        val target = content ?: return // A video post receives no media-layout intervention.
        val insets = root.rootWindowInsets ?: return
        val navBottom = if (Build.VERSION.SDK_INT >= 30)
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        else insets.systemWindowInsetBottom
        // Respect app-owned padding changes without ever adding our inset a second time.
        if (target.paddingBottom != appliedBottom) originalBottom = target.paddingBottom
        target.getLocationOnScreen(position)
        val nativeContentBottom = position[1] + target.height - originalBottom
        val inset = if (nativeContentBottom > inputTop) navBottom.coerceAtLeast(0) else 0
        val wanted = originalBottom + inset
        if (target.paddingBottom != wanted) {
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, wanted)
        }
        appliedBottom = wanted
        appliedInset = inset
        if (loggedInset != inset) {
            XposedBridge.log("[DouyinImmersiveNav] video photo inset content=${target.javaClass.name} " +
                "bounds=[${position[0]},${position[1]}][${position[0] + target.width},${position[1] + target.height}] " +
                "paddingBottom=$originalBottom->$wanted navInset=$navBottom reserve=$inset; viewport=unchanged")
            loggedInset = inset
        }
    }

    private fun hasPhotoAncestor(view: View, page: View): Boolean {
        var parent = view.parent as? View
        while (parent != null && parent !== page) {
            if (parent.javaClass.simpleName == "FeedPhotosGestureDispatchLayout") return true
            parent = parent.parent as? View
        }
        return false
    }

    fun release() {
        content?.let { target ->
            if (target.paddingBottom == appliedBottom && appliedBottom != originalBottom) {
                target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, originalBottom)
            }
        }
        content = null
        photoPager = null
        originalBottom = 0; appliedBottom = 0; appliedInset = 0; loggedInset = -1
    }
}
