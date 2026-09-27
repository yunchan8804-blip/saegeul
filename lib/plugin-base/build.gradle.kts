plugins {
    id("org.fcitx.fcitx5.android.lib-convention")
}

android {
    namespace = "org.fcitx.fcitx5.android.lib.plugin_base"
}

dependencies {
    api(project(":lib:common"))
    implementation(libs.aboutlibraries.core)
}
