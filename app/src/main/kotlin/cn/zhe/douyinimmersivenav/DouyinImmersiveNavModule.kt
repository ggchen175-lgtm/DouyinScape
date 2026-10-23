package cn.zhe.douyinimmersivenav

import cn.zhe.douyinsystemtheme.R
import cn.zhe.douyinsystemtheme.UnifiedChatBridge
import cn.zhe.douyinsystemtheme.NativeChatRegistry
import cn.zhe.douyinsystemtheme.ChatListInsets
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.Instrumentation
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

/** Edge-to-edge adaptation scoped to Douyin's bottom interactive controls. */
class DouyinImmersiveNavModule {
    fun start(lpparam: XC_LoadPackage.LoadPackageParam, app: Application) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        logOnce("module-build", "loaded build=UNIFIED-0.2.3-TRANSPARENT-COMPOSER")
        NativeChatRegistry.destroyed.add { chatRoot ->
            synchronized(ACTIVITY_STATES) { ACTIVITY_STATES.values.toList() }.forEach { state ->
                val dock = state.target
                if (dock != null && isDescendantOf(dock, chatRoot)) releaseChatDock(state)
            }
        }

        hookWindowCalls(lpparam.classLoader)
        hookPanelToggleClicks()
        ChatFloatingTouch.install()
        hookCommentDialogs()
        // Splash hosts hybrid orders windows that can be missed by the Application callbacks.
        XposedHelpers.findAndHookMethod(Instrumentation::class.java, "callActivityOnResume", Activity::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.args[0] as Activity
                    if (activity.javaClass.simpleName == "SplashActivity") attachActivity(activity)
                }
            })
        app.registerActivityLifecycleCallbacks(
                            object : Application.ActivityLifecycleCallbacks {
                                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                                override fun onActivityStarted(activity: Activity) = attachActivity(activity)
                                override fun onActivityResumed(activity: Activity) = attachActivity(activity)
                                override fun onActivityPaused(activity: Activity) = Unit
                                override fun onActivityStopped(activity: Activity) {
                                    val root = activity.findViewById<View>(android.R.id.content) ?: return
                                    val state = synchronized(ACTIVITY_STATES) { ACTIVITY_STATES[root] } ?: return
                                    if (state.videoInputEnabled) {
                                        state.videoInputActive = false
                                        releaseVideoInput(state)
                                    }
                                    state.liveActive = false
                                    releaseLiveBars(state)
                                }
                                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                                override fun onActivityDestroyed(activity: Activity) {
                                    val root = activity.findViewById<View>(android.R.id.content) ?: return
                                    val state = synchronized(ACTIVITY_STATES) { ACTIVITY_STATES.remove(root) } ?: return
                                    detachCommentRoot(root)
                                    releaseLiveBars(state)
                                    releaseVideoInput(state)
                                    releaseChatStrip(state)
                                    releaseChatDock(state)
                                    state.chatFloating.release()
                                    stopChatDiscovery(state)
                                    state.pinListener?.let { listener ->
                                        state.root.viewTreeObserver.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
                                    }
                                    state.pinListener = null
                                    state.pickerListener?.let { listener ->
                                        state.root.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
                                    }
                                    state.pickerListener = null
                                }
                            }
                        )
    }

    private fun hookWindowCalls(loader: ClassLoader) {
        // Window has abstract color setters on some Android releases; PhoneWindow is
        // the concrete implementation invoked by app code.
        try {
            val phoneWindow = Class.forName("com.android.internal.policy.PhoneWindow", false, loader)
            hookWindowClass(phoneWindow)
        } catch (t: Throwable) {
            log("PhoneWindow hook failed: $t")
        }
        // Insets are dispatched only on system-bar/IME changes. Use this narrow
        // signal to update the dock, instead of polling it on every layout pass.
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "dispatchApplyWindowInsets",
                WindowInsets::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args[0] as? WindowInsets ?: return
                        param.args[0] = OrdersEdgeToEdgeController.rewriteInsets(param.thisObject as View, original)
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as View
                        val state = synchronized(ACTIVITY_STATES) { ACTIVITY_STATES[view] } ?: return
                        val insets = param.args[0] as? WindowInsets ?: return
                        if (Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime())) {
                            state.imeGate.observeIme()
                        }
                        view.post { syncChatDock(state, isImeVisible(state.root)) }
                    }
                }
            )
        } catch (t: Throwable) {
            log("insets hook failed: $t")
        }
    }

    private fun hookWindowClass(windowClass: Class<*>) {
        hookWindowMethod(windowClass, "setNavigationBarColor", Int::class.javaPrimitiveType) {
            it.args[0] = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 28) {
            hookWindowMethod(windowClass, "setNavigationBarDividerColor", Int::class.javaPrimitiveType) {
                it.args[0] = Color.TRANSPARENT
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            hookWindowMethod(windowClass, "setNavigationBarContrastEnforced", Boolean::class.javaPrimitiveType) {
                it.args[0] = false
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            hookWindowMethod(windowClass, "setDecorFitsSystemWindows", Boolean::class.javaPrimitiveType) {
                it.args[0] = false
            }
        }
    }

    /** Observe only the cached chat '+' button; leave the app's click listener intact. */
    private fun hookPanelToggleClicks() {
        XposedHelpers.findAndHookMethod(View::class.java, "performClick", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val button = param.thisObject as View
                val state = synchronized(PANEL_BUTTON_STATES) { PANEL_BUTTON_STATES[button] } ?: return
                if (state.target?.isShown != true || !button.isShown) return
                state.imeGate.onPanelToggle(state.pickerOpen, isImeVisible(state.root))
                log("chat attachment toggle requested open=${state.imeGate.panelRequested}; " +
                    "imeLiftHeld=${state.imeGate.holdLift}")
            }
        })
    }

    private fun hookWindowMethod(windowClass: Class<*>, name: String, arg: Class<*>?, change: (XC_MethodHook.MethodHookParam) -> Unit) {
        try {
            XposedHelpers.findAndHookMethod(
                windowClass,
                name,
                arg,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) = change(param)
                }
            )
        } catch (t: Throwable) {
            log("$name hook on ${windowClass.name} unavailable: $t")
        }
    }

    private fun attachActivity(activity: Activity) {
        try {
            val window = activity.window
            if (activity.javaClass.simpleName != "SplashActivity") makeWindowEdgeToEdge(window)
            OrdersEdgeToEdgeController.attach(window, activity.javaClass.name)
            val root = activity.findViewById<View>(android.R.id.content) ?: return
            PageNavigationSafeArea.attach(root, activity.javaClass.name, window)
            if (activity.javaClass.simpleName != "LivePlayActivity") attachCommentRoot(root)
            if (root.getTag(R.id.nav_padding_listener) != null) {
                val existing = root.getTag(R.id.nav_padding_listener) as? ChatDiscoveryState
                if (existing != null) {
                    existing.liveActive = true
                    syncLiveBars(existing, SystemClock.uptimeMillis())
                }
                if (existing?.videoInputEnabled == true) {
                    existing.videoInputActive = true
                    syncVideoInput(existing, SystemClock.uptimeMillis())
                }
                return
            }
            val state = ChatDiscoveryState(root, activity.javaClass.name)
            state.dedicatedBottomPage = activity.javaClass.simpleName == "LivePlayActivity" ||
                activity.javaClass.simpleName == "FlowPageActivity" ||
                activity.javaClass.simpleName == "SplashActivity"
            if (activity.javaClass.simpleName == "DetailActivity" || activity.javaClass.simpleName == "UltraDetailActivity") {
                state.videoInputEnabled = true
                state.videoOverlays = VideoBottomOverlays(root, NAV_FALLBACK_PX)
            }
            // Live rooms can be embedded in DetailActivity's feed. Resolve the
            // native control pair and validate its geometry instead of gating on
            // the host Activity's name.
            state.liveInputId = root.resources.getIdentifier("exf", "id", TARGET_PACKAGE)
            state.liveActionsId = root.resources.getIdentifier("ec", "id", TARGET_PACKAGE)
            state.liveDanmakuId = root.resources.getIdentifier("r62", "id", TARGET_PACKAGE)
            state.liveShopEnabled = true
            root.setTag(R.id.nav_padding_listener, state)
            synchronized(ACTIVITY_STATES) { ACTIVITY_STATES[root] = state }
            syncVideoInput(state, SystemClock.uptimeMillis())
            if (!state.dedicatedBottomPage && state.videoOverlays?.isPrivatePage != true) discoverTargets(state, root as? ViewGroup ?: return)
            syncLiveBars(state, SystemClock.uptimeMillis())
            syncLiveShopCard(state, SystemClock.uptimeMillis())
            syncVideoInput(state, SystemClock.uptimeMillis())
            startChatDiscovery(state)
            logOnce("activity-${activity.javaClass.name}", "attached ${activity.javaClass.name}")
        } catch (t: Throwable) {
            log("activity setup failed (${activity.javaClass.name}): $t")
        }
    }

    /** A comment sheet may have its own Dialog window instead of the Activity's content root. */
    private fun hookCommentDialogs() {
        XposedHelpers.findAndHookMethod(Dialog::class.java, "show", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val window = (param.thisObject as Dialog).window ?: return
                val dialogName = param.thisObject.javaClass.name
                if (dialogName.startsWith("com.bytedance.android.ec.") ||
                    dialogName.startsWith("com.bytedance.android.shopping.") ||
                    dialogName.startsWith("com.bytedance.android.livesdk.livecommerce.")) {
                    makeWindowEdgeToEdge(window)
                    logOnce("dialog-$dialogName", "commerce dialog edge-to-edge class=$dialogName")
                }
                val decor = XposedHelpers.callMethod(window, "peekDecorView") as? View ?: return
                attachCommentRoot(decor)
                OrdersEdgeToEdgeController.attach(window, window.context.javaClass.name)
            }
        })
    }

    private fun attachCommentRoot(root: View) {
        PageNavigationSafeArea.attach(root, (root.context as? Activity)?.javaClass?.name ?: "")
        if (root !is ViewGroup) return
        synchronized(COMMENT_STATES) { if (COMMENT_STATES.containsKey(root)) return }
        val observer = root.viewTreeObserver
        if (!observer.isAlive) return
        val state = CommentInputState(root)
        state.rowId = root.resources.getIdentifier("csy", "id", TARGET_PACKAGE)
        state.editId = root.resources.getIdentifier("e19", "id", TARGET_PACKAGE)
        state.bottomId = root.resources.getIdentifier("mxe", "id", TARGET_PACKAGE)
        state.outerId = root.resources.getIdentifier("e1q", "id", TARGET_PACKAGE)
        state.innerId = root.resources.getIdentifier("e4c", "id", TARGET_PACKAGE)
        state.imeOuterId = root.resources.getIdentifier("t8m", "id", TARGET_PACKAGE)
        state.imeInnerId = root.resources.getIdentifier("slh", "id", TARGET_PACKAGE)
        state.imeContentId = root.resources.getIdentifier("iee", "id", TARGET_PACKAGE)
        state.imeContentParentId = root.resources.getIdentifier("id9", "id", TARGET_PACKAGE)
        state.imeIconsId = root.resources.getIdentifier("j_+", "id", TARGET_PACKAGE)
        if (state.rowId == 0 || state.editId == 0 || state.bottomId == 0 ||
            state.outerId == 0 || state.innerId == 0) return
        synchronized(COMMENT_STATES) { COMMENT_STATES[root] = state }
        state.layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            state.needsSearch = true
            state.imeNeedsSearch = true
        }
        state.pinListener = ViewTreeObserver.OnPreDrawListener {
            val row = state.row
            if (row != null && (!row.isAttachedToWindow || !row.isShown)) releaseCommentRow(state)
            val now = SystemClock.uptimeMillis()
            val commentInterval = if (state.needsSearch) COMMENT_SEARCH_INTERVAL_MS else IDLE_SEARCH_INTERVAL_MS
            if (state.row == null && now - state.lastSearchAt >= commentInterval) {
                state.needsSearch = false
                state.lastSearchAt = now
                var host = state.bottomHost
                if (host == null || !host.isAttachedToWindow || !host.isShown) {
                    host = root.findViewById<View>(state.bottomId) as? ViewGroup
                    state.bottomHost = host
                }
                (if (host?.isShown == true) findCommentRow(host, state, 0)
                    else findCommentRow(root, state, 0))?.let { found ->
                    var owner = found.parent as? ViewGroup
                    while (owner != null && owner.id != state.bottomId) owner = owner.parent as? ViewGroup
                    state.bottomHost = owner
                    state.row = found
                    state.originalTranslationY = found.translationY
                    state.lastAppliedTranslationY = found.translationY
                    state.logNextPin = true
                    log("comment input bound ${describe(found)} bounds=${screenBounds(found)} " +
                        "parent=${(found.parent as? View)?.let(::describe)}")
                }
            }
            pinCommentInput(state)
            true
        }
        state.attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = detachCommentRoot(v)
        }
        observer.addOnGlobalLayoutListener(state.layoutListener)
        observer.addOnPreDrawListener(state.pinListener)
        root.addOnAttachStateChangeListener(state.attachListener)
    }

    private fun findCommentRow(view: View, state: CommentInputState, ancestors: Int): ViewGroup? {
        if (!view.isShown || view !is ViewGroup) return null
        val chain = when (view.id) {
            state.bottomId -> 1
            state.outerId -> if (ancestors and 1 != 0) ancestors or 2 else ancestors
            state.innerId -> if (ancestors and 3 == 3) ancestors or 4 else ancestors
            else -> ancestors
        }
        if (chain == 7 && view.id == state.rowId && view.height > 0 && view.width > 0) {
            val edit = view.findViewById<View>(state.editId)
            if (edit is android.widget.EditText && edit.isShown) return view
        }
        for (index in 0 until view.childCount) {
            findCommentRow(view.getChildAt(index), state, chain)?.let { return it }
        }
        return null
    }

    private fun pinCommentInput(state: CommentInputState) {
        if (Build.VERSION.SDK_INT < 30) return
        val insets = state.root.rootWindowInsets ?: return
        if (state.row == null && state.imeWrapper == null && !insets.isVisible(WindowInsets.Type.ime())) return
        state.root.getLocationOnScreen(state.rootPosition)
        if (insets.isVisible(WindowInsets.Type.ime())) {
            state.row?.let { row ->
                if (row.translationY == state.lastAppliedTranslationY) row.translationY = state.originalTranslationY
                state.lastAppliedTranslationY = row.translationY
            }
            restoreCommentRowClipping(state)
            pinCommentImeContent(state, insets)
            return
        }
        releaseCommentImeContent(state)
        val row = state.row ?: return
        if (!row.isAttachedToWindow || row.height == 0) return
        val navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        if (navBottom <= 0) return
        state.root.getLocationOnScreen(state.rootPosition)
        row.getLocationOnScreen(state.rowPosition)
        val wantedBottom = state.rootPosition[1] + state.root.height - navBottom - COMMENT_BOTTOM_GAP_PX
        val currentBottom = state.rowPosition[1] + row.height
        val delta = (wantedBottom - currentBottom).toFloat()
        if (delta != 0f && kotlin.math.abs(delta) <= COMMENT_PIN_TOLERANCE_PX) {
            reserveCommentRowHeadroom(state, wantedBottom - row.height)
            allowCommentRowOverflow(state, wantedBottom - row.height, wantedBottom)
            row.translationY += delta
            state.lastAppliedTranslationY = row.translationY
            if (state.logNextPin) {
                state.logNextPin = false
                log("comment input applied ${describe(row)} bottomBefore=$currentBottom " +
                    "targetBottom=$wantedBottom after=${screenBounds(row)} navBottom=$navBottom " +
                    "translationY=${state.originalTranslationY}->${row.translationY}; background unchanged")
            }
        }
    }

    /** Choose the smallest named wrapper containing both the icons and the emoji row. */
    private fun findCommentImeContent(view: View, state: CommentInputState): Pair<ViewGroup, ViewGroup>? {
        if (!view.isShown || view !is ViewGroup) return null
        if (state.imeContentId != 0 && view.id == state.imeContentId && view.width > 0 && view.height > 0) {
            var ancestor = view.parent as? ViewGroup
            var contentParentMatched = state.imeContentParentId == 0
            while (ancestor != null) {
                if (ancestor.id == state.imeContentParentId) contentParentMatched = true
                if (contentParentMatched && ((state.imeOuterId != 0 && ancestor.id == state.imeOuterId) ||
                        (state.imeInnerId != 0 && ancestor.id == state.imeInnerId))) {
                    val icons = if (state.imeIconsId != 0) ancestor.findViewById<View>(state.imeIconsId) else null
                    if (icons != null && icons.isShown && icons.height > 0) return ancestor to view
                }
                if (ancestor === state.root) break
                ancestor = ancestor.parent as? ViewGroup
            }
        }
        for (index in 0 until view.childCount) {
            findCommentImeContent(view.getChildAt(index), state)?.let { return it }
        }
        return null
    }

    private fun pinCommentImeContent(state: CommentInputState, insets: WindowInsets) {
        val existing = state.imeWrapper
        val existingContent = state.imeContent
        if (existing != null && (!existing.isAttachedToWindow || !existing.isShown ||
                existingContent == null || !existingContent.isAttachedToWindow || !existingContent.isShown ||
                !isDescendantOf(existingContent, existing))) {
            releaseCommentImeContent(state)
        }
        val now = SystemClock.uptimeMillis()
        if (state.imeWrapper == null && now - state.lastImeSearchAt >= COMMENT_SEARCH_INTERVAL_MS) {
            state.imeNeedsSearch = false
            state.lastImeSearchAt = now
            findCommentImeContent(state.root, state)?.let { found ->
                state.imeWrapper = found.first
                state.imeContent = found.second
                state.originalImeTranslationY = found.first.translationY
                state.lastImeTranslationY = found.first.translationY
                state.imeLogNextPin = true
                log("comment IME bound wrapper=${describe(found.first)} bounds=${screenBounds(found.first)} " +
                    "content=${describe(found.second)} bounds=${screenBounds(found.second)}")
            }
        }
        val wrapper = state.imeWrapper ?: return
        val content = state.imeContent ?: return
        if (content.height == 0) return
        val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
        if (imeBottom <= 0) return
        val imeTop = state.rootPosition[1] + state.root.height - imeBottom
        val wantedContentBottom = imeTop - PageNavigationSafeArea.navigationBottom(state.root)
        content.getLocationOnScreen(state.imeContentPosition)
        val currentContentBottom = state.imeContentPosition[1] + content.height
        val delta = (wantedContentBottom - currentContentBottom).toFloat()
        if (delta != 0f && kotlin.math.abs(delta) <= COMMENT_IME_PIN_TOLERANCE_PX) {
            allowCommentImeOverflow(state, wantedContentBottom - content.height, wantedContentBottom)
            wrapper.translationY += delta
            state.lastImeTranslationY = wrapper.translationY
            if (state.imeLogNextPin) {
                state.imeLogNextPin = false
                log("comment IME applied wrapper=${describe(wrapper)} contentBottomBefore=$currentContentBottom " +
                    "imeTop=$imeTop targetContentBottom=$wantedContentBottom after=${screenBounds(content)} " +
                    "translationY=${state.originalImeTranslationY}->${wrapper.translationY}")
            }
        }
    }

    private fun allowCommentImeOverflow(state: CommentInputState, contentTop: Int, contentBottom: Int) {
        var parent = state.imeWrapper?.parent as? ViewGroup
        while (parent != null) {
            parent.getLocationOnScreen(state.clipParentPosition)
            val top = state.clipParentPosition[1]
            val bottom = top + parent.height
            if (contentTop < top + parent.paddingTop || contentBottom > bottom - parent.paddingBottom) {
                val group = parent
                if (state.imeClipStates.none { it.group === group }) {
                    state.imeClipStates.add(CommentClipState(group, group.clipChildren, group.clipToPadding))
                }
                group.clipChildren = false
                group.clipToPadding = false
            }
            if (parent === state.root) break
            parent = parent.parent as? ViewGroup
        }
    }

    private fun releaseCommentImeContent(state: CommentInputState) {
        state.imeWrapper?.let { wrapper ->
            if (wrapper.translationY == state.lastImeTranslationY) wrapper.translationY = state.originalImeTranslationY
        }
        for (saved in state.imeClipStates) {
            saved.group.clipChildren = saved.clipChildren
            saved.group.clipToPadding = saved.clipToPadding
        }
        state.imeClipStates.clear()
        state.imeWrapper = null
        state.imeContent = null
        state.imeNeedsSearch = true
    }

    private fun allowCommentRowOverflow(state: CommentInputState, rowTop: Int, rowBottom: Int) {
        var parent = state.row?.parent as? ViewGroup
        while (parent != null) {
            parent.getLocationOnScreen(state.clipParentPosition)
            val top = state.clipParentPosition[1]
            val bottom = top + parent.height
            if (rowTop < top + parent.paddingTop || rowBottom > bottom - parent.paddingBottom) {
                val group = parent
                if (state.clipStates.none { it.group === group }) {
                    state.clipStates.add(CommentClipState(group, group.clipChildren, group.clipToPadding))
                }
                group.clipChildren = false
                group.clipToPadding = false
            }
            if (parent.id == state.bottomId) break
            parent = parent.parent as? ViewGroup
        }
    }

    /** Give the whole input row real layout space, rather than drawing outside a 196px box. */
    private fun reserveCommentRowHeadroom(state: CommentInputState, wantedTop: Int) {
        if (state.rowContainerSizes.isNotEmpty()) return
        val containers = ArrayList<ViewGroup>(3)
        var parent = state.row?.parent as? ViewGroup
        var extra = 0
        while (parent != null) {
            if (parent.id == state.innerId || parent.id == state.outerId || parent.id == state.bottomId) {
                parent.getLocationOnScreen(state.clipParentPosition)
                extra = maxOf(extra, state.clipParentPosition[1] - wantedTop)
                containers.add(parent)
            }
            if (parent.id == state.bottomId) break
            parent = parent.parent as? ViewGroup
        }
        if (extra <= 0 || extra > COMMENT_PIN_TOLERANCE_PX) return
        // mxe stays anchored at the window bottom; its background still covers the gesture area.
        // Grow all three named wrappers so fixed-height children cannot retain the old crop.
        for (container in containers) {
            val params = container.layoutParams ?: continue
            val appliedHeight = container.height + extra
            state.rowContainerSizes.add(CommentContainerSize(container, params.height, appliedHeight))
            params.height = appliedHeight
            container.layoutParams = params
        }
        log("comment input headroom extra=${extra}px; whole csy row retained; " +
            containers.joinToString { "${describe(it)} bounds=${screenBounds(it)}" })
    }

    private fun restoreCommentRowClipping(state: CommentInputState) {
        for (saved in state.rowContainerSizes) {
            val params = saved.group.layoutParams ?: continue
            if (params.height == saved.appliedHeight) {
                params.height = saved.originalHeight
                saved.group.layoutParams = params
            }
        }
        state.rowContainerSizes.clear()
        for (saved in state.clipStates) {
            saved.group.clipChildren = saved.clipChildren
            saved.group.clipToPadding = saved.clipToPadding
        }
        state.clipStates.clear()
    }

    private fun releaseCommentRow(state: CommentInputState) {
        state.row?.let { row ->
            if (row.translationY == state.lastAppliedTranslationY) row.translationY = state.originalTranslationY
        }
        restoreCommentRowClipping(state)
        state.row = null
        state.needsSearch = true
    }

    private fun detachCommentRoot(root: View) {
        val state = synchronized(COMMENT_STATES) { COMMENT_STATES.remove(root) } ?: return
        releaseCommentRow(state)
        releaseCommentImeContent(state)
        val observer = root.viewTreeObserver
        if (observer.isAlive) {
            state.layoutListener?.let(observer::removeOnGlobalLayoutListener)
            state.pinListener?.let(observer::removeOnPreDrawListener)
        }
        state.attachListener?.let(root::removeOnAttachStateChangeListener)
    }

    private fun makeWindowEdgeToEdge(window: Window) {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            if (window.navigationBarColor != Color.TRANSPARENT) window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= 28 && window.navigationBarDividerColor != Color.TRANSPARENT) {
                window.navigationBarDividerColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= 29 && window.isNavigationBarContrastEnforced) {
                window.isNavigationBarContrastEnforced = false
            }
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
                val attrs = window.attributes
                val types = attrs.fitInsetsTypes
                val newTypes = types and WindowInsets.Type.navigationBars().inv()
                if (types != newTypes) {
                    attrs.fitInsetsTypes = newTypes
                    window.attributes = attrs
                }
            } else {
                val decor = XposedHelpers.callMethod(window, "peekDecorView") as? View ?: return
                val required = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                if ((decor.systemUiVisibility and required) != required) decor.systemUiVisibility = decor.systemUiVisibility or required
            }
        } catch (t: Throwable) {
            logOnce("window-${window.javaClass.name}", "window setup issue ${window.javaClass.name}: $t")
        }
    }

    /** Search only on layout changes, and only at a throttled rate until chat UI exists. */
    private fun startChatDiscovery(state: ChatDiscoveryState) {
        val root = state.root
        val observer = root.viewTreeObserver
        if (!observer.isAlive) return
        if (state.listener == null) {
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                state.structureDirty = true
                state.videoOverlays?.markDirty()
                if (state.liveShopEnabled) state.liveShopNeedsSearch = true
                try {
                    if (!state.dedicatedBottomPage && state.videoOverlays?.isPrivatePage != true && state.target == null && state.homeSafeArea == null) {
                        val now = SystemClock.uptimeMillis()
                        if (now - state.lastSearchAt >= SEARCH_INTERVAL_MS && root is ViewGroup) {
                            state.lastSearchAt = now
                            discoverTargets(state, root)
                        }
                    }
                } catch (t: Throwable) {
                    logOnce("layout-error-${state.activityName}", "chat layout inspection failed in ${state.activityName}: $t")
                }
            }
            state.listener = listener
            observer.addOnGlobalLayoutListener(listener)
        }

        if (state.pinListener == null) {
            val pin = ViewTreeObserver.OnPreDrawListener {
                val now = SystemClock.uptimeMillis()
                syncLiveBars(state, now)
                syncLiveShopCard(state, now)
                val videoMayDraw = syncVideoInput(state, now, true)
                if (state.videoOverlays?.isPrivatePage == true) {
                    if (state.target != null) releaseChatDock(state)
                    return@OnPreDrawListener videoMayDraw
                }
                if (state.dedicatedBottomPage) return@OnPreDrawListener true
                if (state.target == null && state.homeSafeArea == null && root is ViewGroup) {
                    if (now - state.lastPinSearchAt >= HOME_SEARCH_INTERVAL_MS) {
                        state.lastPinSearchAt = now
                        discoverTargets(state, root)
                    }
                }
                if (state.homeSafeArea?.sync() == false) return@OnPreDrawListener false

                // Home tabs can stay attached while a chat page is shown in the
                // same Activity. Chat discovery must not depend on homeSafeArea == null.
                val dock = state.target
                if (dock != null) {
                    if (!dock.isShown) {
                        if (state.hiddenSince == 0L) state.hiddenSince = now
                        // A rebuilt dock can be ready before the grace period
                        // expires. Bind it before drawing, without the search delay.
                        val replacement = (root as? ViewGroup)?.let { findVisibleChatDock(it, state) }
                        if (replacement != null && replacement !== dock) {
                            bindChatDock(state, replacement, isImeVisible(root))
                        } else if (now - state.hiddenSince >= DOCK_RELEASE_GRACE_MS) {
                            releaseChatDock(state)
                        }
                    } else {
                        state.hiddenSince = 0L
                    }
                }
                if (state.target == null && root is ViewGroup &&
                    (state.structureDirty || now - state.lastDockSearchAt >= CHAT_DOCK_SEARCH_INTERVAL_MS)) {
                    state.lastDockSearchAt = now
                    findVisibleChatDock(root, state)?.let {
                        bindChatDock(state, it, isImeVisible(root))
                    }
                }
                state.structureDirty = false
                if (state.target?.isShown == true) {
                    syncChatDock(state, isImeVisible(root))
                    syncChatSuggestion(state, now)
                    // A viewport bounds change requests layout. Do not expose an unmeasured first frame.
                    if (!state.entryLayoutReady) {
                        if ((state.messageViewport == null || state.messageViewport?.isLayoutRequested == true) && state.entryDeferredFrames < 3 &&
                            now - state.entryLayoutStarted < 100L) {
                            state.entryDeferredFrames++
                            return@OnPreDrawListener false
                        }
                        state.entryLayoutReady = true
                    }
                }

                state.chatFloating.sync((root as? ViewGroup)?.let { NativeChatRegistry.visible(it) }, state.target, state.messageViewport)

                if (state.target?.isShown == true && isImeVisible(root)) {
                    state.stripReturnPending = true
                    state.stripEnterStarted = 0L
                }
                if (state.chatStrip == null && state.target != null) {
                    if ((state.stripReturnPending || now - state.lastStripSearchAt >= CHAT_STRIP_SEARCH_INTERVAL_MS) && root is ViewGroup) {
                        state.lastStripSearchAt = now
                        findChatStrip(root)?.let { bindChatStrip(state, it) }
                    }
                } else if (state.chatStrip?.isShown == true) {
                    pinChatStrip(state)
                } else {
                    releaseChatStrip(state)
                }
                true
            }
            state.pinListener = pin
            observer.addOnPreDrawListener(pin)
        }
    }

    private fun discoverTargets(state: ChatDiscoveryState, root: ViewGroup) {
        state.lastDockSearchAt = SystemClock.uptimeMillis()
        val targets = findTargets(root)
        when {
            targets.chatDock != null -> bindChatDock(state, targets.chatDock, isImeVisible(root))
            targets.homeBar != null -> bindHomeBar(state, targets.homeBar)
        }
    }

    private fun syncVideoInput(state: ChatDiscoveryState, now: Long, preDraw: Boolean = false): Boolean {
        if (!state.videoInputEnabled || !state.videoInputActive) return true
        val coordinator = state.videoOverlays ?: VideoBottomOverlays(state.root, NAV_FALLBACK_PX).also { state.videoOverlays = it }
        val mayDraw = coordinator.sync(now, preDraw)
        state.videoInput = coordinator.input
        return mayDraw
    }

    private fun releaseVideoInput(state: ChatDiscoveryState) {
        state.videoOverlays?.release()
        state.videoOverlays = null
        state.videoInput = null
    }

    /** Live controls are two siblings; their screen-sized common parent must not move. */
    private fun syncLiveBars(state: ChatDiscoveryState, now: Long) {
        if (!state.liveActive || state.liveInputId == 0 || state.liveActionsId == 0) return
        if (isImeVisible(state.root)) {
            if (state.liveLeft != null || state.liveRight != null) releaseLiveBars(state)
            return
        }
        val left = state.liveLeft
        val right = state.liveRight
        if ((left != null && !liveControlOnScreen(state, left, state.originalLiveLeftY)) ||
            (right != null && !liveControlOnScreen(state, right, state.originalLiveRightY))) {
            releaseLiveBars(state)
        }
        if (state.liveLeft == null || state.liveRight == null) {
            if (now - state.lastLiveSearchAt < LIVE_SEARCH_INTERVAL_MS) return
            state.lastLiveSearchAt = now
            val input = findVisibleLiveControl(state, state.liveInputId) ?: return
            val actions = findVisibleLiveControl(state, state.liveActionsId) ?: return
            if (!input.isShown || !actions.isShown) return
            val inputBar = input.parent as? ViewGroup ?: return
            val actionsBar = actions.parent as? ViewGroup ?: return
            if (inputBar === actionsBar || inputBar === state.root || actionsBar === state.root ||
                inputBar.parent !== actionsBar.parent || inputBar.height <= 0 || actionsBar.height <= 0 ||
                inputBar.height >= state.root.height / 2 || actionsBar.height >= state.root.height / 2) return
            state.liveLeft = inputBar
            state.liveRight = actionsBar
            state.originalLiveLeftY = inputBar.translationY
            state.originalLiveRightY = actionsBar.translationY
            log("live bars bound left=${describe(inputBar)} bounds=${screenBounds(inputBar)} " +
                "right=${describe(actionsBar)} bounds=${screenBounds(actionsBar)}; dynamic gesture clearance")
        }
        val insets = state.root.rootWindowInsets
        val nav = if (Build.VERSION.SDK_INT >= 30)
            insets?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())?.bottom ?: NAV_FALLBACK_PX
        else insets?.stableInsetBottom ?: NAV_FALLBACK_PX
        val rootPosition = IntArray(2)
        val position = IntArray(2)
        state.root.getLocationOnScreen(rootPosition)
        val navTop = rootPosition[1] + state.root.height - nav
        fun originalBottom(view: View, originalY: Float): Float {
            view.getLocationOnScreen(position)
            return position[1] + view.height - view.translationY + originalY
        }
        val originalBottom = maxOf(
            state.liveLeft?.let { originalBottom(it, state.originalLiveLeftY) } ?: navTop.toFloat(),
            state.liveRight?.let { originalBottom(it, state.originalLiveRightY) } ?: navTop.toFloat())
        state.liveShift = minOf(0f, navTop - originalBottom)
        state.liveLeft?.let { bar ->
            val wanted = state.originalLiveLeftY + state.liveShift
            if (bar.translationY != wanted) bar.translationY = wanted
        }
        state.liveRight?.let { bar ->
            val wanted = state.originalLiveRightY + state.liveShift
            if (bar.translationY != wanted) bar.translationY = wanted
        }
        syncLiveDanmakuLift(state, now)
    }

    private fun liveControlOnScreen(state: ChatDiscoveryState, view: View, originalY: Float = view.translationY): Boolean {
        if (!view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0) return false
        val viewport = android.graphics.Rect()
        if (!state.root.getGlobalVisibleRect(viewport)) return false
        val position = IntArray(2)
        view.getLocationOnScreen(position)
        // Use the layout baseline, not the module's translated position. A
        // preloaded room off screen must never be pulled into another feed page.
        val top = (position[1] - view.translationY + originalY).toInt()
        return android.graphics.Rect.intersects(viewport,
            android.graphics.Rect(position[0], top, position[0] + view.width, top + view.height))
    }

    private fun findVisibleLiveControl(state: ChatDiscoveryState, id: Int): View? {
        fun find(view: View): View? {
            if (!view.isShown) return null
            if (view.id == id && liveControlOnScreen(state, view)) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        return find(state.root)
    }

    /** Move the complete list above the controls, retaining whole message capsules. */
    private fun syncLiveDanmakuLift(state: ChatDiscoveryState, now: Long) {
        if (state.liveDanmakuId == 0) return
        val existing = state.liveDanmaku
        if (existing != null && (!existing.isAttachedToWindow || !existing.isShown)) {
            releaseLiveDanmakuLift(state)
        }
        if (state.liveDanmaku == null) {
            if (now - state.lastLiveDanmakuSearchAt < LIVE_SEARCH_INTERVAL_MS) return
            state.lastLiveDanmakuSearchAt = now
            val list = state.root.findViewById<View>(state.liveDanmakuId) ?: return
            if (!list.isShown || list.height <= 0 || list.width <= 0) return
            state.liveDanmaku = list
            state.originalLiveDanmakuY = list.translationY
            val before = screenBounds(list)
            list.translationY = state.originalLiveDanmakuY + state.liveShift
            log("live danmaku lift ${describe(list)} before=$before after=${screenBounds(list)} " +
                "shift=${state.liveShift}px; no module clip, padding or scroll-range change")
        }
        val list = state.liveDanmaku ?: return
        val wanted = state.originalLiveDanmakuY + state.liveShift
        if (list.translationY != wanted) list.translationY = wanted
    }

    private fun releaseLiveDanmakuLift(state: ChatDiscoveryState) {
        state.liveDanmaku?.let { list ->
            if (list.translationY == state.originalLiveDanmakuY + state.liveShift) {
                list.translationY = state.originalLiveDanmakuY
            }
        }
        state.liveDanmaku = null
        state.lastLiveDanmakuSearchAt = 0L
    }

    private fun releaseLiveBars(state: ChatDiscoveryState) {
        releaseLiveShopCard(state)
        releaseLiveDanmakuLift(state)
        state.liveLeft?.let { bar ->
            if (bar.translationY == state.originalLiveLeftY + state.liveShift) bar.translationY = state.originalLiveLeftY
        }
        state.liveRight?.let { bar ->
            if (bar.translationY == state.originalLiveRightY + state.liveShift) bar.translationY = state.originalLiveRightY
        }
        state.liveLeft = null
        state.liveRight = null
        state.lastLiveSearchAt = 0L
    }

    private fun syncLiveShopCard(state: ChatDiscoveryState, now: Long) {
        if (!state.liveActive || !state.liveShopEnabled || state.liveLeft == null || isImeVisible(state.root)) return
        val existing = state.liveShopCard
        val anchor = state.liveShopAnchor
        if (existing != null && (!existing.isAttachedToWindow || !existing.isShown ||
                anchor == null || !anchor.isAttachedToWindow || !anchor.isShown || !isDescendantOf(anchor, existing) ||
                !isLiveShopOnScreen(state, existing))) {
            releaseLiveShopCard(state)
        }
        if (state.liveShopCard == null) {
            if (now - state.lastLiveShopSearchAt < LIVE_SEARCH_INTERVAL_MS) return
            state.liveShopNeedsSearch = false
            state.lastLiveShopSearchAt = now
            val found = findLiveShopCard(state, now)
            if (found == null) {
                if (!state.liveShopMissLogged || now - state.lastLiveShopMissAt >= LIVE_SHOP_MISS_INTERVAL_MS) {
                    state.liveShopMissLogged = true
                    state.lastLiveShopMissAt = now
                    log("live shop card miss activity=${state.activityName} root=${screenBounds(state.root)} " +
                        "${state.liveShopSearchDetail}")
                }
                return
            }
            val background = found.first
            val card = found.second
            state.liveShopCard = card
            state.liveShopAnchor = background
            state.originalLiveShopY = card.translationY
            state.lastLiveShopY = card.translationY
            state.logLiveShopPin = true
            val detach = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    if (state.liveShopCard === v) releaseLiveShopCard(state)
                }
            }
            state.liveShopAttachListener = detach
            card.addOnAttachStateChangeListener(detach)
            log("live shop card bound ${describe(card)} bounds=${screenBounds(card)} " +
                "anchor=${describe(background)} originalTranslationY=${state.originalLiveShopY}")
        }
        val card = state.liveShopCard ?: return
        val wanted = state.originalLiveShopY - LIVE_SHOP_CARD_LIFT_PX
        allowLiveShopOverflow(state, card, wanted)
        if (card.translationY != wanted) {
            card.translationY = wanted
            state.lastLiveShopY = card.translationY
        }
        if (state.logLiveShopPin) {
            state.logLiveShopPin = false
            log("live shop card pinned ${describe(card)} lift=${LIVE_SHOP_CARD_LIFT_PX}px " +
                "after=${screenBounds(card)} translationY=${card.translationY}")
        }
    }

    private fun releaseLiveShopCard(state: ChatDiscoveryState) {
        state.liveShopCard?.let { card ->
            state.liveShopAttachListener?.let(card::removeOnAttachStateChangeListener)
            if (card.translationY == state.lastLiveShopY) {
                card.translationY = state.originalLiveShopY
            }
        }
        for (saved in state.liveShopClips) {
            saved.group.clipChildren = saved.clipChildren
            saved.group.clipToPadding = saved.clipToPadding
        }
        state.liveShopClips.clear()
        state.liveShopCard = null
        state.liveShopAnchor = null
        state.liveShopAttachListener = null
        state.lastLiveShopSearchAt = 0L
        state.liveShopNeedsSearch = true
    }

    private fun isLiveShopOnScreen(state: ChatDiscoveryState, card: ViewGroup): Boolean {
        state.root.getLocationOnScreen(state.liveShopRootPosition)
        card.getLocationOnScreen(state.liveShopPosition)
        val x = state.liveShopPosition[0]
        val y = state.liveShopPosition[1]
        val rootX = state.liveShopRootPosition[0]
        val rootBottom = state.liveShopRootPosition[1] + state.root.height
        return card.width > state.root.width / 2 && x >= rootX &&
            x + card.width <= rootX + state.root.width && y > 2200 && y + card.height <= rootBottom
    }

    /** Open only ancestors whose viewport would crop the translated card. Keep its own outline. */
    private fun allowLiveShopOverflow(state: ChatDiscoveryState, card: ViewGroup, wantedY: Float) {
        card.getLocationOnScreen(state.liveShopPosition)
        val top = state.liveShopPosition[1] + wantedY - card.translationY
        val bottom = top + card.height
        val left = state.liveShopPosition[0]
        val right = left + card.width
        var parent = card.parent as? ViewGroup
        while (parent != null) {
            parent.getLocationOnScreen(state.liveShopParentPosition)
            val x = state.liveShopParentPosition[0]
            val y = state.liveShopParentPosition[1]
            if (top < y + parent.paddingTop || bottom > y + parent.height - parent.paddingBottom ||
                left < x + parent.paddingLeft || right > x + parent.width - parent.paddingRight) {
                val group = parent
                if (state.liveShopClips.none { it.group === group }) {
                    state.liveShopClips.add(CommentClipState(group, group.clipChildren, group.clipToPadding))
                    log("live shop card unclip parent=${describe(group)} bounds=${screenBounds(group)} " +
                        "clipChildren=${group.clipChildren} clipToPadding=${group.clipToPadding} " +
                        "clipBounds=${group.clipBounds} clipToOutline=${group.clipToOutline} targetTop=$top")
                }
                if (group.clipChildren) group.clipChildren = false
                if (group.clipToPadding) group.clipToPadding = false
            }
            if (parent === state.root) break
            parent = parent.parent as? ViewGroup
        }
    }

    /** Snapshot the visible tree once, then choose by anchor priority, not traversal order. */
    private fun findLiveShopCard(state: ChatDiscoveryState, now: Long): Pair<View, ViewGroup>? {
        val candidates = state.liveShopCandidates
        val seen = state.liveShopSeen
        candidates.fill(null)
        seen.fill(0)
        val diagnostic = !state.liveShopMissLogged || now - state.lastLiveShopMissAt >= LIVE_SHOP_MISS_INTERVAL_MS
        var firstRejected = "none"
        val pending = state.liveShopSearchStack
        pending.clear()
        pending.add(state.root)
        while (pending.isNotEmpty()) {
            val view = pending.removeAt(pending.size - 1)
            if (!view.isShown) continue
            var name = state.liveShopResourceNames[view.id]
            if (name == null && view.id != View.NO_ID && view.id ushr 24 != 0) {
                name = try {
                    view.resources.getResourceEntryName(view.id)
                } catch (e: android.content.res.Resources.NotFoundException) {
                    logOnce("live-shop-id-${view.id}", "live shop resource unresolved id=${view.id}: $e")
                    ""
                }
                state.liveShopResourceNames.put(view.id, name)
            }
            val priority = LIVE_SHOP_ANCHORS.indexOf(name)
            if (priority >= 0) {
                seen[priority]++
                var parent = view.parent as? ViewGroup
                while (parent != null && parent !== state.root) {
                    parent.getLocationOnScreen(state.liveShopPosition)
                    if (parent.height in 290..330 && state.liveShopPosition[1] > 2200) {
                        if (isLiveShopOnScreen(state, parent)) {
                            if (candidates[priority] == null) candidates[priority] = view to parent
                        } else if (diagnostic) {
                            firstRejected = "$name offscreen=${screenBounds(parent)}"
                            logOnce("live-shop-offscreen-${System.identityHashCode(parent)}",
                                "live shop card reject $firstRejected root=${screenBounds(state.root)}")
                        }
                        break
                    }
                    if (diagnostic && firstRejected == "none") {
                        firstRejected = "$name parent=${describe(parent)} bounds=${screenBounds(parent)}"
                    }
                    parent = parent.parent as? ViewGroup
                }
            }
            if (view is ViewGroup) {
                // Reuse one flat snapshot buffer instead of allocating one array per parent.
                for (index in view.childCount - 1 downTo 0) {
                    view.getChildAt(index)?.let(pending::add)
                }
            }
            if (candidates[0] != null) break
        }
        if (diagnostic) {
            state.liveShopSearchDetail = "anchors=" + LIVE_SHOP_ANCHORS.indices.joinToString {
                "${LIVE_SHOP_ANCHORS[it]}:${seen[it]}"
            } + " firstRejected=$firstRejected"
        }
        val result = candidates.firstOrNull { it != null }
        candidates.fill(null)
        pending.clear()
        return result
    }

    private fun findChatStrip(root: ViewGroup): ViewGroup? {
        val id = root.resources.getIdentifier("ybh", "id", TARGET_PACKAGE)
        if (id == 0) return null
        val strip = root.findViewById<View>(id) as? ViewGroup ?: return null
        return if (strip.isShown && strip.height > 0 && strip.width > 0) strip else null
    }

    private fun findVisibleChatDock(root: ViewGroup, state: ChatDiscoveryState): ViewGroup? {
        if (state.chatDockId == 0) {
            state.chatDockId = root.resources.getIdentifier("jak", "id", TARGET_PACKAGE)
        }
        if (state.chatDockId == 0) return null
        fun visit(view: View): ViewGroup? {
            if (!view.isShown) return null
            if (view is ViewGroup) {
                if (view.id == state.chatDockId && view.width > 0 && view.height > 0) return view
                for (index in 0 until view.childCount) {
                    visit(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        return visit(root)
    }

    private fun bindChatStrip(state: ChatDiscoveryState, strip: ViewGroup) {
        if ((0 until strip.childCount).none { strip.getChildAt(it).height > 0 }) return
        state.chatStrip = strip
        state.originalStripTranslationY = strip.translationY
        state.originalStripAlpha = strip.alpha
        if (state.stripReturnPending && !isImeVisible(state.root)) {
            state.stripReturnPending = false
            val scale = android.provider.Settings.Global.getFloat(strip.context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            state.stripEnterDuration = (220L * scale).toLong()
            state.stripEnterStarted = if (state.stripEnterDuration > 0L) SystemClock.uptimeMillis() else 0L
            log("chat shortcut return motion started duration=${state.stripEnterDuration}ms")
        }
        pinChatStrip(state)
    }

    private fun pinChatStrip(state: ChatDiscoveryState) {
        if (!CHAT_STRIP_CENTER) return
        val strip = state.chatStrip ?: return
        val parent = strip.parent as? ViewGroup ?: return
        if (!strip.isAttachedToWindow || !strip.isShown || strip.isLayoutRequested) return
        // IME transitions replace native chips; never position from a detached child.
        val chip = (0 until strip.childCount).map { strip.getChildAt(it) }
            .firstOrNull { it.isShown && it.height > 0 } ?: return
        val wanted = (parent.height - chip.height) / 2f - CHAT_STRIP_EXTRA_LIFT_PX - strip.top - chip.top
        var offset = 0f
        if (state.stripEnterStarted != 0L) {
            val fraction = ((SystemClock.uptimeMillis() - state.stripEnterStarted).toFloat() /
                state.stripEnterDuration).coerceIn(0f, 1f)
            // Fade and lift together; never move the dock or the navigation surface.
            val remaining = (1f - fraction) * (1f - fraction) * (1f - fraction)
            offset = 10f * strip.resources.displayMetrics.density * remaining
            strip.alpha = state.originalStripAlpha * (1f - remaining)
            state.stripAlphaOwned = true
            if (fraction < 1f) strip.postInvalidateOnAnimation()
            else state.stripEnterStarted = 0L
        }
        val animatedY = wanted + offset
        if (kotlin.math.abs(wanted - state.originalStripTranslationY) <= CHAT_STRIP_PIN_TOLERANCE_PX &&
            strip.translationY != animatedY) strip.translationY = animatedY
    }
    private fun releaseChatStrip(state: ChatDiscoveryState) {
        state.chatStrip?.let {
            it.translationY = state.originalStripTranslationY
            if (state.stripAlphaOwned) it.alpha = state.originalStripAlpha
        }
        state.stripAlphaOwned = false
        state.stripEnterStarted = 0L
        state.chatStrip = null
    }

    private fun stopChatDiscovery(state: ChatDiscoveryState) {
        val listener = state.listener ?: return
        val observer = state.root.viewTreeObserver
        if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        state.listener = null
    }

    private fun findTargets(root: ViewGroup): FoundTargets {
        val found = HashMap<String, View>(4)
        collectChatViews(root, found)
        // Douyin swaps msg_et for microphone_btn_new in voice mode. Both belong
        // to the same jak dock; recognize either so chat stays scoped correctly.
        val composer = found["msg_et"] ?: found["microphone_btn_new"]
        val shortcuts = found["ybh"]
        val namedDock = found["jak"] as? ViewGroup
        val dockVisible = namedDock != null && namedDock.isShown && namedDock.height > 0 && namedDock.width > 0
        val dock = if (!dockVisible) null else when {
            composer != null && shortcuts != null && isDescendantOf(composer, namedDock!!) && isDescendantOf(shortcuts, namedDock) -> namedDock
            composer != null && shortcuts != null -> lowestCommonViewGroup(composer, shortcuts)
            else -> null
        }
        if (dock != null) {
            val message = "chat dock matched ${describe(dock)} bounds=${screenBounds(dock)} " +
                "composer=${composer?.let(::describe) ?: "missing"} shortcuts=${shortcuts?.let(::describe) ?: "missing"}"
            logOnce("chat-target-${System.identityHashCode(dock)}", message)
        } else if (composer != null || shortcuts != null || namedDock != null) {
            logOnce("chat-parent-missing", "chat controls partial: jak=${namedDock?.let(::describe) ?: "missing"} " +
                "composer=${composer?.let(::describe) ?: "missing"} shortcuts=${shortcuts?.let(::describe) ?: "missing"}")
        }
        return FoundTargets(dock, found["cvc"] as? ViewGroup)
    }

    private fun collectChatViews(view: View, found: MutableMap<String, View>) {
        if (!view.isShown) return
        val name = idName(view)
        if (name == "jak" || name == "msg_et" || name == "microphone_btn_new" || name == "ybh") found.putIfAbsent(name, view)
        // Resource names change between Douyin releases (cvc -> cvl in 40.8).
        // Match the stable native host type; never search arbitrary root_view IDs.
        if (name == "cvc" || view.javaClass.name == HOME_TAB_HOST_CLASS) found.putIfAbsent("cvc", view)
        if (found.size >= 5 || view !is ViewGroup) return
        for (i in 0 until view.childCount) {
            collectChatViews(view.getChildAt(i), found)
            if (found.size >= 5) return
        }
    }

    private fun bindHomeBar(state: ChatDiscoveryState, bar: ViewGroup) {
        if (state.homeSafeArea != null) return
        state.homeSafeArea = HomeTabSafeArea.bind(state.root, bar) {
            state.homeSafeArea = null
            if (state.root.isAttachedToWindow) startChatDiscovery(state)
        }
    }

    private fun bindChatDock(state: ChatDiscoveryState, dock: ViewGroup, imeVisible: Boolean) {
        if (state.target === dock) return
        if (state.target != null) releaseChatDock(state)
        state.target = dock
        state.lastDockLogFlags = -1
        state.lastViewportSearchAt = -CHAT_DOCK_SEARCH_INTERVAL_MS
        state.viewportPaddingLogFlags = -1
        val liftTarget = liftTargetFor(dock)
        state.liftTarget = liftTarget
        state.chatGeometry = ChatDockGeometry(state.root, liftTarget)
        state.entryLayoutReady = false
        state.entryDeferredFrames = 0
        state.entryLayoutStarted = SystemClock.uptimeMillis()
        state.messageViewport = findMessageViewport(state.root, dock)
        state.messageViewport?.let { viewport ->
            state.originalViewportPaddingBottom = ChatListInsets.original(viewport)
            logOnce("chat-viewport-${System.identityHashCode(viewport)}", "chat message viewport ${describe(viewport)} " +
                "bounds=${screenBounds(viewport)} originalPaddingBottom=${viewport.paddingBottom}; " +
                "IME reserve target=${NAV_FALLBACK_PX}px")
        }
        state.attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (state.target === v) {
                    // Keep the lift during a temporary detach/rebuild. The
                    // resident pre-draw listener replaces or releases this dock.
                    if (state.hiddenSince == 0L) state.hiddenSince = SystemClock.uptimeMillis()
                }
            }
        }
        dock.addOnAttachStateChangeListener(state.attachListener)
        // The attachment picker expands above and repositions jak itself.
        // Do not apply our extra lift while its recent-photo strip is open.
        val pickerId = dock.resources.getIdentifier("uku", "id", TARGET_PACKAGE)
        if (pickerId != 0) {
            state.pickerId = pickerId
            state.pickerView = state.root.findViewById<View>(pickerId)
            bindPanelToggleButton(state, dock)
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                state.pickerView = state.root.findViewById<View>(pickerId)
                bindPanelToggleButton(state, dock)
                syncChatDock(state, isImeVisible(state.root))
            }
            state.pickerListener = listener
            state.root.viewTreeObserver.takeIf { it.isAlive }?.addOnGlobalLayoutListener(listener)
        }
        syncChatDock(state, imeVisible)
    }

    private fun bindPanelToggleButton(state: ChatDiscoveryState, dock: ViewGroup) {
        val cached = state.panelToggleButton
        if (cached != null && cached.isAttachedToWindow && cached.isShown && isDescendantOf(cached, dock)) return
        if (state.panelButtonsId == 0) {
            state.panelButtonsId = dock.resources.getIdentifier("x0y", "id", TARGET_PACKAGE)
            state.panelButtonId = dock.resources.getIdentifier("mxo", "id", TARGET_PACKAGE)
        }
        if (state.panelButtonsId == 0 || state.panelButtonId == 0) return
        val buttons = dock.findViewById<ViewGroup>(state.panelButtonsId)
        fun hasPanelDescription(view: View): Boolean {
            val description = view.contentDescription?.toString()
            if (description == "更多面板" || description == "关闭面板") return true
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (hasPanelDescription(view.getChildAt(index))) return true
                }
            }
            return false
        }
        fun findButton(view: View): View? {
            if (view.id == state.panelButtonId && hasPanelDescription(view)) return view
            if (view is ViewGroup) {
                for (index in view.childCount - 1 downTo 0) {
                    findButton(view.getChildAt(index))?.let { return it }
                }
            }
            return null
        }
        val button = buttons?.let { findButton(it) }
        if (state.panelToggleButton === button) return
        state.panelToggleButton?.let { previous ->
            synchronized(PANEL_BUTTON_STATES) { PANEL_BUTTON_STATES.remove(previous) }
        }
        state.panelToggleButton = button
        if (button != null) {
            synchronized(PANEL_BUTTON_STATES) { PANEL_BUTTON_STATES[button] = state }
            logOnce("panel-toggle-${System.identityHashCode(button)}",
                "chat attachment toggle bound ${describe(button)}")
        }
    }

    private fun releaseChatDock(state: ChatDiscoveryState) {
        val dock = state.target ?: return
        UnifiedChatBridge.release(state.root, dock)
        releaseChatSuggestion(state)
        state.suggestionOverlayHost = null
        state.chatGeometry?.release()
        state.chatGeometry = null
        restoreViewportBounds(state)
        state.pickerListener?.let { listener ->
            state.root.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(listener)
        }
        state.attachListener?.let(dock::removeOnAttachStateChangeListener)
        state.pickerListener = null
        state.attachListener = null
        state.target = null
        state.liftTarget = null
        state.messageViewport = null
        state.pickerId = 0
        state.pickerView = null
        state.pickerOpen = false
        state.panelToggleButton?.let { button ->
            synchronized(PANEL_BUTTON_STATES) { PANEL_BUTTON_STATES.remove(button) }
        }
        state.panelToggleButton = null
        // Keep the IME hold across dock rebuilds during the same keyboard close.
        state.hiddenSince = 0L
        state.lastDockSearchAt = 0L
    }

    /** Prefer an exactly overlapping wrapper so moving the dock does not clip its top children. */
    private fun liftTargetFor(dock: ViewGroup): ViewGroup {
        val parent = dock.parent as? ViewGroup ?: return dock
        val dockPosition = IntArray(2)
        val parentPosition = IntArray(2)
        dock.getLocationOnScreen(dockPosition)
        parent.getLocationOnScreen(parentPosition)
        return if (
            parent.width == dock.width && parent.height == dock.height &&
            parentPosition[0] == dockPosition[0] && parentPosition[1] == dockPosition[1]
        ) parent else dock
    }

    private fun syncChatDock(state: ChatDiscoveryState, imeVisible: Boolean) {
        val target = state.target ?: return
        val liftTarget = state.liftTarget ?: return
        updatePickerForImeSession(state, imeVisible)
        val pickerOpen = state.pickerOpen
        // The missing navigation inset also needs compensation above the IME.
        // Keep the same lift throughout keyboard opening and closing.
        val readOnly = NativeChatRegistry.containing(target)?.let { NativeChatRegistry.disabledBar(it) } != null
        val geometry = state.chatGeometry ?: return
        if (geometry.sync(imeVisible, pickerOpen, readOnly)) {
            val flags = (if (imeVisible) 1 else 0) or (if (pickerOpen) 2 else 0)
            if (state.lastDockLogFlags != flags) {
                state.lastDockLogFlags = flags
                log("chat dock geometry ${describe(target)} reserve=${geometry.reserve} " +
                    "translationY=${geometry.originalTranslation}->${geometry.appliedTranslation} " +
                    "ime=$imeVisible picker=$pickerOpen readOnly=$readOnly")
            }
        }
        syncMessageViewportBounds(state, imeVisible, pickerOpen)
        UnifiedChatBridge.publish(state.root, target, liftTarget, state.messageViewport, imeVisible, pickerOpen)
    }

    private fun syncMessageViewportBounds(state: ChatDiscoveryState, imeVisible: Boolean, pickerOpen: Boolean) {
        val dock = state.target ?: return
        var viewport = state.messageViewport
        if (viewport == null || !viewport.isAttachedToWindow || !viewport.isShown) {
            val now = SystemClock.uptimeMillis()
            if (state.entryLayoutReady && now - state.lastViewportSearchAt < CHAT_DOCK_SEARCH_INTERVAL_MS) return
            state.lastViewportSearchAt = now
            val replacement = findMessageViewport(state.root, dock)
            if (replacement !== viewport) {
                restoreViewportBounds(state)
                state.messageViewport = replacement
                state.originalViewportPaddingBottom = replacement?.let(ChatListInsets::original) ?: 0
                state.viewportPaddingLogFlags = -1
            }
            viewport = replacement
        }
        viewport ?: return
        val reserve = state.chatGeometry?.reserve ?: 0
        val desiredPadding = ChatListInsets.apply(viewport, reserve)
        val flags = (if (imeVisible) 1 else 0) or (if (pickerOpen) 2 else 0)
        if (state.viewportPaddingLogFlags != flags || state.viewportLogPadding != desiredPadding) {
            state.viewportPaddingLogFlags = flags
            state.viewportLogPadding = desiredPadding
            log("chat message measured reserve applied ${describe(viewport)} bounds=${screenBounds(viewport)} " +
                "original=${state.originalViewportPaddingBottom} actual=${viewport.paddingBottom} " +
                "reserve=$reserve imeVisible=$imeVisible atBottom=${!viewport.canScrollVertically(1)}")
        }
    }

    private fun updatePickerForImeSession(state: ChatDiscoveryState, imeVisible: Boolean) {
        val dock = state.target ?: return
        val picker = state.pickerView
        val rawShown = picker != null && picker.isAttachedToWindow && picker.isShown &&
            picker.width > 0 && picker.height > 0
        var settled = false
        if (state.imeGate.holdLift && !imeVisible && !rawShown && dock.isShown) {
            val originalBottom = state.chatGeometry?.nativeBottom() ?: return
            settled = kotlin.math.abs(originalBottom - state.root.height) <= DOCK_SETTLED_TOLERANCE_PX
        }
        val pickerOpen = state.imeGate.update(imeVisible, rawShown, settled)
        state.pickerOpen = pickerOpen
        val flags = (if (rawShown) 1 else 0) or (if (pickerOpen) 2 else 0) or
            (if (state.imeGate.holdLift) 4 else 0) or (if (state.imeGate.panelRequested) 8 else 0)
        if (state.pickerLogFlags != flags) {
            state.pickerLogFlags = flags
            log("chat picker rawShown=$rawShown confirmedOpen=$pickerOpen " +
                "imeLiftHeld=${state.imeGate.holdLift} userRequested=${state.imeGate.panelRequested} " +
                "imeVisible=$imeVisible")
        }
    }

    private fun restoreViewportBounds(state: ChatDiscoveryState) {
        val viewport = state.messageViewport ?: return
        ChatListInsets.restore(viewport)
    }

    /** The small v6m inside 14i/pk3 is a suggestion row, not the message list. */
    private fun findChatSuggestion(state: ChatDiscoveryState): Pair<ViewGroup, ViewGroup>? {
        if (!state.suggestionIdsResolved) {
            val resources = state.root.resources
            state.suggestionOverlayId = resources.getIdentifier("14i", "id", TARGET_PACKAGE)
            state.suggestionPanelId = resources.getIdentifier("pk3", "id", TARGET_PACKAGE)
            state.suggestionRowId = resources.getIdentifier("v6m", "id", TARGET_PACKAGE)
            state.suggestionItemId = resources.getIdentifier("n-w", "id", TARGET_PACKAGE)
            state.suggestionScopeId = resources.getIdentifier("jq7", "id", TARGET_PACKAGE)
            state.suggestionIdsResolved = true
        }
        if (state.suggestionOverlayId == 0 || state.suggestionPanelId == 0 || state.suggestionRowId == 0) return null
        var scope = state.root
        var ancestor: View? = state.messageViewport
        while (ancestor != null) {
            if (state.suggestionScopeId != 0 && ancestor.id == state.suggestionScopeId) {
                scope = ancestor
                break
            }
            ancestor = ancestor.parent as? View
        }
        fun visit(view: View, insideOverlay: Boolean): Pair<ViewGroup, ViewGroup>? {
            if (!view.isShown || view !is ViewGroup) return null
            val overlay = insideOverlay || view.id == state.suggestionOverlayId
            if (view.id == state.suggestionOverlayId) state.suggestionOverlayHost = view
            if (overlay && view.id == state.suggestionPanelId && view.width > 0 && view.height > 0) {
                val row = view.findViewById<View>(state.suggestionRowId) as? ViewGroup
                if (row != null && row.isShown && row.width > 0 && row.height > 0) {
                    val itemMatches = (0 until row.childCount).any { index ->
                        val item = row.getChildAt(index)
                        item.height > 0 && (if (state.suggestionItemId != 0) {
                            item.id == state.suggestionItemId
                        } else idName(item) == "n-w")
                    }
                    if (itemMatches) return view to row
                }
            }
            for (index in 0 until view.childCount) {
                visit(view.getChildAt(index), overlay)?.let { return it }
            }
            return null
        }
        val cachedOverlay = state.suggestionOverlayHost
        if (cachedOverlay != null && cachedOverlay.isAttachedToWindow && cachedOverlay.isShown) {
            return visit(cachedOverlay, false)
        }
        state.suggestionOverlayHost = null
        return visit(scope, false)
    }

    private fun syncChatSuggestion(state: ChatDiscoveryState, now: Long) {
        val dock = state.target ?: return
        if (state.suggestionPanel == null && !isImeVisible(state.root) && !state.imeGate.holdLift) return
        val previousPanel = state.suggestionPanel
        val previousRow = state.suggestionRow
        if (previousPanel != null && (previousRow == null || !previousPanel.isAttachedToWindow ||
                !previousPanel.isShown || !previousRow.isAttachedToWindow || !previousRow.isShown ||
                !isDescendantOf(previousRow, previousPanel))) {
            releaseChatSuggestion(state)
        }
        if (state.suggestionPanel == null && now - state.lastSuggestionSearchAt >= CHAT_SUGGESTION_SEARCH_INTERVAL_MS) {
            state.lastSuggestionSearchAt = now
            val found = findChatSuggestion(state)
            if (found != null) {
                state.suggestionPanel = found.first
                state.suggestionRow = found.second
                state.originalSuggestionTranslationY = found.first.translationY
                state.lastSuggestionTranslationY = found.first.translationY
                state.suggestionJustBound = true
            }
        }
        val panel = state.suggestionPanel ?: return
        val row = state.suggestionRow ?: return
        if (panel.height == 0 || row.height == 0) return
        dock.getLocationOnScreen(state.suggestionDockPosition)
        row.getLocationOnScreen(state.suggestionRowPosition)
        val dockTop = state.suggestionDockPosition[1]
        val rowBottom = state.suggestionRowPosition[1] + row.height
        val delta = (dockTop - rowBottom).toFloat()
        if (delta != 0f && kotlin.math.abs(delta) <= CHAT_SUGGESTION_ALIGNMENT_TOLERANCE_PX) {
            // Move pk3 as one unit; its large 14i parent has room above it.
            // Translating just the RecyclerView would clip against pk3's top.
            panel.translationY += delta
            state.lastSuggestionTranslationY = panel.translationY
        }
        if (state.suggestionJustBound) {
            state.suggestionJustBound = false
            log("chat suggestion bound ${describe(panel)} row=${describe(row)} " +
                "rowBottomBefore=$rowBottom dockTop=$dockTop rowAfter=${screenBounds(row)} " +
                "panelTranslation=${state.originalSuggestionTranslationY}->${panel.translationY}")
        }
    }

    private fun releaseChatSuggestion(state: ChatDiscoveryState) {
        state.suggestionPanel?.let { panel ->
            if (panel.translationY == state.lastSuggestionTranslationY) {
                panel.translationY = state.originalSuggestionTranslationY
            }
        }
        state.suggestionPanel = null
        state.suggestionRow = null
        state.suggestionJustBound = false
    }

    private fun isImeVisible(root: View): Boolean {
        val insets = root.rootWindowInsets ?: return false
        return Build.VERSION.SDK_INT >= 30 && insets.isVisible(WindowInsets.Type.ime())
    }

    /** Find the scrollable chat history ending next to the dock, without touching other screens. */
    private fun findMessageViewport(root: View, dock: View): ViewGroup? {
        val chatScope = NativeChatRegistry.containing(dock) ?: return null
        val candidates = ArrayList<ViewGroup>()
        val suggestionPanelId = root.resources.getIdentifier("pk3", "id", TARGET_PACKAGE)
        val suggestionOverlayId = root.resources.getIdentifier("14i", "id", TARGET_PACKAGE)
        fun walk(view: View) {
            if (!view.isShown || (suggestionPanelId != 0 && view.id == suggestionPanelId) ||
                (suggestionOverlayId != 0 && view.id == suggestionOverlayId)) return
            if (view is ViewGroup) {
                val name = view.javaClass.name.lowercase()
                val scrollLike = name.contains("recyclerview") || name.contains("scrollview") || name.contains("listview") ||
                    (view.isNestedScrollingEnabled && (view.canScrollVertically(-1) || view.canScrollVertically(1)))
                if (scrollLike && view.height >= root.height / 3 && !isDescendantOf(view, dock) && !isDescendantOf(dock, view)) {
                    candidates.add(view)
                }
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }
        walk(chatScope)
        if (candidates.isEmpty()) return null
        // Prefer the actual message RecyclerView under MeasureOnceRelativeLayout2.
        // The identically named v6m in 14i/pk3 was excluded above.
        val messageLists = candidates.filter { candidate ->
            if (idName(candidate) != "v6m") return@filter false
            var ancestor = candidate.parent as? View
            while (ancestor != null) {
                if (ancestor.javaClass.name.contains("MeasureOnceRelativeLayout2")) return@filter true
                ancestor = ancestor.parent as? View
            }
            candidate.javaClass.name.contains("CustomRecyclerView")
        }
        val dockPos = IntArray(2)
        dock.getLocationOnScreen(dockPos)
        return messageLists.filter { it.javaClass.name == "com.ss.android.ugc.aweme.im.widget.CustomRecyclerView" }.minByOrNull { candidate ->
            val p = IntArray(2)
            candidate.getLocationOnScreen(p)
            kotlin.math.abs((p[1] + candidate.height) - dockPos[1])
        }
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    private fun lowestCommonViewGroup(first: View, second: View): ViewGroup? {
        val ancestors = HashSet<View>()
        var v: View? = first
        while (v != null) { ancestors.add(v); v = v.parent as? View }
        v = second
        while (v != null) {
            if (v is ViewGroup && v in ancestors) return v
            v = v.parent as? View
        }
        return null
    }

    private fun idName(view: View): String = try {
        if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
    } catch (_: Throwable) { "" }

    private fun describe(view: View): String = "class=${view.javaClass.name} id=${idName(view)}"

    private fun screenBounds(view: View): String {
        val p = IntArray(2)
        view.getLocationOnScreen(p)
        return "[${p[0]},${p[1]}][${p[0] + view.width},${p[1] + view.height}]"
    }

    private fun log(message: String) {
        Log.w(TAG, message)
        XposedBridge.log("[$TAG] $message")
    }

    private fun logOnce(key: String, message: String) {
        synchronized(LOGGED_MESSAGES) {
            if (!LOGGED_MESSAGES.add(key)) return
        }
        log(message)
    }

    /** Independent of animation duration, IME target visibility, and dock rebuilds. */
    private class ChatImeGate {
        var holdLift: Boolean = false
            private set
        var panelRequested: Boolean = false
            private set
        private var lastImeVisible: Boolean = false

        fun observeIme() {
            holdLift = true
        }

        fun onPanelToggle(panelOpen: Boolean, imeVisible: Boolean) {
            if (imeVisible) lastImeVisible = true
            panelRequested = !panelOpen
        }

        fun update(imeVisible: Boolean, rawPanelShown: Boolean, dockSettled: Boolean): Boolean {
            if (imeVisible) {
                holdLift = true
                // Returning to text input invalidates an older panel request.
                if (!lastImeVisible) panelRequested = false
            }
            lastImeVisible = imeVisible
            if (holdLift && !imeVisible && !rawPanelShown && dockSettled) {
                holdLift = false
                panelRequested = false
            }
            return !imeVisible && rawPanelShown && (!holdLift || panelRequested)
        }
    }

    private data class CommentContainerSize(val group: ViewGroup, val originalHeight: Int, val appliedHeight: Int)

    private data class CommentClipState(val group: ViewGroup, val clipChildren: Boolean, val clipToPadding: Boolean)

    private class CommentInputState(val root: ViewGroup) {
        var bottomHost: ViewGroup? = null
        val rowContainerSizes = ArrayList<CommentContainerSize>(3)
        var rowId = 0
        var editId = 0
        var bottomId = 0
        var outerId = 0
        var innerId = 0
        var imeOuterId = 0
        var imeInnerId = 0
        var imeContentId = 0
        var imeContentParentId = 0
        var imeIconsId = 0
        var imeWrapper: ViewGroup? = null
        var imeContent: ViewGroup? = null
        var originalImeTranslationY = 0f
        var lastImeTranslationY = 0f
        var imeNeedsSearch = true
        var imeLogNextPin = false
        var lastImeSearchAt = 0L
        val imeContentPosition = IntArray(2)
        val imeClipStates = ArrayList<CommentClipState>()
        var row: ViewGroup? = null
        var originalTranslationY = 0f
        var lastAppliedTranslationY = 0f
        var logNextPin = false
        var needsSearch = true
        var lastSearchAt = 0L
        val rootPosition = IntArray(2)
        val rowPosition = IntArray(2)
        val clipParentPosition = IntArray(2)
        val clipStates = ArrayList<CommentClipState>()
        var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
        var pinListener: ViewTreeObserver.OnPreDrawListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
    }

    private class ChatDiscoveryState(val root: View, val activityName: String) {
        var entryLayoutReady = false
        var entryLayoutStarted = 0L
        var entryDeferredFrames = 0
        var videoInputEnabled = false
        var videoInputActive = true
        var videoInput: ViewGroup? = null
        var videoOverlays: VideoBottomOverlays? = null
        var structureDirty = true
        var dedicatedBottomPage = false
        val liveShopCandidates = arrayOfNulls<Pair<View, ViewGroup>>(5)
        val liveShopSeen = IntArray(5)
        val liveShopSearchStack = ArrayList<View>()
        var lastDockLogFlags = -1
        var lastViewportSearchAt = 0L
        var liveShopEnabled = false
        var liveShopNeedsSearch = true
        var liveShopMissLogged = false
        var lastLiveShopMissAt = 0L
        var liveShopSearchDetail = ""
        val liveShopResourceNames = android.util.SparseArray<String>()
        val liveShopRootPosition = IntArray(2)
        val liveShopParentPosition = IntArray(2)
        val liveShopClips = ArrayList<CommentClipState>()
        var liveShopAnchor: View? = null
        var liveShopCard: ViewGroup? = null
        var originalLiveShopY = 0f
        var lastLiveShopY = 0f
        var logLiveShopPin = false
        val liveShopPosition = IntArray(2)
        var lastLiveShopSearchAt = 0L
        var liveShopAttachListener: View.OnAttachStateChangeListener? = null
        var liveActive = true
        var liveInputId = 0
        var liveActionsId = 0
        var liveDanmakuId = 0
        var liveDanmaku: View? = null
        var originalLiveDanmakuY = 0f
        var lastLiveDanmakuSearchAt = 0L
        var liveLeft: ViewGroup? = null
        var liveRight: ViewGroup? = null
        var originalLiveLeftY = 0f
        var originalLiveRightY = 0f
        var liveShift = 0f
        var lastLiveSearchAt = 0L
        var listener: ViewTreeObserver.OnGlobalLayoutListener? = null
        var target: ViewGroup? = null
        var liftTarget: ViewGroup? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        var chatGeometry: ChatDockGeometry? = null
        val chatFloating = ChatFloatingGeometry()
        var pinListener: ViewTreeObserver.OnPreDrawListener? = null
        var pickerId: Int = 0
        var pickerView: View? = null
        var panelToggleButton: View? = null
        var panelButtonsId: Int = 0
        var panelButtonId: Int = 0
        val imeGate = ChatImeGate()
        var pickerLogFlags: Int = -1
        var pickerOpen: Boolean = false
        var pickerListener: ViewTreeObserver.OnGlobalLayoutListener? = null
        var lastSearchAt: Long = 0
        var lastPinSearchAt: Long = 0
        var lastDockSearchAt: Long = 0
        var hiddenSince: Long = 0L
        var chatDockId: Int = 0
        var messageViewport: ViewGroup? = null
        var originalViewportPaddingBottom: Int = 0
        var viewportPaddingLogFlags = -1
        var viewportLogPadding = -1
        var homeSafeArea: HomeTabSafeArea? = null
        var chatStrip: ViewGroup? = null
        var originalStripTranslationY: Float = 0f
        var originalStripAlpha = 1f
        var stripAlphaOwned = false
        var stripReturnPending = false
        var stripEnterStarted = 0L
        var stripEnterDuration = 220L
        var lastStripSearchAt: Long = 0
        var suggestionPanel: ViewGroup? = null
        var suggestionOverlayHost: ViewGroup? = null
        var suggestionRow: ViewGroup? = null
        var originalSuggestionTranslationY: Float = 0f
        var lastSuggestionTranslationY: Float = 0f
        var suggestionJustBound: Boolean = false
        var lastSuggestionSearchAt: Long = 0
        var suggestionIdsResolved: Boolean = false
        var suggestionOverlayId: Int = 0
        var suggestionPanelId: Int = 0
        var suggestionRowId: Int = 0
        var suggestionItemId: Int = 0
        var suggestionScopeId: Int = 0
        val suggestionDockPosition = IntArray(2)
        val suggestionRowPosition = IntArray(2)
    }

    private data class FoundTargets(val chatDock: ViewGroup?, val homeBar: ViewGroup?)

    companion object {
        private const val TARGET_PACKAGE = "com.ss.android.ugc.aweme"
        private const val TAG = "DouyinImmersiveNav"
        private const val NAV_FALLBACK_PX = 56
        private const val LIVE_SHOP_CARD_LIFT_PX = 24
        private const val LIVE_SHOP_MISS_INTERVAL_MS = 5000L
        private val LIVE_SHOP_ANCHORS = listOf("by", "iv_close", "tv_title", "wwd", "3of")
        private const val LIVE_SEARCH_INTERVAL_MS = 100L
        private const val COMMENT_SEARCH_INTERVAL_MS = 100L
        private const val COMMENT_PIN_TOLERANCE_PX = 80
        private const val COMMENT_BOTTOM_GAP_PX = 12
        private const val COMMENT_IME_PIN_TOLERANCE_PX = 240
        private const val CHAT_DOCK_SEARCH_INTERVAL_MS = 100L
        private const val DOCK_RELEASE_GRACE_MS = 500L
        private const val DOCK_SETTLED_TOLERANCE_PX = 2
        private const val CHAT_SUGGESTION_SEARCH_INTERVAL_MS = 100L
        private const val CHAT_SUGGESTION_ALIGNMENT_TOLERANCE_PX = 160
        private const val HOME_TAB_HOST_CLASS = "com.ss.android.ugc.aweme.homepage.tab.bottom.ui.tabbar.container.MainBottomTabContainer"
        private const val HOME_SEARCH_INTERVAL_MS = 100L
        private const val CHAT_STRIP_CENTER = true
        private const val CHAT_STRIP_EXTRA_LIFT_PX = 0
        private const val CHAT_STRIP_PIN_TOLERANCE_PX = 120
        private const val CHAT_STRIP_SEARCH_INTERVAL_MS = 100L
        private const val SEARCH_INTERVAL_MS = 350L
        private const val IDLE_SEARCH_INTERVAL_MS = 1000L
        private val LOGGED_MESSAGES = HashSet<String>()
        private val ACTIVITY_STATES = WeakHashMap<View, ChatDiscoveryState>()
        private val PANEL_BUTTON_STATES = WeakHashMap<View, ChatDiscoveryState>()
        private val COMMENT_STATES = WeakHashMap<View, CommentInputState>()
    }
}
