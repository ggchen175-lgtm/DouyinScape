package cn.zhe.douyinimmersivenav

import android.view.MotionEvent
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Only the bound chat root and actual floating circle participate. */
internal object ChatFloatingTouch {
    private val owners = WeakHashMap<ViewGroup, WeakReference<ChatFloatingGeometry>>()
    fun bind(root: ViewGroup, owner: ChatFloatingGeometry) { owners[root] = WeakReference(owner) }
    fun release(root: ViewGroup, owner: ChatFloatingGeometry) {
        if (owners[root]?.get() === owner) owners.remove(root)
    }
    fun install() {
        XposedHelpers.findAndHookMethod(ViewGroup::class.java, "dispatchTouchEvent", MotionEvent::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val owner = owners[param.thisObject]?.get() ?: return
                    if (owner.onTouch(param.args[0] as MotionEvent)) param.result = true
                }
            })
    }
}
