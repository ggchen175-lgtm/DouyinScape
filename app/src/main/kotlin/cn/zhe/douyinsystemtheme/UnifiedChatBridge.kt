package cn.zhe.douyinsystemtheme

import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** The navigation engine owns geometry; the theme engine consumes its already-bound dock. */
object UnifiedChatBridge {
    class Dock(root: View, dock: ViewGroup, lift: ViewGroup, viewport: ViewGroup?, val ime: Boolean, val picker: Boolean) {
        val root = WeakReference(root)
        val dock = WeakReference(dock)
        val lift = WeakReference(lift)
        val viewport = WeakReference(viewport)
    }
    private val states = WeakHashMap<View, Dock>()
    fun publish(root: View, dock: ViewGroup, lift: ViewGroup, viewport: ViewGroup?, ime: Boolean, picker: Boolean) {
        val old = states[root]
        if (old?.dock?.get() === dock && old.lift.get() === lift && old.viewport.get() === viewport && old.ime == ime && old.picker == picker) return
        states[root] = Dock(root, dock, lift, viewport, ime, picker)
    }
    fun get(root: View): Dock? = states[root]?.takeIf { it.dock.get()?.isShown == true }
    fun release(root: View, dock: ViewGroup) { if (states[root]?.dock?.get() === dock) states.remove(root) }
}
