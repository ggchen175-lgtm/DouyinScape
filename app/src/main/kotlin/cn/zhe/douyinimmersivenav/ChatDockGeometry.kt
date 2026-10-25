package cn.zhe.douyinimmersivenav

import android.view.View
import android.view.ViewGroup

/** One writer for the composer wrapper. History consumes this same reserve. */
internal class ChatDockGeometry(private val root: View, private val lift: ViewGroup) {
    val originalTranslation = lift.translationY
    var appliedTranslation = originalTranslation
        private set
    var reserve = 0
        private set
    private val position = IntArray(2)
    private data class PanelOffset(var original: Float, var applied: Float)
    private val suggestions = java.util.IdentityHashMap<View, PanelOffset>()
    private var lastPanelSearch = 0L

    /** Native GIF suggestions are siblings of the lifted input, not its children. */
    private fun syncSuggestions() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPanelSearch >= 250) {
            lastPanelSearch = now
            fun visit(view: View) {
                if (view.javaClass.name.endsWith(".gifreply.GifSearchPanelLayout")) {
                    var parent = view.parent
                    while (parent is View) {
                        if (parent === lift) return // Already follows the composer.
                        parent = (parent as View).parent
                    }
                    if (!suggestions.containsKey(view))
                        suggestions[view] = PanelOffset(view.translationY, view.translationY)
                    return
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
            }
            visit(root)
        }
        val iterator = suggestions.entries.iterator()
        lift.getLocationOnScreen(position)
        val composerTop = position[1]
        while (iterator.hasNext()) {
            val (panel, offset) = iterator.next()
            if (!panel.isAttachedToWindow) { iterator.remove(); continue }
            if (panel.translationY != offset.applied) offset.original = panel.translationY
            panel.getLocationOnScreen(position)
            val nativeBottom = position[1] + panel.height - (panel.translationY - offset.original)
            val overlap = if (panel.isShown) (nativeBottom - composerTop).coerceIn(0f, reserve.toFloat()) else 0f
            offset.applied = offset.original - overlap
            if (panel.translationY != offset.applied) panel.translationY = offset.applied
        }
    }

    fun sync(ime: Boolean, picker: Boolean, readOnly: Boolean): Boolean {
        reserve = if (readOnly || picker && !ime) 0 else PageNavigationSafeArea.navigationBottom(root)
        var wanted = originalTranslation - reserve
        // Fold the final animation overshoot into the same calculation. Never
        // write a fixed lift and a second corrected value during one draw.
        if (!ime && !picker && !readOnly && lift.isAttachedToWindow && lift.height > 0) {
            root.getLocationOnScreen(position)
            val safeBottom = position[1] + root.height - PageNavigationSafeArea.navigationBottom(root)
            lift.getLocationOnScreen(position)
            val wantedBottom = position[1] + lift.height + wanted - lift.translationY
            val correction = safeBottom - wantedBottom
            if (correction < 0 && -correction <= 160) wanted += correction
        }
        appliedTranslation = wanted
        val changed = lift.translationY != wanted
        if (changed) lift.translationY = wanted
        syncSuggestions()
        return changed
    }

    fun nativeBottom(): Float {
        lift.getLocationOnScreen(position)
        return position[1] + lift.height - (lift.translationY - originalTranslation)
    }

    fun release() {
        suggestions.forEach { (panel, offset) ->
            if (panel.translationY == offset.applied) panel.translationY = offset.original
        }
        suggestions.clear()
        if (lift.translationY == appliedTranslation) lift.translationY = originalTranslation
        reserve = 0
    }
}

