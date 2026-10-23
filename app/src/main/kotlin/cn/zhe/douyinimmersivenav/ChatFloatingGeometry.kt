package cn.zhe.douyinimmersivenav

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.view.animation.DecelerateInterpolator
import cn.zhe.douyinsystemtheme.ChatFloatingControl
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.graphics.Rect
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedBridge
import cn.zhe.douyinsystemtheme.NativeChatRegistry

/** Only navigation owns placement; the theme observer may recolor these views. */
internal class ChatFloatingGeometry {
    private var tip: ViewGroup? = null
    private var original = 0f
    private var applied = 0f
    private var scopeRef: ViewGroup? = null
    private var shown = false
    private var progress = 1f
    private var entering: ValueAnimator? = null
    private var exiting: ValueAnimator? = null
    private var ghost: BitmapDrawable? = null
    private var snapshot: Bitmap? = null
    private var snapshotX = 0
    private var snapshotY = 0
    private var surface: ViewGroup? = null
    private var alpha = 1f
    private var scaleX = 1f
    private var scaleY = 1f
    private val rootPosition = IntArray(2)
    private val position = IntArray(2)
    private var viewport: ViewGroup? = null
    private var touching = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f
    private val hit = Rect()

    fun sync(chat: ViewGroup?, composer: ViewGroup?, history: ViewGroup?) {
        val candidate = chat?.let(ChatFloatingControl::find)
        if (candidate !== tip || chat !== scopeRef) {
            release()
            tip = candidate
            scopeRef = chat
            if (chat != null && candidate != null) ChatFloatingTouch.bind(chat, this)
            surface = candidate?.let(ChatFloatingControl::surface)
            alpha = surface?.alpha ?: 1f
            scaleX = surface?.scaleX ?: 1f
            scaleY = surface?.scaleY ?: 1f
            original = candidate?.translationY ?: 0f
            applied = original
        }
        val control = tip ?: return
        viewport = history
        val scope = chat ?: return
        if (!control.isShown) {
            if (shown) {
                entering?.cancel(); entering = null
                restoreSurface()
                animateExit(scope)
            }
            shown = false
            return
        }
        val bar = NativeChatRegistry.disabledBar(scope)
            ?: composer?.takeIf { it.isShown && it.height > 0 }
        if (bar == null) { release(); return }
        val face = surface ?: return
        if (!face.isShown || face.height == 0) return
        if (control.translationY != applied) original = control.translationY
        bar.getLocationOnScreen(position)
        var limit = position[1] - 8 * scope.resources.displayMetrics.density
        // Reserve the entire shadow wrapper, rather than only the circular face.
        control.getLocationOnScreen(position)
        val nativeBottom = position[1] + control.height - (control.translationY - original)
        var parent = control.parent as? ViewGroup
        while (parent != null && parent !== scope) {
            if (parent.clipChildren || parent.clipToPadding) {
                parent.getLocationOnScreen(position)
                limit = minOf(limit, (position[1] + parent.height - if (parent.clipToPadding) parent.paddingBottom else 0).toFloat())
            }
            parent = parent.parent as? ViewGroup
        }
        applied = original + minOf(0f, limit - nativeBottom)
        if (control.translationY != applied) control.translationY = applied
        if (!shown) {
            clearExit()
            progress = 0f
            applySurface()
            entering = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200L
                interpolator = DecelerateInterpolator(2f)
                addUpdateListener { progress = it.animatedValue as Float; applySurface() }
                start()
            }
        }
        shown = true
        // Snapshot only the small control for exit after the app sets GONE.
        if (progress >= 0.999f && face.width > 0 && face.height > 0) {
            if (snapshot?.width != face.width || snapshot?.height != face.height) {
                snapshot?.recycle()
                snapshot = Bitmap.createBitmap(face.width, face.height, Bitmap.Config.ARGB_8888)
            }
            snapshot?.let { it.eraseColor(0); face.draw(Canvas(it)) }
            face.getLocationOnScreen(position); scope.getLocationOnScreen(rootPosition)
            snapshotX = position[0] - rootPosition[0]; snapshotY = position[1] - rootPosition[1]
        }
    }

    private fun applySurface() {
        surface?.let {
            it.alpha = alpha * progress
            it.scaleX = scaleX * (0.86f + 0.14f * progress)
            it.scaleY = scaleY * (0.86f + 0.14f * progress)
        }
    }
    private fun restoreSurface() { surface?.let { it.alpha = alpha; it.scaleX = scaleX; it.scaleY = scaleY } }
    private fun animateExit(root: ViewGroup) {
        clearExit()
        val bitmap = snapshot ?: return
        val drawable = BitmapDrawable(root.resources, bitmap)
        ghost = drawable
        drawable.setBounds(snapshotX, snapshotY, snapshotX + bitmap.width, snapshotY + bitmap.height)
        root.overlay.add(drawable)
        exiting = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 160L
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val p = it.animatedValue as Float
                val dx = ((1f - p) * bitmap.width * 0.07f).toInt()
                val dy = ((1f - p) * bitmap.height * 0.07f).toInt()
                drawable.setBounds(snapshotX + dx, snapshotY + dy,
                    snapshotX + bitmap.width - dx, snapshotY + bitmap.height - dy)
                drawable.alpha = (255 * p).toInt()
                if (p == 0f) root.overlay.remove(drawable)
            }
            start()
        }
    }
    private fun clearExit() {
        exiting?.cancel(); exiting = null
        ghost?.let { scopeRef?.overlay?.remove(it) }; ghost = null
    }
    fun onTouch(event: MotionEvent): Boolean {
        val root = scopeRef ?: return false
        val control = tip ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (!control.isAttachedToWindow || !control.isEnabled) return false
            val face = surface ?: return false
            if (control.isShown) {
                if (!face.getGlobalVisibleRect(hit)) return false
            } else {
                // The exiting circle is still visible for a short time after GONE.
                val visual = ghost?.takeIf { it.alpha > 32 } ?: return false
                root.getLocationOnScreen(position)
                hit.set(visual.bounds)
                hit.offset(position[0], position[1])
            }
            val x = event.rawX; val y = event.rawY
            val dx = (x - hit.exactCenterX()) / (hit.width() / 2f)
            val dy = (y - hit.exactCenterY()) / (hit.height() / 2f)
            if (dx * dx + dy * dy > 1f) return false
            touching = true; moved = false; downX = x; downY = y
            // A fling must not consume the first tap before the native listener sees it.
            viewport?.let { XposedHelpers.callMethod(it, "stopScroll") }
            root.parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (!touching) return false
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val slop = ViewConfiguration.get(root.context).scaledTouchSlop
            if (kotlin.math.abs(event.rawX - downX) > slop || kotlin.math.abs(event.rawY - downY) > slop) moved = true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            root.parent?.requestDisallowInterceptTouchEvent(false)
            if (event.actionMasked == MotionEvent.ACTION_UP && !moved) {
                fun clickable(view: View): View? {
                    if (view.hasOnClickListeners()) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) clickable(view.getChildAt(i))?.let { return it }
                    return null
                }
                val clicked = clickable(control)?.performClick() == true
                XposedBridge.log("[DouyinUnified] floating native click dispatched=$clicked; scroll stopped before routing")
            }
        }
        return true
    }
    fun release() {
        scopeRef?.let { ChatFloatingTouch.release(it, this); it.parent?.requestDisallowInterceptTouchEvent(false) }
        viewport = null; touching = false
        entering?.cancel(); entering = null
        clearExit()
        restoreSurface()
        tip?.let { if (it.translationY == applied) it.translationY = original }
        snapshot?.recycle(); snapshot = null
        tip = null; scopeRef = null; surface = null; shown = false; progress = 1f
    }
}
