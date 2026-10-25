package cn.zhe.douyinsystemtheme

import android.app.Notification
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.service.notification.StatusBarNotification
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** Replace only Douyin chat notification header artwork. Never alter or repost the notification. */
internal object NotificationAvatar {
    private const val PKG = "com.ss.android.ugc.aweme"
    private val workers = Executors.newFixedThreadPool(2)
    private val cache = LruCache<String, Bitmap>(24)
    private val pending = HashMap<String, MutableList<WeakReference<ViewGroup>>>()
    private val states = WeakHashMap<ViewGroup, State>()
    private var rowType: Class<*>? = null
    private var errorLogged = false
    private class IconState(val view: WeakReference<ImageView>, val original: Drawable?, val tint: ColorStateList?,
        val background: Drawable?, val scale: ImageView.ScaleType, val clip: Boolean, val replacement: Drawable)
    private class State(val row: WeakReference<ViewGroup>, val url: String) {
        val icons = ArrayList<IconState>()
        var bitmap: Bitmap? = null
        var listener: ViewTreeObserver.OnPreDrawListener? = null
    }
    fun install(loader: ClassLoader) {
        try {
            val type = Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, loader)
            rowType = type
            for (name in listOf("onNotificationUpdated", "onAttachedToWindow")) {
                XposedBridge.hookAllMethods(type, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val row = p.thisObject as? ViewGroup ?: return
                        try { update(row) } catch (t: Throwable) { failure(t) }
                    }
                })
            }
            // RemoteViews inflate asynchronously after onNotificationUpdated.
            // Rebind the newly inflated header instead of retaining the old ImageView.
            XposedBridge.hookAllMethods(type, "onLayout", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val row = p.thisObject as? ViewGroup ?: return
                    val state = states[row]?.takeIf { it.bitmap != null } ?: return
                    try { bindIcons(row, state) } catch (t: Throwable) { failure(t) }
                }
            })
            XposedBridge.log("[DouyinUnified] notification avatar SystemUI hooks installed; Douyin chat only")
        } catch (t: Throwable) { failure(t) }
    }
    private fun failure(t: Throwable) {
        if (!errorLogged) { errorLogged = true; XposedBridge.log("[DouyinUnified] notification avatar unavailable: ${t.javaClass.name}") }
    }
    private fun notification(row: ViewGroup): Notification? {
        val entry = XposedHelpers.callMethod(row, "getEntry") ?: return null
        val sbn = XposedHelpers.callMethod(entry, "getSbn") as? StatusBarNotification ?: return null
        return sbn.notification.takeIf { sbn.packageName == PKG }
    }
    private fun avatarUrl(n: Notification): String? {
        try {
        val intent = n.contentIntent?.let { XposedHelpers.callMethod(it, "getIntent") as? android.content.Intent } ?: return null
        val data = intent.getStringExtra("push_body") ?: return null
        val push = JSONObject(data)
        val route = Uri.parse(push.optString("open_url"))
        if (route.host != "chat" || route.getQueryParameter("conversation_id").isNullOrBlank()) return null
        val value = push.optString("image_url")
        val uri = Uri.parse(value)
        val host = uri.host ?: return null
        if (uri.scheme != "https" || !listOf("douyinpic.com", "byteimg.com", "pstatp.com", "douyincdn.com").any { host == it || host.endsWith(".$it") }) return null
        return value
        } catch (_: Throwable) { return null }

    }
    private fun update(row: ViewGroup) {
        val url = notification(row)?.let(::avatarUrl)
        if (url == null) { release(row); return }
        val existing = states[row]
        if (existing?.url == url && existing.bitmap != null) { bindIcons(row, existing); return }
        if (existing?.url != url) { release(row); states[row] = State(WeakReference(row), url) }
        val state = states[row] ?: return
        val cached = cache.get(url)
        if (cached != null) { state.bitmap = cached; bindIcons(row, state); return }
        synchronized(pending) {
            val list = pending[url]
            if (list != null) { if (list.none { it.get() === row }) list.add(WeakReference(row)); return }
            pending[url] = arrayListOf(WeakReference(row))
        }
        val context = row.context.applicationContext
        workers.execute {
            val bitmap = try { fetch(url)?.let { artwork(context, it) } } catch (t: Throwable) { failure(t); null }
            if (bitmap != null) cache.put(url, bitmap)
            val targets = synchronized(pending) { pending.remove(url).orEmpty() }
            for (ref in targets) ref.get()?.post {
                val current = ref.get() ?: return@post
                val currentState = states[current]?.takeIf { it.url == url } ?: return@post
                // A recycled row may now represent a different conversation.
                try {
                    if (notification(current)?.let(::avatarUrl) != url) { release(current); return@post }
                    if (bitmap != null) { currentState.bitmap = bitmap; bindIcons(current, currentState) }
                } catch (t: Throwable) { failure(t) }
            }
        }
    }
    private fun fetch(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 4000; connection.readTimeout = 4000
        connection.instanceFollowRedirects = false
        try {
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    if (out.size() + count > 2 * 1024 * 1024) return null
                    out.write(buffer, 0, count)
                }; out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096) return null
            val options = BitmapFactory.Options().apply { inSampleSize = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 256) }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally { connection.disconnect() }
    }
    private fun artwork(context: Context, image: Bitmap): Bitmap {
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = 150f / minOf(image.width, image.height)
        shader.setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate((150-image.width*scale)/2, (150-image.height*scale)/2) })
        paint.shader = shader; canvas.drawCircle(75f,75f,75f,paint); paint.shader = null
        val appIcon = context.packageManager.getApplicationIcon(PKG).mutate()
        appIcon.setBounds(107,107,157,157); appIcon.draw(canvas)
        return bitmap
    }
    private fun bindIcons(row: ViewGroup, state: State) {
        val bitmap = state.bitmap ?: return
        state.icons.removeAll { it.view.get() == null }
        val iconId = android.R.id.icon
        val groupIconId = row.resources.getIdentifier("icon", "id", "com.android.systemui")
        fun visit(view: View) {
            if (view !== row && rowType?.isInstance(view) == true) return
            if (view is ImageView && (view.id == iconId || groupIconId != 0 && view.id == groupIconId) && state.icons.none { it.view.get() === view }) {
                val drawable = BitmapDrawable(view.resources, bitmap)
                state.icons.add(IconState(WeakReference(view), view.drawable, view.imageTintList, view.background,
                    view.scaleType, view.clipToOutline, drawable))
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(row)
        fun applyIcons() {
            for (saved in state.icons) saved.view.get()?.let {
                if (it.drawable !== saved.replacement) it.setImageDrawable(saved.replacement)
                if (it.imageTintList != null) it.imageTintList = null
                if (it.background != null) it.background = null
                if (it.scaleType != ImageView.ScaleType.FIT_CENTER) it.scaleType = ImageView.ScaleType.FIT_CENTER
                if (it.clipToOutline) it.clipToOutline = false
            }
        }
        applyIcons()
        XposedHelpers.setAdditionalInstanceField(row, "cn.zhe.notification.prepare_artwork", Runnable {
            // A collapsed stack renders this child's content without dispatching the
            // hidden row. Reapply only this live state's already loaded artwork.
            if (states[row] === state) applyIcons()
        })
        if (state.listener == null && row.viewTreeObserver.isAlive) {
            val listener = ViewTreeObserver.OnPreDrawListener { if (state.row.get()?.isAttachedToWindow == true && states[row] === state) applyIcons(); true }
            state.listener = listener; row.viewTreeObserver.addOnPreDrawListener(listener)
            row.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    release(v as ViewGroup); v.removeOnAttachStateChangeListener(this)
                }
            })
            XposedBridge.log("[DouyinUnified] notification avatar bound header icons=${state.icons.size}; no notification text changed")
        }
    }
    private fun release(row: ViewGroup) {
        val state = states.remove(row) ?: return
        XposedHelpers.removeAdditionalInstanceField(row, "cn.zhe.notification.prepare_artwork")
        if (row.viewTreeObserver.isAlive) state.listener?.let(row.viewTreeObserver::removeOnPreDrawListener)
        for (saved in state.icons) saved.view.get()?.let {
            if (it.drawable === saved.replacement) {
                it.setImageDrawable(saved.original); it.imageTintList = saved.tint; it.background = saved.background
                it.scaleType = saved.scale; it.clipToOutline = saved.clip
            }
        }
    }
}
