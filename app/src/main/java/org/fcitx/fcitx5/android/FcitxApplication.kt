/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.vault.KeystoreVaultCipher
import org.fcitx.fcitx5.android.input.ai.TypingDnaRepository
import org.fcitx.fcitx5.android.input.ai.TypingDnaVault
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.userManager
import timber.log.Timber
import java.io.File
import kotlin.system.exitProcess

class FcitxApplication : Application() {

    val coroutineScope = MainScope() + CoroutineName("FcitxApplication")

    val typingDnaRepository: TypingDnaRepository by lazy {
        TypingDnaRepository(File(filesDir, "typing_dna.json"), cipher = vaultCipher)
    }

    val typingDnaVault: TypingDnaVault by lazy {
        TypingDnaVault(stagingFile = File(filesDir, "typing_dna_pending.json"), cipher = vaultCipher)
    }

    val personalNgramModel: PersonalNgramModel by lazy {
        PersonalNgramModel(storeFile = File(filesDir, "personal_ngram.json"), cipher = vaultCipher)
    }

    val personalSentenceVault: PersonalSentenceVault by lazy {
        PersonalSentenceVault(storeFile = File(filesDir, "personal_rag.json"), cipher = vaultCipher)
    }

    val vaultCipher: KeystoreVaultCipher by lazy { KeystoreVaultCipher() }

    val predictionMetricsStore: PredictionMetricsStore by lazy {
        PredictionMetricsStore(storeFile = File(filesDir, "prediction_metrics.json"), cipher = vaultCipher)
    }

    val baseKoreanVocabulary: BaseKoreanVocabulary by lazy {
        BaseKoreanVocabulary { assets.open("ko_base_vocab.tsv").reader(Charsets.UTF_8) }
    }

    val correctionPatternStore: CorrectionPatternStore by lazy {
        CorrectionPatternStore(storeFile = File(filesDir, "personal_corrections.json"), cipher = vaultCipher)
    }

    val typoCorrector: KeyboardAwareTypoCorrector by lazy {
        KeyboardAwareTypoCorrector(substitutionCost = correctionPatternStore::personalizedSubstitutionCost)
    }

    /**
     * 기본 어휘 TSV와 개인 n-gram 유니그램을 오타 교정 트라이에 미리 채워 둔다.
     * 실패해도 앱 기동에는 영향이 없어야 하므로 예외는 삼키지 않고 로그만 남긴다.
     */
    fun warmUpLanguageAssets() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                baseKoreanVocabulary.load()
                baseKoreanVocabulary.forEachWord { word, prior -> typoCorrector.addWord(word, prior) }
                personalNgramModel.forEachUnigram { word, count ->
                    typoCorrector.addWord(word, PersonalNgramModel.personalPrior(count))
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to warm up language assets")
            }
        }
    }

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SHUTDOWN) return
            Timber.d("Device shutting down, trying to save fcitx state...")
            val fcitx = FcitxDaemon.getFirstConnectionOrNull()
                ?: return Timber.d("No active fcitx connection, skipping")
            fcitx.runImmediately { save() }
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_UNLOCKED) return
            if (!isDirectBootMode) return
            Timber.d("Device unlocked, app will exit now and restart to normal mode")
            FcitxDaemon.getFirstConnectionOrNull()?.also {
                // try to shutdown fcitx gracefully
                FcitxDaemon.stopFcitx()
            }
            AppUtil.exit()
        }
    }

    private val restartFcitxInstanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESTART_FCITX_INSTANCE) return
            if (FcitxDaemon.getFirstConnectionOrNull() != null) {
                Timber.i("Received broadcast '${intent.action}', try to restart fcitx instance ...")
                FcitxDaemon.restartFcitx()
            } else {
                Timber.i("Received broadcast '${intent.action}', but there's no fcitx instance")
            }
        }
    }

    var isDirectBootMode = false
        private set

    val directBootAwareContext: Context
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isDirectBootMode) {
            createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !userManager.isUserUnlocked) {
            isDirectBootMode = true
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        val ctx = directBootAwareContext

        if (!BuildConfig.DEBUG) {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                val crashTime = System.currentTimeMillis()
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
                val lastCrashTimePrefKey = "last_crash_time"
                val lastCrashTime = sharedPreferences.getLong(lastCrashTimePrefKey, -1L)
                // make sure it was written to persistent storage
                sharedPreferences.edit(commit = true) {
                    putLong(lastCrashTimePrefKey, crashTime)
                }
                if (crashTime - lastCrashTime <= 10_000L) {
                    // continuous crashes within 10 seconds, maybe in a crash loop. just bail
                    exitProcess(10)
                }
                startActivity<LogActivity> {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(LogActivity.FROM_CRASH, true)
                    // avoid transaction overflow
                    val truncated = e.stackTraceToString().let {
                        if (it.length > MAX_STACKTRACE_SIZE)
                            it.take(MAX_STACKTRACE_SIZE) + "<truncated>"
                        else
                            it
                    }
                    putExtra(LogActivity.CRASH_STACK_TRACE, truncated)
                }
                exitProcess(10)
            }
        }

        instance = this
        // we don't have AppPrefs available yet
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        Timber.setupForest(verbose = sharedPrefs.getBoolean("verbose_log", false))

        Timber.d("isDirectBootMode=$isDirectBootMode")

        AppPrefs.init(sharedPrefs)
        // record last pid for crash logs
        AppPrefs.getInstance().internal.pid.apply {
            val currentPid = Process.myPid()
            lastPid = getValue()
            Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
            setValue(currentPid)
        }
        ClipboardManager.init(ctx)
        ThemeManager.init(resources.configuration)
        Locales.onLocaleChange(resources.configuration)
        registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isDirectBootMode) {
            AppPrefs.getInstance().syncToDeviceEncryptedStorage()
            ThemeManager.syncToDeviceEncryptedStorage()
        }
        ContextCompat.registerReceiver(
            this,
            restartFcitxInstanceReceiver,
            IntentFilter(ACTION_RESTART_FCITX_INSTANCE),
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
        warmUpLanguageAssets()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeManager.onSystemPlatteChange(newConfig)
        Locales.onLocaleChange(newConfig)
    }

    companion object {
        private var lastPid: Int? = null
        private var instance: FcitxApplication? = null
        fun getInstance() =
            instance ?: throw IllegalStateException("FcitxApplication has not been created!")

        fun getLastPid() = lastPid
        private const val MAX_STACKTRACE_SIZE = 128000

        const val ACTION_RESTART_FCITX_INSTANCE =
            "${BuildConfig.APPLICATION_ID}.action.RESTART_FCITX_INSTANCE"

        /**
         * This permission is requested by com.android.shell, makes it possible to restart
         * fcitx instance from `adb shell am` command:
         * ```sh
         * adb shell am broadcast -a net.chanpaca.saegeul.action.RESTART_FCITX_INSTANCE
         * ```
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/packages/Shell/AndroidManifest.xml#67
         *
         * other candidate: android.permission.TEST_INPUT_METHOD requires Android 14
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#628
         */
        const val PERMISSION_TEST_INPUT_METHOD = "android.permission.READ_INPUT_STATE"
    }
}
