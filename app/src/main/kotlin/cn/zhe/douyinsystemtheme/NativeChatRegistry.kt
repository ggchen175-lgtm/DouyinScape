package cn.zhe.douyinsystemtheme

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Verified RipsChatRoomFragment lifecycle, including read-only/disabled-input variants. */
internal object NativeChatRegistry {
    private class Entry(fragment: Any) { val fragment = WeakReference(fragment) }
    private val entries = WeakHashMap<ViewGroup, Entry>()
    val destroyed = ArrayList<(ViewGroup) -> Unit>()
    val changed = ArrayList<(ViewGroup) -> Unit>()
    fun install(loader: ClassLoader) {
        val type = Class.forName("com.ss.android.ugc.aweme.im.business.chat.root.RipsChatRoomFragment", false, loader)
        XposedHelpers.findAndHookMethod(type, "onViewCreated", View::class.java, Bundle::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                val root = p.args[0] as? ViewGroup ?: return
                entries[root] = Entry(p.thisObject)
                XposedBridge.log("[DouyinUnified] native chat registered before view binding root=${System.identityHashCode(root)}")
            }
        })
        var owner: Class<*>? = type
        while (owner != null) {
            val method = owner.declaredMethods.firstOrNull { it.name == "onDestroyView" && it.parameterTypes.isEmpty() }
            if (method != null) {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val root = entries.entries.firstOrNull { it.value.fragment.get() === p.thisObject }?.key ?: return
                        entries.remove(root)
                        destroyed.toList().forEach { callback -> callback(root) }
                    }
                })
                break
            }
            owner = owner.superclass
        }
        val tips = Class.forName("com.ss.android.ugc.aweme.im.business.chat.feature.newmsgtip.NewMsgTipsUI", false, loader)
        XposedBridge.hookAllMethods(tips, "onNewMsgTipStataChange", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if (p.hasThrowable()) return
                val view = XposedHelpers.getObjectField(p.thisObject, "b") as? View ?: return
                val root = containing(view) ?: return
                changed.forEach { it(root) }
            }
        })
    }
    fun containing(view: View): ViewGroup? {
        var current: View? = view
        while (current != null) {
            if (current is ViewGroup && entries.containsKey(current)) return current
            current = current.parent as? View
        }
        return null
    }
    fun visible(content: ViewGroup): ViewGroup? {
        val viewport = android.graphics.Rect()
        if (!content.getGlobalVisibleRect(viewport)) return null
        val rect = android.graphics.Rect()
        fun inside(view: View): Boolean {
            var current: View? = view
            while (current != null) { if (current === content) return true; current = current.parent as? View }
            return false
        }
        return entries.keys.filter { inside(it) && it.isAttachedToWindow && it.isShown && it.alpha > 0.01f &&
            it.getGlobalVisibleRect(rect) && android.graphics.Rect.intersects(rect, viewport) }
            .maxByOrNull { it.getGlobalVisibleRect(rect); rect.width().toLong() * rect.height() }
    }
    fun wallpaper(root: ViewGroup): Boolean? = try {
        entries[root]?.fragment?.get()?.let { fragment ->
            val session = XposedHelpers.callMethod(fragment, "getSessionInfo")
            XposedHelpers.getBooleanField(session, "chatWallpaperSet")
        }
    } catch (e: Throwable) { null }
    fun disabledBar(root: ViewGroup): ViewGroup? {
        // InputDisableBarUI / r/0du.xml: 64dp LinearLayout containing mzi.
        val label = root.findViewById<View>(0x7f0a57a4) ?: return null
        return (label.parent as? ViewGroup)?.takeIf { label.isShown && it.isShown }
    }
    fun owner(root: ViewGroup): Activity? = entries[root]?.fragment?.get()?.let {
        XposedHelpers.callMethod(it, "getActivity") as? Activity
    }
}
