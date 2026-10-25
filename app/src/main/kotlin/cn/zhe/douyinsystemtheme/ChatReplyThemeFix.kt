package cn.zhe.douyinsystemtheme

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Reply-only surfaces, including attached popup/dialog roots. No chat dock translations. */
internal class ChatReplyThemeFix(
    private val enabled: () -> Boolean, private val dark: () -> Boolean, private val log: (String) -> Unit
) {
    private data class Chat(val root: WeakReference<ViewGroup>, val wallpaper: Boolean?)
    private class State(val root: WeakReference<ViewGroup>) {
        val originals = WeakHashMap<View, Drawable?>()
        val applied = WeakHashMap<View, Drawable>()
        val appliedColors = WeakHashMap<View, Int>()
        var extension: Extension? = null
        var lastScan = 0L
        var night: Boolean? = null
        var wallpaper: Boolean? = null
        var logged = false
        var dirty = true
        var panel = WeakReference<ViewGroup>(null)
    }
    private val chats = WeakHashMap<Activity, Chat>()
    private val states = WeakHashMap<ViewGroup, State>()
    private val observed = WeakHashMap<ViewGroup, Boolean>()
    private val position = IntArray(2)
    private val popupOriginals = WeakHashMap<PopupWindow, Drawable?>()
    private val popupApplied = WeakHashMap<PopupWindow, Drawable>()
    private val dialogOriginals = WeakHashMap<Dialog, Drawable?>()
    private val dialogApplied = WeakHashMap<Dialog, Drawable>()

    fun install() {
        val show = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (enabled()) prepareWindow(param.thisObject)
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || !enabled()) return
                prepareWindow(param.thisObject)
                val content = when (val instance = param.thisObject) {
                    is PopupWindow -> instance.contentView
                    is Dialog -> instance.window?.peekDecorView()
                    else -> null
                } ?: return
                val owner = activity(content.context) ?: return
                if (!chats.containsKey(owner)) return
                observe(content.rootView as? ViewGroup ?: return, owner)
            }
        }
        XposedBridge.hookAllMethods(PopupWindow::class.java, "showAtLocation", show)
        XposedBridge.hookAllMethods(PopupWindow::class.java, "showAsDropDown", show)
        XposedBridge.hookAllMethods(Dialog::class.java, "show", show)
        val dismiss = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                when (val instance = param.thisObject) {
                    is PopupWindow -> {
                        val applied = popupApplied.remove(instance)
                        val original = popupOriginals.remove(instance)
                        if (applied != null && instance.background === applied) instance.setBackgroundDrawable(original)
                    }
                    is Dialog -> {
                        val applied = dialogApplied.remove(instance)
                        val original = dialogOriginals.remove(instance)
                        if (applied != null && instance.window?.peekDecorView()?.background === applied)
                            instance.window?.setBackgroundDrawable(original)
                    }
                }
            }
        }
        XposedBridge.hookAllMethods(PopupWindow::class.java, "dismiss", dismiss)
        XposedBridge.hookAllMethods(Dialog::class.java, "dismiss", dismiss)
    }
    private fun prepareWindow(instance: Any) {
        val content = when (instance) {
            is PopupWindow -> instance.contentView
            is Dialog -> instance.window?.peekDecorView()
            else -> null
        } ?: return
        val owner = activity(content.context) ?: return
        val chat = chats[owner] ?: return
        val wallpaper = chat.wallpaper ?: return
        var label = false
        var controls = false
        fun inspect(v: View) {
            if (v.visibility == View.GONE) return
            if (v is TextView && replyText(v.text?.toString()?.trim().orEmpty())) label = true
            if (v is android.widget.EditText || v.contentDescription?.toString() in setOf("关闭回复", "取消回复", "关闭")) controls = true
            if (v is ViewGroup) for (i in 0 until v.childCount) inspect(v.getChildAt(i))
        }
        inspect(content)
        if (!label || !controls) return
        val color = if (wallpaper) 0 else if (dark()) 0xff161616.toInt() else 0xffefefef.toInt()
        when (instance) {
            is PopupWindow -> {
                val raw = solid(instance.background)
                if ((raw in SURFACES || (wallpaper && raw != null)) && raw != color) {
                    if (popupApplied[instance] !== instance.background) popupOriginals[instance] = instance.background
                    val replacement = ColorDrawable(color)
                    instance.setBackgroundDrawable(replacement); popupApplied[instance] = replacement
                    log("reply popup background prepared before draw night=${dark()} wallpaper=$wallpaper")
                }
            }
            is Dialog -> {
                val decor = instance.window?.peekDecorView() ?: return
                val raw = solid(decor.background)
                if ((raw in SURFACES || (wallpaper && raw != null)) && raw != color) {
                    if (dialogApplied[instance] !== decor.background) dialogOriginals[instance] = decor.background
                    val replacement = ColorDrawable(color)
                    instance.window?.setBackgroundDrawable(replacement); dialogApplied[instance] = replacement
                    log("reply dialog background prepared before draw night=${dark()} wallpaper=$wallpaper")
                }
            }
        }
    }
    fun sync(owner: Activity, scope: ViewGroup?, wallpaper: Boolean?, layoutChanged: Boolean = false) {
        if (scope == null || !scope.isShown || !enabled()) {
            chats.remove(owner)
            states.keys.toList().filter { activity(it.context) === owner }.forEach(::releaseRoot)
            return
        }
        chats[owner] = Chat(WeakReference(scope), wallpaper)
        if (layoutChanged) states[scope]?.dirty = true
        repair(scope, scope, wallpaper)
    }
    private fun observe(root: ViewGroup, owner: Activity) {
        if (observed.put(root, true) != null) return
        val ownerRef = WeakReference(owner)
        val rootRef = WeakReference(root)
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { rootRef.get()?.let { states[it]?.dirty = true } }
        val listener = ViewTreeObserver.OnPreDrawListener {
            val current = rootRef.get()
            val chat = ownerRef.get()?.let { chats[it] }
            if (current != null && chat != null && enabled()) chat.root.get()?.let {
                repair(current, it, chat.wallpaper)
            }
            true
        }
        root.viewTreeObserver.addOnPreDrawListener(listener)
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        chats[owner]?.let { chat -> chat.root.get()?.let { repair(root, it, chat.wallpaper) } }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(listener)
                if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
                (v as? ViewGroup)?.let { releaseRoot(it); observed.remove(it) }
                v.removeOnAttachStateChangeListener(this)
            }
        })
    }
    private fun repair(root: ViewGroup, chatRoot: ViewGroup, wallpaper: Boolean?) {
        // Inline replies share the native page background, including its navigation area.
        // A decor overlay has a different lifetime/color transform and leaves a hard band.
        if (UnifiedOptions.immersive && root === chatRoot) {
            repairInline(root, wallpaper)
            return
        }
        val state = states.getOrPut(root) { State(WeakReference(root)) }
        if (wallpaper == null) { restore(state); return } // Never flatten an unknown wallpaper state.
        val now = android.os.SystemClock.uptimeMillis()
        val changed = state.night != dark() || state.wallpaper != wallpaper
        val scan = changed || (state.dirty && now-state.lastScan >= 100L) || now-state.lastScan >= 350L
        if (scan) {
            state.lastScan = now
            state.dirty = root.width == 0 || root.height == 0
            state.night = dark(); state.wallpaper = wallpaper
            var label: TextView? = null
            fun find(v: View) {
                if (!v.isShown || label != null) return
                if (v is TextView) {
                    val text = v.text?.toString()?.trim().orEmpty()
                    if (replyText(text)) label = v
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))
            }
            find(root)
            val anchor = label
            var candidate: ViewGroup? = null
            var p = anchor?.parent as? ViewGroup
            while (p != null && p !== root) {
                p.getLocationOnScreen(position)
                val below = root.height <= chatRoot.height / 2 || position[1] >= screenTop(chatRoot) + chatRoot.height / 2
                if (below && p.width >= chatRoot.width * 3 / 4 && p.height in 1..(chatRoot.height / 3) &&
                    hasReplyControls(p)) candidate = p
                p = p.parent as? ViewGroup
            }
            // A small popup root may itself be the reply panel.
            if (candidate == null && anchor != null && root !== chatRoot && root.width >= chatRoot.width * 3 / 4 &&
                root.height in 1..(chatRoot.height / 3) && hasReplyControls(root)) candidate = root
            // Do not restore a dark original while the quote label has disappeared but its
            // native closing animation/panel is still visible. Restore on actual hide/detach.
            if (candidate == null && anchor == null) candidate = state.panel.get()?.takeIf { it.isAttachedToWindow && it.isShown && hasReplyControls(it) }
            if (candidate !== state.panel.get()) {
                restore(state)
                state.panel = WeakReference(candidate)
                state.logged = false
            }
        }
        val panel = state.panel.get()
        if (panel == null || !panel.isShown) { restore(state); state.panel.clear(); return }
        val color = if (wallpaper) 0 else if (dark()) 0xff161616.toInt() else 0xffefefef.toInt()
        fun recolor(view: View) {
            if (!view.isShown) return
            // Patch only full-width flat neutral backplates, including thin seams. Preserve
            // bubble/thumbnail/icon colors, rounded controls, images, blur and multicolor gradients.
            if (view.width >= panel.width * 3 / 4) {
                val raw = solid(view.background)
                val roundedControl = view.background is GradientDrawable && view.height < root.height / 10 &&
                    android.os.Build.VERSION.SDK_INT >= 24 && (view.background as GradientDrawable).cornerRadius > 0
                // Preserve the native translucent wallpaper scrim (#bf2a2a2a at night),
                // then replay that same backdrop below the dock. Only remove opaque flat fills.
                if (!roundedControl && raw != null && (!wallpaper || raw ushr 24 == 255) &&
                    (raw in SURFACES || wallpaper) && raw != color) {
                    if (!state.applied.containsKey(view) || state.applied[view] !== view.background)
                        state.originals[view] = view.background
                    val replacement = view.background?.constantState?.newDrawable(view.resources)?.mutate()
                    when (replacement) {
                        is ColorDrawable -> replacement.color = color
                        is GradientDrawable -> replacement.setColor(color)
                    }
                    if (replacement != null) { view.background = replacement; state.applied[view] = replacement }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) recolor(view.getChildAt(i))
        }
        if (scan) recolor(panel)
        val extension = state.extension ?: Extension().also { state.extension = it; root.overlay.add(it) }
        val imageId = chatRoot.resources.getIdentifier("nyy", "id", "com.ss.android.ugc.aweme")
        val dockId = chatRoot.resources.getIdentifier("mxb", "id", "com.ss.android.ugc.aweme")
        val dock = if (wallpaper && dockId != 0) chatRoot.findViewById<View>(dockId) else null
        extension.update(root, panel, color, if (wallpaper && imageId != 0) chatRoot.findViewById<ImageView>(imageId) else null, dock)
        if (!state.logged) {
            state.logged = true
            log("reply surface bound class=${panel.javaClass.name} popup=${root !== chatRoot} night=${dark()} " +
                "wallpaper=$wallpaper color=${Integer.toHexString(color)}; nav background only, dock/insets unchanged")
        }
    }
    private fun restore(state: State) {
        for ((view, drawable) in state.applied) if (view.background === drawable &&
            (state.appliedColors[view] == null || solid(drawable) == state.appliedColors[view])) view.background = state.originals[view]
        state.applied.clear(); state.originals.clear()
        state.appliedColors.clear()
        state.extension?.let { state.root.get()?.overlay?.remove(it) }
        state.extension = null
    }
    private fun repairInline(root: ViewGroup, wallpaper: Boolean?) {
        val state = states.getOrPut(root) { State(WeakReference(root)) }
        if (wallpaper == null) { restore(state); return }
        // Resolve the live dock in this chat scope. Numeric IDs and quote-slot
        // names changed between 40.7 and 40.8; the hint/close relationship did not.
        fun named(scope: View, name: String): View? {
            val id = scope.resources.getIdentifier(name, "id", "com.ss.android.ugc.aweme")
            return if (id == 0) null else scope.findViewById(id)
        }
        val dock = (named(root, "jak") as? ViewGroup)?.takeIf { it.isShown }
            ?: (named(root, "msg_et")?.let { field ->
                var fallbackAncestor = field.parent as? ViewGroup
                while (fallbackAncestor != null && fallbackAncestor !== root) {
                    if (fallbackAncestor.width >= root.width * 3 / 4 &&
                        named(fallbackAncestor, "iv_close")?.isShown == true &&
                        named(fallbackAncestor, "tv_hint")?.isShown == true) return@let fallbackAncestor
                    fallbackAncestor = fallbackAncestor.parent as? ViewGroup
                }
                null
            })
        val hint = dock?.let { named(it, "tv_hint") }
        val close = dock?.let { named(it, "iv_close") }
        var quote: ViewGroup? = null
        if (dock != null && hint?.isShown == true && close?.isShown == true) {
            var candidate = hint.parent as? ViewGroup
            while (candidate != null && candidate !== dock) {
                if (candidate.width >= dock.width * 3 / 4 && candidate.height > 0 &&
                    named(candidate, "iv_close") === close) {
                    quote = candidate
                    break
                }
                candidate = candidate.parent as? ViewGroup
            }
        }
        val active = dock?.isShown == true && quote?.isShown == true
        if (!active || dock == null || quote == null) {
            restore(state); state.panel.clear(); state.logged = false; return
        }
        var base = if (dark()) 0xff161616.toInt() else 0xffefefef.toInt()
        var parent = dock.parent as? View
        while (parent != null && parent !== root.parent) {
            val raw = solid(parent.background)
            if (parent.width >= root.width*3/4 && parent.height >= root.height/2 && raw in SURFACES) {
                base = raw!!; break
            }
            parent = parent.parent as? View
        }
        val color = if (wallpaper) 0 else base
        val changed = state.night != dark() || state.wallpaper != wallpaper || state.panel.get() !== quote
        val now = android.os.SystemClock.uptimeMillis()
        if (!changed && !state.dirty && now-state.lastScan < 100L) return
        state.lastScan = now; state.dirty = false; state.night = dark(); state.wallpaper = wallpaper
        state.panel = WeakReference(quote)
        fun paint(v: View, value: Int) {
            if (solid(v.background) == value) return
            if (state.applied[v] !== v.background) state.originals[v] = v.background
            val replacement = v.background?.constantState?.newDrawable(v.resources)?.mutate()
            when (replacement) {
                is ColorDrawable -> replacement.color = value
                is GradientDrawable -> replacement.setColor(value)
                else -> return
            }
            state.applied[v] = replacement; state.appliedColors[v] = value; v.background = replacement
        }
        // Keep the native input shape; locate its rounded backing from the
        // editor ancestry instead of a version-specific container name.
        var input: View? = null
        var inputParent = named(dock, "msg_et")?.parent as? View
        while (inputParent != null && inputParent !== dock) {
            val drawable = inputParent.background as? GradientDrawable
            if (drawable != null && inputParent.width >= dock.width * 3 / 4 &&
                android.os.Build.VERSION.SDK_INT >= 24 &&
                (drawable.cornerRadius > 0f || drawable.cornerRadii != null)) {
                input = inputParent
                break
            }
            inputParent = inputParent.parent as? View
        }
        fun visit(v: View) {
            if (!v.isShown) return
            if (v === input) {
                // Preserve corner radii and the wallpaper control's native translucency.
                if (!wallpaper && v.background is GradientDrawable && solid(v.background) != null)
                    paint(v, if (dark()) 0xff393939.toInt() else 0xffffffff.toInt())
                return
            }
            val bg = v.background
            val flat = bg is ColorDrawable || bg is GradientDrawable && android.os.Build.VERSION.SDK_INT >= 24 &&
                bg.colors == null && bg.cornerRadius == 0f && bg.cornerRadii == null
            val raw = solid(bg)
            if (v.width >= dock.width*3/4 && flat && raw != null && (wallpaper || raw in SURFACES)) paint(v, color)
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(dock)
        // No navigation extension: the original page/wallpaper already reaches the screen edge.
        state.extension?.let { root.overlay.remove(it) }; state.extension = null
        if (!state.logged || changed) {
            state.logged = true
            log("inline reply native slot night=${dark()} wallpaper=$wallpaper base=${Integer.toHexString(base)}; flat surfaces unified, rounded input retained; navigation overlay absent")
        }
    }
    private fun releaseRoot(root: ViewGroup) { states.remove(root)?.let(::restore) }
    fun release(owner: Activity) {
        chats.remove(owner)
        states.keys.toList().filter { activity(it.context) === owner }.forEach(::releaseRoot)
    }
    private fun screenTop(v: View): Int { v.getLocationOnScreen(position); return position[1] }
    private fun replyText(text: String) = text.length in 2..80 && (text == "回复" ||
        text.startsWith("回复 ") || text.startsWith("回复：") || text.startsWith("回复:"))
    private fun hasReplyControls(group: ViewGroup): Boolean {
        for (name in listOf("jak", "mxb", "msg_et", "iv_close")) {
            val id = group.resources.getIdentifier(name, "id", "com.ss.android.ugc.aweme")
            if (id != 0 && group.findViewById<View>(id)?.isShown == true) return true
        }
        fun visit(view: View): Boolean {
            if (!view.isShown) return false
            if (view is android.widget.EditText || view.contentDescription?.toString() in
                setOf("关闭", "取消回复", "关闭回复")) return true
            if (view is ViewGroup) for (i in 0 until view.childCount) if (visit(view.getChildAt(i))) return true
            return false
        }
        return visit(group)
    }
    private fun activity(context: Context): Activity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            val next = c.baseContext
            if (next === c) break
            c = next
        }
        return null
    }
    private fun solid(d: Drawable?): Int? = when (d) {
        is ColorDrawable -> d.color
        is GradientDrawable -> if (android.os.Build.VERSION.SDK_INT >= 24 && d.colors == null) d.color?.defaultColor else null
        else -> null
    }
    private class Extension : Drawable() {
        private val paint = android.graphics.Paint()
        private val pos = IntArray(2)
        private val origin = IntArray(2)
        private var wallpaper = WeakReference<ImageView>(null)
        private var root = WeakReference<ViewGroup>(null)
        private var backdrop = WeakReference<Drawable>(null)
        private var backdropCopy: Drawable? = null
        private var backdropAlpha = 255
        fun update(r: ViewGroup, panel: ViewGroup, color: Int, image: ImageView?, dock: View?) {
            var changed = root.get() !== r || wallpaper.get() !== image || paint.color != color || image?.isDirty == true
            root = WeakReference(r); wallpaper = WeakReference(image); paint.color = color
            val native = dock?.takeIf { it.isShown }?.background
            val opacity = ((dock?.alpha ?: 1f) * 255).toInt().coerceIn(0, 255)
            if (backdrop.get() !== native || backdropAlpha != opacity) {
                backdrop = WeakReference(native)
                backdropCopy = native?.constantState?.newDrawable(r.resources)?.mutate()
                backdropCopy?.state = dock?.drawableState ?: intArrayOf()
                backdropAlpha = opacity
                changed = true
            }
            val insets = r.rootWindowInsets
            val nav = if (android.os.Build.VERSION.SDK_INT >= 30)
                insets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
            else insets?.systemWindowInsetBottom ?: 0
            r.getLocationOnScreen(origin); panel.getLocationOnScreen(pos)
            val panelBottom = pos[1] + panel.height - origin[1]
            // Draw only below the panel, within the actual nav inset. No controls are covered.
            val top = maxOf(panelBottom, r.height - nav).coerceIn(0, r.height)
            val left = pos[0] - origin[0]
            val next = Rect(left.coerceAtLeast(0), top, (left + panel.width).coerceAtMost(r.width), r.height)
            if (bounds != next) { bounds = next; changed = true }
            if (changed) invalidateSelf()
        }
        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            val save = canvas.save()
            canvas.clipRect(bounds)
            val image = wallpaper.get()
            val r = root.get()
            if (image != null && r != null && image.isShown) {
                image.getLocationOnScreen(pos); r.getLocationOnScreen(origin)
                canvas.translate((pos[0] - origin[0]).toFloat(), (pos[1] - origin[1]).toFloat())
                image.draw(canvas) // Same native wallpaper, with its existing drawable bounds/fade.
                canvas.translate((origin[0] - pos[0]).toFloat(), (origin[1] - pos[1]).toFloat())
                // Background only: no inputs/text/icons copied into the navigation inset.
                backdropCopy?.let { d ->
                    val layer = canvas.saveLayerAlpha(bounds.left.toFloat(), bounds.top.toFloat(),
                        bounds.right.toFloat(), bounds.bottom.toFloat(), backdropAlpha)
                    d.bounds = bounds
                    d.draw(canvas)
                    canvas.restoreToCount(layer)
                }
            } else if (paint.color ushr 24 != 0) canvas.drawRect(bounds, paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
    companion object {
        private val SURFACES = setOf(0xff141414.toInt(), 0xff161823.toInt(), 0xff0f151f.toInt(), 0xff171925.toInt(),
            0xff191826.toInt(), 0xff171924.toInt(), 0xff161616.toInt(), 0xffefefef.toInt(), 0xffffffff.toInt())
    }
}
