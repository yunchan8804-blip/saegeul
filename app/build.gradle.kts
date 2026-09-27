import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask

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
val admobBannerUnitId = providers.gradleProperty("ADMOB_BANNER_UNIT_ID")
    .orElse(providers.environmentVariable("ADMOB_BANNER_UNIT_ID"))
    .orElse("ca-app-pub-3940256099942544/6300978111")
val admobRewardedUnitId = providers.gradleProperty("ADMOB_REWARDED_UNIT_ID")
    .orElse(providers.environmentVariable("ADMOB_REWARDED_UNIT_ID"))
    .orElse("ca-app-pub-3940256099942544/5224354917")

android {
    namespace = "org.fcitx.fcitx5.android"
    testBuildType = if (releaseDeviceGate.get()) "release" else "debug"

    testOptions {
        unitTests.all {
            // VaultBackupTest builds a >64MB export in memory to prove the size gate; the default
            // 512MB test JVM heap runs out on CI runners.
            it.maxHeapSize = "2g"
        }
    }

    defaultConfig {
        applicationId = ProductIdentity.applicationId
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["admobAppId"] = admobAppId.get()
        buildConfigField("String", "ADMOB_INTERSTITIAL_UNIT_ID", admobInterstitialUnitId.get().asBuildConfigString())
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", admobBannerUnitId.get().asBuildConfigString())
        buildConfigField("String", "ADMOB_REWARDED_UNIT_ID", admobRewardedUnitId.get().asBuildConfigString())
        buildConfigField("String", "DISTRIBUTION_CHANNEL", "user".asBuildConfigString())
        buildConfigField("boolean", "SHOW_DEVELOPER_SURFACES", "false")
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
            buildConfigField(
                "String",
                "DISTRIBUTION_CHANNEL",
                "developer".asBuildConfigString()
            )
            buildConfigField("boolean", "SHOW_DEVELOPER_SURFACES", "true")
            resValue("mipmap", "app_icon", "@mipmap/ic_launcher_debug")
            resValue("mipmap", "app_icon_round", "@mipmap/ic_launcher_round_debug")
            resValue("string", "app_name", "@string/app_name_debug")
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
        if (variant.name == "debug" || variant.name == "release") {
            val hangulJniDest = layout.buildDirectory.dir("hangulEngine/${variant.name}/jniLibs")
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
                into(hangulJniDest)
                doLast {
                    // AGP has changed this stripped-libs intermediate path before without notice
                    // (see the CI NO-SOURCE incident noted below), which would silently ship an
                    // APK/AAB without the Korean input engine. Fail the build instead.
                    val expectedAbis = buildAbiOverride
                        ?.split(",")
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        ?: Versions.supportedABIs
                    val destDir = hangulJniDest.get().asFile
                    val missing = expectedAbis.filter {
                        !destDir.resolve(it).resolve("libhangul.so").isFile
                    }
                    if (missing.isNotEmpty()) {
                        throw GradleException(
                            "Hangul engine native library (libhangul.so) is missing for " +
                                "ABI(s) $missing under $destDir. The :plugin:hangul stripped " +
                                "native libs path may have moved; check AGP's " +
                                "intermediates/stripped_native_libs layout."
                        )
                    }
                }
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
        "fcitx5-lua"
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

val nativeNextWordAsset = "usr/share/fcitx5/hangul/nextword.txt"

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
        // 다음 어절은 앱의 코퍼스 n-gram(korean/ko-ngram.bin)이 맡는다. 네이티브 사전이 있으면
        // 그 후보가 항상 먼저 나와 코퍼스 후보를 밀어내므로 메인 앱에는 싣지 않는다.
        exclude(nativeNextWordAsset)
    }
    into(layout.projectDirectory.dir("src/main/assets"))
    val staleNativeNextWord = layout.projectDirectory.file("src/main/assets/$nativeNextWordAsset").asFile
    val hangulAddonConf =
        layout.projectDirectory.file("src/main/assets/usr/share/fcitx5/addon/hangul.conf").asFile
    doLast {
        staleNativeNextWord.delete()
        // Same NO-SOURCE risk as bundleHangulEngineJni*: if :plugin:hangul's CMake install
        // layout changes, this Copy task can silently find nothing to copy and ship a build
        // where fcitx5 never even registers the Korean input method addon.
        if (!hangulAddonConf.isFile) {
            throw GradleException(
                "Hangul engine addon descriptor is missing at $hangulAddonConf after " +
                    "bundleHangulEngineAssets. :plugin:hangul's CMake install output may " +
                    "have changed or produced no assets."
            )
        }
    }
    mustRunAfter("installFcitxComponent")
    mustRunAfter("deleteFcitxComponentExcludeFiles")
}
tasks.named("generateDataDescriptor") {
    dependsOn(bundleHangulEngineAssets)
}
// AssetManager로 직접 읽는 대용량 언어 자산은 dataDir 복사 대상에서 뺀다.
extensions.configure<DataDescriptorPluginExtension>("generateDataDescriptor") {
    excludes.addAll("ko_base_vocab.tsv", "korean/ko-ngram.bin")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    ksp(project(":codegen"))
    implementation(project(":lib:fcitx5"))
    implementation(project(":lib:fcitx5-lua"))
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
    implementation(libs.litertlm.android)
    implementation(libs.androidx.work.runtime)
    testImplementation(libs.junit)
    testImplementation(libs.json)
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
