package cn.zhe.douyinimmersivenav

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Insets
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.widget.TextView
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** Enabled only by the orders search hint inside SplashActivity's actual window. */
internal object OrdersEdgeToEdgeController {
    private const val PACKAGE = "com.ss.android.ugc.aweme"
    private const val SEARCH_HINT = "搜索团购/外卖订单"
    private val windows = WeakHashMap<View, State>()

    fun attach(window: Window, ownerName: String) {
        if (Build.VERSION.SDK_INT < 30 || !isSplashHost(window.context, ownerName)) return
        // Never install decor from an Activity.onCreate callback.
        val decor = XposedHelpers.callMethod(window, "peekDecorView") as? ViewGroup ?: return
        if (windows.containsKey(decor) || !decor.viewTreeObserver.isAlive) return
        val state = State(window, decor)
        windows[decor] = state
        state.layout = ViewTreeObserver.OnGlobalLayoutListener { state.dirty = true }
        state.pin = ViewTreeObserver.OnPreDrawListener {
            sync(state)
            true
        }
        state.detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                restore(state)
                windows.remove(decor)
                val observer = decor.viewTreeObserver
                if (observer.isAlive) {
                    observer.removeOnGlobalLayoutListener(state.layout)
                    observer.removeOnPreDrawListener(state.pin)
                }
                decor.removeOnAttachStateChangeListener(this)
            }
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(state.layout)
        decor.viewTreeObserver.addOnPreDrawListener(state.pin)
        decor.addOnAttachStateChangeListener(state.detach)
        log("orders window observed owner=$ownerName decor=${decor.javaClass.name}; awaiting page marker")
        sync(state)
    }

    private fun isSplashHost(context: Context, ownerName: String): Boolean {
        if (ownerName.substringAfterLast('.') == "SplashActivity") return true
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current.javaClass.simpleName == "SplashActivity"
            val next = current.baseContext
            if (next === current) break
            current = next
        }
        return false
    }

    /** Preserve status/cutout/IME data. Remove navigation fitting only for the recognized window. */
    fun rewriteInsets(view: View, original: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT < 30) return original
        val state = windows[view] ?: return original
        if (!state.active || original.isVisible(WindowInsets.Type.ime())) return original
        val nav = original.getInsets(WindowInsets.Type.navigationBars())
        val stable = original.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
        if (nav.bottom == 0 && stable.bottom == 0) return original
        val legacy = original.systemWindowInsets
        return WindowInsets.Builder(original)
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(nav.left, nav.top, nav.right, 0))
            .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.of(stable.left, stable.top, stable.right, 0))
            .setSystemWindowInsets(Insets.of(legacy.left, legacy.top, legacy.right, 0))
            .build()
    }

    private fun sync(state: State) {
        val now = SystemClock.uptimeMillis()
        val marker = state.marker
        if (state.active && (marker == null || !marker.isAttachedToWindow || !marker.isShown || !matches(marker))) {
            restore(state)
            state.dirty = true
        }
        if (!state.active) {
            val interval = if (state.dirty) 100L else 1000L
            if (now - state.lastSearch < interval) return
            state.lastSearch = now
            state.dirty = false
            val body = findBody(state) ?: return
            val search = findMarker(body) ?: return
            val insets = state.decor.rootWindowInsets ?: return
            val nav = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
            if (nav <= 0) return
            state.body = body
            state.marker = search
            state.nav = nav
            state.active = true
            state.originalUi = state.decor.systemUiVisibility
            state.originalFitTypes = state.window.attributes.fitInsetsTypes
            state.decor.getLocationOnScreen(state.rootPosition)
            val navTop = state.rootPosition[1] + state.decor.height - nav
            var branch: View? = body
            while (branch != null) {
                val view = branch
                view.getLocationOnScreen(state.position)
                val params = view.layoutParams
                val fixedHeight = params?.height ?: -1
                val grow = view !== state.decor && fixedHeight > 0 &&
                    kotlin.math.abs(state.position[1] + view.height - navTop) <= 2 &&
                    view.width >= state.decor.width * 9 / 10
                state.branches.add(Branch(view, view.fitsSystemWindows, view.paddingBottom,
                    fixedHeight, if (grow) fixedHeight + nav else fixedHeight))
                if (view === state.decor) break
                branch = view.parent as? View
            }
            val navId = state.decor.resources.getIdentifier("navigationBarBackground", "id", "android")
            state.navBackground = if (navId != 0) state.decor.findViewById(navId) else null
            state.originalNavAlpha = state.navBackground?.alpha ?: 1f
            state.window.setDecorFitsSystemWindows(false)
            state.window.navigationBarColor = Color.TRANSPARENT
            state.window.isNavigationBarContrastEnforced = false
            state.dirty = true
            state.decor.requestApplyInsets()
            log("orders page bound owner=${state.window.context.javaClass.name} body=${bounds(body)} navBottom=$nav; content edge-to-edge enabled")
        }
        val required = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (state.decor.systemUiVisibility and required != required) {
            state.decor.systemUiVisibility = state.decor.systemUiVisibility or required
        }
        val attrs = state.window.attributes
        val wantedTypes = attrs.fitInsetsTypes and WindowInsets.Type.navigationBars().inv()
        if (attrs.fitInsetsTypes != wantedTypes) {
            attrs.fitInsetsTypes = wantedTypes
            state.window.attributes = attrs
        }
        for (saved in state.branches) {
            if (saved.view.fitsSystemWindows) saved.view.fitsSystemWindows = false
            val bottom = if (saved.padding >= state.nav) saved.padding - state.nav else saved.padding
            if (saved.view.paddingBottom != bottom) {
                saved.view.setPadding(saved.view.paddingLeft, saved.view.paddingTop, saved.view.paddingRight, bottom)
            }
            val params = saved.view.layoutParams
            if (params != null && saved.grownHeight != saved.height && params.height != saved.grownHeight) {
                params.height = saved.grownHeight
                saved.view.layoutParams = params
            }
        }
        state.navBackground?.let { if (it.alpha != 0f) it.alpha = 0f }
        if (state.dirty && now - state.lastListsSearch >= 100L) {
            state.dirty = false
            state.lastListsSearch = now
            state.body?.let { collectLists(it, state) }
        }
        for (saved in state.lists) {
            if (!saved.view.isAttachedToWindow) continue
            val bottom = saved.padding + state.nav
            if (saved.view.paddingBottom != bottom) {
                saved.view.setPadding(saved.view.paddingLeft, saved.view.paddingTop, saved.view.paddingRight, bottom)
            }
            if (saved.view.clipToPadding) saved.view.clipToPadding = false
        }
        state.decor.getLocationOnScreen(state.rootPosition)
        val navTop = state.rootPosition[1] + state.decor.height - state.nav
        for (saved in state.floaters) {
            if (!saved.view.isAttachedToWindow || !saved.view.isShown) continue
            saved.view.getLocationOnScreen(state.position)
            val delta = navTop - (state.position[1] + saved.view.height)
            if (delta != 0 && kotlin.math.abs(delta) <= state.nav * 2) {
                saved.view.translationY += delta
                saved.lastY = saved.view.translationY
            }
        }
        if (!state.loggedGeometry && !state.decor.isLayoutRequested && state.lists.isNotEmpty()) {
            state.loggedGeometry = true
            log("orders viewport applied body=${state.body?.let(::bounds)} navigationBarBackground alpha=${state.navBackground?.alpha} " +
                state.lists.joinToString { "list=${bounds(it.view)} paddingBottom=${it.view.paddingBottom}" })
        }
    }

    private fun findBody(state: State): ViewGroup? {
        for (name in arrayOf("bh6", "bhr", "bhs")) {
            val id = state.decor.resources.getIdentifier(name, "id", PACKAGE)
            val candidate = if (id != 0) state.decor.findViewById<View>(id) as? ViewGroup else null
            if (candidate?.isShown == true && findMarker(candidate) != null) return candidate
        }
        // Mixed pages may expose their resource names through a different Resources instance.
        val marker = findMarker(state.decor) ?: return null
        var parent = marker.parent as? ViewGroup
        while (parent != null && parent !== state.decor) {
            val name = if (parent.id != View.NO_ID && parent.id ushr 24 != 0) {
                try { parent.resources.getResourceEntryName(parent.id) }
                catch (e: android.content.res.Resources.NotFoundException) {
                    if (unresolvedIds.add(parent.id)) log("orders resource unresolved id=${parent.id}: $e")
                    ""
                }
            } else ""
            if (name == "bh6" || name == "bhr" || name == "bhs") return parent
            parent = parent.parent as? ViewGroup
        }
        return null
    }

    private fun matches(view: TextView): Boolean = view.hint?.toString() == SEARCH_HINT || view.text?.toString() == SEARCH_HINT
    private fun findMarker(view: View): TextView? {
        if (!view.isShown) return null
        if (view is android.widget.EditText && matches(view)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findMarker(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun collectLists(view: View, state: State) {
        if (!view.isShown || view !is ViewGroup) return
        var type: Class<*>? = view.javaClass
        var recycler = false
        while (type != null && type != ViewGroup::class.java) {
            if (type.name.contains("RecyclerView") || type.name.contains("ListView")) { recycler = true; break }
            type = type.superclass
        }
        if (recycler) {
            if (view.height > state.decor.height / 3 && view.width > state.decor.width / 2 && state.lists.none { it.view === view }) {
                state.lists.add(ListState(view, view.paddingBottom, view.clipToPadding))
            }
            return
        }
        if (view.height in 1..state.nav * 3 && view.width in 1..state.decor.width / 2 &&
            (view.isClickable || view.hasFocusable())) {
            view.getLocationOnScreen(state.position)
            state.decor.getLocationOnScreen(state.rootPosition)
            val bottom = state.position[1] + view.height
            val windowBottom = state.rootPosition[1] + state.decor.height
            if (bottom in (windowBottom - state.nav * 2)..windowBottom) {
                if (state.floaters.none { it.view === view }) {
                    state.floaters.add(Floater(view, view.translationY, view.translationY))
                }
                return
            }
        }
        for (index in 0 until view.childCount) collectLists(view.getChildAt(index), state)
    }

    private fun restore(state: State) {
        if (!state.active) return
        state.active = false
        for (saved in state.floaters) {
            if (saved.view.translationY == saved.lastY) saved.view.translationY = saved.originalY
        }
        state.floaters.clear()
        for (saved in state.lists) {
            saved.view.setPadding(saved.view.paddingLeft, saved.view.paddingTop, saved.view.paddingRight, saved.padding)
            saved.view.clipToPadding = saved.clip
        }
        state.lists.clear()
        for (saved in state.branches) {
            saved.view.fitsSystemWindows = saved.fits
            saved.view.setPadding(saved.view.paddingLeft, saved.view.paddingTop, saved.view.paddingRight, saved.padding)
            val params = saved.view.layoutParams
            if (params != null && saved.grownHeight != saved.height && params.height == saved.grownHeight) {
                params.height = saved.height
                saved.view.layoutParams = params
            }
        }
        state.branches.clear()
        state.navBackground?.alpha = state.originalNavAlpha
        val mask = View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        state.decor.systemUiVisibility = (state.decor.systemUiVisibility and mask.inv()) or (state.originalUi and mask)
        if (state.decor.isAttachedToWindow) {
            val attrs = state.window.attributes
            attrs.fitInsetsTypes = state.originalFitTypes
            state.window.attributes = attrs
        }
        state.body = null
        state.marker = null
        state.loggedGeometry = false
        state.decor.requestApplyInsets()
        log("orders page released; window and list adjustments restored")
    }

    private data class Branch(val view: View, val fits: Boolean, val padding: Int, val height: Int, val grownHeight: Int)
    private data class ListState(val view: ViewGroup, val padding: Int, val clip: Boolean)
    private data class Floater(val view: View, val originalY: Float, var lastY: Float)
    private class State(val window: Window, val decor: ViewGroup) {
        var active = false
        var dirty = true
        var lastSearch = 0L
        var lastListsSearch = 0L
        var marker: TextView? = null
        var body: ViewGroup? = null
        var nav = 0
        var originalUi = 0
        var originalFitTypes = 0
        var navBackground: View? = null
        var originalNavAlpha = 1f
        var loggedGeometry = false
        val position = IntArray(2)
        val rootPosition = IntArray(2)
        val branches = ArrayList<Branch>()
        val lists = ArrayList<ListState>()
        val floaters = ArrayList<Floater>()
        lateinit var layout: ViewTreeObserver.OnGlobalLayoutListener
        lateinit var pin: ViewTreeObserver.OnPreDrawListener
        lateinit var detach: View.OnAttachStateChangeListener
    }

    private fun bounds(view: View): String {
        val p = IntArray(2)
        view.getLocationOnScreen(p)
        return "[${p[0]},${p[1]}][${p[0] + view.width},${p[1] + view.height}]"
    }
    private fun log(message: String) = XposedBridge.log("[DouyinImmersiveNav] $message")
    private val unresolvedIds = HashSet<Int>()
}
