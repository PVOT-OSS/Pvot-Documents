// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

ext {
    val versionMajor = 0
    val versionMinor = 1
    val versionPatch = 0

    set("versionMajor", versionMajor)
    set("versionMinor", versionMinor)
    set("versionPatch", versionPatch)
    set("computedVersionCode", versionMajor * 10000 + versionMinor * 100 + versionPatch)
    set("computedVersionName", "$versionMajor.$versionMinor.$versionPatch")
}
