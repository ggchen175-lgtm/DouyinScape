package cn.zhe.douyinsystemtheme

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.util.WeakHashMap

/** Only standalone circular chat scroll controls; never avatars or message-row media. */
internal class ChatScrollControlFix(private val dark: () -> Boolean, private val log: (String) -> Unit) {
    private val originals = WeakHashMap<View, Drawable?>()
    private val applied = WeakHashMap<View, Drawable>()
    private val iconOriginal = WeakHashMap<ImageView, ColorStateList?>()
    private val iconApplied = WeakHashMap<ImageView, ColorStateList>()
    private val rect = Rect()
    private val rootRect = Rect()
    private val names = android.util.SparseArray<String>()
    private var lastScan = 0L
    private var lastNight: Boolean? = null
    private var scope = java.lang.ref.WeakReference<ViewGroup>(null)
    private var nativeContext: android.content.Context? = null
    private var nativeNight: Boolean? = null
    private val nativeModes = WeakHashMap<View, Boolean>()
    fun sync(root: ViewGroup?, force: Boolean = false) {
        if (root == null) { release(); return }
        if (scope.get() !== root) { release(); scope = java.lang.ref.WeakReference(root) }
        val now = android.os.SystemClock.uptimeMillis()
        if (!force && lastNight == dark() && now - lastScan < 250L) return
        lastScan = now; lastNight = dark()
        if (!root.getGlobalVisibleRect(rootRect)) return
        val density = root.resources.displayMetrics.density
        // 40.7.0 NewMsgTipsUI: te1 is a LayerDrawable, tez's arrow is its BACKGROUND.
        // Reload the native day/night resources; neither GradientDrawable nor imageTint
        // handling can update this control. te0 is the surrounding ShadowFrameLayout.
        val nativeTip = ChatFloatingControl.find(root)
        val nativeSurface = nativeTip?.let(ChatFloatingControl::surface)
        val nativeArrow = nativeSurface?.let { group -> (0 until group.childCount)
            .map { group.getChildAt(it) }.filterIsInstance<ImageView>().firstOrNull() }
        if (nativeTip?.isShown == true && nativeSurface != null && nativeArrow != null) {
            if (nativeNight != dark() || nativeContext == null) {
                val config = android.content.res.Configuration(root.resources.configuration)
                config.uiMode = (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark()) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                nativeContext = root.context.createConfigurationContext(config)
                nativeNight = dark()
            }
            val context = nativeContext!!
            fun resourceBackground(view: View, id: Int) {
                if (applied[view] === view.background && applied.containsKey(view) && nativeModes[view] == dark()) return
                if (applied[view] !== view.background) originals[view] = view.background
                val replacement = context.resources.getDrawable(id, context.theme).mutate()
                view.background = replacement; applied[view] = replacement
                nativeModes[view] = dark()
                log("chat scroll native resource id=${name(view)} night=${dark()} resource=${Integer.toHexString(id)}")
            }
            // These resource IDs were verified on 40.7 only. New layouts retain
            // their own native background instead of receiving an unrelated asset.
            if (name(nativeTip) == "te0") {
                resourceBackground(nativeSurface, 0x7f083af2)
                resourceBackground(nativeArrow, 0x7f084074)
            }
        }
        fun visit(v: View, inMessages: Boolean, inDock: Boolean) {
            if (!v.isShown) return
            if (v === nativeTip) return // Already handled by its verified native resource identity.
            val kind = v.javaClass.simpleName
            val message = inMessages || kind.contains("RecyclerView")
            val idName = name(v)
            val dock = inDock || idName in setOf("jak", "mxb", "msg_et")
            val explicit = v.contentDescription?.toString()?.let {
                it.contains("回到底部") || it.contains("滚动到最新") || it.contains("回到最新")
            } == true || kind.contains("ScrollToBottom") || idName.contains("scroll_to_bottom")
            val bg = v.background as? GradientDrawable
            val round = bg != null && (bg.shape == GradientDrawable.OVAL ||
                (android.os.Build.VERSION.SDK_INT >= 24 && bg.cornerRadius >= minOf(v.width, v.height) * 0.4f))
            val size = v.width in (24 * density).toInt()..(72 * density).toInt() &&
                kotlin.math.abs(v.width - v.height) <= 4 * density
            val visible = v.getGlobalVisibleRect(rect)
            val floating = visible && rect.centerX() in (rootRect.left + rootRect.width() * 0.35f).toInt()..
                (rootRect.left + rootRect.width() * 0.65f).toInt() &&
                rect.centerY() in (rootRect.top + rootRect.height() * 0.55f).toInt()..
                (rootRect.top + rootRect.height() * 0.95f).toInt()
            if (!dock && (explicit || (!message && round && size && floating && v.hasOnClickListeners()))) {
                val color = if (dark()) 0xff393939.toInt() else 0xffffffff.toInt()
                val raw = when (val d = v.background) {
                    is ColorDrawable -> d.color
                    is GradientDrawable -> if (android.os.Build.VERSION.SDK_INT >= 24) d.color?.defaultColor else null
                    else -> null
                }
                if (raw in setOf(0xff393939.toInt(), 0xffffffff.toInt(), 0xffefefef.toInt()) && raw != color) {
                    if (applied[v] !== v.background) originals[v] = v.background
                    val replacement = v.background?.constantState?.newDrawable(v.resources)?.mutate()
                    when (replacement) {
                        is ColorDrawable -> replacement.color = color
                        is GradientDrawable -> replacement.setColor(color)
                    }
                    if (replacement != null) { v.background = replacement; applied[v] = replacement }
                    log("chat scroll control class=${v.javaClass.name} id=$idName night=${dark()} color=${Integer.toHexString(color)}")
                }
                fun icons(view: View) {
                    if (view is ImageView) {
                        val tint = if (dark()) 0xffeeeeee.toInt() else 0xff161616.toInt()
                        if (view.imageTintList?.defaultColor != tint) {
                            if (!iconOriginal.containsKey(view)) iconOriginal[view] = view.imageTintList
                            val list = ColorStateList.valueOf(tint)
                            view.imageTintList = list; iconApplied[view] = list
                        }
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) icons(view.getChildAt(i))
                }
                icons(v)
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i), message, dock)
        }
        visit(root, false, false)
    }
    private fun name(v: View): String {
        if (v.id == View.NO_ID || v.id ushr 24 == 0) return ""
        names[v.id]?.let { return it }
        val name = try { v.resources.getResourceEntryName(v.id) }
        catch (failure: android.content.res.Resources.NotFoundException) {
            log("scroll control resource lookup failed id=${v.id}: $failure"); ""
        }
        names.put(v.id, name)
        return name
    }
    fun release() {
        for ((v, drawable) in applied) if (v.background === drawable) v.background = originals[v]
        for ((v, tint) in iconApplied) if (v.imageTintList === tint) v.imageTintList = iconOriginal[v]
        originals.clear(); applied.clear(); iconOriginal.clear(); iconApplied.clear()
        scope.clear(); lastNight = null; lastScan = 0L; nativeContext = null; nativeNight = null; nativeModes.clear()
    }
}
