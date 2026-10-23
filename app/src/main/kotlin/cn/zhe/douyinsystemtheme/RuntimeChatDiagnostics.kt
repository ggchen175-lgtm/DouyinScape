package cn.zhe.douyinsystemtheme

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference

/** Shell-requested geometry only. No message text, images or periodic tree walks. */
internal object RuntimeChatDiagnostics {
    private var activity = WeakReference<Activity>(null)
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { activity = WeakReference(a) }
            override fun onActivityCreated(a: Activity, s: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val a = activity.get() ?: return
                val out = StringBuilder("activity=${a.javaClass.name} night=${a.resources.configuration.uiMode} status=${a.window.statusBarColor.toUInt().toString(16)} nav=${a.window.navigationBarColor.toUInt().toString(16)}\n")
                val content = a.findViewById<ViewGroup>(android.R.id.content)
                val chat = content?.let(NativeChatRegistry::visible)
                out.append("registeredChat=${chat?.javaClass?.name} id=${chat?.id} parent=${(chat?.parent as? View)?.id}\n")
                fun visit(v: View, depth: Int) {
                    if (depth > 45 || !v.isShown) return
                    val p = IntArray(2); v.getLocationOnScreen(p)
                    val id = try { v.resources.getResourceEntryName(v.id) } catch (_: Exception) { "-" }
                    val bg = v.background
                    val color = (bg as? ColorDrawable)?.color?.toUInt()?.toString(16)
                    out.append(" ".repeat(depth)).append("${v.javaClass.name} id=$id xy=${p.toList()} size=${v.width}x${v.height} trans=${v.translationX},${v.translationY} pad=${v.paddingLeft},${v.paddingTop},${v.paddingRight},${v.paddingBottom} bg=${bg?.javaClass?.simpleName}:$color alpha=${v.alpha}")
                    if (v is ViewGroup) out.append(" clip=${v.clipChildren}/${v.clipToPadding}")
                    if (bg is android.graphics.drawable.GradientDrawable && android.os.Build.VERSION.SDK_INT >= 24)
                        out.append(" gradientColor=${bg.color?.defaultColor?.toUInt()?.toString(16)} radius=${bg.cornerRadius}")
                    if (v is android.widget.ImageView) out.append(" drawable=${v.drawable?.javaClass?.name} drawableBounds=${v.drawable?.bounds} matrix=${v.imageMatrix}")
                    out.append('\n')
                    if (v is ViewGroup) for (n in 0 until v.childCount) visit(v.getChildAt(n), depth+1)
                }
                visit(a.window.decorView, 0)
                // Product shelves are dialog windows; Activity decor alone cannot
                // show their navigation fitting. Still only dump on shell request.
                try {
                    val type = Class.forName("android.view.WindowManagerGlobal")
                    val manager = de.robv.android.xposed.XposedHelpers.callStaticMethod(type, "getInstance")
                    val roots = de.robv.android.xposed.XposedHelpers.getObjectField(manager, "mViews") as? List<*>
                    roots?.filterIsInstance<View>()?.filter { it !== a.window.decorView && it.isShown }?.forEach { decor ->
                        out.append("EXTRA_WINDOW class=${decor.javaClass.name} insets=${decor.rootWindowInsets}\n")
                        visit(decor, 0)
                    }
                } catch (failure: Throwable) { out.append("extra-window-dump-unavailable=${failure.javaClass.name}\n") }
                java.io.File(app.filesDir, "unified-geometry.txt").writeText(out.toString())
            }
        }
        val filter = IntentFilter("cn.zhe.douyinunified.DUMP_GEOMETRY")
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter, "android.permission.DUMP", null)
    }
}
