package cn.zhe.douyinsystemtheme

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Scoped background and contrast corrections; never recolor arbitrary text or pictures. */
internal class ThemeViewRepairs(
    private val loader: ClassLoader,
    private val main: Handler,
    private val isDark: () -> Boolean,
    private val isMessageTab: () -> Boolean,
    private val enabled: () -> Boolean,
    private val syncPreviews: (ViewGroup, Boolean, Boolean) -> Unit,
    private val log: (String) -> Unit,
    private val onFocusReturned: (Activity) -> Unit = {}
) {
    private val watches = WeakHashMap<Activity, Watch>()
    private val textColors = WeakHashMap<TextView, ColorStateList>()
    private val iconColors = WeakHashMap<ImageView, IconColor>()
    private val backgroundColors = WeakHashMap<View, Int>()
    private val seen = HashSet<String>()
    private val replyFix = ChatReplyThemeFix(enabled, isDark, log)
    private val chatFragmentType by lazy { Class.forName("com.ss.android.ugc.aweme.im.business.chat.root.RipsChatRoomFragment", false, loader) }
    private val forwardingType by lazy { Class.forName("com.facebook.drawee.drawable.ForwardingDrawable", false, loader) }
    private val forwardingChild by lazy { forwardingType.getDeclaredField("a").apply { isAccessible = true } }
    private val fadeType by lazy { Class.forName("com.facebook.drawee.drawable.FadeDrawable", false, loader) }
    private val fadeLayers by lazy { fadeType.superclass.getDeclaredField("d").apply { isAccessible = true } }
    private val fadeAlphas by lazy { fadeType.getDeclaredField("p").apply { isAccessible = true } }
    private val labels = setOf("鎴戠殑璁㈠崟", "瑙傜湅鍘嗗彶", "鎴戠殑閽卞寘", "绋嶅悗鍐嶇湅", "鍏ㄩ儴鍔熻兘", "缂栬緫涓婚〉")

    fun watch(activity: Activity) {
        if (watches.containsKey(activity)) { request(activity); return }
        val root = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
        val watch = Watch(activity, root)
        watch.scrollFix = ChatScrollControlFix(isDark, log)
        watch.focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (!focused) watch.covered = true
            else if (watch.covered) {
                watch.covered = false
                watch.activity.get()?.let { owner -> if (enabled()) onFocusReturned(owner) }
            }
        }
        watch.listener = ViewTreeObserver.OnGlobalLayoutListener { watch.dirty = true }
        watch.preDraw = ViewTreeObserver.OnPreDrawListener {
            val owner = watch.activity.get()
            val content = watch.root.get()
            if (owner != null && content != null && !owner.isDestroyed) {
                try {
                    val now = android.os.SystemClock.uptimeMillis()
                    val night = isDark()
                    val modeChanged = watch.lastNight != night
                    if (modeChanged) { watch.lastNight = night; watch.dirty = true }
                    val geometry = UnifiedChatBridge.get(content)
                    val nativeRoot = NativeChatRegistry.visible(content)
                    if (nativeRoot != null && watch.chatRoot.get() !== nativeRoot) armChatEntry(watch, nativeRoot)
                    val dock = geometry?.dock?.get() ?: watch.chatDock.get()?.takeIf { it.isAttachedToWindow && it.isShown }
                        ?: if (watch.dirty || now - watch.lastDockSearch >= 250L) {
                            watch.lastDockSearch = now; visibleChatInput(content)
                        } else null
                    val chat = nativeRoot != null
                    if (nativeRoot != null && enabled()) {
                        val wallpaper = NativeChatRegistry.wallpaper(nativeRoot)
                        if (watch.customWallpaper != wallpaper) { watch.customWallpaper = wallpaper; watch.dirty = true }
                    }
                    if (watch.chat != chat) { watch.chat = chat; watch.dirty = true }
                    if (dock != null && enabled() && (watch.dirty || watch.chatDock.get() !== dock || watch.customWallpaper == null)) {
                        val wallpaper = nativeRoot?.let(NativeChatRegistry::wallpaper) ?: chatWallpaper(dock, watch)
                        watch.chatDock = WeakReference(dock)
                        if (watch.customWallpaper != wallpaper) { watch.customWallpaper = wallpaper; watch.dirty = true }
                    }
                    val message = !chat && owner.javaClass.name == "com.ss.android.ugc.aweme.main.MainActivity" && isMessageTab()
                    watch.exitingWallpaper = !chat && outgoingChatVisible(content, watch)
                    if ((chat || watch.exitingWallpaper) && enabled()) syncChatStatusBar(owner, watch)
                    if (watch.message != message) { watch.message = message; watch.dirty = true }
                    val layoutChanged = watch.dirty
                    if (watch.dirty && (modeChanged || watch.entryPending || now - watch.lastStructureRepair >= 120L)) {
                        watch.dirty = false
                        watch.lastStructureRepair = now
                        repair(owner, content)
                        collectMessageSurfaces(content, watch)
                        collectChatSurfaces(content, watch)
                    }
                    syncMessageSurfaces(content, watch)
                    restoreMessageStatusBar(owner, watch)
                    syncChatSurfaces(watch)
                    replyFix.sync(owner, if (chat) watch.chatRoot.get() else null, watch.customWallpaper, layoutChanged)
                    if (chat && enabled() && UnifiedOptions.immersive)
                        watch.bottomSurface.sync(content, geometry, watch.customWallpaper, night, layoutChanged, nativeRoot)
                    else watch.bottomSurface.release()
                    watch.scrollFix.sync(if (chat && enabled()) nativeRoot else null)
                    syncPreviews(content, layoutChanged, watch.message)
                    if (ENTRY_GATE_ENABLED && chat && enabled() && !chatEntryReady(watch)) return@OnPreDrawListener false
                } catch (failure: Throwable) {
                    once("view-error-${owner.javaClass.name}", "FAILED before-draw repair ${owner.javaClass.name}: $failure")
                }
            }
            true
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(watch.listener)
        root.viewTreeObserver.addOnPreDrawListener(watch.preDraw)
        root.viewTreeObserver.addOnWindowFocusChangeListener(watch.focusListener)
        watches[activity] = watch
    }

    fun request(activity: Activity) { watches[activity]?.dirty = true }

    fun refreshDrawState(activity: Activity) {
        val watch = watches[activity] ?: return
        val root = watch.root.get() ?: return
        watch.dirty = true
        fun invalidate(view: View) {
            if (!view.isShown) return
            view.invalidate()
            if (view is ViewGroup) for (i in 0 until view.childCount) invalidate(view.getChildAt(i))
        }
        // One pass after focus/configuration recovery, never a permanent redraw loop.
        root.postOnAnimation {
            if (activity.isDestroyed || !root.isAttachedToWindow) return@postOnAnimation
            invalidate(root)
            syncPreviews(root, true, watch.message)
            log("focus/config redraw activity=${activity.javaClass.name} message=${watch.message}; visible display lists invalidated once")
        }
    }

    private fun outgoingChatVisible(content: ViewGroup, watch: Watch): Boolean {
        val chat = watch.chatRoot.get() ?: return false
        if (!chat.isAttachedToWindow || !chat.isShown || chat.alpha <= 0.01f) return false
        val visible = android.graphics.Rect()
        val viewport = android.graphics.Rect()
        if (!chat.getGlobalVisibleRect(visible) || !content.getGlobalVisibleRect(viewport) || !visible.intersect(viewport)) return false
        // Retain the outgoing header only while native View animation/transient-state evidence
        // exists, or while its actual position/alpha is moving off screen. No timer gate.
        var v: View? = chat
        while (v != null && v !== content) {
            if (v.animation?.let { !it.hasEnded() } == true || v.hasTransientState() ||
                v.alpha < 0.99f || kotlin.math.abs(v.translationX) > 1f) return true
            v = v.parent as? View
        }
        return visible.left > viewport.left + 1 || visible.right < viewport.right - 1
    }

    fun installChatEntryHook() {
        replyFix.install()
        NativeChatRegistry.changed.add { root ->
            watches.values.firstOrNull { it.chatRoot.get() === root }?.let { watch ->
                if (enabled()) watch.scrollFix.sync(root, true)
                watch.dirty = true
                root.postInvalidateOnAnimation()
            }
        }
        NativeChatRegistry.destroyed.add { root ->
            watches.values.filter { it.chatRoot.get() === root }.forEach { watch ->
                watch.bottomSurface.release()
                watch.statusBackdrop.release()
                watch.scrollFix.release()
                watch.chatDock.clear()
                watch.dirty = true
                watch.root.get()?.let { content ->
                    // Rebuild retained display lists once after removing chat-owned layers.
                    content.postOnAnimation {
                        fun redraw(view: View) {
                            if (!view.isShown) return
                            view.invalidate()
                            if (view is ViewGroup) for (i in 0 until view.childCount) redraw(view.getChildAt(i))
                        }
                        redraw(content)
                    }
                }
            }
        }
        de.robv.android.xposed.XposedHelpers.findAndHookMethod(chatFragmentType, "onViewCreated", View::class.java,
            android.os.Bundle::class.java, object : de.robv.android.xposed.XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable() || !enabled()) return
                    val owner = de.robv.android.xposed.XposedHelpers.callMethod(param.thisObject, "getActivity") as? Activity ?: return
                    val watch = watches[owner] ?: return // New Activities are armed at their first preDraw.
                    val root = param.args[0] as? ViewGroup ?: return
                    armChatEntry(watch, root)
                }
            })
    }

    private fun armChatEntry(watch: Watch, root: ViewGroup) {
        if (watch.chatRoot.get() !== root) {
            releaseWallpaperBackdrop(watch)
            watch.chatSurfaces.clear()
            watch.customWallpaper = null
            watch.chatDock.clear()
            watch.chatSession.clear()
        }
        watch.chatRoot = WeakReference(root)
        watch.entryDeadline = android.os.SystemClock.uptimeMillis() + 100L
        watch.entryPending = true
        watch.entryFrames = 0
        watch.wallpaperCompleteDrawable.clear()
        watch.dirty = true
    }

    private fun chatEntryReady(watch: Watch): Boolean {
        val root = watch.chatRoot.get() ?: return true
        val expectedNight = if (isDark()) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
        val configured = root.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == expectedNight
        val id = root.resources.getIdentifier("nyy", "id", "com.ss.android.ugc.aweme")
        val image = if (id != 0) root.findViewById<ImageView>(id) else null
        val drawable = image?.drawable
        if (!watch.entryPending && drawable != null && watch.wallpaperCompleteDrawable.get() === drawable) return true
        // Put the loading color BELOW the image, never in an overlay over the wallpaper.
        // This also lets Fresco's draw-driven fade advance after the short entry gate.
        if (watch.customWallpaper == true && image != null) {
            if (watch.wallpaperImage.get() !== image) {
                releaseWallpaperBackdrop(watch)
                watch.wallpaperImage = WeakReference(image)
                watch.originalWallpaperBackground = image.background
                watch.wallpaperBackdrop = ColorDrawable()
            }
            val backdrop = watch.wallpaperBackdrop
            if (backdrop != null) {
                val color = if (isDark()) 0xff161616.toInt() else 0xffefefef.toInt()
                if (backdrop.color != color) backdrop.color = color
                if (image.background !== backdrop) image.background = backdrop
            }
        }
        val state = inspectWallpaper(drawable)
        val decoded = state.first
        if (state.second || watch.customWallpaper == false) {
            releaseWallpaperBackdrop(watch)
            if (state.second) watch.wallpaperCompleteDrawable = WeakReference(drawable)
        }
        if (!watch.entryPending) return true
        val wallpaperReady = watch.customWallpaper == false ||
            (watch.customWallpaper == true && image?.isShown == true && image.imageAlpha > 0 && decoded)
        val ready = configured && wallpaperReady
        val expired = watch.entryFrames >= 3 || android.os.SystemClock.uptimeMillis() >= watch.entryDeadline
        if (ready || expired) {
            watch.entryPending = false
            log("chat entry gate ${if (ready) "ready" else "BUDGET"} frames=${watch.entryFrames} night=$expectedNight configured=$configured wallpaper=${watch.customWallpaper} nyyDrawable=${drawable?.javaClass?.name} decoded=$decoded fullyVisible=${state.second}; max=3 frames/100ms; loading backdrop below wallpaper")
            return true
        }
        watch.entryFrames++
        root.postInvalidateOnAnimation()
        return false
    }

    /** 40.7.0: X.1TnO -> ForwardingDrawable.a -> FadeDrawable actual-image slot 2.
     * Intrinsic size of the outer drawable is not an image-completion signal. */
    private fun inspectWallpaper(drawable: android.graphics.drawable.Drawable?): Pair<Boolean, Boolean> {
        fun inspect(value: android.graphics.drawable.Drawable?, depth: Int): Pair<Boolean, Boolean> {
            if (value == null || depth > 8) return false to false
            if (value is BitmapDrawable) {
                val decoded = !value.bitmap.isRecycled && value.bitmap.width > 32 && value.bitmap.height > 32
                return decoded to (decoded && value.alpha >= 254)
            }
            if (forwardingType.isInstance(value)) {
                return inspect(forwardingChild.get(value) as? android.graphics.drawable.Drawable, depth + 1)
            }
            if (fadeType.isInstance(value)) {
                val layers = fadeLayers.get(value) as? Array<*> ?: return false to false
                val alphas = fadeAlphas.get(value) as? IntArray ?: return false to false
                val actual = inspect(layers.getOrNull(2) as? android.graphics.drawable.Drawable, depth + 1)
                return actual.first to (actual.second && (alphas.getOrNull(2) ?: 0) >= 254)
            }
            return false to false // Unknown wrappers do not extend the gate beyond its frame budget.
        }
        return try { inspect(drawable, 0) } catch (failure: Throwable) {
            once("wallpaper-inspect-${failure.javaClass.name}", "FAILED wallpaper delegate inspection: $failure; short gate budget retained")
            false to false
        }
    }

    private fun releaseWallpaperBackdrop(watch: Watch) {
        val image = watch.wallpaperImage.get()
        val backdrop = watch.wallpaperBackdrop
        if (image != null && backdrop != null && image.background === backdrop) image.background = watch.originalWallpaperBackground
        watch.wallpaperImage.clear()
        watch.originalWallpaperBackground = null
        watch.wallpaperBackdrop = null
    }

    private fun visibleChatInput(root: ViewGroup): View? {
        // Voice mode removes/hides msg_et while the chat dock remains visible.
        val ids = listOf("jak", "mxb", "msg_et").map {
            root.resources.getIdentifier(it, "id", "com.ss.android.ugc.aweme")
        }.filter { it != 0 }.toSet()
        if (ids.isEmpty()) return null
        fun find(view: View): View? {
            if (!view.isShown) return null
            if (view.id in ids && view.width > 0 && view.height > 0) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(root)
    }

    private fun chatWallpaper(dock: View, watch: Watch): Boolean? {
        try {
            var session = watch.chatSession.get()
            if (watch.chatDock.get() !== dock || session == null) {
                val manager = Class.forName("androidx.fragment.app.FragmentManager", false, loader)
                var fragment = manager.getMethod("findFragment", View::class.java).invoke(null, dock)
                while (fragment != null && !chatFragmentType.isInstance(fragment)) {
                    fragment = de.robv.android.xposed.XposedHelpers.callMethod(fragment, "getParentFragment")
                }
                if (fragment == null) {
                    once("wallpaper-no-owner", "chat wallpaper unknown: dock has no Rips chat owner; keep status bar transparent, skip solid chat correction")
                    return null
                }
                session = de.robv.android.xposed.XposedHelpers.callMethod(fragment, "getSessionInfo") ?: return null
                watch.chatDock = WeakReference(dock)
                watch.chatSession = WeakReference(session)
                val chatRoot = de.robv.android.xposed.XposedHelpers.callMethod(fragment, "getView") as? ViewGroup
                if (chatRoot != null && watch.chatRoot.get() !== chatRoot) armChatEntry(watch, chatRoot)
            }
            // 40.7.0: getter LJIJJLI() reads this exact boolean field. Read the
            // live model so changing wallpaper inside an open chat also takes effect.
            return de.robv.android.xposed.XposedHelpers.getBooleanField(session, "chatWallpaperSet")
        } catch (failure: Throwable) {
            once("wallpaper-error-${failure.javaClass.name}", "FAILED chat wallpaper lookup: $failure; transparent fallback, no solid chat correction")
            return null
        }
    }

    private fun syncChatStatusBar(activity: Activity, watch: Watch) {
        watch.chatStatusModified = true
        val light = !isDark()
        val immersive = watch.customWallpaper != false
        val headerColor = if (light) 0xffefefef.toInt() else 0xff161616.toInt()
        // A single Window statusBarColor cannot follow a half-swiped chat Fragment.
        // Keep the window transparent and draw its header segment in decor coordinates.
        val wantedColor = android.graphics.Color.TRANSPARENT
        val decor = activity.window.decorView as? ViewGroup
        val chatRoot = watch.chatRoot.get()
        if (!immersive && decor != null && chatRoot != null) {
            watch.statusBackdrop.update(chatRoot, headerColor)
        } else watch.statusBackdrop.release()
        // Icon appearance and the status-bar backplate are independent properties.
        // BGPrimary's night #161823 must not survive over a #161616 chat header.
        if (activity.window.statusBarColor != wantedColor) activity.window.statusBarColor = wantedColor
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (watch.originalStatusContrast == null) watch.originalStatusContrast = activity.window.isStatusBarContrastEnforced
            if (activity.window.isStatusBarContrastEnforced) activity.window.isStatusBarContrastEnforced = false
        }
        val statusId = activity.resources.getIdentifier("statusBarBackground", "id", "android")
        if (statusId != 0) activity.window.decorView.findViewById<View>(statusId)?.let { bar ->
            if (solidColor(bar) != null && solidColor(bar) != wantedColor) setSolidColor(bar, wantedColor)
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            activity.window.insetsController?.let { controller ->
                val wanted = if (light) mask else 0
                if (controller.systemBarsAppearance and mask != wanted) controller.setSystemBarsAppearance(wanted, mask)
            }
        } else {
            val decor = activity.window.decorView
            val mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            val wanted = if (light) decor.systemUiVisibility or mask else decor.systemUiVisibility and mask.inv()
            if (decor.systemUiVisibility != wanted) decor.systemUiVisibility = wanted
        }
        once("chat-native-palette-$light-${watch.customWallpaper}", "chat status bar wallpaper=${watch.customWallpaper} transparent=$immersive lightStatusBar=$light statusBarColor=${Integer.toHexString(wantedColor)}; wallpaper surfaces excluded from solid correction")
    }

    private fun restoreMessageStatusBar(activity: Activity, watch: Watch) {
        if (watch.chat || watch.exitingWallpaper || !watch.chatStatusModified) return
        watch.statusBackdrop.release()
        if (android.os.Build.VERSION.SDK_INT >= 29) watch.originalStatusContrast?.let {
            activity.window.isStatusBarContrastEnforced = it
            watch.originalStatusContrast = null
        }
        if (watch.message && enabled()) {
            val wanted = android.graphics.Color.TRANSPARENT
            if (activity.window.statusBarColor != wanted) activity.window.statusBarColor = wanted
            val id = activity.resources.getIdentifier("statusBarBackground", "id", "android")
            if (id != 0) activity.window.decorView.findViewById<View>(id)?.let {
                if (solidColor(it) != null && solidColor(it) != wanted) setSolidColor(it, wanted)
            }
            log("chat exit: message status bar restored to ${Integer.toHexString(wanted)}; chat surface cache released")
        }
        watch.chatStatusModified = false
        releaseWallpaperBackdrop(watch)
        watch.chatSurfaces.clear()
        watch.chatRoot.clear()
        watch.chatDock.clear()
        watch.chatSession.clear()
        watch.entryPending = false
        watch.wallpaperCompleteDrawable.clear()
    }

    private fun collectChatSurfaces(root: ViewGroup, watch: Watch) {
        if (!watch.chat || watch.customWallpaper != false) return
        // Only the Fragment owns these surfaces. Never include its Activity/content
        // ancestors, which are reused by the message list after the chat is removed.
        val scope = watch.chatRoot.get() ?: return
        val known = watch.chatSurfaces.mapNotNull { it.get() }.toHashSet()
        val position = IntArray(2)
        scope.getLocationOnScreen(position)
        val top = position[1]
        fun visit(view: View) {
            if (!view.isShown) return
            view.getLocationOnScreen(position)
            val header = view.height > 0 && position[1] >= top && position[1] + view.height <= top + scope.height / 4
            if (view.width >= scope.width * 3 / 4 && (view.height >= scope.height / 3 || header) &&
                solidColor(view) in CHAT_SURFACE_COLORS && known.add(view)) watch.chatSurfaces.add(WeakReference(view))
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(scope)
    }

    private fun syncChatSurfaces(watch: Watch) {
        if (!enabled() || !watch.chat || watch.customWallpaper != false) return
        val wanted = if (isDark()) 0xff161616.toInt() else 0xffefefef.toInt()
        val iterator = watch.chatSurfaces.iterator()
        while (iterator.hasNext()) {
            val view = iterator.next().get()
            if (view == null || !view.isAttachedToWindow) { iterator.remove(); continue }
            if (!view.isShown) continue
            val raw = solidColor(view) ?: continue
            if (raw in CHAT_SURFACE_COLORS && raw != wanted) {
                setSolidColor(view, wanted)
                once("chat-surface-${view.id}-$raw-$wanted", "chat reference surface id=${view.id} class=${view.javaClass.name} raw=${Integer.toHexString(raw)} after=${Integer.toHexString(wanted)}; cold-start palette")
            }
        }
    }
    fun release(activity: Activity) {
        replyFix.release(activity)
        val watch = watches.remove(activity) ?: return
        watch.scrollFix.release()
        watch.statusBackdrop.release()
        watch.bottomSurface.release()
        releaseWallpaperBackdrop(watch)
        watch.root.get()?.viewTreeObserver?.takeIf { it.isAlive }?.let {
            it.removeOnGlobalLayoutListener(watch.listener)
            it.removeOnPreDrawListener(watch.preDraw)
            it.removeOnWindowFocusChangeListener(watch.focusListener)
        }
        for (reference in watch.backplates) reference.get()?.let { view ->
            val original = backgroundColors.remove(view)
            if (original != null && solidColor(view) == watch.palette) setSolidColor(view, original)
        }
    }

    private fun collectMessageSurfaces(root: ViewGroup, watch: Watch) {
        // Retain previously patched surfaces so leaving the tab can restore them.
        if (!watch.message) return
        val added = watch.backplates.mapNotNull { it.get() }.toHashSet()
        root.getLocationOnScreen(watch.position)
        val headerLimit = watch.position[1] + root.height / 4
        fun visit(view: View) {
            if (!view.isShown) return
            if (view === watch.chatRoot.get()) return // Outgoing chat is still its own surface.
            view.getLocationOnScreen(watch.position)
            val header = watch.position[1] < headerLimit && view.height > 0 &&
                watch.position[1] + view.height <= headerLimit
            // Include full-width neutral header backplates, alongside the verified list surfaces.
            // Small list items and bottom bars are still excluded.
            if (view.width >= root.width * 3 / 4 && (view.height >= root.height / 3 || header) &&
                solidColor(view) in CHAT_SURFACE_COLORS && added.add(view)) {
                watch.backplates.add(WeakReference(view))
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
    }

    private fun syncMessageSurfaces(root: ViewGroup, watch: Watch) {
        val apply = enabled() && watch.message
        val night = isDark()
        if (apply && (watch.paletteNight != night || watch.palette == null)) {
            // Verified native resource pair: default #fff -> night #161616.
            // It matches the supplied cold-start baseline; BGPrimary does not.
            val config = android.content.res.Configuration(root.resources.configuration)
            config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            watch.palette = root.context.createConfigurationContext(config).resources.getColor(0x7f060004, null)
            watch.paletteNight = night
            once("message-palette-$night", "message reference palette resource=0x7f060004 night=$night color=${Integer.toHexString(watch.palette!!)}; bottom bar unchanged")
        }
        val iterator = watch.backplates.iterator()
        while (iterator.hasNext()) {
            val view = iterator.next().get()
            if (view == null || !view.isAttachedToWindow) { iterator.remove(); continue }
            var ancestor: View? = view
            var belongsToChat = false
            while (ancestor != null) {
                if (ancestor === watch.chatRoot.get()) { belongsToChat = true; break }
                ancestor = ancestor.parent as? View
            }
            if (belongsToChat) continue
            val color = solidColor(view) ?: continue
            if (apply && color in CHAT_SURFACE_COLORS) {
                val wanted = watch.palette ?: continue
                if (color != wanted) {
                    backgroundColors.putIfAbsent(view, color)
                    setSolidColor(view, wanted)
                    once("message-surface-${view.id}-$wanted", "message surface before-draw id=${view.id} class=${view.javaClass.name} color=${Integer.toHexString(wanted)}")
                }
            } else if (!apply) {
                val original = backgroundColors.remove(view)
                // A cached original from the previous NIGHT mode is not a valid rollback
                // for an active chat. Its scoped current-theme palette is applied below.
                if (!watch.chat && original != null && color == watch.palette) setSolidColor(view, original)
            }
        }
    }

    private fun solidColor(view: View): Int? = when (val background = view.background) {
        is ColorDrawable -> background.color
        is GradientDrawable -> if (android.os.Build.VERSION.SDK_INT >= 24) background.color?.defaultColor else null
        else -> null // Never flatten images, blur layers, or multicolor gradients.
    }

    private fun setSolidColor(view: View, color: Int) {
        val background = view.background ?: return
        val isolated = background.constantState?.newDrawable(view.resources)?.mutate() ?: return
        when (isolated) {
            is ColorDrawable -> isolated.color = color
            is GradientDrawable -> isolated.setColor(color)
            else -> return
        }
        view.background = isolated
    }

    private fun repair(activity: Activity, root: ViewGroup) {
        if (!enabled()) return
        val dark = isDark()
        if (activity.javaClass.simpleName == "FlowPageActivity") repairRetainedPage(activity, root, dark)
        val foundLabels = ArrayList<TextView>(6)
        fun visit(view: View) {
            if (!view.isShown) return
            if (view is TextView && view.text.toString() in labels) foundLabels.add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        // Keep native IM surfaces and message bubble colors entirely untouched.
        for (label in foundLabels) {
            if (!dark) {
                textColors.remove(label)
                label.setTextColor(IM_BLUE)
            } else if (luma(label.currentTextColor) < 100) {
                textColors.putIfAbsent(label, label.textColors)
                once("profile-text-${label.text}", "profile entry text=${label.text} id=${label.id} " +
                    "rawColor=${Integer.toHexString(label.currentTextColor)} alpha=${label.alpha}; set=#e6ffffff")
                label.setTextColor(PROFILE_TEXT)
            }
            // Find this entry's small icon+label block, not the entire profile page.
            var parent = label.parent as? ViewGroup
            var block: ViewGroup? = null
            while (parent != null && parent !== root && parent.height <= root.height / 4 && parent.width <= root.width / 2) {
                block = parent
                parent = parent.parent as? ViewGroup
            }
            fun icons(view: View) {
                if (view is ImageView) {
                    if (!dark) {
                        iconColors.remove(view)
                        if (monochrome(view)) view.imageTintList = ColorStateList.valueOf(IM_BLUE)
                    } else if (monochrome(view)) {
                        if (!iconColors.containsKey(view)) iconColors[view] = IconColor(view.imageTintList)
                        if (view.imageTintList?.defaultColor != PROFILE_TEXT) {
                            once("profile-icon-${label.text}-${view.id}", "profile entry icon=${label.text} id=${view.id} " +
                                "drawable=${view.drawable?.javaClass?.name} tint=${view.imageTintList} alpha=${view.alpha}; set=#e6ffffff")
                            view.imageTintList = ColorStateList.valueOf(PROFILE_TEXT)
                        }
                    } else {
                        once("profile-icon-unhandled-${label.text}-${view.id}", "profile entry icon left unchanged text=${label.text} " +
                            "id=${view.id} drawable=${view.drawable?.javaClass?.name} tint=${view.imageTintList} alpha=${view.alpha}")
                    }
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) icons(view.getChildAt(i))
            }
            block?.let(::icons)
        }
    }

    /** Flow articles cannot restore their in-memory post on Activity.recreate().
     * Only neutral UI surfaces/text and verified toolbar icons participate here;
     * post images, avatars, accent labels and multicolor backgrounds stay native. */
    private fun repairRetainedPage(activity: Activity, root: ViewGroup, dark: Boolean) {
        val primary = if (dark) 0xffeeeeee.toInt() else 0xff161823.toInt()
        fun neutral(color: Int): Boolean {
            val r = (color ushr 16) and 255
            val g = (color ushr 8) and 255
            val b = color and 255
            return maxOf(r, g, b) - minOf(r, g, b) <= 24
        }
        fun foreground(color: Int): Int {
            if (!neutral(color) || color ushr 24 == 0) return color
            val y = luma(color)
            val rgb = when {
                dark && y < 90 -> 0xeeeeee
                dark && y < 165 -> 0xaaaaaa
                !dark && y > 215 -> 0x161823
                !dark && y > 165 -> 0x737373
                else -> return color
            }
            return (color and 0xff000000.toInt()) or rgb
        }
        fun visit(view: View) {
            if (!view.isShown) return
            solidColor(view)?.let { color ->
                if (neutral(color) && color ushr 24 >= 240) {
                    val y = luma(color)
                    val wanted = when {
                        dark && y >= 245 -> 0xff161616.toInt()
                        dark && y >= 200 -> 0xff252525.toInt()
                        !dark && y < 30 -> android.graphics.Color.WHITE
                        !dark && y < 65 -> 0xfff2f2f2.toInt()
                        else -> color
                    }
                    if (wanted != color) setSolidColor(view, wanted)
                }
            }
            if (view is TextView) {
                val color = foreground(view.currentTextColor)
                if (color != view.currentTextColor) view.setTextColor(color)
                val hint = foreground(view.currentHintTextColor)
                if (hint != view.currentHintTextColor) view.setHintTextColor(hint)
            }
            if (view is ImageView) {
                val id = try { view.resources.getResourceEntryName(view.id) } catch (_: Exception) { "" }
                if (id in setOf("iv_back", "j_9", "ods", "n0n", "m3d", "j+g")) {
                    val accent = when (id) {
                        "ods" -> 0xfffe2c55.toInt()
                        "m3d" -> 0xffffd12d.toInt()
                        else -> primary
                    }
                    if (view.imageTintList?.defaultColor != primary) view.imageTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_activated), intArrayOf()),
                        intArrayOf(accent, accent, primary))
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            activity.window.insetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
        }
        (activity.window.decorView.background as? ColorDrawable)?.let {
            val wanted = if (dark) 0xff161616.toInt() else android.graphics.Color.WHITE
            if (it.color != wanted) activity.window.decorView.setBackgroundColor(wanted)
        }
        once("retained-palette-${activity.javaClass.simpleName}-$dark", "retained page live palette activity=${activity.javaClass.simpleName} night=$dark; post/scroll/back stack retained; media excluded")
    }

    private fun monochrome(view: ImageView): Boolean {
        val drawable = view.drawable ?: return false
        if (drawable.javaClass.name.contains("VectorDrawable")) return true
        if (drawable !is BitmapDrawable) return false
        val bitmap = drawable.bitmap ?: return false
        if (bitmap.isRecycled || (android.os.Build.VERSION.SDK_INT >= 26 && bitmap.config == android.graphics.Bitmap.Config.HARDWARE)) return false
        var opaque = 0
        for (x in 0..6) for (y in 0..6) {
            val color = bitmap.getPixel(x * (bitmap.width - 1) / 6, y * (bitmap.height - 1) / 6)
            if ((color ushr 24) < 30) continue
            opaque++
            val r = (color shr 16) and 255; val g = (color shr 8) and 255; val b = color and 255
            if (maxOf(r, g, b) - minOf(r, g, b) > 48) return false
        }
        return opaque > 0
    }
    private fun luma(color: Int): Int = (((color shr 16) and 255) * 299 + ((color shr 8) and 255) * 587 + (color and 255) * 114) / 1000
    private fun once(key: String, message: String) { synchronized(seen) { if (seen.add(key)) log(message) } }
    private class IconColor(val original: ColorStateList?)
    private class Watch(owner: Activity, content: ViewGroup) {
        val activity = WeakReference(owner)
        val root = WeakReference(content)
        var dirty = true
        var message = false
        var chat = false
        var exitingWallpaper = false
        val statusBackdrop = ChatStatusBackdrop()
        val bottomSurface = ChatBottomSurface()
        var lastNight: Boolean? = null
        var lastDockSearch = 0L
        var lastStructureRepair = 0L
        var covered = false
        lateinit var scrollFix: ChatScrollControlFix
        lateinit var focusListener: ViewTreeObserver.OnWindowFocusChangeListener
        var chatRoot = WeakReference<ViewGroup>(null)
        var chatStatusModified = false
        var entryPending = false
        var entryDeadline = 0L
        var entryFrames = 0
        var wallpaperImage = WeakReference<ImageView>(null)
        var originalWallpaperBackground: android.graphics.drawable.Drawable? = null
        var wallpaperBackdrop: ColorDrawable? = null
        var wallpaperCompleteDrawable = WeakReference<android.graphics.drawable.Drawable>(null)
        var customWallpaper: Boolean? = null
        var chatDock = WeakReference<View>(null)
        var chatSession = WeakReference<Any>(null)
        var originalStatusContrast: Boolean? = null
        val chatSurfaces = ArrayList<WeakReference<View>>()
        var paletteNight: Boolean? = null
        var palette: Int? = null
        val position = IntArray(2)
        val backplates = ArrayList<WeakReference<View>>()
        lateinit var preDraw: ViewTreeObserver.OnPreDrawListener
        lateinit var listener: ViewTreeObserver.OnGlobalLayoutListener
    }
    companion object {
        private const val ENTRY_GATE_ENABLED = true // 0.1.26 A/B: only the chat-entry gate differs.
        private val CHAT_SURFACE_COLORS = setOf(0xff161823.toInt(), 0xff161616.toInt(), 0xffefefef.toInt(), android.graphics.Color.WHITE)
        private const val IM_BLUE = -15329245 // 0xff161823
        private const val INPUT_GREY = -13027015 // 0xff393939
        private const val PROFILE_TEXT = -419430401 // 0xe6ffffff
    }
}
