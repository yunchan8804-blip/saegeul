import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.w3c.dom.Element
import org.w3c.dom.Node

plugins {
    id("org.fcitx.fcitx5.android.app-convention")
    id("org.fcitx.fcitx5.android.native-app-convention")
    id("org.fcitx.fcitx5.android.build-metadata")
    id("org.fcitx.fcitx5.android.data-descriptor")
    id("org.fcitx.fcitx5.android.fcitx-component")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

/**
 * Verifies the final manifest instead of the source manifest because a manifest-merger directive
 * can remove a valid source `<queries>` block. AppAuth discovers browsers through the exact
 * `VIEW` + `BROWSABLE` + `http` query below on Android 11 and newer.
 */
abstract class VerifyOAuthBrowserVisibilityTask : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val document = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }.newDocumentBuilder().parse(mergedManifest.get().asFile)

        val queries = document.documentElement.childElements("queries")
        val hasBrowserVisibilityQuery = queries.any { query ->
            query.childElements("intent").any { intent ->
                intent.hasAndroidName("action", "android.intent.action.VIEW") &&
                    intent.hasAndroidName("category", "android.intent.category.BROWSABLE") &&
                    intent.hasAndroidName("data", "http", attribute = "scheme")
            }
        }

        if (!hasBrowserVisibilityQuery) {
            throw GradleException(
                "Merged manifest ${mergedManifest.get().asFile} is missing the Android 11+ browser " +
                    "visibility query required by AppAuth (VIEW + BROWSABLE + http). Check <queries> " +
                    "merge directives before shipping OAuth."
            )
        }
    }

    private fun Element.childElements(name: String): List<Element> = buildList {
        for (index in 0 until childNodes.length) {
            val child = childNodes.item(index)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == name) {
                add(child as Element)
            }
        }
    }

    private fun Element.hasAndroidName(
        childName: String,
        expected: String,
        attribute: String = "name"
    ): Boolean = childElements(childName).any {
        it.getAttributeNS(ANDROID_NAMESPACE, attribute) == expected
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}

val debugAiProviderName = providers.gradleProperty("AI_PROVIDER_NAME").orElse("OpenAI")
val debugAiProviderBaseUrl = providers.gradleProperty("AI_PROVIDER_BASE_URL")
    .orElse("https://api.openai.com/v1")
val debugAiProviderApiKey = providers.gradleProperty("AI_PROVIDER_API_KEY").orElse("")
val releaseDeviceGate = providers.gradleProperty("releaseDeviceGate")
    .orElse(providers.environmentVariable("RELEASE_DEVICE_GATE"))
    .map(String::toBoolean)
    .orElse(false)
val inferredSourceTag = providers.provider {
    val versionName = buildVersionName
    if (versionName.matches(Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?"""))) {
        "${ProductIdentity.slug}-v$versionName"
    } else {
        ""
    }
}
val sourceTag = providers.gradleProperty("SOURCE_TAG")
    .orElse(providers.environmentVariable("SOURCE_TAG"))
    .orElse(inferredSourceTag)
val sourceRepositoryUrl = providers.gradleProperty("SOURCE_REPOSITORY_URL")
    .orElse(ProductIdentity.repositoryUrl)
val sourceArchiveUrl = providers.gradleProperty("SOURCE_ARCHIVE_URL").orElse(
    sourceTag.map { tag ->
        if (tag.isBlank()) {
            "${ProductIdentity.repositoryUrl}/releases"
        } else {
            "${ProductIdentity.repositoryUrl}/releases/download/$tag/$tag-source.tar.gz"
        }
    }
)
val productWebsiteUrl = providers.gradleProperty("PRODUCT_WEBSITE_URL")
    .orElse(ProductIdentity.websiteUrl)
val privacyPolicyUrl = providers.gradleProperty("PRIVACY_POLICY_URL")
    .orElse(ProductIdentity.privacyPolicyUrl)
val faqUrl = providers.gradleProperty("FAQ_URL").orElse(ProductIdentity.faqUrl)
val admobAppId = providers.gradleProperty("ADMOB_APP_ID")
    .orElse(providers.environmentVariable("ADMOB_APP_ID"))
    .orElse("ca-app-pub-3940256099942544~3347511713")
val admobInterstitialUnitId = providers.gradleProperty("ADMOB_INTERSTITIAL_UNIT_ID")
    .orElse(providers.environmentVariable("ADMOB_INTERSTITIAL_UNIT_ID"))
    .orElse("ca-app-pub-3940256099942544/1033173712")

android {
    namespace = "org.fcitx.fcitx5.android"
    testBuildType = if (releaseDeviceGate.get()) "release" else "debug"

    defaultConfig {
        applicationId = ProductIdentity.applicationId
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "${ProductIdentity.applicationId}.oauth"
        manifestPlaceholders["admobAppId"] = admobAppId.get()
        buildConfigField("String", "ADMOB_INTERSTITIAL_UNIT_ID", admobInterstitialUnitId.get().asBuildConfigString())
        buildConfigField("String", "AI_PROVIDER_NAME", "OpenAI".asBuildConfigString())
        buildConfigField(
            "String",
            "AI_PROVIDER_BASE_URL",
            "https://api.openai.com/v1".asBuildConfigString()
        )
        buildConfigField("String", "AI_PROVIDER_API_KEY", "".asBuildConfigString())
        buildConfigField("String", "DISTRIBUTION_CHANNEL", "user".asBuildConfigString())
        buildConfigField("boolean", "SHOW_DEVELOPER_SURFACES", "false")
        buildConfigField(
            "String",
            "AI_OAUTH_REDIRECT_URI",
            "${ProductIdentity.applicationId}.oauth:/callback".asBuildConfigString()
        )
        buildConfigField("String", "AI_FAST_MODEL", "gpt-5.6-luna".asBuildConfigString())
        buildConfigField("String", "AI_BALANCED_MODEL", "gpt-5.6-terra".asBuildConfigString())
        buildConfigField("String", "AI_QUALITY_MODEL", "gpt-5.6-sol".asBuildConfigString())
        buildConfigField(
            "String",
            "SOURCE_REPOSITORY_URL",
            sourceRepositoryUrl.get().asBuildConfigString()
        )
        buildConfigField(
            "String",
            "SOURCE_ARCHIVE_URL",
            sourceArchiveUrl.get().asBuildConfigString()
        )
        buildConfigField("String", "SOURCE_TAG", sourceTag.get().asBuildConfigString())
        buildConfigField(
            "String",
            "PRODUCT_WEBSITE_URL",
            productWebsiteUrl.get().asBuildConfigString()
        )
        buildConfigField(
            "String",
            "PRIVACY_POLICY_URL",
            privacyPolicyUrl.get().asBuildConfigString()
        )
        buildConfigField("String", "FAQ_URL", faqUrl.get().asBuildConfigString())

        @Suppress("UnstableApiUsage")
        externalNativeBuild {
            cmake {
                targets(
                    // jni
                    "native-lib",
                    // copy fcitx5 built-in addon libraries
                    "copy-fcitx5-modules",
                    // android specific modules
                    "androidfrontend",
                    "androidkeyboard",
                    "androidnotification"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        resValues = true
        buildConfig = true
    }

    buildTypes {
        release {
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round")
            resValue("string", "app_name", "@string/app_name_release")
            proguardFile("proguard-rules.pro")
            testProguardFile("proguard-test-rules.pro")
        }
        debug {
            manifestPlaceholders["appAuthRedirectScheme"] =
                "${ProductIdentity.applicationId}.debug.oauth"
            buildConfigField(
                "String",
                "DISTRIBUTION_CHANNEL",
                "developer".asBuildConfigString()
            )
            buildConfigField("boolean", "SHOW_DEVELOPER_SURFACES", "true")
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
            buildConfigField(
                "String",
                "AI_PROVIDER_NAME",
                debugAiProviderName.get().asBuildConfigString()
            )
            buildConfigField(
                "String",
                "AI_PROVIDER_BASE_URL",
                debugAiProviderBaseUrl.get().asBuildConfigString()
            )
            buildConfigField(
                "String",
                "AI_PROVIDER_API_KEY",
                debugAiProviderApiKey.get().asBuildConfigString()
            )
            buildConfigField(
                "String",
                "AI_OAUTH_REDIRECT_URI",
                "${ProductIdentity.applicationId}.debug.oauth:/callback".asBuildConfigString()
            )
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    sourceSets {
        getByName("debug") {
            jniLibs.directories.add(
                layout.buildDirectory.dir("hangulEngine/debug/jniLibs").get().asFile.absolutePath
            )
        }
        getByName("release") {
            jniLibs.directories.add(
                layout.buildDirectory.dir("hangulEngine/release/jniLibs").get().asFile.absolutePath
            )
        }
    }
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val verifyTask = tasks.register<VerifyOAuthBrowserVisibilityTask>(
            "verify${variantName}OAuthBrowserVisibility"
        ) {
            group = "verification"
            description = "Verifies AppAuth browser visibility in the merged $variantName manifest."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }

        // Keep ordinary CI checks and every installable APK build covered. The artifact provider
        // establishes the dependency on manifest merging without relying on an AGP output path.
        tasks.matching { task ->
            task.name == "check" || task.name == "assemble$variantName"
        }.configureEach {
            dependsOn(verifyTask)
        }

        if (variant.name == "debug" || variant.name == "release") {
            val hangulJni = tasks.register<Copy>("bundleHangulEngineJni$variantName") {
                group = "build"
                description =
                    "Copy the Hangul native addon into the main app for ${variant.name}."
                dependsOn(":plugin:hangul:strip${variantName}DebugSymbols")
                from(
                    project(":plugin:hangul").layout.buildDirectory.dir(
                        "intermediates/stripped_native_libs/${variant.name}/" +
                            "strip${variantName}DebugSymbols/out/lib"
                    )
                )
                into(layout.buildDirectory.dir("hangulEngine/${variant.name}/jniLibs"))
            }
            tasks.matching {
                it.name == "merge${variantName}JniLibFolders" ||
                    it.name == "merge${variantName}NativeLibs"
            }.configureEach {
                dependsOn(hangulJni)
            }
        }
    }
}

// The instrumented-test APK is loaded before the target APK. Its separately generated j$ runtime
// therefore shadows the target runtime, so L8 must retain the members referenced only by target
// code as well as those it can observe directly from AndroidTest.
tasks.withType<L8DexDesugarLibTask>().configureEach {
    if (name == "l8DexDesugarLibReleaseAndroidTest") {
        keepRulesConfigurations.add("-keep class j$.** { *; }")
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime"
    )
    // Keep the first independent release focused on Korean input. These exclusions also remove
    // stale generated assets after switching from an older build that included Chinese Addons.
    excludeFiles = listOf(
        "usr/share/fcitx5/addon/chttrans.conf",
        "usr/share/fcitx5/addon/fullwidth.conf",
        "usr/share/fcitx5/addon/pinyin.conf",
        "usr/share/fcitx5/addon/pinyinhelper.conf",
        "usr/share/fcitx5/addon/punctuation.conf",
        "usr/share/fcitx5/addon/table.conf",
        "usr/share/fcitx5/chttrans",
        "usr/share/fcitx5/inputmethod",
        "usr/share/fcitx5/lua/imeapi/extensions/pinyin.lua",
        "usr/share/fcitx5/pinyin",
        "usr/share/fcitx5/pinyinhelper",
        "usr/share/fcitx5/punctuation",
        "usr/share/fcitx5/table",
        "usr/share/locale/*/LC_MESSAGES/fcitx5-chinese-addons.mo",
        "usr/share/opencc"
    )
    installPrebuiltAssets = true
}

val bundleHangulEngineAssets = tasks.register<Copy>("bundleHangulEngineAssets") {
    group = "build"
    description = "Copy Hangul engine assets into the main app so Play user builds include Korean input."
    // A clean checkout has no generated plugin assets yet. Depending on the plugin descriptor
    // task guarantees its CMake install has populated src/main/assets before this Copy task
    // decides whether it has any sources. Without this edge, warm local builds pass while CI
    // reports NO-SOURCE and produces a main bundle without the Hangul engine.
    dependsOn(":plugin:hangul:generateDataDescriptor")
    from(project(":plugin:hangul").file("src/main/assets")) {
        exclude("descriptor.json")
    }
    into(layout.projectDirectory.dir("src/main/assets"))
    mustRunAfter("installFcitxComponent")
    mustRunAfter("deleteFcitxComponentExcludeFiles")
}
tasks.named("generateDataDescriptor") {
    dependsOn(bundleHangulEngineAssets)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:common"))
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.common)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.paging)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.startup)
    implementation(libs.androidx.viewpager2)
    implementation(libs.material)
    implementation(libs.arrow.core)
    implementation(libs.arrow.functions)
    implementation(libs.imagecropper)
    implementation(libs.flexbox)
    implementation(libs.dependency)
    implementation(libs.timber)
    implementation(libs.tesseract4android)
    implementation(libs.appauth)
    implementation(libs.okhttp)
    implementation(libs.google.mobile.ads)
    implementation(libs.splitties.bitflags)
    implementation(libs.splitties.dimensions)
    implementation(libs.splitties.resources)
    implementation(libs.splitties.views.dsl)
    implementation(libs.splitties.views.dsl.appcompat)
    implementation(libs.splitties.views.dsl.constraintlayout)
    implementation(libs.splitties.views.dsl.coordinatorlayout)
    implementation(libs.splitties.views.dsl.recyclerview)
    implementation(libs.splitties.views.recyclerview)
    implementation(libs.aboutlibraries.core)
    debugImplementation("com.google.ai.edge.litertlm:litertlm-android:0.13.1")
    debugImplementation("androidx.work:work-runtime:2.10.5")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.lifecycle.testing)
    androidTestImplementation(libs.junit)
}

configurations {
    all {
        // remove Baseline Profile Installer or whatever it is...
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
        // remove unwanted splitties libraries...
        exclude(group = "com.louiscad.splitties", module = "splitties-appctx")
        exclude(group = "com.louiscad.splitties", module = "splitties-systemservices")
    }
}
