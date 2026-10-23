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
        if (lift.translationY == wanted) return false
        lift.translationY = wanted
        return true
    }

    fun nativeBottom(): Float {
        lift.getLocationOnScreen(position)
        return position[1] + lift.height - (lift.translationY - originalTranslation)
    }

    fun release() {
        if (lift.translationY == appliedTranslation) lift.translationY = originalTranslation
        reserve = 0
    }
}
