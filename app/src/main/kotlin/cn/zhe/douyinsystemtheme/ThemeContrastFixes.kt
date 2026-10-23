package cn.zhe.douyinsystemtheme

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Version-specific business rendering fixes. No resource replacements or page rebuilding. */
internal class ThemeContrastFixes(
    private val loader: ClassLoader,
    private val appContext: android.content.Context,
    private val enabled: () -> Boolean,
    private val dark: () -> Boolean,
    private val log: (String) -> Unit
) {
    private val quickEntries = ProfileQuickEntryThemeFix(loader, enabled, dark, log,
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName)
    private val mask = Paint().apply { color = 0xb3000000.toInt() }
    private val seen = HashSet<String>()
    private val hintModes = java.util.WeakHashMap<View, Boolean?>()
    private val secondaryPalette = HashMap<Boolean, Int>()
    private val previewDrawColor = ThreadLocal<Int>()
    private val spanDrawHooks = HashSet<java.lang.reflect.Method>()
    private val watchedSpanTypes = HashSet<Class<*>>()
    private val voiceSpans = java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
    private val replacementHooks = HashSet<java.lang.reflect.Method>()
    private val scanStates = java.util.WeakHashMap<android.view.ViewGroup, ScanState>()
    private val previewStates = java.util.WeakHashMap<View, PreviewState>()
    private val attachWatches = java.util.WeakHashMap<View, Boolean>()
    private val messageRoots = java.util.WeakHashMap<android.view.ViewGroup, Boolean>()
    private val previewLayouts = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<android.text.Layout, Boolean>())

    fun installProfile408() { quickEntries.install() }

    fun install() {
        quickEntries.install()
        installRenderStateHooks()
        val banner = Class.forName("com.ss.android.ugc.aweme.profile.ui.ProfileBannerLayout", false, loader)
        // Banner is a background-only sibling of the header controls in r/24_.xml.
        // Paint after its image children, before the sibling labels/avatar are drawn.
        val foreground = banner.getMethod("onDrawForeground", Canvas::class.java)
        XposedBridge.hookMethod(foreground, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!banner.isInstance(param.thisObject) || param.hasThrowable() || !enabled() || !dark()) return
                val view = param.thisObject as View
                if (view.id != 0x7f0a9445 || !belongsToMain(view)) return
                val canvas = param.args[0] as Canvas
                canvas.drawRect(0f, 0f, view.width.toFloat(), view.height.toFloat(), mask)
                once("banner", "profile banner id=wh9 night mask=#b3000000; resource 0x7f060056 unchanged")
            }
        })

        val hint = Class.forName("com.ss.android.ugc.aweme.im.sdk.module.session.rips.list.vh.subui.HintSubUI", false, loader)
        val bindings = XposedBridge.hookAllMethods(hint, "LIZIZ", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || !enabled()) return
                diagnose("hint bind") {
                    val view = XposedHelpers.callMethod(param.thisObject, "LIZLLL") as? View
                        ?: error("HintSubUI returned no preview view")
                    hintModes[view] = null
                    fixHint(view)
                    hintModes[view] = dark()
                }
            }
        })
        check(bindings.isNotEmpty()) { "HintSubUI.LIZIZ not found" }
        val lightText = Class.forName("com.ss.android.ugc.aweme.im.widget.IMLightTextView", false, loader)
        XposedBridge.hookAllMethods(lightText, "LIZJ", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as View
                if (param.hasThrowable() || (!hintModes.containsKey(view) && !isSessionPreview(view))) return
                // This is also used by recycled cells which bypass HintSubUI's bind method.
                hintModes[view] = null
                previewStates.remove(view)
                view.invalidate()
            }
        })
        // A warm relaunch can reuse render state after its original bind. Recheck at draw time.
        for (renderer in listOf(TextView::class.java,
            Class.forName("com.ss.android.ugc.aweme.im.widget.IMLightTextView", false, loader))) {
            val draw = renderer.getDeclaredMethod("onDraw", Canvas::class.java)
            XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    if (!enabled()) return
                    if (!hintModes.containsKey(view)) {
                        // Retained/preloaded cells may never re-enter HintSubUI.LIZIZ after relaunch.
                        // 40.7.0: BaseSessionViewHolder.g is tv_sub_title; its row also owns avatar 0x1131.
                        if (!isSessionPreview(view)) return
                        hintModes[view] = null
                        once("preview-retained-${view.id}-${view.javaClass.name}",
                            "preview discovered at draw id=tv_sub_title class=${view.javaClass.name}; bind registration bypassed")
                    }
                    diagnose("hint before draw") {
                        val night = dark()
                        val missing = view.javaClass.name == "com.ss.android.ugc.aweme.im.widget.IMLightTextView" &&
                            XposedHelpers.getObjectField(view, if (XposedHelpers.getBooleanField(view, "mUseBoringLayout")) "boringLayout" else "staticLayout") == null
                        if (hintModes[view] != night || missing) {
                            fixHint(view)
                            hintModes[view] = night
                        }
                        val wanted = secondaryColor(view)
                        if (view is TextView) {
                            watchSpanDraws(view.text)
                            view.layout?.let { watchSpanDraws(it.text) }
                            if (view.currentTextColor != wanted) view.setTextColor(wanted)
                        } else {
                            (XposedHelpers.callMethod(view, "getText") as? CharSequence)?.let(::watchSpanDraws)
                            val paint = XposedHelpers.callMethod(view, "getTextPaint") as android.text.TextPaint
                            paint.color = wanted
                            // The precomputed layout can have its own Paint, distinct from the view's.
                            for (field in listOf("mPreComputedStaticLayout", "staticLayout", "boringLayout")) {
                                val layout = XposedHelpers.getObjectField(view, field) as? android.text.Layout
                                layout?.let { watchSpanDraws(it.text) }
                                layout?.paint?.color = wanted
                            }
                        }
                        param.setObjectExtra("preview-draw-active", true)
                        param.setObjectExtra("preview-draw-previous", previewDrawColor.get())
                        previewDrawColor.set(wanted)
                    }
                }
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.getObjectExtra("preview-draw-active") != true) return
                    val previous = param.getObjectExtra("preview-draw-previous") as? Int
                    if (previous == null) previewDrawColor.remove() else previewDrawColor.set(previous)
                }
            })
        }
        log("contrast hooks installed: ProfileBannerLayout.wh9 and HintSubUI previews; no global color replacements")
    }

    private fun installRenderStateHooks() {
        val state = Class.forName("com.ss.android.ugc.aweme.im.sdk.module.session.rips.list.renderstate.HintRenderStateTransform\$HintRenderState", false, loader)
        XposedBridge.hookAllConstructors(state, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled() || param.args.size != 5) return
                diagnose("preview state construction") {
                    // Verified constructor: id, spannableText, bitmapSpanParamList, textColor, rightMargin.
                    param.args[3] = secondaryColor(appContext)
                }
            }
        })
        XposedHelpers.findAndHookMethod(state, "LJIIIZ", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || !enabled()) return
                diagnose("preview state textColor") {
                    val wanted = secondaryColor(appContext)
                    once("state-color-${param.result}-$wanted", "preview render-state color raw=${param.result} wanted=${Integer.toHexString(wanted)}")
                    param.result = wanted
                }
            }
        })
        XposedHelpers.findAndHookMethod(state, "LJIIIIZZ", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || !enabled()) return
                (param.result as? android.text.Layout)?.let { layout ->
                    diagnose("preview state layout read") { registerPreviewLayout(layout) }
                }
            }
        })
        XposedHelpers.findAndHookMethod(state, "LJII", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable() || !enabled()) return
                (param.result as? CharSequence)?.let { text ->
                    diagnose("preview render-state spans") { watchSpanDraws(text) }
                }
            }
        })
        XposedHelpers.findAndHookMethod(state, "LJIIL", android.text.StaticLayout::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!enabled()) return
                (param.args[0] as? android.text.Layout)?.let { layout ->
                    diagnose("preview state layout write") { registerPreviewLayout(layout) }
                }
            }
        })
        // Layout itself is the render object. This remains applicable when a virtual/flattened
        // session renderer has no tv_sub_title child in the Activity's native View tree.
        for (type in listOf(android.text.Layout::class.java, android.text.BoringLayout::class.java)) {
            for (name in listOf("draw", "drawText")) {
                XposedBridge.hookAllMethods(type, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val layout = param.thisObject as android.text.Layout
                        if (!previewLayouts.containsKey(layout) || !enabled()) return
                        diagnose("preview layout draw") {
                            val wanted = secondaryColor(appContext)
                            layout.paint.color = wanted
                            watchSpanDraws(layout.text)
                            param.setObjectExtra("preview-layout-active", true)
                            param.setObjectExtra("preview-layout-previous", previewDrawColor.get())
                            previewDrawColor.set(wanted)
                            once("layout-draw-${type.name}-$name-$wanted",
                                "preview native layout draw class=${layout.javaClass.name} method=$name color=${Integer.toHexString(wanted)}; View id not required")
                        }
                    }
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.getObjectExtra("preview-layout-active") != true) return
                        val previous = param.getObjectExtra("preview-layout-previous") as? Int
                        if (previous == null) previewDrawColor.remove() else previewDrawColor.set(previous)
                    }
                })
            }
        }
        log("preview render-state hooks installed: textColor/LJIIIZ and StaticLayout/LJIIIIZZ/LJIIL")
    }

    private fun registerPreviewLayout(layout: android.text.Layout) {
        previewLayouts[layout] = true
        layout.paint.color = secondaryColor(appContext)
        watchSpanDraws(layout.text)
        once("state-layout-${layout.javaClass.name}-${dark()}",
            "preview render-state layout tracked class=${layout.javaClass.name} night=${dark()}")
    }

    fun syncVisiblePreviews(root: android.view.ViewGroup, forceScan: Boolean, messagePage: Boolean) {
        quickEntries.sync()
        messageRoots[root] = messagePage
        if (!messagePage) return
        if (!enabled()) return
        val night = dark()
        val now = android.os.SystemClock.uptimeMillis()
        val scan = scanStates.getOrPut(root) { ScanState() }
        if (scan.night == night && now - scan.lastScan < 120L) return
        scan.lastScan = now
        scan.night = night
        var found = 0
        var refreshed = 0
        var traversed = 0
        var idHits = 0
        var hiddenHits = 0
        var rendererRejected = 0
        fun visit(view: View) {
            traversed++
            val named = view.id == 0x7f0a9f1c
            if (named) {
                idHits++
                if (!view.isShown) hiddenHits++
                if (!isPreviewRenderer(view)) rendererRejected++
                once("preview-candidate-${System.identityHashCode(root)}-${System.identityHashCode(view)}",
                    "preview candidate id=${Integer.toHexString(view.id)} class=${view.javaClass.name} " +
                        "shown=${view.isShown} attached=${view.isAttachedToWindow} context=${view.context.javaClass.name} " +
                        "parents=${parentSummary(view)}")
            }
            // The root is already scoped to the visible Messages tab by ThemeViewRepairs.
            // Do not require an Activity-owned Context or a particular avatar/depth here.
            if (named && view.isShown && isPreviewRenderer(view)) {
                found++
                diagnose("preview preDraw synchronization") {
                    val text = if (view is TextView) view.text else XposedHelpers.callMethod(view, "getText") as? CharSequence
                    val layout = if (view is TextView) view.layout else XposedHelpers.getObjectField(view, "staticLayout")
                    val signature = PreviewState(night, System.identityHashCode(root),
                        System.identityHashCode(text), System.identityHashCode(layout), view.width, view.height)
                    if (!hintModes.containsKey(view)) hintModes[view] = null
                    if (!attachWatches.containsKey(view)) {
                        attachWatches[view] = true
                        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                hintModes[v] = null
                                previewStates.remove(v)
                                v.invalidate()
                            }
                            override fun onViewDetachedFromWindow(v: View) {
                                previewStates.remove(v)
                                hintModes[v] = null
                            }
                        })
                    }
                    if (previewStates[view] != signature || hintModes[view] != night) {
                        fixHint(view)
                        text?.let(::watchSpanDraws)
                        // Force a new display list even if RecyclerView retained the old RenderNode.
                        view.invalidate()
                        previewStates[view] = signature
                        hintModes[view] = night
                        refreshed++
                    }
                }
            }
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        once("preview-scan-${System.identityHashCode(root)}-$night-$found-$idHits-$hiddenHits",
            "visible preview sync night=$night nodes=$found refreshed=$refreshed root=${System.identityHashCode(root)} " +
                "traversed=$traversed idHits=$idHits hiddenHits=$hiddenHits rendererRejected=$rendererRejected rootShown=${root.isShown}")
    }

    private data class PreviewState(val night: Boolean, val root: Int, val text: Int, val layout: Int, val width: Int, val height: Int)
    private class ScanState { var lastScan = 0L; var night: Boolean? = null }

    @Synchronized private fun watchSpanDraws(text: CharSequence) {
        if (text !is Spanned) return
        for (span in text.getSpans(0, text.length, android.text.style.CharacterStyle::class.java)) {
            // Only voice-preview replacements are eligible; emojis and other image messages stay native.
            if (span is android.text.style.ReplacementSpan) {
                if (text.toString().contains("[语音]")) watchVoiceReplacement(span)
                continue
            }
            if (watchedSpanTypes.contains(span.javaClass)) continue
            val method = span.javaClass.getMethod("updateDrawState", android.text.TextPaint::class.java)
            if (spanDrawHooks.contains(method)) { watchedSpanTypes.add(span.javaClass); continue }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val wanted = previewDrawColor.get() ?: return
                    if (param.hasThrowable()) return
                    val paint = param.args[0] as android.text.TextPaint
                    val raw = paint.color
                    val r = raw shr 16 and 255; val g = raw shr 8 and 255; val b = raw and 255
                    val neutral = maxOf(r, g, b) - minOf(r, g, b) < 48
                    val wrong = if (wanted and 0x00ffffff == 0x00ffffff) maxOf(r, g, b) < 80
                        else minOf(r, g, b) > 200
                    if ((raw ushr 24) < 25 || (neutral && wrong)) {
                        paint.color = wanted
                        once("span-draw-${param.thisObject.javaClass.name}-$raw-$wanted",
                            "preview final span draw corrected class=${param.thisObject.javaClass.name} " +
                                "raw=${Integer.toHexString(raw)} wanted=${Integer.toHexString(wanted)}")
                    }
                }
            })
            spanDrawHooks.add(method)
            watchedSpanTypes.add(span.javaClass)
            once("span-watch-${method.declaringClass.name}",
                "preview span draw hook class=${method.declaringClass.name}; active only inside preview rendering")
        }
    }

    @Synchronized private fun watchVoiceReplacement(span: android.text.style.ReplacementSpan) {
        voiceSpans[span] = true
        val spanClassName = span.javaClass.name
        val method = span.javaClass.getMethod("draw", Canvas::class.java, CharSequence::class.java,
            Integer.TYPE, Integer.TYPE, java.lang.Float.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Paint::class.java)
        if (!replacementHooks.add(method)) return
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val wanted = previewDrawColor.get() ?: return
                if (!voiceSpans.containsKey(param.thisObject)) return
                val paint = param.args[8] as Paint
                param.setObjectExtra("voice-paint", paint.color)
                paint.color = wanted
                // Tint only the voice replacement's drawable, and restore it immediately after draw.
                val drawable = (param.thisObject as? android.text.style.DynamicDrawableSpan)?.drawable
                if (drawable != null) {
                    param.setObjectExtra("voice-drawable", drawable)
                    param.setObjectExtra("voice-filter", drawable.colorFilter)
                    drawable.setColorFilter(wanted, android.graphics.PorterDuff.Mode.SRC_IN)
                }
                once("voice-replacement-$spanClassName-$wanted",
                    "voice preview replacement draw class=$spanClassName color=${Integer.toHexString(wanted)}; scoped tint")
            }
            override fun afterHookedMethod(param: MethodHookParam) {
                val raw = param.getObjectExtra("voice-paint") as? Int ?: return
                (param.args[8] as Paint).color = raw
                (param.getObjectExtra("voice-drawable") as? android.graphics.drawable.Drawable)?.colorFilter =
                    param.getObjectExtra("voice-filter") as? android.graphics.ColorFilter
            }
        })
    }

    private fun isSessionPreview(view: View): Boolean {
        if (view.id != 0x7f0a9f1c || !isPreviewRenderer(view)) return false
        var parent = view.parent as? android.view.ViewGroup
        while (parent != null) {
            val row = parent
            if (messageRoots.containsKey(row)) return messageRoots[row] == true
            parent = row.parent as? android.view.ViewGroup
        }
        return false
    }

    private fun isPreviewRenderer(view: View): Boolean = view is TextView ||
        view.javaClass.name == "com.ss.android.ugc.aweme.im.widget.IMLightTextView"

    private fun parentSummary(view: View): String {
        val chain = ArrayList<String>(5)
        var parent = view.parent as? View
        repeat(5) {
            val current = parent ?: return@repeat
            chain.add("${current.javaClass.simpleName}:${Integer.toHexString(current.id)}:shown=${current.isShown}")
            parent = current.parent as? View
        }
        return chain.joinToString("/")
    }

    private fun fixHint(view: View) {
        val wanted = secondaryColor(view)
        if (view is TextView) {
            val raw = view.currentTextColor
            if (view.currentTextColor != wanted) view.setTextColor(wanted)
            val corrected = correctSpans(view.text, wanted)
            if (corrected != null) view.text = corrected
            watchSpanDraws(view.text)
            view.layout?.let(::registerPreviewLayout)
            if (view.text.toString().contains("[语音]")) {
                once("voice-textview-${System.identityHashCode(view)}-${dark()}",
                    "voice preview TextView night=${dark()} currentTextColor=${Integer.toHexString(raw)}->${Integer.toHexString(wanted)} " +
                        "paint=${Integer.toHexString(view.paint.color)} layout=${view.layout?.javaClass?.name}")
            }
        } else if (view.javaClass.name == "com.ss.android.ugc.aweme.im.widget.IMLightTextView") {
            val paint = XposedHelpers.callMethod(view, "getTextPaint") as android.text.TextPaint
            var text = XposedHelpers.callMethod(view, "getText") as? CharSequence ?: return
            val origin = XposedHelpers.getObjectField(view, "mOriginText") as? CharSequence
            if (text.isEmpty() && !origin.isNullOrEmpty()) {
                // A new builder bypasses LIZJ's same-instance early return while preserving spans.
                XposedHelpers.callMethod(view, "LIZJ", SpannableStringBuilder(origin), null, null)
                text = XposedHelpers.callMethod(view, "getText") as? CharSequence ?: return
            }
            val corrected = correctSpans(text, wanted)
            val activeLayout = if (XposedHelpers.getBooleanField(view, "mUseBoringLayout")) "boringLayout" else "staticLayout"
            val cachedLayout = XposedHelpers.getObjectField(view, activeLayout) as? android.text.Layout
            val contentWidth = view.width - view.paddingLeft - view.paddingRight
            val missingLayout = contentWidth > 0 && cachedLayout == null
            val wrongWidth = contentWidth > 0 && cachedLayout != null && cachedLayout.width > contentWidth
            val collapsedMeasure = !text.isEmpty() && cachedLayout != null && cachedLayout.height > 0 &&
                view.measuredHeight <= view.paddingTop + view.paddingBottom && view.layoutParams?.height != 0
            val rawPaint = paint.color
            XposedHelpers.callMethod(view, "setTextColor", wanted)
            // A color change does not require discarding native voice/rich-text layout caches.
            for (field in listOf("mPreComputedStaticLayout", "staticLayout", "boringLayout")) {
                (XposedHelpers.getObjectField(view, field) as? android.text.Layout)?.let(::registerPreviewLayout)
            }
            watchSpanDraws(text)
            if (corrected != null || missingLayout || wrongWidth) {
                if (corrected != null) XposedHelpers.callMethod(view, "LIZJ", corrected, null, null)
                val width = view.width - view.paddingLeft - view.paddingRight
                if (width > 0) {
                    // onDraw only rebuilds BoringLayout itself. A cleared StaticLayout must be
                    // rebuilt now; requesting layout during RecyclerView reuse can be deferred.
                    XposedHelpers.callMethod(view, "LIZIZ", width)
                }
                view.requestLayout()
            }
            if (collapsedMeasure) {
                view.requestLayout()
                once("preview-measure-${System.identityHashCode(view)}-${dark()}",
                    "preview native measure requested: nonempty layout=${cachedLayout?.height} measuredHeight=${view.measuredHeight}; no fixed row height")
            }
            view.invalidate()
            if (text.toString().contains("[语音]")) {
                val layout = XposedHelpers.getObjectField(view, activeLayout) as? android.text.Layout
                val spans = (text as? Spanned)?.getSpans(0, text.length, Any::class.java)?.joinToString { it.javaClass.name } ?: "none"
                once("voice-state-${System.identityHashCode(view)}-${dark()}-${layout == null}",
                    "voice preview state night=${dark()} renderer=${view.javaClass.name} length=${text.length} " +
                        "paint=${Integer.toHexString(rawPaint)}->${Integer.toHexString(paint.color)} " +
                        "layout=${layout?.javaClass?.name} layoutColor=${layout?.paint?.color?.let(Integer::toHexString)} spans=$spans")
            }
        } else {
            once("unknown-${view.javaClass.name}", "preview renderer unsupported class=${view.javaClass.name} id=${view.id}")
            return
        }
        once("hint-${view.javaClass.name}-$wanted", "preview color class=${view.javaClass.name} id=${view.id} TextSecondary=${Integer.toHexString(wanted)} dark=${dark()}")
    }

    private fun secondaryColor(view: View): Int = secondaryColor(view.context)

    private fun secondaryColor(context: android.content.Context): Int {
        val night = dark()
        return synchronized(secondaryPalette) { secondaryPalette.getOrPut(night) {
            val config = android.content.res.Configuration(context.resources.configuration)
            config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            val color = context.createConfigurationContext(config).resources.getColor(0x7f060194, null)
            log("preview semantic palette forcedNight=$night TextSecondary=${Integer.toHexString(color)}; old View Context ignored")
            color
        } }
    }

    private fun belongsToMain(view: View): Boolean {
        var context = view.context
        while (context is android.content.ContextWrapper) {
            if (context is android.app.Activity) return context.javaClass.name == "com.ss.android.ugc.aweme.main.MainActivity"
            val base = context.baseContext
            if (base === context) return false
            context = base
        }
        return false
    }

    private fun correctSpans(text: CharSequence, wanted: Int): CharSequence? {
        if (text !is Spanned) return null
        var copy: SpannableStringBuilder? = null
        for (span in text.getSpans(0, text.length, android.text.style.CharacterStyle::class.java)) {
            val underlying = span.underlying
            val color = when (underlying) {
                is ForegroundColorSpan -> underlying.foregroundColor
                is android.text.style.TextAppearanceSpan -> underlying.textColor?.defaultColor ?: continue
                else -> continue
            }
            val r = color shr 16 and 255; val g = color shr 8 and 255; val b = color and 255
            // Preserve red group notices, blue links and other colored emphasis.
            val neutral = maxOf(r, g, b) - minOf(r, g, b) < 48
            val wrong = if (dark()) maxOf(r, g, b) < 80 else minOf(r, g, b) > 200
            if (!neutral || !wrong || color == wanted || color ushr 24 == 0) continue
            val target = copy ?: SpannableStringBuilder(text).also { copy = it }
            target.removeSpan(span)
            val replacement = if (underlying is android.text.style.TextAppearanceSpan) {
                // Preserve typeface, size and style when correcting a cached text appearance.
                android.text.style.TextAppearanceSpan(underlying.family, underlying.textStyle, underlying.textSize,
                    android.content.res.ColorStateList.valueOf(wanted), underlying.linkTextColor)
            } else ForegroundColorSpan(wanted)
            target.setSpan(replacement, text.getSpanStart(span), text.getSpanEnd(span), text.getSpanFlags(span))
            once("preview-span-${underlying.javaClass.name}-$wanted",
                "preview stale color span=${underlying.javaClass.name} color=${Integer.toHexString(color)} -> ${Integer.toHexString(wanted)}")
        }
        return copy
    }

    private fun diagnose(stage: String, block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            once("failure-$stage-${failure.javaClass.name}", "FAILED $stage: $failure")
        }
    }
    private fun once(key: String, message: String) { synchronized(seen) { if (seen.add(key)) log(message) } }
}
