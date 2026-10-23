package cn.zhe.douyinsystemtheme

import android.app.Activity
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import de.robv.android.xposed.XposedHelpers

/** Same-process, version-specific restoration of the embedded Rips chat, not just its tab. */
internal class ChatSessionRestore(private val loader: ClassLoader, private val log: (String) -> Unit) {
    private data class Host(val type: String, val id: Int, val tag: String?)
    private data class Snapshot(val epoch: Int, val type: String, val id: Int, val tag: String?,
        val hosts: List<Host>, val arguments: Bundle, val fragmentState: Any?,
        val conversation: String, val draft: String?, val scroll: Parcelable?)
    private val pending = HashMap<Int, Snapshot>()
    private val submitted = HashSet<Int>()
    private data class DrawTarget(val epoch: Int, val type: String, val conversation: String)
    private val drawTargets = HashMap<Int, DrawTarget>()
    private val chatClass by lazy { Class.forName(CHAT, false, loader) }

    private fun children(manager: Any): List<Any> =
        (XposedHelpers.callMethod(manager, "getFragments") as? List<*>)?.filterNotNull() ?: emptyList()

    private fun walk(manager: Any, visit: (Any) -> Boolean): Any? {
        for (fragment in children(manager)) {
            if (visit(fragment)) return fragment
            walk(XposedHelpers.callMethod(fragment, "getChildFragmentManager"), visit)?.let { return it }
        }
        return null
    }

    private fun views(root: View?, predicate: (View) -> Boolean): View? {
        if (root == null || !root.isShown) return null
        if (predicate(root)) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) views(root.getChildAt(i), predicate)?.let { return it }
        return null
    }

    fun capture(activity: Activity, epoch: Int) {
        if (activity.javaClass.name != MAIN) return
        pending.remove(activity.taskId)
        submitted.remove(activity.taskId)
        drawTargets.remove(activity.taskId)
        val manager = XposedHelpers.callMethod(activity, "getSupportFragmentManager")
        val fragment = walk(manager) {
            chatClass.isInstance(it) && (XposedHelpers.callMethod(it, "getView") as? View)?.isShown == true
        } ?: return
        val id = XposedHelpers.callMethod(fragment, "getId") as Int
        if (id <= 0) { log("FAILED chat capture: embedded fragment has no restorable container"); return }
        val arguments = XposedHelpers.callMethod(fragment, "getArguments") as? Bundle
            ?: error("visible chat has no arguments")
        val session = XposedHelpers.callMethod(fragment, "getSessionInfo") ?: error("chat session missing")
        val conversation = XposedHelpers.callMethod(session, "getConversationId") as? String
            ?: error("chat conversation missing")
        val hosts = ArrayList<Host>()
        var parent = XposedHelpers.callMethod(fragment, "getParentFragment")
        while (parent != null) {
            hosts.add(Host(parent.javaClass.name, XposedHelpers.callMethod(parent, "getId") as Int,
                XposedHelpers.callMethod(parent, "getTag") as? String))
            parent = XposedHelpers.callMethod(parent, "getParentFragment")
        }
        val view = XposedHelpers.callMethod(fragment, "getView") as? View
        val inputId = activity.resources.getIdentifier("msg_et", "id", "com.ss.android.ugc.aweme")
        val draft = (views(view) { it is EditText && inputId != 0 && it.id == inputId } as? EditText)?.text?.toString()
        val list = views(view) { it.javaClass.name == "com.ss.android.ugc.aweme.im.widget.CustomRecyclerView" }
        val layout = list?.let { XposedHelpers.callMethod(it, "getLayoutManager") }
        val scroll = layout?.let { XposedHelpers.callMethod(it, "onSaveInstanceState") as? Parcelable }
        val owner = XposedHelpers.callMethod(fragment, "getParentFragmentManager")
        pending[activity.taskId] = Snapshot(epoch, fragment.javaClass.name, id,
            XposedHelpers.callMethod(fragment, "getTag") as? String, hosts.reversed(),
            Bundle(arguments).apply { classLoader = loader },
            XposedHelpers.callMethod(owner, "LJLJJLL", fragment), conversation, draft, scroll)
        drawTargets[activity.taskId] = DrawTarget(epoch, fragment.javaClass.name, conversation)
        log("chat captured epoch=$epoch task=${activity.taskId} container=$id hosts=${hosts.size} draftLength=${draft?.length} scroll=${scroll != null}; conversation retained in memory")
    }

    /** false means the restored tab/fragment host has not finished attaching yet. */
    fun restore(activity: Activity, epoch: Int, attempt: Int): Boolean {
        val snapshot = pending[activity.taskId] ?: return true
        if (snapshot.epoch != epoch) { pending.remove(activity.taskId); return true }
        val manager = XposedHelpers.callMethod(activity, "getSupportFragmentManager")
        val existing = walk(manager) { it.javaClass.name == snapshot.type }
        if (existing != null) {
            val root = XposedHelpers.callMethod(existing, "getView") as? View
            if (root?.isShown != true) return waitOrExpire(activity, attempt)
            val session = XposedHelpers.callMethod(existing, "getSessionInfo") ?: return false
            if (XposedHelpers.callMethod(session, "getConversationId") != snapshot.conversation) {
                log("FAILED chat restore: another conversation is visible; will not replace it")
                pending.remove(activity.taskId)
                return true
            }
            val inputId = activity.resources.getIdentifier("msg_et", "id", "com.ss.android.ugc.aweme")
            val input = views(root) { it is EditText && inputId != 0 && it.id == inputId } as? EditText
            if (input != null && snapshot.draft != null && input.text.isNullOrEmpty()) {
                input.setText(snapshot.draft)
                input.setSelection(input.text.length)
            }
            if (snapshot.scroll != null) {
                val list = views(root) { it.javaClass.name == "com.ss.android.ugc.aweme.im.widget.CustomRecyclerView" }
                val layout = list?.let { XposedHelpers.callMethod(it, "getLayoutManager") }
                if (layout != null) XposedHelpers.callMethod(layout, "onRestoreInstanceState", snapshot.scroll)
            }
            pending.remove(activity.taskId)
            submitted.remove(activity.taskId)
            log("chat restored epoch=$epoch task=${activity.taskId} native=${attempt == 0}; conversation matches; no tab navigation")
            return true
        }
        if (activity.taskId in submitted) return waitOrExpire(activity, attempt)
        var owner = manager
        for (host in snapshot.hosts) {
            val match = children(owner).firstOrNull {
                it.javaClass.name == host.type && XposedHelpers.callMethod(it, "getId") == host.id &&
                    XposedHelpers.callMethod(it, "getTag") == host.tag
            } ?: return waitOrExpire(activity, attempt)
            owner = XposedHelpers.callMethod(match, "getChildFragmentManager")
        }
        if (XposedHelpers.callMethod(owner, "isStateSaved") == true ||
            activity.findViewById<View>(snapshot.id) == null) return waitOrExpire(activity, attempt)
        // 40.7.0 also obfuscates FragmentFactory; these chat fragments have a no-arg
        // constructor, matching the default factory's instantiation contract.
        val fragment = Class.forName(snapshot.type, false, loader).getDeclaredConstructor().newInstance()
        XposedHelpers.callMethod(fragment, "setArguments", Bundle(snapshot.arguments))
        if (snapshot.fragmentState != null) XposedHelpers.callMethod(fragment, "setInitialSavedState", snapshot.fragmentState)
        val transaction = XposedHelpers.callMethod(owner, "beginTransaction")
        // A theme rebuild is not a user opening a conversation. Do not crossfade the
        // message list with the restored chat while its view is attaching.
        XposedHelpers.callMethod(transaction, "setCustomAnimations", 0, 0, 0, 0)
        XposedHelpers.callMethod(transaction, "setTransition", 0)
        XposedHelpers.callMethod(transaction, "add", snapshot.id, fragment, snapshot.tag)
        XposedHelpers.callMethod(transaction, "addToBackStack", "system-theme-chat-restore")
        XposedHelpers.callMethod(transaction, "commit")
        submitted.add(activity.taskId)
        log("chat restore submitted epoch=$epoch task=${activity.taskId} originalContainer=${snapshot.id}; preserved fragment arguments/state")
        return false
    }

    fun hasDrawTarget(task: Int, epoch: Int) = drawTargets[task]?.epoch == epoch

    fun isChatDrawReady(activity: Activity, epoch: Int): Boolean {
        val expected = drawTargets[activity.taskId] ?: return false
        if (expected.epoch != epoch) return false
        val manager = XposedHelpers.callMethod(activity, "getSupportFragmentManager")
        val fragment = walk(manager) {
            it.javaClass.name == expected.type && (XposedHelpers.callMethod(it, "getView") as? View)?.isShown == true
        } ?: return false
        val root = XposedHelpers.callMethod(fragment, "getView") as? View ?: return false
        if (!root.isLaidOut || root.width == 0 || root.height == 0) return false
        var view: View? = root
        while (view != null) {
            if (view.alpha < .999f || view.animation?.let { !it.hasEnded() } == true) return false
            view = view.parent as? View
        }
        val session = XposedHelpers.callMethod(fragment, "getSessionInfo") ?: return false
        return XposedHelpers.callMethod(session, "getConversationId") == expected.conversation
    }

    fun finishDrawGate(task: Int) { drawTargets.remove(task) }

    private fun waitOrExpire(activity: Activity, attempt: Int): Boolean {
        if (attempt < 30) return false
        pending.remove(activity.taskId)
        submitted.remove(activity.taskId)
        log("FAILED chat restore timeout task=${activity.taskId}; no duplicate fragment submitted")
        return true
    }

    companion object {
        private const val MAIN = "com.ss.android.ugc.aweme.main.MainActivity"
        private const val CHAT = "com.ss.android.ugc.aweme.im.business.chat.root.RipsChatRoomFragment"
    }
}
