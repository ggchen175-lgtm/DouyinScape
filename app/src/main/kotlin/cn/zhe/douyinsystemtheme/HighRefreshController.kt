package cn.zhe.douyinsystemtheme

import android.app.Activity
import android.app.Application
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.util.WeakHashMap

/** Window-level votes, including dialogs/subwindows. No video Surface or synthetic redraw loop. */
internal class HighRefreshController(private val app: Application) {
    private val displays = app.getSystemService(DisplayManager::class.java)
    private val rates = HashMap<Int, Float>()
    private val windows = WeakHashMap<Window, Boolean>()
    private val logged = HashSet<String>()
    private fun rate(display: Display?): Float {
        display ?: return 0f
        rates[display.displayId]?.let { return it }
        val mode = display.mode
        val advertised = display.supportedModes.asSequence().filter {
            it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight
        }.maxOfOrNull { it.refreshRate } ?: mode.refreshRate
        val highest = maxOf(advertised, mode.refreshRate, display.refreshRate)
        rates[display.displayId] = highest
        val key = "${display.displayId}-$highest-${display.refreshRate}"
        if (logged.add(key)) XposedBridge.log("[DouyinUnified] refresh display=${display.displayId} request=$highest Hz observedDisplay=${display.refreshRate} Hz resolution=${mode.physicalWidth}x${mode.physicalHeight}; not measured app FPS")
        return highest
    }
    private fun rewrite(attrs: WindowManager.LayoutParams, display: Display?): Boolean {
        if (attrs.type !in 1..1999) return false
        val highest = rate(display ?: displays.getDisplay(Display.DEFAULT_DISPLAY))
        if (highest <= 0 || (attrs.preferredDisplayModeId == 0 && kotlin.math.abs(attrs.preferredRefreshRate - highest) < 0.01f)) return false
        // A preferred mode ID would override preferredRefreshRate and may also select a resolution.
        attrs.preferredDisplayModeId = 0
        attrs.preferredRefreshRate = highest
        return true
    }
    private fun apply(window: Window) {
        windows[window] = true
        val attrs = window.attributes
        if (rewrite(attrs, window.peekDecorView()?.display)) window.attributes = attrs
    }
    fun install() {
        XposedBridge.hookAllMethods(Window::class.java, "setAttributes", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val attrs = param.args.firstOrNull() as? WindowManager.LayoutParams ?: return
                rewrite(attrs, (param.thisObject as Window).peekDecorView()?.display)
            }
        })
        try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.args.firstOrNull() as? View ?: return
                    val index = param.args.indexOfFirst { it is WindowManager.LayoutParams }
                    if (index < 0) return
                    val original = param.args[index] as WindowManager.LayoutParams
                    val copy = WindowManager.LayoutParams().apply { copyFrom(original) }
                    if (rewrite(copy, view.display)) param.args[index] = copy
                }
            }
            XposedBridge.hookAllMethods(global, "addView", hook)
            XposedBridge.hookAllMethods(global, "updateViewLayout", hook)
        } catch (e: Throwable) { XposedBridge.log("[DouyinUnified] subwindow refresh hook unavailable: $e") }
        displays.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) { rates.remove(id) }
            override fun onDisplayRemoved(id: Int) { rates.remove(id) }
            override fun onDisplayChanged(id: Int) {
                rates.remove(id)
                windows.keys.toList().forEach(::apply)
            }
        }, Handler(Looper.getMainLooper()))
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) { apply(a.window) }
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) { windows.remove(a.window) }
        })
    }
}
