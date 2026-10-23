package cn.zhe.douyinimmersivenav

import android.content.res.Resources
import android.os.Build
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.EditText
import de.robv.android.xposed.XposedBridge
import java.util.IdentityHashMap
import kotlin.math.abs

/** Private-video controls retain their accepted positions; photo content reserves the nav inset. */
internal class VideoBottomOverlays(private val root: View, private val fallbackNav: Int) {
    private data class Saved(val view: View, val original: Float, var applied: Float)
    private data class Clip(val view: ViewGroup, val children: Boolean, val padding: Boolean)
    private data class Roles(
        val page: ViewGroup, val input: ViewGroup, val search: View,
        val intro: View, val body: View, val rail: View?, val seek: View?
    )
    private val names = SparseArray<String>()
    private val all = ArrayList<View>()
    private val pageViews = ArrayList<View>()
    private val stack = ArrayList<View>()
    private val pages = ArrayList<ViewGroup>()
    private val saved = IdentityHashMap<View, Saved>()
    private val clips = ArrayList<Clip>()
    private val position = IntArray(2)
    private val rootPosition = IntArray(2)
    private var roles: Roles? = null
    private val photoInsets = VideoPhotoInsets(root)
    private val mediaSafety = VideoMediaSafety(root)
    private var publicActive = false
    private var selectedPage: ViewGroup? = null
    private var dirty = true
    private var loggedPage: ViewGroup? = null
    private var waitingPage: ViewGroup? = null
    private var deferredFrames = 0
    private var waitStarted = 0L
    private var lastMissing = ""
    private var lastCorrection = ""
    private var pageLoggedSkip: ViewGroup? = null
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { dirty = true }

    var input: ViewGroup? = null
        private set
    var isPrivatePage = false
        private set

    init {
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
    }
    fun markDirty() { dirty = true }

    fun sync(now: Long, preDraw: Boolean = false): Boolean {
        if (!root.isAttachedToWindow || root.height == 0 || root.width == 0) return true
        PageNavigationSafeArea.syncPublicVideo(root, preDraw)?.let { ready ->
            if (!publicActive) {
                restoreTransaction()
                roles = null
                input = null
                isPrivatePage = false
                publicActive = true
            }
            return ready
        }
        publicActive = false
        root.getLocationOnScreen(rootPosition)
        val navTop = navigationTop()
        if (dirty || pages.isEmpty() || pages.any { !it.isAttachedToWindow }) {
            collect(root, all)
            pages.clear()
            for (view in all) if (view is ViewGroup && isPage(view)) pages.add(view)
        }
        // ViewPager scrolls without requesting layout. Compare the cached page bounds EVERY draw.
        val page = pages.filter { it.isShown && it.width > 0 && it.height > 0 }
            .maxByOrNull { intersectionArea(it, navTop) }
            ?.takeIf { intersectionArea(it, navTop) > 0L }
            ?: input?.let { ancestor(it) { candidate -> isPage(candidate) } as? ViewGroup }
        if (page !== selectedPage) {
            restoreTransaction()
            selectedPage = page
            roles = null
            input = null
            loggedPage = null
            pageLoggedSkip = null
            lastMissing = ""
            lastCorrection = ""
            dirty = true
        }
        if (page == null) { dirty = true; return true }
        if (dirty || roles == null || roles?.input?.isAttachedToWindow != true) {
            // all may predate a page scroll; refresh external input/seek anchors on selection too.
            collect(root, all)
            collect(page, pageViews)
            discover(page, navTop)
            dirty = false
        }
        val current = roles
        if (current == null) {
            // Public/search videos use the comment composer (e19), not the
            // private-message row. Its gesture clearance must not depend on
            // optional intro/related-search controls being inflated.
            input?.takeIf { preDraw && measured(it) }?.let { composer ->
                val ime = Build.VERSION.SDK_INT >= 30 &&
                    root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
                if (ime) restoreTransaction() else pinBottom(composer, composer, navTop)
            }
            val hasPictureIndicator = pageViews.any { name(it) == "progress_bar" }
            if (input == null && hasPictureIndicator) {
                // An input-less picture post is a native page variant, not a partial transaction.
                if (pageLoggedSkip !== page) {
                    log("video pin skipped input-less picture page=${bounds(page)}; native drawing preserved")
                    pageLoggedSkip = page
                }
                isPrivatePage = true
                return true
            }
            if (input == null) return true
            // Never hide individual roles. Allow at most three cancelled draws while inflation finishes.
            if (waitingPage !== page) {
                waitingPage = page; waitStarted = now; deferredFrames = 0
            }
            if (preDraw && deferredFrames < 3 && now - waitStarted < 150L) {
                deferredFrames++
                dirty = true
                root.postInvalidateOnAnimation()
                return false
            }
            return true
        }
        if (!preDraw) { root.postInvalidateOnAnimation(); return true }
        if (Build.VERSION.SDK_INT >= 30 && root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) {
            // Video input IME layout is not the private-chat dock; leave its native keyboard handling intact.
            restoreTransaction()
            return true
        }
        if (!measured(current.input) || !measured(current.search) || !measured(current.intro)) {
            // A post can permanently omit the related-search row, including
            // keeping a zero-height placeholder. Still reserve gesture space.
            if (measured(current.input)) pinBottom(current.input, current.input, navTop)
            dirty = true
            return true
        }
        val before = if (loggedPage !== page) describeRoles(current) else ""
        // 1. Absolute input bottom. 2. Search bottom follows input top with exactly 18px gap.
        pinBottom(current.input, current.input, navTop)
        // No viewport/renderer resizing, scaling or clipping. Only a confirmed photo-content
        // wrapper receives a bottom inset; video rendering is entirely owned by Douyin.
        photoInsets.sync(page, pageViews, top(current.input))
        val searchBottom = top(current.input) - INPUT_SEARCH_GAP_PX
        val searchTarget = if (inside(current.search, current.body)) current.body else current.search
        pinBottom(searchTarget, current.search, searchBottom)
        // Prefer the intro's wrapper. If it also contains search, it has already moved as one unit.
        // Otherwise (xcf) these are sibling branches: constrain both in this same pre-draw pass.
        val introBottom = top(current.search)
        val body = current.body
        if (inside(current.search, body)) {
            // A shared wrapper can only preserve an existing adjacency; correct a mismatched child locally.
            pinBottom(current.intro, current.intro, introBottom)
        } else {
            pinBottom(body, current.intro, introBottom)
        }
        current.rail?.let { rail ->
            // Rail follows the ACTUAL intro displacement, not a fixed -56 or its own box bottom.
            val introShift = displacement(body) +
                (if (body !== current.intro) displacement(current.intro) else 0f)
            setTranslation(rail, original(rail) + introShift)
        }
        current.seek?.takeIf { measured(it) }?.let {
            // Native gpa height=154 => 2716 - 154 + 17 = 2579. Optional, never a readiness gate.
            pinBottom(it, it, top(current.input) + SEEK_BOTTOM_FROM_INPUT_TOP_PX)
        }
        verify(current, navTop, searchBottom)
        // Run after overlay correction on EVERY pre-draw, including layouts triggered by
        // asynchronous video-size callbacks. The holder receives at most one nav inset.
        mediaSafety.sync(page, current.input, photoInsets.appliedInset)
        if (loggedPage !== page) {
            log("video pin applied page=${bounds(page)} before={$before} after={${describeRoles(current)}} " +
                "check=${check(current, navTop, searchBottom)} player=native photoInset=${photoInsets.appliedInset}")
            loggedPage = page
        }
        return true
    }

    private fun discover(page: ViewGroup, navTop: Int) {
        fun local(id: String) = pageViews.firstOrNull { name(it) == id && it.isShown }
        val editor = all.filterIsInstance<EditText>().filter { view ->
            view.isShown && (name(view) == "swi" ||
                view.hint?.toString()?.contains("发消息或按住说话") == true)
        }.maxByOrNull { intersectionArea(it, navTop) }
        // gpa and SupportSeekBarFrameLayout are Activity-level siblings of the ViewPager in the
        // supplied runtime tree. Only permit those explicit external anchors; never external cne/b43/rail.
        val gpa = editor?.let { field -> ancestor(field) { view ->
            view is ViewGroup && (view.javaClass.simpleName == "MeasureOnceRelativeLayout2" || name(view) == "gpa")
        } as? ViewGroup } ?: all.filterIsInstance<ViewGroup>().filter { view ->
            name(view) == "gpa" && view.isShown && intersectionArea(view, navTop) > 0 &&
                all.any { (name(it) == "pju" || name(it) == "e19") && inside(it, view) }
        }.maxByOrNull { intersectionArea(it, navTop) }
        input = gpa
        if (gpa == null) {
            restoreTransaction(); roles = null
            return
        }
        // Reject an input explicitly owned by a different (preloaded) page.
        val inputPage = ancestor(gpa) { isPage(it) }
        if (inputPage != null && inputPage !== page) { input = null; roles = null; return }
        isPrivatePage = true
        val intro = pageViews.firstOrNull { it.javaClass.simpleName == "AwemeIntroInfoLayout" && it.isShown }
            ?: local("b43")
        val search = pageViews.firstOrNull { view ->
            view.javaClass.simpleName == "LinearLayoutCompat" && name(view) == "cne" && view.isShown
        } ?: local("cne") ?: pageViews.firstOrNull { view ->
            view is ViewGroup && view.javaClass.simpleName == "LinearLayoutCompat" &&
                pageViews.any { child -> child is android.widget.TextView &&
                    child.text?.toString() == "相关搜索" && inside(child, view) }
        }
        val body = intro?.let { view -> ancestorWithin(view, page) { candidate ->
            candidate.javaClass.simpleName == "MeasureOnceCheckDrawChildFrameLayout" || name(candidate) == "i-3"
        } } ?: intro
        val rail = pageViews.firstOrNull { view ->
            view.javaClass.simpleName == "MeasureOnceCheckDrawChildRelativeLayout" &&
                view.isShown && pageViews.any { child ->
                    (name(child) == "s7c" || name(child) == "6j4" || name(child) == "s0y") && inside(child, view)
                }
        } ?: local("i-7") ?: local("6j4")
        val localSeek = pageViews.firstOrNull { isSeek(it) && it.isShown }
        val externalSeek = all.filter { view -> isSeek(view) && view.isShown &&
            ancestor(view) { isPage(it) } == null &&
            ancestor(view) { it.javaClass.simpleName == "SupportSeekBarFrameLayout" } != null &&
            intersectionArea(view, navTop) > 0
        }.singleOrNull()
        val seek = localSeek ?: externalSeek
        if (intro == null || search == null || body == null) {
            val missing = "intro=${intro != null} search=${search != null}"
            if (missing != lastMissing) {
                log("video pin waiting page=${bounds(page)} $missing; no role hidden")
                lastMissing = missing
            }
            roles = null
            return
        }
        val next = Roles(page, gpa, search, intro, body, rail, seek)
        val desired = listOfNotNull(gpa, search, intro, body, rail, seek)
        // Replacement instances do not inherit an old instance's module translation.
        for (old in saved.values.toList()) if (desired.none { it === old.view }) {
            restore(old); saved.remove(old.view)
        }
        roles = next
    }

    private fun verify(r: Roles, navTop: Int, searchBottom: Int) {
        val failures = check(r, navTop, searchBottom)
        if (failures == "ok") { lastCorrection = ""; return }
        // One correction pass, no layout requests and no exceptions swallowed.
        pinBottom(r.input, r.input, navTop)
        val searchTarget = if (inside(r.search, r.body)) r.body else r.search
        pinBottom(searchTarget, r.search, top(r.input) - INPUT_SEARCH_GAP_PX)
        val body = if (inside(r.search, r.body)) r.intro else r.body
        pinBottom(body, r.intro, top(r.search))
        r.rail?.let { setTranslation(it, original(it) + displacement(r.body) +
            (if (r.body !== r.intro) displacement(r.intro) else 0f)) }
        r.seek?.takeIf { measured(it) }?.let { pinBottom(it, it, top(r.input) + SEEK_BOTTOM_FROM_INPUT_TOP_PX) }
        val result = "$failures -> ${check(r, navTop, top(r.input) - INPUT_SEARCH_GAP_PX)}"
        if (result != lastCorrection) {
            log("video pin corrected page=${bounds(r.page)} $result ${describeRoles(r)}")
            lastCorrection = result
        }
    }
    private fun check(r: Roles, navTop: Int, searchBottom: Int): String {
        val errors = ArrayList<String>(4)
        if (abs(bottom(r.input) - navTop) > 1) errors.add("gpa=${bottom(r.input)}/$navTop")
        if (abs(bottom(r.search) - searchBottom) > 1) errors.add("cne=${bottom(r.search)}/$searchBottom")
        if (abs(bottom(r.intro) - top(r.search)) > 1) errors.add("b43=${bottom(r.intro)}/${top(r.search)}")
        r.seek?.takeIf { measured(it) }?.let {
            val target = top(r.input) + SEEK_BOTTOM_FROM_INPUT_TOP_PX
            if (abs(bottom(it) - target) > 1) errors.add("seek=${bottom(it)}/$target")
        }
        return if (errors.isEmpty()) "ok" else errors.joinToString()
    }

    private fun pinBottom(target: View, reference: View, wantedBottom: Int) {
        // Subtract our current translation to obtain the unshifted layout baseline. Parent movement
        // is included in the actual screen coordinate, so a descendant is never lifted twice.
        val baseline = bottom(reference).toFloat() - target.translationY
        val wanted = wantedBottom - baseline
        if (abs(target.translationY - wanted) >= 0.5f) setTranslation(target, wanted)
    }
    private fun original(view: View): Float = saved[view]?.original ?: view.translationY
    private fun displacement(view: View): Float = view.translationY - original(view)
    private fun setTranslation(view: View, wanted: Float) {
        val record = saved[view] ?: Saved(view, view.translationY, view.translationY).also { saved[view] = it }
        allowOverflow(view, wanted)
        if (view.translationY != wanted) view.translationY = wanted
        record.applied = wanted
    }
    private fun allowOverflow(view: View, wanted: Float) {
        val targetTop = top(view) + wanted - view.translationY
        val targetBottom = targetTop + view.height
        var parent = view.parent as? ViewGroup
        // Never disable the page/ViewPager clipping: doing so would reveal adjacent pages.
        while (parent != null && parent !== selectedPage && parent !== root) {
            val group = parent
            if (targetTop < top(group) + group.paddingTop ||
                targetBottom > bottom(group) - group.paddingBottom) {
                if (clips.none { it.view === group }) clips.add(Clip(group, group.clipChildren, group.clipToPadding))
                group.clipChildren = false
                group.clipToPadding = false
            }
            parent = group.parent as? ViewGroup
        }
    }
    private fun restoreTransaction() {
        photoInsets.release()
        for (record in saved.values) restore(record)
        saved.clear()
        for (clip in clips) {
            clip.view.clipChildren = clip.children
            clip.view.clipToPadding = clip.padding
        }
        clips.clear()
        mediaSafety.release()
    }
    private fun restore(record: Saved) {
        if (record.view.translationY == record.applied) record.view.translationY = record.original
    }
    fun release() {
        if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        restoreTransaction()
        all.clear(); pageViews.clear(); pages.clear(); stack.clear()
        roles = null; input = null; selectedPage = null; isPrivatePage = false
    }
    private fun navigationTop(): Int {
        val nav = if (Build.VERSION.SDK_INT >= 30)
            root.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: fallbackNav
        else root.rootWindowInsets?.systemWindowInsetBottom ?: fallbackNav
        // Screen coordinates, including a nonzero content-root origin.
        return rootPosition[1] + root.height - nav
    }
    private fun intersectionArea(view: View, navTop: Int): Long {
        view.getLocationOnScreen(position)
        val w = (minOf(position[0] + view.width, rootPosition[0] + root.width) -
            maxOf(position[0], rootPosition[0])).coerceAtLeast(0)
        val h = (minOf(position[1] + view.height, navTop) - maxOf(position[1], rootPosition[1])).coerceAtLeast(0)
        return w.toLong() * h
    }
    private fun collect(start: View, output: MutableList<View>) {
        output.clear(); stack.clear(); stack.add(start)
        while (stack.isNotEmpty()) {
            val view = stack.removeAt(stack.lastIndex)
            if (view.visibility != View.VISIBLE) continue
            output.add(view)
            if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) stack.add(view.getChildAt(i))
        }
    }
    private fun isPage(view: View) = view.javaClass.simpleName == "MonitorScrollFrameLayout" || name(view) == "w0g"
    private fun isSeek(view: View) = view.javaClass.simpleName == "CustomizedUISeekBar" || name(view) == "6hz"
    private fun measured(view: View) = view.isAttachedToWindow && view.width > 0 && view.height > 0 && !view.isLayoutRequested
    private fun ancestor(view: View, predicate: (View) -> Boolean): View? = ancestorWithin(view, root, predicate)
    private fun ancestorWithin(view: View, limit: View, predicate: (View) -> Boolean): View? {
        var parent = view.parent as? View
        while (parent != null && parent !== limit) {
            if (predicate(parent)) return parent
            parent = parent.parent as? View
        }
        return null
    }
    private fun inside(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }
    private fun top(view: View): Int { view.getLocationOnScreen(position); return position[1] }
    private fun bottom(view: View) = top(view) + view.height
    private fun name(view: View): String {
        val id = view.id
        if (id == View.NO_ID || id ushr 24 == 0) return ""
        names[id]?.let { return it }
        val value = try { view.resources.getResourceEntryName(id) }
        catch (error: Resources.NotFoundException) {
            log("video pin resource unavailable id=$id $error"); ""
        }
        names.put(id, value)
        return value
    }
    private fun bounds(view: View): String {
        view.getLocationOnScreen(position)
        return "[${position[0]},${position[1]}][${position[0] + view.width},${position[1] + view.height}]"
    }
    private fun describeRoles(r: Roles): String = listOfNotNull(
        "gpa" to r.input, "cne" to r.search, "b43" to r.intro, "body" to r.body,
        r.rail?.let { "rail" to it }, r.seek?.let { "seek" to it }
    ).joinToString { (role, view) -> "$role=${view.javaClass.simpleName} id=${name(view)} ${bounds(view)} ty=${view.translationY}" }
    private fun log(message: String) = XposedBridge.log("[DouyinImmersiveNav] $message")

    private companion object {
        const val INPUT_SEARCH_GAP_PX = 18
        const val SEEK_BOTTOM_FROM_INPUT_TOP_PX = 17
    }
}
