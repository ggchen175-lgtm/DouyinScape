package cn.zhe.douyinimmersivenav

import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import java.util.IdentityHashMap

/** Full-window native media measurement; playback controls retain navigation clearance. */
internal class PinchVideoSafeArea(private val root: ViewGroup) {
    private data class Height(val view: View, val original: Int, var applied: Int)
    private data class Translation(val original: Float, var applied: Float)
    private var scene: ViewGroup? = null
    private var viewport: Height? = null
    private var space: Height? = null
    private var bar: View? = null
    private var originalBarBackground: Drawable? = null
    private var transparent: Drawable? = null
    private var barTranslation: Translation? = null
    private val progress = IdentityHashMap<View, Translation>()
    private val visible = Rect()
    private val position = IntArray(2)
    private var lastSearch = 0L
    private var lastProgressSearch = 0L

    val isActive: Boolean get() = scene?.isAttachedToWindow == true && scene?.isShown == true
    fun markDirty() { lastSearch = 0L; lastProgressSearch = 0L }

    fun sync(): Boolean {
        if (!isActive || bar?.isAttachedToWindow != true) {
            release()
            val now = SystemClock.uptimeMillis()
            if (now - lastSearch < 350L) return false
            lastSearch = now
            discover(root)
        }
        val controls = bar ?: return false
        val host = viewport ?: return false
        root.getLocationOnScreen(position)
        val windowBottom = position[1] + root.height
        val nav = PageNavigationSafeArea.navigationBottom(root)
        var changed = false
        // Home releases its own inherited padding through HomeTabSafeArea.
        // An independent player may also have an explicitly sized viewport.
        host.view.getLocationOnScreen(position)
        val wantedHeight = windowBottom - position[1]
        if (wantedHeight > 0 && host.view.height in (wantedHeight - nav)..wantedHeight &&
            host.view.layoutParams.height != wantedHeight) {
            host.view.layoutParams = host.view.layoutParams.apply { height = wantedHeight }
            host.applied = wantedHeight
            changed = true
        }
        space?.takeIf { it.view.isAttachedToWindow }?.let { reserve ->
            if (reserve.view.layoutParams.height != 0) {
                reserve.view.layoutParams = reserve.view.layoutParams.apply { height = 0 }
                reserve.applied = 0
                changed = true
            }
        }
        if (controls.background !== transparent) {
            originalBarBackground = controls.background
            transparent = ColorDrawable(0)
            controls.background = transparent
        }
        if (changed) return true
        val safeBottom = windowBottom - nav
        val translation = barTranslation ?: return false
        controls.getLocationOnScreen(position)
        val nativeBottom = position[1] + controls.height - (controls.translationY - translation.original)
        translation.applied = translation.original - (nativeBottom - safeBottom).coerceIn(0f, nav.toFloat())
        if (kotlin.math.abs(controls.translationY - translation.applied) >= 0.5f)
            controls.translationY = translation.applied
        val now = SystemClock.uptimeMillis()
        if (now - lastProgressSearch >= 350L) {
            lastProgressSearch = now
            discoverProgress(host.view)
        }
        controls.getLocationOnScreen(position)
        val controlTop = position[1]
        val iterator = progress.iterator()
        while (iterator.hasNext()) {
            val (seek, saved) = iterator.next()
            if (!seek.isAttachedToWindow) { iterator.remove(); continue }
            if (!seek.isShown) continue
            seek.getLocationOnScreen(position)
            val nativeTrackBottom = position[1] + seek.height - seek.paddingBottom -
                (seek.translationY - saved.original)
            saved.applied = saved.original + minOf(0f, controlTop - nativeTrackBottom)
            if (kotlin.math.abs(seek.translationY - saved.applied) >= 0.5f)
                seek.translationY = saved.applied
        }
        return false
    }

    private fun discover(view: View, depth: Int = 0) {
        if (scene != null || !view.isShown || depth > 38 || view !is ViewGroup) return
        if (view.javaClass.name == "com.ss.android.ugc.aweme.pinch.ui.VideoPinchView" &&
            view.getGlobalVisibleRect(visible) && visible.width() >= root.width * 0.8f &&
            visible.height() >= root.height * 0.65f) {
            val controls = findBar(view, view.width, view.height)
            var ancestor = view.parent as? ViewGroup
            while (controls != null && ancestor != null && ancestor !== root) {
                val reserve = findClass(ancestor, "com.ss.android.ugc.aweme.feed.ui.bottom.BottomSpace")
                if (reserve != null && findClass(ancestor, "com.ss.android.ugc.aweme.common.widget.RTViewPager") != null) {
                    scene = view
                    viewport = Height(ancestor, ancestor.layoutParams.height, ancestor.layoutParams.height)
                    space = Height(reserve, reserve.layoutParams.height, reserve.layoutParams.height)
                    bar = controls
                    originalBarBackground = controls.background
                    barTranslation = Translation(controls.translationY, controls.translationY)
                    XposedBridge.log("[DouyinImmersiveNav] fullscreen media viewport bound; native spacer released, playback controls inset separately")
                    return
                }
                ancestor = ancestor.parent as? ViewGroup
            }
        }
        for (i in 0 until view.childCount) discover(view.getChildAt(i), depth + 1)
    }

    private fun findBar(view: View, width: Int, height: Int, depth: Int = 0): View? {
        if (depth > 12) return null
        if (view.width >= width * 0.8f && view.height in 1..(height / 3) && view.background is ColorDrawable &&
            findClass(view, "com.ss.android.ugc.aweme.pinch.ui.VideoPinchBar") != null) return view
        if (view is ViewGroup) for (i in 0 until view.childCount)
            findBar(view.getChildAt(i), width, height, depth + 1)?.let { return it }
        return null
    }

    private fun findClass(view: View, name: String, depth: Int = 0): View? {
        if (depth > 12) return null
        if (view.javaClass.name == name) return view
        if (view is ViewGroup) for (i in 0 until view.childCount)
            findClass(view.getChildAt(i), name, depth + 1)?.let { return it }
        return null
    }

    private fun discoverProgress(view: View, depth: Int = 0) {
        if (!view.isShown || depth > 18) return
        if (view.javaClass.name.contains("SeekBar") && view.width >= root.width * 0.8f &&
            view.height in 1..(root.height / 10))
            progress.getOrPut(view) { Translation(view.translationY, view.translationY) }
        if (view is ViewGroup) for (i in 0 until view.childCount) discoverProgress(view.getChildAt(i), depth + 1)
    }

    fun release() {
        for (saved in listOfNotNull(space, viewport)) if (saved.view.layoutParams.height == saved.applied)
            saved.view.layoutParams = saved.view.layoutParams.apply { height = saved.original }
        bar?.let { view ->
            if (view.background === transparent) view.background = originalBarBackground
            barTranslation?.let { if (view.translationY == it.applied) view.translationY = it.original }
        }
        for ((view, saved) in progress) if (view.translationY == saved.applied) view.translationY = saved.original
        progress.clear()
        scene = null; viewport = null; space = null; bar = null
        transparent = null; originalBarBackground = null; barTranslation = null
    }
}
