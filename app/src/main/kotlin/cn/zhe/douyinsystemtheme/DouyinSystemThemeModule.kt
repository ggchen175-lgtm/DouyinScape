package cn.zhe.douyinsystemtheme

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Field
import java.util.WeakHashMap

/** Native theme bridge for the inspected Douyin 40.7.0 APK. No process termination hooks. */
class DouyinSystemThemeModule : IXposedHookLoadPackage {
    // Xposed constructs this class before the application's main Looper exists.
    // Create the Handler only in initialize(), after Application.attach has returned.
    private lateinit var main: Handler
    private val pageEpochs = WeakHashMap<Activity, Int>()
    private val pendingRecreates = WeakHashMap<Activity, Boolean>()
    private val processedEpochs = WeakHashMap<Activity, Int>()
    private val recreatedPages = WeakHashMap<Activity, Boolean>()
    private val incomingSavedState = WeakHashMap<Activity, Boolean>()
    private val pendingTabs = HashMap<Int, TabSnapshot>()
    private val savedMainStates = HashMap<Int, Bundle>()
    private val deferredPages = WeakHashMap<Activity, Int>()
    private val restoringMainState = ThreadLocal<Bundle>()
    private val delegateStateHooks = HashSet<Class<*>>()
    private val rebuildInFlight = WeakHashMap<Activity, Boolean>()
    private val miraHookedClasses = HashSet<Class<*>>()
    private val errors = HashSet<String>()
    private val diagnostics = HashMap<String, String>()
    private var application: Application? = null
    private var resumed = WeakReference<Activity>(null)
    private var lastForeground = WeakReference<Activity>(null)
    private lateinit var loader: ClassLoader
    private lateinit var skin: Class<*>
    private lateinit var setMode: Method
    private lateinit var getMode: Method
    private lateinit var defaultModeField: Field
    private lateinit var viewRepairs: ThemeViewRepairs
    private lateinit var chatRestore: ChatSessionRestore
    private lateinit var updateResources: Method
    private lateinit var repo: Any
    private lateinit var readPreference: Method
    @Volatile private var systemNight = Configuration.UI_MODE_NIGHT_UNDEFINED
    @Volatile private var ready = false
    @Volatile private var preferenceChanged = false
    private var installedVersion = ""
    private var appliedMode = 0
    @Volatile private var rebuilding = false
    @Volatile private var applyAfterBatch = false
    private var queuedRecreates = 0
    private var rebuildStartedAt = 0L
    private var epoch = 0
    private val applyTask = Runnable { safely("apply") { applySystemTheme() } }
    private val finishBatchTask = Runnable { safely("finish recreate batch") { finishRecreateBatch() } }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        log("entered, pkg=${param.packageName}, process=${param.processName}; fix=handler-late")
        if (param.packageName != PACKAGE || param.processName != PACKAGE) return
        loader = param.classLoader
        XposedHelpers.findAndHookMethod(Application::class.java, "attach", Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (application != null) return
                    application = param.thisObject as Application
                    safely("initialization") { initialize(application!!) }
                }
            })
    }

    fun start(app: Application, classLoader: ClassLoader) {
        if (application != null) return
        application = app; loader = classLoader
        safely("unified theme initialization") { initialize(app) }
    }

    private fun initialize(app: Application) {
        val mainLooper = checkNotNull(Looper.getMainLooper()) {
            "Main Looper unavailable after Application.attach"
        }
        main = Handler(mainLooper)
        val version = app.packageManager.getPackageInfo(PACKAGE, 0).versionName
        installedVersion = version
        // Obfuscated method contracts below were verified against 40.7.0 only.
        if (version !in setOf("40.7.0", "40.8.0")) {
            log("disabled: inspected versions=40.7.0/40.8.0, installed=$version")
            return
        }
        skin = Class.forName("com.ss.android.ugc.aweme.setting.TiktokSkinHelper", false, loader)
        val delegate = Class.forName("androidx.appcompat.app.AppCompatDelegate", false, loader)
        setMode = delegate.getDeclaredMethod("LJJIFFI", Int::class.javaPrimitiveType).apply { isAccessible = true }
        getMode = delegate.getDeclaredMethod("getDefaultNightMode").apply { isAccessible = true }
        defaultModeField = delegate.getDeclaredField("a").apply { isAccessible = true }
        check(defaultModeField.type == Int::class.javaPrimitiveType) { "AppCompat default mode field has changed" }
        updateResources = skin.getDeclaredMethod("LJIIJ", Resources::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        log("resolved skin=${skin.name}; setter=$setMode; getter=$getMode; resources=$updateResources")
        val keva = Class.forName("com.bytedance.keva.Keva", false, loader)
        repo = checkNotNull(keva.getMethod("getRepo", String::class.java).invoke(null, "setting")) {
            "Keva.getRepo(setting) returned null"
        }
        readPreference = keva.getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
        val initialUiMode = Resources.getSystem().configuration.uiMode
        systemNight = initialUiMode and Configuration.UI_MODE_NIGHT_MASK
        val contrast = ThemeContrastFixes(loader, app, { ready && followsSystem() }, { desiredMode() == 2 }, ::log)
        // Business renderer contracts are separate from global theme delivery.
        // Their 40.7 obfuscated model classes must not disable the verified 40.8 bridge.
        if (version == "40.7.0") safely("40.7 business contrast") { contrast.install() }
        else {
            safely("40.8 verified profile renderer") { contrast.installProfile408() }
            log("40.8 core/profile theme enabled; other unverified 40.7 business-renderer hooks skipped")
        }
        chatRestore = ChatSessionRestore(loader, ::log)
        viewRepairs = ThemeViewRepairs(loader, main, { ready && followsSystem() && desiredMode() == 2 },
            { currentMainTab()?.lowercase()?.let { it.contains("message") || it.contains("notification") || it == "im" } == true },
            { ready && followsSystem() }, contrast::syncVisiblePreviews, ::log, ::handleFocusReturn)
        viewRepairs.installChatEntryHook()
        val mainActivityClass = Class.forName(MAIN_ACTIVITY, false, loader)
        // AmeActivity.onStart dispatches this BEFORE onActivityResumed, so a
        // deferred MainActivity can reach MRNightModeComponent's stale-view cast
        // before our refresh queue runs. Follow-system is owned by this module:
        // let the managed recreation restore its full state instead of running
        // the native partial refresh against the previous theme's view tree.
        XposedHelpers.findAndHookMethod(mainActivityClass, "onNightModeChanged", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!ready || !followsSystem()) return
                param.result = null
                enqueue()
                log("main native night callback replaced by managed refresh; epoch=$epoch deferred=${deferredPages.containsKey(param.thisObject)}")
            }
        })
        // Capture after the app's complete save method, not midway through super.onSaveInstanceState.
        XposedHelpers.findAndHookMethod(mainActivityClass, "onSaveInstanceState", Bundle::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as Activity
                if (param.hasThrowable() || pendingTabs[activity.taskId]?.oldActivity?.get() !== activity) return
                val state = param.args[0] as? Bundle ?: return
                savedMainStates[activity.taskId] = Bundle(state)
                log("main full state saved task=${activity.taskId} keys=${state.keySet()} epoch=$epoch")
            }
        })
        // Hook the already-created delegate returned by u(); never instantiate it earlier than the app.
        XposedHelpers.findAndHookMethod(mainActivityClass, if (version == "40.8.0") "t" else "u", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (restoringMainState.get() == null || param.hasThrowable()) return
                safely("install delegate state restoration") {
                    val delegate = param.result ?: error("main delegate is null")
                    if (!delegateStateHooks.add(delegate.javaClass)) return@safely
                    val method = delegate.javaClass.getMethod("onCreate", Bundle::class.java)
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val saved = restoringMainState.get() ?: return
                            if (param.args[0] == null) param.args[0] = Bundle(saved)
                            log("main delegate state forwarded class=${param.thisObject.javaClass.name} restoredState=${param.args[0] != null}")
                        }
                    })
                }
            }
        })
        XposedHelpers.findAndHookMethod(mainActivityClass, "onCreate", Bundle::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as Activity
                val snapshot = pendingTabs[activity.taskId]
                if (snapshot != null && snapshot.epoch == epoch && snapshot.oldActivity.get() !== activity) {
                    val retained = savedMainStates.remove(activity.taskId)
                    if (retained != null) {
                        if (param.args[0] == null) param.args[0] = Bundle(retained)
                        restoringMainState.set(Bundle(param.args[0] as Bundle))
                    }
                }
                val saved = param.args[0] as? Bundle
                incomingSavedState[activity] = saved != null
                log("MainActivity onCreate incomingSavedState=${saved != null} savedTab=${saved?.getString(TAB_STATE_KEY)} epoch=$epoch")
            }
            override fun afterHookedMethod(param: MethodHookParam) { restoringMainState.remove() }
        })
        log("resolved preference repo=${repo.javaClass.name}; reader=$readPreference; " +
            "system rawUiMode=${hex(initialUiMode)} night=${hex(systemNight)}")

        // Activity inherits this method from android.view.ContextThemeWrapper on Android.
        // Resolve the actual declaring method: findAndHookMethod(Activity, ...) cannot
        // locate inherited implementations on classic Xposed.
        val overrideMethod = Activity::class.java.getMethod("applyOverrideConfiguration", Configuration::class.java)
        XposedBridge.hookMethod(overrideMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject !is Activity) return
                safely("activity override configuration") {
                    val cfg = param.args.firstOrNull() as? Configuration
                    if (cfg == null) {
                        log("override config null; activity=${param.thisObject.javaClass.name}; unchanged")
                        return@safely
                    }
                    val original = cfg.uiMode
                    if (!ready || !followsSystem()) {
                        log("override config unchanged activity=${param.thisObject.javaClass.name} " +
                            "rawUiMode=${hex(original)} ready=$ready")
                        return@safely
                    }
                    val want = if (desiredMode() == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    // Copy all fields, replacing only NIGHT bits; do not mutate a shared caller config.
                    val corrected = Configuration(cfg)
                    corrected.uiMode = (original and Configuration.UI_MODE_NIGHT_MASK.inv()) or want
                    param.args[0] = corrected
                    log("override config forced activity=${param.thisObject.javaClass.name} " +
                        "rawUiMode=${hex(original)} rewrittenUiMode=${hex(corrected.uiMode)} " +
                        "systemNight=${hex(systemNight)}")
                }
            }
        })
        log("hook installed applyOverrideConfiguration owner=${overrideMethod.declaringClass.name}")

        // Capture the unmodified framework configuration before Douyin pins resource uiMode.
        val configurationMethod = app.javaClass.getMethod("onConfigurationChanged", Configuration::class.java)
        XposedBridge.hookMethod(configurationMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val raw = (param.args[0] as Configuration).uiMode
                val bits = raw and Configuration.UI_MODE_NIGHT_MASK
                log("application config rawUiMode=${hex(raw)} incomingNight=${hex(bits)} previousNight=${hex(systemNight)}")
                if (bits == Configuration.UI_MODE_NIGHT_YES || bits == Configuration.UI_MODE_NIGHT_NO) {
                    systemNight = bits
                    if (rebuilding) log("configuration deferred during rebuild; night=${hex(bits)} epoch=$epoch")
                    enqueue()
                }
            }
        })
        log("hook installed configuration callback=$configurationMethod")
        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                log("component config rawUiMode=${hex(newConfig.uiMode)} capturedSystemNight=${hex(systemNight)}")
                enqueue()
            }
            override fun onLowMemory() = Unit
        })
        XposedBridge.hookMethod(setMode, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                safely("native mode setter") {
                    val original = param.args[0]
                    if (ready && followsSystem()) {
                        param.args[0] = desiredMode()
                        // The native setter loops over all Delegates and may recreate them itself.
                        // Write its verified 40.7.0 cache instead; this module owns the serial queue.
                        defaultModeField.setInt(null, desiredMode())
                        param.result = null
                        enqueue()
                    }
                    logChanged("setter", "native setter raw=$original rewritten=${param.args[0]} ready=$ready")
                }
            }
        })
        for (name in listOf("isNightMode", "LJIIJJI")) {
            XposedHelpers.findAndHookMethod(skin, name, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    safely("native night query") {
                        if (ready && followsSystem()) {
                            param.result = desiredMode() == 2
                            logChanged("query-$name", "native query $name forced=${param.result} systemNight=${hex(systemNight)}")
                        }
                    }
                }
            })
            log("hook installed native query=$name")
        }
        // Observe preference writes, but never overwrite the user's appSkin choice.
        val storePreference = repo.javaClass.getMethod("storeInt", String::class.java, Int::class.javaPrimitiveType)
        XposedBridge.hookMethod(storePreference, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.thisObject === repo && param.args.firstOrNull() == "appSkin") {
                    preferenceChanged = true
                    log("preference write setting/appSkin value=${param.args.getOrNull(1)}")
                    enqueue()
                }
            }
        })
        log("hook installed preference writer=$storePreference")
        // This exact 40.7.0 method starts MainActivity and then kills the process.
        // Replace only its theme-settings call when follow-system is selected.
        if (version == "40.7.0") {
        val restart = Class.forName("X.0SQ1", false, loader)
        XposedHelpers.findAndHookMethod(restart, "LIZ", Context::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                safely("theme restart replacement") {
                    val context = param.args[0] as? Activity
                    if (ready && context?.javaClass?.name == THEME_SETTINGS && followsSystem()) {
                        param.result = null
                        enqueue()
                        log("theme-settings restart replaced; pid=${Process.myPid()}")
                    }
                }
            }
        })
        }
        // Observe, never block, framework Activity recreation so fallback cannot queue it twice.
        XposedHelpers.findAndHookMethod(Activity::class.java, "recreate", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                pendingRecreates[param.thisObject as Activity] = true
            }
        })
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (retainPage(activity)) safely("retain page configuration") {
                    val info = XposedHelpers.getObjectField(activity, "mActivityInfo") as android.content.pm.ActivityInfo
                    info.configChanges = info.configChanges or android.content.pm.ActivityInfo.CONFIG_UI_MODE
                }
                pageEpochs[activity] = epoch
                recreatedPages[activity] = rebuilding && state != null
                log("created activity=${activity.javaClass.name} rawUiMode=${hex(activity.resources.configuration.uiMode)} " +
                    "epoch=$epoch recreated=${recreatedPages[activity]} restoredState=${state != null} " +
                    "incomingSavedState=${incomingSavedState[activity]}")
            }
            override fun onActivityStarted(activity: Activity) {
                viewRepairs.watch(activity)
                gateMainTabRestore(activity)
                enqueue()
            }
            override fun onActivityResumed(activity: Activity) {
                resumed = WeakReference(activity)
                lastForeground = WeakReference(activity)
                log("resumed activity=${activity.javaClass.name} rawUiMode=${hex(activity.resources.configuration.uiMode)} " +
                    "epoch=$epoch recreated=${recreatedPages[activity] == true}")
                enqueue()
                restoreMainTab(activity, 0)
                restoreChatAfterTab(activity, 0)
                resumeDeferredPage(activity)
            }
            override fun onActivityPaused(activity: Activity) {
                if (resumed.get() === activity) resumed.clear()
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {
                if (activity.javaClass.name == MAIN_ACTIVITY) {
                    safely("save main tab") {
                        val tab = pendingTabs[activity.taskId]?.tab ?: currentMainTab()
                        if (tab != null) state.putString(TAB_STATE_KEY, tab)
                        log("saveInstanceState activity=${activity.javaClass.name} tab=$tab keys=${state.keySet().size} epoch=$epoch")
                    }
                }
            }
            override fun onActivityDestroyed(activity: Activity) {
                pageEpochs.remove(activity)
                pendingRecreates.remove(activity)
                processedEpochs.remove(activity)
                recreatedPages.remove(activity)
                incomingSavedState.remove(activity)
                deferredPages.remove(activity)
                viewRepairs.release(activity)
                rebuildInFlight.remove(activity)
                log("destroyed activity=${activity.javaClass.name} changing=${activity.isChangingConfigurations} epoch=$epoch")
                if (resumed.get() === activity) resumed.clear()
            }
        })
        ready = true
        log("loaded build=SYSTEM-THEME-0.1; version=$version; pid=${Process.myPid()}; following=${followsSystem()}; fix=unified-0.2.3-transparent-composer")
        enqueue()
    }

    private fun followsSystem(): Boolean {
        val value = readPreference.invoke(repo, "appSkin", -1) as? Int
            ?: error("Keva.getInt(setting/appSkin) returned null or non-Int")
        logChanged("preference", "preference read setting/appSkin=$value following=${value == -2}")
        return value == -2
    }

    private fun hex(value: Int): String = "0x${value.toString(16)}"

    private fun logChanged(key: String, message: String) {
        synchronized(diagnostics) {
            if (diagnostics[key] != message) {
                diagnostics[key] = message
                log(message)
            }
        }
    }

    private fun desiredMode(): Int = if (systemNight == Configuration.UI_MODE_NIGHT_YES) 2 else 1

    private fun enqueue() {
        if (rebuilding) {
            // Capture incoming NIGHT bits, but do not apply resources or start another batch.
            applyAfterBatch = true
            return
        }
        main.removeCallbacks(applyTask)
        main.postDelayed(applyTask, 80L)
    }

    private fun handleFocusReturn(activity: Activity) {
        if (!ready || !followsSystem() || activity.isDestroyed) return
        val bits = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if ((bits == Configuration.UI_MODE_NIGHT_YES || bits == Configuration.UI_MODE_NIGHT_NO) && bits != systemNight) {
            log("focus recovery NIGHT resync old=${hex(systemNight)} actual=${hex(bits)}")
            systemNight = bits
        }
        enqueue()
        if (!rebuilding) viewRepairs.refreshDrawState(activity)
    }

    private fun applySystemTheme() {
        if (!ready) return
        if (rebuilding) { applyAfterBatch = true; return }
        if (!followsSystem()) {
            appliedMode = 0
            preferenceChanged = false
            logChanged("apply-gate", "apply skipped: user preference is not follow-system")
            return
        }
        val wanted = desiredMode()
        val previous = appliedMode
        // Only an actual NIGHT transition creates a new epoch; native field mismatches
        // and callbacks caused by recreate cannot create another epoch.
        val flipped = previous != 0 && previous != wanted
        val nativeMode = getMode.invoke(null) as? Int
            ?: error("AppCompatDelegate.getDefaultNightMode returned null or non-Int")
        if (previous == wanted && nativeMode == wanted && !preferenceChanged) {
            logChanged("apply-gate", "NIGHT unchanged; mode=$wanted epoch=$epoch; no rebuild")
            return
        }
        if (flipped) epoch++
        if (flipped) for (activity in pageEpochs.keys.toList()) {
            if (activity === resumed.get() && !activity.isFinishing && !activity.isDestroyed) safely("capture embedded chat") {
                chatRestore.capture(activity, epoch)
            }
        }
        appliedMode = wanted
        preferenceChanged = false
        // Set before updating resources: their callbacks are deferred until this batch finishes.
        if (flipped) {
            rebuilding = true
            rebuildStartedAt = SystemClock.uptimeMillis()
            applyAfterBatch = false
            main.removeCallbacks(applyTask)
        }
        safely("apply native resource state") {
            XposedHelpers.setStaticIntField(skin, "LIZJ", -2)
            defaultModeField.setInt(null, wanted)
            val app = checkNotNull(application) { "application missing during apply" }
            val raw = app.resources.configuration.uiMode
            updateResources.invoke(null, app.resources, true)
            log("application resources rawUiMode=${hex(raw)} afterUiMode=${hex(app.resources.configuration.uiMode)}")
        }
        log("system theme applied mode=$wanted previous=$previous flipped=$flipped epoch=$epoch pid=${Process.myPid()}")
        if (!flipped) return
        queueActivityRebuilds(epoch)
    }

    private fun queueActivityRebuilds(batchEpoch: Int, only: Activity? = null) {
        check(Looper.myLooper() === Looper.getMainLooper()) { "Recreate queue must run on main thread" }
        // WeakHashMap keys are identity references to Activity instances. An epoch value
        // avoids the equality/duplicate problems of Set<WeakReference<Activity>>.
        val front = resumed.get() ?: lastForeground.get()
        val activities = pageEpochs.keys.filter { only == null || it === only }.map { WeakReference(it) }
        log("rebuild batch start epoch=$batchEpoch activities=${activities.size} pid=${Process.myPid()}")
        for (reference in activities) {
            val activity = reference.get() ?: continue
            if (activity.isFinishing || activity.isDestroyed) continue
            if (processedEpochs[activity] == batchEpoch) continue
            if ((front != null && front !== activity) || retainPage(activity)) {
                safely("refresh retained page") {
                    if (!retainPage(activity)) deferredPages[activity] = batchEpoch
                    synchronizeActivityResources(activity.resources)
                    // Never synthesize lifecycle/configuration callbacks into a
                    // stopped page. Refresh it after its real resume instead.
                    if (retainPage(activity)) {
                        activity.onConfigurationChanged(Configuration(activity.resources.configuration))
                        viewRepairs.request(activity)
                        viewRepairs.refreshDrawState(activity)
                        pageEpochs[activity] = batchEpoch
                    }
                    log("theme retained activity=${activity.javaClass.name} foreground=${activity === front} epoch=$batchEpoch; no relaunch")
                }
                continue
            }
            processedEpochs[activity] = batchEpoch
            if (activity.isChangingConfigurations || pendingRecreates[activity] == true) {
                log("recreate already pending activity=${activity.javaClass.name} epoch=$batchEpoch")
                continue
            }
            queuedRecreates++
            main.post {
                try {
                    safely("recreate activity") {
                        val current = reference.get() ?: return@safely
                        if (current.isFinishing || current.isDestroyed || current.isChangingConfigurations) return@safely
                        val resources = current.resources
                        if (current.javaClass.name == MAIN_ACTIVITY) {
                            safely("capture main tab") {
                                (pendingTabs[current.taskId]?.tab ?: currentMainTab())?.let { tab ->
                                    pendingTabs[current.taskId] = TabSnapshot(tab, batchEpoch, WeakReference(current))
                                    log("capture main tab=$tab task=${current.taskId} epoch=$batchEpoch")
                                }
                            }
                        }
                        val oldMode = resources.configuration.uiMode
                        var resourceSync = false
                        safely("Activity resources before recreate ${current.javaClass.name}") {
                            synchronizeActivityResources(resources)
                            resourceSync = true
                        }
                        val newMode = resources.configuration.uiMode
                        rebuildInFlight[current] = true
                        log("recreate activity=${current.javaClass.name} oldUiMode=${hex(oldMode)} " +
                            "newUiMode=${hex(newMode)} resources=${resources.javaClass.name} epoch=$batchEpoch " +
                            "recreated=true resourceSync=$resourceSync pid=${Process.myPid()}")
                        current.recreate()
                    }
                } finally {
                    queuedRecreates--
                    main.removeCallbacks(finishBatchTask)
                    main.postDelayed(finishBatchTask, 100L)
                }
            }
        }
        main.removeCallbacks(finishBatchTask)
        main.postDelayed(finishBatchTask, 100L)
    }

    private fun resumeDeferredPage(activity: Activity) {
        if (deferredPages[activity] == null) return
        val reference = WeakReference(activity)
        fun attempt() {
            val current = reference.get() ?: return
            if (current.isFinishing || current.isDestroyed || resumed.get() !== current) return
            if (rebuilding) { main.postDelayed({ attempt() }, 100L); return }
            deferredPages.remove(current)
            if (!followsSystem() || pageEpochs[current] == epoch) {
                log("deferred page already current activity=${current.javaClass.name} epoch=$epoch")
                return
            }
            rebuilding = true
            rebuildStartedAt = SystemClock.uptimeMillis()
            applyAfterBatch = false
            log("refresh deferred page after return activity=${current.javaClass.name} task=${current.taskId} epoch=$epoch")
            queueActivityRebuilds(epoch, current)
        }
        main.post { safely("refresh deferred page") { attempt() } }
    }

    private fun retainPage(activity: Activity) =
        activity.javaClass.simpleName == "FlowPageActivity"


    private fun finishRecreateBatch() {
        if (!rebuilding) return
        val expired = SystemClock.uptimeMillis() - rebuildStartedAt >= 5000L
        if ((queuedRecreates > 0 || rebuildInFlight.isNotEmpty()) && !expired) {
            main.postDelayed(finishBatchTask, 100L)
            return
        }
        if (expired && rebuildInFlight.isNotEmpty()) {
            log("rebuild wait timeout epoch=$epoch inFlight=${rebuildInFlight.size}; no repeated recreation")
            for (activity in rebuildInFlight.keys.toList()) pendingRecreates.remove(activity)
        }
        rebuildInFlight.clear()
        rebuilding = false
        log("rebuild batch finished epoch=$epoch deferred=$applyAfterBatch pid=${Process.myPid()}")
        resumed.get()?.let { if (!it.isDestroyed) viewRepairs.refreshDrawState(it) }
        val recheck = applyAfterBatch
        applyAfterBatch = false
        if (recheck) enqueue() // Same NIGHT => no-op; a genuine subsequent toggle => one new epoch.
    }

    private fun synchronizeActivityResources(resources: Resources) {
        val wantedBits = if (desiredMode() == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        val copy = Configuration(resources.configuration)
        copy.uiMode = (copy.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or wantedBits
        resources.updateConfiguration(copy, resources.displayMetrics)
        if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != wantedBits) {
            log("Activity resources rejected NIGHT; class=${resources.javaClass.name}; installing targeted Mira hooks")
            installMiraHooks(resources)
            resources.updateConfiguration(copy, resources.displayMetrics)
        }
        val after = resources.configuration.uiMode
        log("activity resources class=${resources.javaClass.name} wantedNight=${hex(wantedBits)} afterUiMode=${hex(after)}")
        check(after and Configuration.UI_MODE_NIGHT_MASK == wantedBits) { "Activity Resources still reject NIGHT: ${resources.javaClass.name}" }
    }

    private fun installMiraHooks(resources: Resources) {
        var type: Class<*>? = resources.javaClass
        while (type != null && type.name != "com.bytedance.frameworks.plugin.core.res.MiraResourcesWrapper") type = type.superclass
        val wrapper = type ?: error("NIGHT mismatch in non-Mira Resources ${resources.javaClass.name}")
        if (miraHookedClasses.contains(wrapper)) return
        val getter = wrapper.getMethod("getConfiguration")
        val updater = wrapper.getMethod("updateConfiguration", Configuration::class.java, android.util.DisplayMetrics::class.java)
        XposedBridge.hookMethod(getter, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!wrapper.isInstance(param.thisObject) || param.hasThrowable()) return
                safely("Mira getConfiguration") {
                    if (!ready || !followsSystem()) return@safely
                    val original = param.result as? Configuration ?: error("Mira getConfiguration returned null")
                    val bits = if (desiredMode() == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    if (original.uiMode and Configuration.UI_MODE_NIGHT_MASK != bits) {
                        val copy = Configuration(original)
                        copy.uiMode = (copy.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or bits
                        param.result = copy
                        logChanged("mira-get", "Mira getConfiguration rawUiMode=${hex(original.uiMode)} rewrittenUiMode=${hex(copy.uiMode)}")
                    }
                }
            }
        })
        XposedBridge.hookMethod(updater, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!wrapper.isInstance(param.thisObject)) return
                safely("Mira updateConfiguration") {
                    if (!ready || !followsSystem()) return@safely
                    val original = param.args[0] as? Configuration ?: return@safely
                    val copy = Configuration(original)
                    val bits = if (desiredMode() == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    copy.uiMode = (copy.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or bits
                    param.args[0] = copy
                    logChanged("mira-update", "Mira updateConfiguration rawUiMode=${hex(original.uiMode)} rewrittenUiMode=${hex(copy.uiMode)}")
                }
            }
        })
        miraHookedClasses.add(wrapper)
        log("Mira hooks installed getterOwner=${getter.declaringClass.name} updaterOwner=${updater.declaringClass.name}")
    }

    private fun bottomTabs(): Any? {
        val home = Class.forName("com.ss.android.ugc.aweme.ability.EzHomePage", false, loader)
        return XposedHelpers.callStaticMethod(home, "getBottomTabAbility")
    }

    private fun currentMainTab(): String? {
        var result: String? = null
        safely("read current main tab") {
            val ability = bottomTabs() ?: return@safely
            result = (XposedHelpers.callMethod(ability, "currentShowTab") as? String)?.takeIf { it.isNotEmpty() }
        }
        return result
    }

    private fun restoreMainTab(activity: Activity, attempt: Int) {
        if (activity.javaClass.name != MAIN_ACTIVITY || activity.isFinishing || activity.isDestroyed) return
        val snapshot = pendingTabs[activity.taskId] ?: return
        if (snapshot.oldActivity.get() === activity) return
        if (snapshot.epoch != epoch) return
        var done = false
        safely("restore main tab") {
            val ability = bottomTabs() ?: return@safely
            val current = XposedHelpers.callMethod(ability, "currentShowTab") as? String
            if (current == snapshot.tab) {
                done = true
            } else if (attempt == 0 || attempt % 5 == 0) {
                    // performClick()==true only means a listener exists, not that routing succeeded.
                    if (installedVersion == "40.8.0") {
                        // Do not reuse 40.7's obfuscated mapper/router. Route through
                        // the actual native tab control and verify currentShowTab afterwards.
                        val label = when (snapshot.tab) {
                            "homepage_notification" -> "消息"
                            "homepage_profile" -> "我"
                            "homepage_home" -> "首页"
                            "homepage_mall" -> "商城"
                            "homepage_familiar" -> "朋友"
                            else -> null
                        }
                        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                        fun find(view: android.view.View, inHost: Boolean): android.view.View? {
                            val host = inHost || view.javaClass.name ==
                                "com.ss.android.ugc.aweme.homepage.tab.bottom.ui.tabbar.container.MainBottomTabContainer"
                            if (!view.isShown) return null
                            if (host && view is android.widget.TextView && label != null && view.text?.toString()?.trim() == label) {
                                var target: android.view.View? = view
                                while (target != null && target !== content) {
                                    if (target.javaClass.name == "com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab") return target
                                    target = target.parent as? android.view.View
                                }
                            }
                            if (view is android.view.ViewGroup) for (i in 0 until view.childCount)
                                find(view.getChildAt(i), host)?.let { return it }
                            return null
                        }
                        val target = content?.let { find(it, false) }
                        if (target != null) target.performClick()
                        log("restore 40.8 native tab target=${snapshot.tab} found=${target != null} epoch=$epoch")
                    } else {
                    val configClass = Class.forName("com.ss.android.ugc.aweme.gateway.TabRouteConfig", false, loader)
                    val route = configClass.getConstructor(String::class.java, String::class.java, String::class.java)
                        .newInstance("", "", "")
                    val mapper = Class.forName("X.0gvU", false, loader)
                    val tabName = XposedHelpers.callStaticMethod(mapper, "LIZLLL", snapshot.tab) as? String
                        ?: error("native tab-name mapping returned null for ${snapshot.tab}")
                    XposedHelpers.callMethod(ability, "bm", tabName, route)
                    log("restore main tab native route=bm target=${snapshot.tab} bottomTabName=$tabName epoch=$epoch")
                    }
                log("restore main tab requested old=$current target=${snapshot.tab} attempt=$attempt epoch=$epoch")
            }
        }
        if (done) {
            pendingTabs.remove(activity.taskId)
            viewRepairs.request(activity)
            log("main tab restored tab=${snapshot.tab} task=${activity.taskId} epoch=$epoch")
        } else if (attempt >= 0 && attempt < 30) {
            val reference = WeakReference(activity)
            main.postDelayed({ reference.get()?.let { restoreMainTab(it, attempt + 1) } }, 100L)
        } else if (attempt >= 30) {
            pendingTabs.remove(activity.taskId)
            log("FAILED restore main tab timeout target=${snapshot.tab} task=${activity.taskId} epoch=$epoch")
        }
    }

    private fun restoreChatAfterTab(activity: Activity, attempt: Int) {
        if (activity.javaClass.name != MAIN_ACTIVITY || activity.isFinishing || activity.isDestroyed) return
        var done = false
        safely("restore embedded chat") {
            if (pendingTabs[activity.taskId] == null) done = chatRestore.restore(activity, epoch, attempt)
        }
        if (!done && attempt < 30) {
            val reference = WeakReference(activity)
            main.postDelayed({ reference.get()?.let { restoreChatAfterTab(it, attempt + 1) } }, 100L)
        }
    }

    private fun gateMainTabRestore(activity: Activity) {
        if (activity.javaClass.name != MAIN_ACTIVITY) return
        val initialTab = pendingTabs[activity.taskId]
        if (initialTab?.oldActivity?.get() === activity) return
        val gateEpoch = epoch
        val waitForChat = chatRestore.hasDrawTarget(activity.taskId, gateEpoch)
        if (initialTab == null && !waitForChat) return
        val decor = activity.window.decorView
        val deadline = android.os.SystemClock.uptimeMillis() + if (waitForChat) 3000L else 1500L
        var frames = 0
        if (waitForChat) log("chat first-frame gate start task=${activity.taskId} epoch=$gateEpoch; wait for conversation, not just notification tab")
        val pin = object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (activity.isDestroyed || epoch != gateEpoch || android.os.SystemClock.uptimeMillis() >= deadline) {
                    if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(this)
                    chatRestore.finishDrawGate(activity.taskId)
                    log("first-frame gate ended task=${activity.taskId} chat=$waitForChat frames=$frames destroyed=${activity.isDestroyed}; deadline/epoch guard")
                    return true
                }
                var readyToDraw = false
                safely("chat/tab first-frame gate") {
                    restoreMainTab(activity, if (frames % 5 == 0) -5 else -1)
                    val tabReady = pendingTabs[activity.taskId] == null
                    if (tabReady && waitForChat) chatRestore.restore(activity, gateEpoch, frames.coerceAtMost(29))
                    readyToDraw = tabReady && (!waitForChat || chatRestore.isChatDrawReady(activity, gateEpoch))
                }
                frames++
                if (!readyToDraw) {
                    decor.postInvalidateOnAnimation()
                } else {
                    if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnPreDrawListener(this)
                    chatRestore.finishDrawGate(activity.taskId)
                    log("first-frame gate ready task=${activity.taskId} chat=$waitForChat frames=$frames epoch=$gateEpoch; first draw contains restored page")
                }
                return readyToDraw
            }
        }
        decor.viewTreeObserver.addOnPreDrawListener(pin)
    }

    private data class TabSnapshot(val tab: String, val epoch: Int, val oldActivity: WeakReference<Activity>)

    private inline fun safely(stage: String, block: () -> Unit) {
        try { block() } catch (error: Throwable) {
            val cause = error.cause ?: error
            synchronized(errors) {
                if (errors.add("$stage:${cause.javaClass.name}:${cause.message}")) {
                    log("FAILED $stage: $cause")
                    XposedBridge.log(cause)
                }
            }
        }
    }

    private fun log(message: String) = XposedBridge.log("[DouyinSystemTheme] $message")

    companion object {
        private const val PACKAGE = "com.ss.android.ugc.aweme"
        private const val THEME_SETTINGS = "com.ss.android.ugc.aweme.setting.theme.ThemeSwitchActivity"
        private const val MAIN_ACTIVITY = "com.ss.android.ugc.aweme.main.MainActivity"
        private const val TAB_STATE_KEY = "cn.zhe.douyinsystemtheme.savedTab"
    }
}
