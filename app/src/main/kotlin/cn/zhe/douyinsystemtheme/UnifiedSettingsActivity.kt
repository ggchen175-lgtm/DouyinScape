package cn.zhe.douyinsystemtheme

import android.app.Activity
import android.os.Bundle
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

class UnifiedSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("unified", MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24*dp).toInt(), (32*dp).toInt(), (24*dp).toInt(), (24*dp).toInt())
        }
        panel.addView(TextView(this).apply { text = "抖境 · DouyinScape ${ModuleVersion.of(this@UnifiedSettingsActivity)}"; textSize = 22f })
        for ((key, label) in listOf("immersive" to "手势导航沉浸", "theme" to "跟随系统浅深色主题", "highRefresh" to "请求屏幕最高刷新率")) {
            panel.addView(CheckBox(this).apply {
                text = label; textSize = 18f; isChecked = prefs.getBoolean(key, true)
                setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
            })
        }
        panel.addView(TextView(this).apply {
            text = "让抖音界面自然延伸至手势导航区域，保持内容、输入栏与系统主题协调一致。\n\n启用方式\n在 LSPosed 中启用本模块并勾选抖音。抖音内主题请选择跟随系统；更改模块开关后，请彻底关闭并重新打开抖音。\n\n可选通知头像\n如需抖音私信联系人头像，还需勾选系统界面（System UI）并重新加载系统界面；该操作可能需要重新解锁。头像加载失败时保留应用图标。通知堆叠由独立模块提供。\n\n兼容性\n当前主要验证抖音 40.8.0，保留 40.7.0 适配。抖音升级后可能需要更新模块，请查看项目兼容说明。请停用作用相同的旧模块，避免重复调整布局。\n\n刷新率\n根据当前分辨率请求设备可用的最高刷新率。实际帧率由系统策略、温控与应用负载共同决定。\n\n开源项目\nhttps://github.com/ggchen175-lgtm/DouyinScape"; textSize = 16f
        })
        setContentView(panel)
    }
}

/** Read-only three-boolean settings. External callers cannot change preferences. */
class UnifiedOptionsProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "read") return null
        val prefs = context!!.getSharedPreferences("unified", 0)
        return Bundle().apply {
            for (key in listOf("immersive", "theme", "highRefresh")) putBoolean(key, prefs.getBoolean(key, true))
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
