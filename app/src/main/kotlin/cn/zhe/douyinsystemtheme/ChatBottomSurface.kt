package cn.zhe.douyinsystemtheme

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Wallpaper composers are transparent; only a disabled plain-chat bar owns a backplate. */
internal class ChatBottomSurface {
    private val originals = WeakHashMap<View, Drawable?>()
    private val applied = WeakHashMap<View, Drawable>()
    private var dockRef = WeakReference<ViewGroup>(null)
    private var hostRef = WeakReference<ViewGroup>(null)
    private var layer: Surface? = null
    private var mode: Boolean? = null
    private var wallpaperMode: Boolean? = null
    private var lastScan = 0L
    private val pos = IntArray(2)
    private val origin = IntArray(2)
    private val bounds = Rect()

    fun sync(root: ViewGroup, state: UnifiedChatBridge.Dock?, wallpaper: Boolean?, night: Boolean, dirty: Boolean, chat: ViewGroup?) {
        val readOnly = chat?.let(NativeChatRegistry::disabledBar)
        val dock = readOnly ?: state?.dock?.get()
        if ((wallpaper != true && readOnly == null) || dock == null || !dock.isShown || chat == null) { release(); return }
        if (dockRef.get() !== dock || wallpaperMode != (wallpaper == true)) {
            release()
            dockRef = WeakReference(dock)
            wallpaperMode = wallpaper == true
            if (wallpaper != true) {
            // Only the plain disabled-input bar has a page-local background extension.
            var host: ViewGroup = chat
            var page = dock.parent as? ViewGroup
            while (page != null && page !== chat) {
                if (page is android.widget.FrameLayout && page.width == chat.width && page.height == chat.height) {
                    host = page; break
                }
                page = page.parent as? ViewGroup
            }
            var foreground: View = dock
            while (foreground.parent is View && foreground.parent !== host) foreground = foreground.parent as View
            if (foreground.parent !== host) return
            val surface = Surface(host.context)
            surface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            surface.isClickable = false; surface.isFocusable = false
            host.addView(surface, host.indexOfChild(foreground), ViewGroup.LayoutParams(-1, -1))
            hostRef = WeakReference(host); layer = surface
            XposedBridge.log("[DouyinUnified] disabled plain-chat bar attached inside native page before controls")
            } else {
                XposedBridge.log("[DouyinUnified] wallpaper composer transparent; no panel scrim or navigation overlay; rounded controls retained")
            }
        }
        if (wallpaper != true && mode != night) {
            val cfg = android.content.res.Configuration(root.resources.configuration)
            cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            layer?.color = root.context.createConfigurationContext(cfg).resources.getColor(0x7f060040, null)
            mode = night
        }
        fun clear(view: View) {
            val owned = applied[view]
            if (owned === view.background && applied.containsKey(view)) return
            originals[view] = view.background
            val transparent = ColorDrawable(0)
            applied[view] = transparent; view.background = transparent
        }
        clear(dock)
        if (readOnly != null) {
            var wrapper = dock.parent as? ViewGroup
            while (wrapper != null && wrapper !== chat && wrapper.width == dock.width && wrapper.height == dock.height) {
                val bg = wrapper.background
                if (bg is ColorDrawable || bg is GradientDrawable && android.os.Build.VERSION.SDK_INT >= 24 &&
                    bg.colors == null && bg.cornerRadius == 0f && bg.cornerRadii == null) clear(wrapper)
                wrapper = wrapper.parent as? ViewGroup
            }
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (readOnly == null && (dirty && now-lastScan >= 100L || now-lastScan >= 500L)) {
            lastScan = now
            fun visit(v: View) {
                if (!v.isShown) return
                if (v !== dock && v.width >= dock.width*3/4) {
                    val bg = v.background
                    if (bg is ColorDrawable || bg is GradientDrawable && android.os.Build.VERSION.SDK_INT >= 24 &&
                        bg.colors == null && bg.cornerRadius == 0f && bg.cornerRadii == null) clear(v)
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
            }
            visit(dock)
            state?.lift?.get()?.takeIf { it !== dock && it.width == dock.width && it.height == dock.height && it.background is ColorDrawable }?.let(::clear)
        }
        if (wallpaper == true) return
        val host = hostRef.get() ?: return
        dock.getLocationOnScreen(pos); host.getLocationOnScreen(origin)
        val bottom = if (state?.ime == true || state?.picker == true) pos[1]+dock.height-origin[1] else host.height
        bounds.set(0, (pos[1]-origin[1]).coerceIn(0, host.height), host.width, bottom.coerceIn(0, host.height))
        layer?.update(bounds)
    }
    fun release() {
        layer?.let { (it.parent as? ViewGroup)?.removeView(it) }; layer = null
        for ((v, bg) in applied) if (v.background === bg) v.background = originals[v]
        applied.clear(); originals.clear(); dockRef.clear(); hostRef.clear(); mode = null; wallpaperMode = null; lastScan = 0L
    }
    private class Surface(context: android.content.Context) : View(context) {
        private val paint = Paint()
        private val rect = Rect()
        var color: Int = 0
            set(value) { if (field != value) { field = value; invalidate() } }
        init { setWillNotDraw(false) }
        fun update(next: Rect) { if (rect != next) { rect.set(next); invalidate() } }
        override fun onDraw(canvas: Canvas) { paint.color = color; canvas.drawRect(rect, paint) }
    }
}
