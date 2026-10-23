package cn.zhe.douyinsystemtheme

import android.app.Application
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

internal object UnifiedOptions {
    var immersive = true
    var theme = true
    var highRefresh = true
    fun load(context: Context) {
        try {
            val values = context.contentResolver.call(Uri.parse("content://cn.zhe.douyinunified.options"), "read", null, null)
                ?: error("settings provider returned null")
            immersive = values.getBoolean("immersive", true)
            theme = values.getBoolean("theme", true)
            highRefresh = values.getBoolean("highRefresh", true)
        } catch (e: Exception) {
            XposedBridge.log("[DouyinUnified] settings unavailable; defaults enabled: $e")
        }
    }
}

/** One load-package/attach entry; the navigation owner is initialized before theme observers. */
class UnifiedModule : IXposedHookLoadPackage {
    private companion object { val processStarted = java.util.concurrent.atomic.AtomicBoolean(false) }
    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        if (param.packageName == "com.android.systemui") {
            NotificationAvatar.install(param.classLoader)
            return
        }
        if (param.packageName != "com.ss.android.ugc.aweme" || param.processName != param.packageName) return
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (!processStarted.compareAndSet(false, true)) return
                    val app = p.thisObject as Application
                    RuntimeChatDiagnostics.install(app)
                    UnifiedOptions.load(app)
                    if (UnifiedOptions.immersive || UnifiedOptions.theme) NativeChatRegistry.install(param.classLoader)
                    if (UnifiedOptions.immersive) ChatListInsets.install(param.classLoader)
                    XposedBridge.log("[DouyinUnified] loaded 0.3.17 nav=${UnifiedOptions.immersive} theme=${UnifiedOptions.theme} highRefresh=${UnifiedOptions.highRefresh}; native-page-navigation-safe-area")
                    if (UnifiedOptions.immersive) try {
                        cn.zhe.douyinimmersivenav.DouyinImmersiveNavModule().start(param, app)
                    } catch (e: Throwable) { XposedBridge.log("[DouyinUnified] navigation initialization failed: $e") }
                    if (UnifiedOptions.theme) DouyinSystemThemeModule().start(app, param.classLoader)
                    if (UnifiedOptions.highRefresh) HighRefreshController(app).install()
                }
            })
    }
}
