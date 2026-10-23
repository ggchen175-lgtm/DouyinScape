plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "cn.zhe.douyinsystemtheme"
    compileSdk = 34
    defaultConfig {
        applicationId = "cn.zhe.douyinunified"
        minSdk = 23
        targetSdk = 28
        versionCode = 28
        versionName = "0.3.17"
    }
    kotlinOptions { jvmTarget = "1.8" }
    // Official signing is configured locally; never commit a keystore or credentials.
    val moduleKeystore = providers.environmentVariable("DOUYINSCAPE_KEYSTORE").orNull
    if (!moduleKeystore.isNullOrBlank()) {
        signingConfigs {
            create("moduleLocal") {
                storeFile = file(moduleKeystore)
                storePassword = providers.environmentVariable("DOUYINSCAPE_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("DOUYINSCAPE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("DOUYINSCAPE_KEY_PASSWORD").orNull
            }
        }
        buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("moduleLocal") } }
    }
}
dependencies { compileOnly("de.robv.android.xposed:api:82") }
