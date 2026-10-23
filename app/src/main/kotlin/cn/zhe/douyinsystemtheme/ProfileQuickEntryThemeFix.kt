package cn.zhe.douyinsystemtheme

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** 40.7.0 KMP card cells expose virtual accessibility nodes, not native TextViews. */
internal class ProfileQuickEntryThemeFix(
    private val loader: ClassLoader,
    private val enabled: () -> Boolean,
    private val dark: () -> Boolean,
    private val log: (String) -> Unit,
    private val targetVersion: String = "40.7.0"
) {
    private val labels = setOf("我的订单", "观看历史", "我的钱包", "稍后再看", "全部功能")
    private val models = WeakHashMap<Any, Boolean?>()
    private val icons = HashMap<String, Pair<Any?, Any?>>()
    private val seen = HashSet<String>()
    private val fields = arrayOf("LIZ", "LIZIZ", "LIZJ", "LIZLLL", "LJ", "LJFF", "LJI",
        "LJII", "LJIIIIZZ", "LJIIIZ", "LJIIJ", "LJIIJJI", "LJIIL", "LJIILIIL",
        "LJIILJJIL", "LJIILL", "LJIILLIIL", "LJIIZILJ", "LJIJ", "LJIJI")
    private lateinit var constructor: java.lang.reflect.Constructor<*>

    fun install() {
        installTitleRendering()
        val state = Class.forName(if (targetVersion == "40.8.0") "X.0MMY" else "X.0QcT", false, loader)
        constructor = state.declaredConstructors.single { it.parameterCount == 20 }.apply { isAccessible = true }
        XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                val title = param.args[10] as? String ?: return
                if (title !in labels) return
                val night = dark()
                val lightIcon = param.args[1]
                val darkIcon = param.args[2]
                // Preserve the app's original pair; copies of our normalized state must not
                // replace it, otherwise a later light-mode transition would retain dark icons.
                val pair = synchronized(icons) {
                    if (lightIcon != darkIcon) icons[title] = lightIcon to darkIcon
                    icons[title]
                }
                val selected = if (night) pair?.second else pair?.first
                if (selected != null) {
                    param.args[1] = selected
                    param.args[2] = selected
                }
                // LJIIL (argument 12) is subTitleTextColor, NOT the visible title.
                // Leave subtitles intact; title rendering reads DuxTheme independently.
                once("state-$title-$night", "profile quick entry KMP state title=$title night=$night " +
                    "iconPair=${pair != null} iconSelected=${selected != null}; subtitle color unchanged")
            }
        })
        val model = Class.forName("com.ss.kmp.ugc.aweme.profile.cardentry.view_model.CardCellViewModel", false, loader)
        XposedBridge.hookAllConstructors(model, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) return
                val card = XposedHelpers.getObjectField(param.thisObject, "a") ?: return
                if (XposedHelpers.getObjectField(card, "title") !in labels) return
                synchronized(models) { models[param.thisObject] = null }
            }
        })
        log("profile quick entry hooks installed: CardCellViewModel / X.0QcT; five labels only; native TextView not required")
    }

    private fun installTitleRendering() {
        val colorKt = Class.forName("androidx.compose.ui.graphics.ColorKt", false, loader)
        val pack = colorKt.getDeclaredMethod("Color", Int::class.javaPrimitiveType)
        val colors = mapOf(false to (pack.invoke(null, foreground(false)) as Long),
            true to (pack.invoke(null, foreground(true)) as Long))
        val textKt = Class.forName("androidx.compose.material.TextKt", false, loader)
        val renderers = textKt.declaredMethods.filter {
            it.name.startsWith("Text-") && it.parameterTypes.size > 6 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[2] == Long::class.javaPrimitiveType &&
                it.parameterTypes.takeLast(3).all { p -> p == Int::class.javaPrimitiveType }
        }
        check(renderers.isNotEmpty()) { "Compose String Text renderer missing in 40.7.0" }
        for (method in renderers) XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                val title = param.args[0] as? String ?: return
                if (title !in labels) return
                // Restrict to CardEntryItem's title helpers, not matching text elsewhere.
                val caller = Thread.currentThread().stackTrace.firstOrNull {
                    it.className in (if (targetVersion == "40.8.0") setOf("X.0MMW") else setOf("X.0QcS", "X.0QZg"))
                } ?: return
                val night = dark()
                val raw = param.args[2] as Long
                val wanted = colors.getValue(night)
                param.args[2] = wanted
                // Compose color is a packed Long; never pass an Android ARGB Int here.
                // Make color explicit and mark it changed so cached light color cannot win.
                val defaults = param.args.lastIndex
                param.args[defaults] = (param.args[defaults] as Int) and 2.inv()
                val changed = defaults - 2
                param.args[changed] = ((param.args[changed] as Int) and 0x380.inv()) or 0x100
                once("title-render-$title-$night", "profile quick entry title render title=$title night=$night " +
                    "caller=${caller.className}.${caller.methodName} rawPacked=0x${java.lang.Long.toHexString(raw)} " +
                    "afterPacked=0x${java.lang.Long.toHexString(wanted)} argb=0x${Integer.toHexString(foreground(night))}")
            }
        })
        log("profile quick entry title hooks installed: Compose Text String renderers=${renderers.size}; CardEntryItem callers only")
    }

    /** Existing preDraw calls this for retained models; emit a new immutable StateFlow value
     * only on a theme flip. Never mutate the old value: StateFlow equality would hide it. */
    fun sync() {
        if (!enabled()) return
        val night = dark()
        val pending = synchronized(models) { models.entries.filter { it.value != night }.map { it.key } }
        for (model in pending) {
            val card = XposedHelpers.getObjectField(model, "a") ?: continue
            val title = XposedHelpers.getObjectField(card, "title") as? String ?: continue
            if (title !in labels) continue
            if (XposedHelpers.callMethod(model, "getCurrentPage").toString() != "MY_PROFILE") continue
            val flow = XposedHelpers.getObjectField(model, "n") ?: continue
            val old = XposedHelpers.callMethod(flow, "getValue") ?: continue
            // Wait until the initial business binding has supplied the title and icon pair.
            if (XposedHelpers.getObjectField(old, "LJIIJ") != title) continue
            val args = fields.map { XposedHelpers.getObjectField(old, it) }.toTypedArray()
            val next = constructor.newInstance(*args)
            val changed = XposedHelpers.callMethod(flow, "compareAndSet", old, next) as Boolean
            if (changed) {
                synchronized(models) { models[model] = night }
                once("emit-$title-$night", "profile quick entry KMP emitted title=$title night=$night; icon state refreshed; title uses Compose render hook")
            }
        }
    }

    private fun foreground(night: Boolean) = if (night) 0xe6ffffff.toInt() else 0xff161823.toInt()
    private fun once(key: String, message: String) {
        synchronized(seen) { if (seen.add(key)) log(message) }
    }
}
