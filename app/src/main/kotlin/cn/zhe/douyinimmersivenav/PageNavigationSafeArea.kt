package cn.zhe.douyinimmersivenav

import android.os.Build
import android.os.SystemClock
import android.graphics.drawable.GradientDrawable
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.Window
import android.widget.EditText
import java.util.WeakHashMap

/** Backgrounds remain edge-to-edge; reserve space only in native content hosts. */
internal object PageNavigationSafeArea {
    fun navigationBottom(root: View): Int = if (Build.VERSION.SDK_INT >= 30)
        root.rootView.rootWindowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())?.bottom ?: 0
        else 0

    private val states = WeakHashMap<ViewGroup, State>()
    fun syncPublicVideo(root: View, preDraw: Boolean): Boolean? =
        states[root]?.publicVideo?.sync(preDraw)
    private const val TRENDS = "com.ss.android.ugc.aweme.trends.TrendsDetailActivity"
    private const val MUSIC = "com.ss.android.ugc.aweme.music.ui.MusicDetailActivity"
    private const val COMMENT_PAGER = "com.ss.android.ugc.aweme.comment.widget.FixedCommentViewPager"

    fun attach(root: View, activityName: String = "", window: Window? = null) {
        if (root !is ViewGroup || states.containsKey(root)) return
        val state = State(root, activityName == TRENDS, activityName == MUSIC, window,
            activityName == "com.bytedance.ies.ugc.aweme.photos.detail.flow.page.FlowPageActivity")
        if (activityName == "com.ss.android.ugc.aweme.listen.v2.view.ListenAwemeActivityV2") {
            state.decorTransfer = DecorNavigationInsetTransfer(root,
                "com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateConstraintLayout")
        }
        states[root] = state
        state.preDraw = ViewTreeObserver.OnPreDrawListener {
            val now = SystemClock.uptimeMillis()
            if (state.dirty || now - state.lastSearch > 500L) {
                state.dirty = false
                state.lastSearch = now
                state.discover(root, false)
            }
            !state.apply()
        }
        state.layout = ViewTreeObserver.OnGlobalLayoutListener {
            state.dirty = true
            state.decorTransfer?.markDirty()
        }
        state.attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (root.viewTreeObserver.isAlive) {
                    root.viewTreeObserver.removeOnPreDrawListener(state.preDraw)
                    root.viewTreeObserver.removeOnGlobalLayoutListener(state.layout)
                }
                root.removeOnAttachStateChangeListener(this)
                state.restore()
                states.remove(root)
            }
        }
        root.viewTreeObserver.addOnPreDrawListener(state.preDraw)
        root.viewTreeObserver.addOnGlobalLayoutListener(state.layout)
        root.addOnAttachStateChangeListener(state.attach)
    }

    private class Padding(val view: View, val original: Int = view.paddingBottom) {
        var applied = original
        fun set(extra: Int): Boolean {
            val wanted = original + extra
            applied = wanted
            if (view.paddingBottom == wanted) return false
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, wanted)
            return true
        }
        fun restore() { if (view.paddingBottom == applied) set(0) }
    }

    private class State(val root: ViewGroup, val trends: Boolean, val music: Boolean, val window: Window?, val flow: Boolean) {
        lateinit var preDraw: ViewTreeObserver.OnPreDrawListener
        lateinit var layout: ViewTreeObserver.OnGlobalLayoutListener
        lateinit var attach: View.OnAttachStateChangeListener
        var dirty = true
        var lastSearch = 0L
        var decorTransfer: DecorNavigationInsetTransfer? = null
        val publicVideo = PublicVideoSafeArea(root)
        val composeHosts = ArrayList<Padding>()
        var flowViewport: Padding? = null
        var dock: View? = null
        var list: ViewGroup? = null
        var listPadding: Padding? = null
        var originalClip = true
        var originalListClipToPadding = true
        var musicParent: ViewGroup? = null
        var originalMusicPadding = 0
        var appliedMusicPadding = 0
        var originalDockMargin = 0
        var appliedDockMargin = 0
        var musicParentClipToPadding = true
        var topBackdrop: View? = null
        var toolbar: ViewGroup? = null
        var toolbarClipChildren = true
        var toolbarClipToPadding = true
        var originalTopHeight = 0
        var nativeTopHeight = 0
        var appliedTopHeight = 0
        var originalTopTranslation = 0f
        var appliedTopTranslation = 0f
        var originalTopScale = 1f
        var originalTopPivot = 0f
        val originalStatusColor = window?.statusBarColor
        val originalStatusContrast = if (Build.VERSION.SDK_INT >= 29) window?.isStatusBarContrastEnforced else null
        var gradient: View? = null
        var gradientHeight = 0
        var originalGradientHeight = 0
        var originalGradientMargin = 0
        var appliedGradientHeight = 0
        var originalGradientTranslation = 0f
        var appliedGradientTranslation = 0f
        var gradientParent: ViewGroup? = null
        var originalParentClip = true
        val position = IntArray(2)

        fun discover(view: View, inComment: Boolean, depth: Int = 0) {
            if (depth > 45 || !view.isShown) return
            if (flow && view is ViewGroup && view.javaClass.name ==
                "com.bytedance.ies.ugc.aweme.category.view.MonitorConstraintLayout" &&
                (0 until view.childCount).any { view.getChildAt(it) is EditText }) {
                val parent = view.parent as? ViewGroup
                if (parent != null && flowViewport?.view !== parent) {
                    flowViewport?.restore()
                    flowViewport = Padding(parent)
                }
            }
            val comment = inComment || view.javaClass.name == COMMENT_PAGER
            if (comment && view.javaClass.name == "androidx.compose.ui.platform.ComposeView") {
                if (composeHosts.none { it.view === view }) composeHosts.add(Padding(view))
                return
            }
            if (trends || music) {
                val id = try { view.resources.getResourceEntryName(view.id) } catch (_: Exception) { "" }
                if (music && id == "znv" && view is ViewGroup && topBackdrop == null) {
                    for (i in 0 until view.childCount) {
                        val child = view.getChildAt(i)
                        val name = try { child.resources.getResourceEntryName(child.id) } catch (_: Exception) { "" }
                        if (name == "2io" && child.height > 0 && child.background != null) {
                            topBackdrop = child
                            toolbar = view
                            toolbarClipChildren = view.clipChildren
                            toolbarClipToPadding = view.clipToPadding
                            nativeTopHeight = child.height
                            originalTopHeight = child.layoutParams.height
                            originalTopTranslation = child.translationY
                            originalTopScale = child.scaleY
                            originalTopPivot = child.pivotY
                        }
                    }
                }
                if (id == "bottom_layout") {
                    dock = view
                    if (music && musicParent == null) {
                        musicParent = view.parent as? ViewGroup
                        originalMusicPadding = musicParent?.paddingBottom ?: 0
                        musicParentClipToPadding = musicParent?.clipToPadding ?: true
                        originalDockMargin = (view.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
                    }
                    if (gradient == null) {
                        val parent = view.parent as? ViewGroup
                        if (parent != null) for (i in 0 until parent.childCount) {
                            val candidate = parent.getChildAt(i)
                            val name = try { candidate.resources.getResourceEntryName(candidate.id) } catch (_: Exception) { "" }
                            if (name == "cst" && candidate.background is GradientDrawable && candidate.height > 0) {
                                val params = candidate.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                                gradient = candidate
                                gradientHeight = candidate.height
                                originalGradientHeight = params.height
                                originalGradientMargin = params.bottomMargin
                                originalGradientTranslation = candidate.translationY
                                gradientParent = parent
                                originalParentClip = parent.clipToPadding
                            }
                        }
                    }
                }
                if (((trends && id == "23k") || (music && view.javaClass.name ==
                        "com.ss.android.ugc.aweme.music.v2.widgets.MusicDetailRecyclerView")) &&
                    view is ViewGroup && list !== view) {
                    list = view
                    listPadding = Padding(view)
                    originalClip = view.clipChildren
                    originalListClipToPadding = view.clipToPadding
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount)
                discover(view.getChildAt(i), comment, depth + 1)
        }

        fun apply(): Boolean {
            if (Build.VERSION.SDK_INT < 30) return false
            val insets = root.rootWindowInsets ?: return false
            val ime = insets.isVisible(WindowInsets.Type.ime())
            val nav = navigationBottom(root)
            var changed = decorTransfer?.sync() ?: false
            changed = (publicVideo.sync(true) == false) || changed
            flowViewport?.takeIf { it.view.isAttachedToWindow && it.view.isShown }?.let { viewport ->
                root.rootView.getLocationOnScreen(position)
                val safeBottom = position[1] + root.rootView.height - nav
                viewport.view.getLocationOnScreen(position)
                val overlap = (position[1] + viewport.view.height - viewport.original - safeBottom).coerceIn(0, nav)
                changed = viewport.set(if (ime) 0 else overlap) || changed
            }
            if (music) {
                window?.let { win ->
                    if (win.statusBarColor != Color.TRANSPARENT) win.statusBarColor = Color.TRANSPARENT
                    if (win.isStatusBarContrastEnforced) win.isStatusBarContrastEnforced = false
                    val id = root.resources.getIdentifier("statusBarBackground", "id", "android")
                    if (id != 0) win.decorView.findViewById<View>(id)?.let { overlay ->
                        if ((overlay.background as? ColorDrawable)?.color != Color.TRANSPARENT)
                            overlay.setBackgroundColor(Color.TRANSPARENT)
                    }
                }
                topBackdrop?.let { backdrop ->
                    val top = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                    val params = backdrop.layoutParams
                    val height = nativeTopHeight + top
                    if (params.height != height) {
                        params.height = height
                        backdrop.layoutParams = params
                        changed = true
                    }
                    appliedTopHeight = height
                    // RelativeLayout can force this backdrop back to the
                    // toolbar's measured height despite an explicit height.
                    // Scale only its background surface to cover both bands.
                    if (backdrop.height > 0) {
                        backdrop.pivotY = 0f
                        val scale = originalTopScale * height.toFloat() / backdrop.height
                        if (backdrop.scaleY != scale) backdrop.scaleY = scale
                    }
                    val translation = originalTopTranslation - top
                    if (backdrop.translationY != translation) backdrop.translationY = translation
                    appliedTopTranslation = translation
                    toolbar?.clipChildren = false
                    toolbar?.clipToPadding = false
                    // Reuse the native header drawable and its scroll-driven
                    // alpha, so status pixels follow the real header palette.
                    musicParent?.clipToPadding = false
                }
            }
            val iterator = composeHosts.iterator()
            while (iterator.hasNext()) {
                val host = iterator.next()
                if (!host.view.isAttachedToWindow) {
                    host.restore()
                    iterator.remove()
                } else if (host.view.isShown) {
                    // Compose already handles the keyboard's inset itself. Its
                    // viewport needs only the unconsumed navigation safe area.
                    root.rootView.getLocationOnScreen(position)
                    val safeBottom = position[1] + root.rootView.height - nav
                    host.view.getLocationOnScreen(position)
                    val overlap = (position[1] + host.view.height - safeBottom).coerceIn(0, nav)
                    changed = host.set(if (ime) 0 else overlap) || changed
                }
            }
            val scroll = list
            val bar = dock
            if ((trends || music) && scroll?.isShown == true && bar?.isShown == true && !ime) {
                if (music) musicParent?.let { parent ->
                    // Music pads the entire page, stopping thumbnail pixels at
                    // the gesture region. Give that inset only to the dock;
                    // the native scrolling background can then reach the edge.
                    val consumed = minOf(originalMusicPadding, nav)
                    val padding = originalMusicPadding - consumed
                    if (parent.paddingBottom != padding) {
                        parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, padding)
                        changed = true
                    }
                    appliedMusicPadding = padding
                    (bar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                        val margin = originalDockMargin + consumed
                        if (params.bottomMargin != margin) {
                            params.bottomMargin = margin
                            bar.layoutParams = params
                            changed = true
                        }
                        appliedDockMargin = margin
                    }
                }
                gradient?.let { background ->
                    val params = background.layoutParams as? ViewGroup.MarginLayoutParams
                    if (params != null) {
                        // The page already pads its controls for gestures, but
                        // clips this native gradient at that padding's top edge.
                        val height = gradientHeight + nav
                        val margin = originalGradientMargin
                        if (params.height != height || params.bottomMargin != margin) {
                            params.height = height
                            params.bottomMargin = margin
                            background.layoutParams = params
                            changed = true
                        }
                        appliedGradientHeight = height
                        // ConstraintLayout clamps negative bottom margins.
                        // Translate just the enlarged background into the
                        // gesture area; native controls remain in their layout.
                        val translation = originalGradientTranslation + nav
                        if (background.translationY != translation) background.translationY = translation
                        appliedGradientTranslation = translation
                        gradientParent?.clipToPadding = false
                    }
                }
                if (scroll.height > 0 && bar.height > 0) {
                    scroll.getLocationOnScreen(position)
                    val nativeBottom = position[1] + scroll.height
                    bar.getLocationOnScreen(position)
                    val reserve = (nativeBottom - position[1]).coerceAtLeast(0)
                    // Match the floating native bar: items may draw behind it,
                    // while trailing padding lets the final item scroll clear.
                    changed = (listPadding?.set(reserve) ?: false) || changed
                    scroll.clipToPadding = false
                    scroll.clipChildren = true
                }
            }
            return changed
        }

        fun restore() {
            publicVideo.release()
            flowViewport?.restore()
            decorTransfer?.release()
            composeHosts.forEach { it.restore() }
            musicParent?.let { parent ->
                parent.clipToPadding = musicParentClipToPadding
                if (parent.paddingBottom == appliedMusicPadding)
                    parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, originalMusicPadding)
                dock?.let { bar ->
                    val params = bar.layoutParams as? ViewGroup.MarginLayoutParams
                    if (params != null && params.bottomMargin == appliedDockMargin) {
                        params.bottomMargin = originalDockMargin
                        bar.layoutParams = params
                    }
                }
            }
            topBackdrop?.let { backdrop ->
                backdrop.scaleY = originalTopScale
                backdrop.pivotY = originalTopPivot
                if (backdrop.translationY == appliedTopTranslation) backdrop.translationY = originalTopTranslation
                if (backdrop.layoutParams.height == appliedTopHeight) {
                    val params = backdrop.layoutParams
                    params.height = originalTopHeight
                    backdrop.layoutParams = params
                }
                toolbar?.clipChildren = toolbarClipChildren
                toolbar?.clipToPadding = toolbarClipToPadding
            }
            if (music) window?.let { win ->
                originalStatusColor?.let { win.statusBarColor = it }
                if (Build.VERSION.SDK_INT >= 29) originalStatusContrast?.let { win.isStatusBarContrastEnforced = it }
            }
            gradient?.let { background ->
                if (background.translationY == appliedGradientTranslation)
                    background.translationY = originalGradientTranslation
                val params = background.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null && params.height == appliedGradientHeight) {
                    params.height = originalGradientHeight
                    params.bottomMargin = originalGradientMargin
                    background.layoutParams = params
                }
                gradientParent?.clipToPadding = originalParentClip
            }
            list?.let { scroll ->
                listPadding?.restore()
                scroll.clipToPadding = originalListClipToPadding
                scroll.clipChildren = originalClip
            }
        }
    }
}
