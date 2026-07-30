import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
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

android {
    namespace = "org.fcitx.fcitx5.android"

    defaultConfig {
        applicationId = "org.fcitx.fcitx5.android"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = "org.fcitx.fcitx5.android.oauth"
        buildConfigField("String", "AI_PROVIDER_NAME", "OpenAI".asBuildConfigString())
        buildConfigField(
            "String",
            "AI_PROVIDER_BASE_URL",
            "https://api.openai.com/v1".asBuildConfigString()
        )
        buildConfigField("String", "AI_PROVIDER_API_KEY", "".asBuildConfigString())
        buildConfigField(
            "String",
            "AI_OAUTH_REDIRECT_URI",
            "org.fcitx.fcitx5.android.oauth:/callback".asBuildConfigString()
        )
        buildConfigField("String", "AI_FAST_MODEL", "gpt-5.6-luna".asBuildConfigString())
        buildConfigField("String", "AI_BALANCED_MODEL", "gpt-5.6-terra".asBuildConfigString())
        buildConfigField("String", "AI_QUALITY_MODEL", "gpt-5.6-sol".asBuildConfigString())

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
        }
        debug {
            manifestPlaceholders["appAuthRedirectScheme"] =
                "org.fcitx.fcitx5.android.debug.oauth"
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
                "org.fcitx.fcitx5.android.debug.oauth:/callback".asBuildConfigString()
            )
        }
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
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
    }
}

fcitxComponent {
    includeLibs = listOf(
        "fcitx5",
        "fcitx5-lua",
        "libime",
        "fcitx5-chinese-addons"
    )
    // exclude (delete immediately after install) tables that nobody would use
    excludeFiles = listOf("cangjie", "erbi", "qxm", "wanfeng").map {
        "usr/share/fcitx5/inputmethod/$it.conf"
    }
    installPrebuiltAssets = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
    implementation(project(":lib:libime"))
    implementation(project(":lib:fcitx5-chinese-addons"))
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
    testImplementation(libs.junit)
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
