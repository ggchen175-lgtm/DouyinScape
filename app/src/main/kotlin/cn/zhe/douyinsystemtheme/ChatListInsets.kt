package cn.zhe.douyinsystemtheme

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap

/** Reserve the lifted composer in measured bounds; native RecyclerView owns its scroll anchor. */
internal object ChatListInsets {
    private class Saved(var extra: Int = 0)
    private val saved = WeakHashMap<ViewGroup, Saved>()
    private lateinit var listType: Class<*>
    fun install(loader: ClassLoader) {
        listType = Class.forName("com.ss.android.ugc.aweme.im.widget.CustomRecyclerView", false, loader)
        var owner: Class<*>? = listType
        while (owner != null) {
            val method = owner.declaredMethods.firstOrNull { it.name == "onMeasure" &&
                it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)) }
            if (method != null) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val view = p.thisObject as? ViewGroup ?: return
                        if (eligible(view)) {
                            // Measuring alone must not claim a reserve. The navigation
                            // controller registers this list with its actual dock inset.
                            val record = saved[view] ?: return
                            val spec = p.args[1] as Int
                            // Reserve physical viewport space before RecyclerView measures.
                            // Changing padding after native IME layout invalidates its end anchor.
                            if (View.MeasureSpec.getMode(spec) != View.MeasureSpec.UNSPECIFIED) {
                                p.args[1] = View.MeasureSpec.makeMeasureSpec(
                                    (View.MeasureSpec.getSize(spec) - record.extra).coerceAtLeast(0),
                                    View.MeasureSpec.getMode(spec))
                            }
                        }
                    }
                })
                break
            }
            owner = owner.superclass
        }
        NativeChatRegistry.destroyed.add { root ->
            saved.keys.toList().filter { descendant(it, root) }.forEach(::restore)
        }
    }
    private fun eligible(view: ViewGroup): Boolean {
        if (view.id != 0x7f0a919e || !listType.isInstance(view)) return false
        val root = NativeChatRegistry.containing(view) ?: return false
        var p = view.parent as? View
        while (p != null && p !== root) {
            val name = if (p.id == View.NO_ID) "" else try { p.resources.getResourceEntryName(p.id) } catch (_: Exception) { "" }
            if (name == "14i" || name == "pk3") return false
            p = p.parent as? View
        }
        return true
    }
    fun original(view: ViewGroup): Int = view.paddingBottom
    fun apply(view: ViewGroup, reserve: Int): Int {
        val record = saved.getOrPut(view) { Saved() }
        if (record.extra != reserve) {
            record.extra = reserve
            view.requestLayout()
        }
        return view.paddingBottom
    }
    fun restore(view: ViewGroup) {
        if (saved.remove(view) != null) view.requestLayout()
    }
    private fun descendant(view: View, root: View): Boolean {
        var v: View? = view
        while (v != null) { if (v === root) return true; v = v.parent as? View }
        return false
    }
}
