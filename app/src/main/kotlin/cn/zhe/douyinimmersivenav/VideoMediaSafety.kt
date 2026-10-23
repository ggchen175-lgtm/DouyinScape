package cn.zhe.douyinimmersivenav

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import de.robv.android.xposed.XposedBridge

/** DetailActivity only. One media-branch inset, plus an opaque backing behind the native capsule.
 * No renderer transforms, height changes, clipBounds or playback callbacks are installed.
 */
internal class VideoMediaSafety(private val root: View) {
    private data class Clip(val view: ViewGroup, val children: Boolean, val padding: Boolean)
    private var holder: ViewGroup? = null
    private var baselinePadding = 0
    private var appliedPadding = 0
    private var input: ViewGroup? = null
    private var originalBackground: Drawable? = null
    // Native capsule compositing over the normal black video backdrop gives the measured 39–40.
    // Using opaque 39 behind that translucent capsule would blend twice and make it too bright.
    private val backing = ColorDrawable(0xFF020202.toInt())
    private val clips = ArrayList<Clip>()
    private val position = IntArray(2)
    private var lastLog = ""

    fun sync(page: ViewGroup, dock: ViewGroup, photoReserve: Int) {
        if (input !== dock) {
            restoreBackground()
            input = dock
            originalBackground = dock.background
        }
        if (dock.background !== backing) {
            // Keep a theme/background replacement made asynchronously by the app for release.
            originalBackground = dock.background
            dock.background = backing
        }
        if (backing.color != 0xFF020202.toInt()) backing.color = 0xFF020202.toInt()
        if (backing.alpha != 255) backing.alpha = 255
        val candidate = mediaHolder(page)
        if (candidate !== holder) {
            restoreHolder()
            holder = candidate
            if (candidate != null) {
                baselinePadding = candidate.paddingBottom
                appliedPadding = baselinePadding
            }
        }
        val target = holder ?: return
        val insets = root.rootWindowInsets ?: return
        val navBottom = if (Build.VERSION.SDK_INT >= 30)
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        else insets.systemWindowInsetBottom
        if (target.paddingBottom != appliedPadding) baselinePadding = target.paddingBottom
        dock.getLocationOnScreen(position)
        val dockTop = position[1]
        target.getLocationOnScreen(position)
        val nativeBottom = position[1] + target.height - baselinePadding
        // T4 already reserves this inset inside the photo subtree. Never reserve it twice.
        val reserve = if (nativeBottom > dockTop)
            (navBottom - photoReserve).coerceAtLeast(0) else 0
        val wanted = baselinePadding + reserve
        if (target.paddingBottom != wanted) {
            target.setPadding(target.paddingLeft, target.paddingTop, target.paddingRight, wanted)
        }
        appliedPadding = wanted
        // The holder clips all of its media branches. Its own background may still occupy its
        // native box; the opaque dock backing also covers that case (including sample C).
        enforceClip(target)
        for (i in 0 until target.childCount) {
            val child = target.getChildAt(i)
            if (child is ViewGroup && child.javaClass.simpleName == "VideoPinchViewContainer") {
                enforceClip(child)
            }
        }
        val signature = "$reserve/$photoReserve/$navBottom"
        if (signature != lastLog) {
            XposedBridge.log("[DouyinImmersiveNav] video media safety holder=${target.javaClass.name} " +
                "paddingBottom=$baselinePadding->$wanted navInset=$navBottom photoReserve=$photoReserve " +
                "contentBottom=${position[1] + target.height - wanted} dockTop=$dockTop " +
                "opaqueDock=true nativeCapsule=true viewport=unchanged")
            lastLog = signature
        }
    }

    private fun mediaHolder(page: View): ViewGroup? {
        var view = page.parent as? View
        while (view != null && view !== root) {
            if (view is ViewGroup && view.javaClass.simpleName == "VideoViewHolderRootView") return view
            if (view.javaClass.simpleName == "RTViewPager") break
            view = view.parent as? View
        }
        return null
    }
    private fun enforceClip(view: ViewGroup) {
        if (clips.none { it.view === view }) clips.add(Clip(view, view.clipChildren, view.clipToPadding))
        if (!view.clipChildren) view.clipChildren = true
        if (!view.clipToPadding) view.clipToPadding = true
    }
    private fun restoreHolder() {
        holder?.let { view ->
            if (view.paddingBottom == appliedPadding && appliedPadding != baselinePadding) {
                view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, baselinePadding)
            }
        }
        for (clip in clips) {
            clip.view.clipChildren = clip.children
            clip.view.clipToPadding = clip.padding
        }
        clips.clear()
        holder = null; baselinePadding = 0; appliedPadding = 0; lastLog = ""
    }
    private fun restoreBackground() {
        input?.let { if (it.background === backing) it.background = originalBackground }
        input = null; originalBackground = null
    }
    fun release() { restoreBackground(); restoreHolder() }
}
