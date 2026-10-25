package cn.zhe.douyinsystemtheme

import android.content.Context

/**
 * The module version is read back from the installed APK instead of being
 * typed into log lines and the settings title. Those two copies silently
 * drifted from `build.gradle.kts` and made a fresh build report the previous
 * release, which is indistinguishable from "the update did not apply".
 */
internal object ModuleVersion {
    private const val PACKAGE = "cn.zhe.douyinunified"
    private const val FALLBACK = "0.3.27"

    fun of(context: Context?): String = runCatching {
        context?.packageManager?.getPackageInfo(PACKAGE, 0)?.versionName
    }.getOrNull() ?: FALLBACK
}
