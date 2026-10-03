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
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.work.Configuration as WorkConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.ExternalAiCredentialPurge
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.ai.BundledKoreanNgram
import org.fcitx.fcitx5.android.input.ai.PersonalNgramModel
import org.fcitx.fcitx5.android.input.ai.metrics.PredictionMetricsStore
import org.fcitx.fcitx5.android.input.ai.ondevice.GeneratedSentenceBank
import org.fcitx.fcitx5.android.input.ai.ondevice.OnDeviceAiSupport
import org.fcitx.fcitx5.android.input.ai.rag.PersonalSentenceVault
import org.fcitx.fcitx5.android.input.ai.sentencepack.SentencePackRepository
import org.fcitx.fcitx5.android.input.ai.vault.KeystoreVaultCipher
import org.fcitx.fcitx5.android.input.policy.DirectBootInputPolicy
import org.fcitx.fcitx5.android.data.points.LevelRewardStore
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaRepository
import org.fcitx.fcitx5.android.input.ai.VaultHabitStore
import org.fcitx.fcitx5.android.input.ai.typingdna.TypingDnaVault
import org.fcitx.fcitx5.android.input.ai.typo.BaseKoreanVocabulary
import org.fcitx.fcitx5.android.input.ai.typo.CorrectionPatternStore
import org.fcitx.fcitx5.android.input.ai.typo.KeyboardAwareTypoCorrector
import org.fcitx.fcitx5.android.ui.main.LogActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.BackgroundProgressNotifier
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.userManager
import timber.log.Timber
import java.io.File
import kotlin.system.exitProcess

class FcitxApplication : Application(), WorkConfiguration.Provider {

    /**
     * WorkManager starts on its first use instead of at process start, because its default
     * initializer opens a credential-encrypted database and kills the process in Direct Boot.
     */
    override val workManagerConfiguration: WorkConfiguration = WorkConfiguration.Builder().build()

    val coroutineScope = MainScope() + CoroutineName("FcitxApplication")

    val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    /**
     * Credential-encrypted storage (filesDir, noBackupFilesDir, default SharedPreferences) does not
     * exist in Direct Boot. Every lazy below that reads or writes it calls this first, so a caller
     * that reaches it before the first unlock fails at once with the offending name instead of
     * failing later inside a storage API.
     */
    private fun requireCredentialStorage(name: String) {
        DirectBootInputPolicy.requireCredentialProtectedStorage(isDirectBootMode, name)
    }

    val typingDnaRepository: TypingDnaRepository by lazy {
        requireCredentialStorage("typingDnaRepository")
        TypingDnaRepository.onSentencesAnalyzed = { analyzed, newLevel ->
            val now = System.currentTimeMillis()
            VaultHabitStore(this).record(now, analyzed)
            LevelRewardStore(this).grantIfLevelUp(newLevel, now)
        }
        TypingDnaRepository(File(filesDir, "typing_dna.json"), cipher = vaultCipher)
    }

    val typingDnaVault: TypingDnaVault by lazy {
        requireCredentialStorage("typingDnaVault")
        TypingDnaVault(
            stagingFile = File(filesDir, "typing_dna_pending.json"),
            cipher = vaultCipher,
            diagnostics = collectionDiagnostics
        )
    }

    val collectionDiagnostics: org.fcitx.fcitx5.android.input.ai.CollectionDiagnostics by lazy {
        requireCredentialStorage("collectionDiagnostics")
        org.fcitx.fcitx5.android.input.ai.CollectionDiagnostics(this)
    }

    val personalNgramModel: PersonalNgramModel by lazy {
        requireCredentialStorage("personalNgramModel")
        PersonalNgramModel(storeFile = File(filesDir, "personal_ngram.json"), cipher = vaultCipher)
    }

    val personalSentenceVault: PersonalSentenceVault by lazy {
        requireCredentialStorage("personalSentenceVault")
        PersonalSentenceVault(storeFile = File(filesDir, "personal_rag.json"), cipher = vaultCipher)
    }

    val generatedSentenceBank: GeneratedSentenceBank by lazy {
        requireCredentialStorage("generatedSentenceBank")
        GeneratedSentenceBank(file = File(noBackupFilesDir, "gemma_materials.json"), cipher = vaultCipher)
    }

    val sentencePacks: SentencePackRepository by lazy {
        requireCredentialStorage("sentencePacks")
        SentencePackRepository(this, applicationScope) {
            !AppPrefs.getInstance().advanced.offlineMode.getValue()
        }
    }

    val personalGraphStore: org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore by lazy {
        requireCredentialStorage("personalGraphStore")
        org.fcitx.fcitx5.android.input.ai.rag.PersonalGraphStore(storeFile = File(filesDir, "personal_graph.json"), cipher = vaultCipher)
    }

    val vaultCipher: KeystoreVaultCipher by lazy {
        requireCredentialStorage("vaultCipher")
        KeystoreVaultCipher()
    }

    val predictionMetricsStore: PredictionMetricsStore by lazy {
        requireCredentialStorage("predictionMetricsStore")
        PredictionMetricsStore(storeFile = File(filesDir, "prediction_metrics.json"), cipher = vaultCipher)
    }

    val baseKoreanVocabulary: BaseKoreanVocabulary by lazy {
        BaseKoreanVocabulary { assets.open("ko_base_vocab.tsv").reader(Charsets.UTF_8) }
    }

    /** 번들 코퍼스 어절 n-gram. [warmUpLanguageAssets]가 IO 스레드에서 읽어 채우며, 그 전에는 null이다. */
    @Volatile
    var bundledKoreanNgram: BundledKoreanNgram? = null
        private set

    val correctionPatternStore: CorrectionPatternStore by lazy {
        requireCredentialStorage("correctionPatternStore")
        CorrectionPatternStore(storeFile = File(filesDir, "personal_corrections.json"), cipher = vaultCipher)
    }

    val typoCorrector: KeyboardAwareTypoCorrector by lazy {
        requireCredentialStorage("typoCorrector")
        KeyboardAwareTypoCorrector(substitutionCost = correctionPatternStore::personalizedSubstitutionCost)
    }

    /**
     * 기본 어휘 TSV와 개인 n-gram 유니그램을 오타 교정 트라이에 미리 채워 둔다.
     * 실패해도 앱 기동에는 영향이 없어야 하므로 예외는 삼키지 않고 로그만 남긴다.
     *
     * Direct Boot에서는 문장팩·생성 문장 은행·개인 n-gram·오타 교정 트라이(개인 교정 기록에 기대는)가
     * 모두 잠금 해제 전에는 없는 저장소를 쓰므로 건너뛰고, 앱 번들(assets)만 읽는다.
     */
    fun warmUpLanguageAssets() {
        val credentialStorageAvailable =
            DirectBootInputPolicy.allowsCredentialProtectedFeatures(isDirectBootMode)
        if (credentialStorageAvailable) {
            sentencePacks.prepare()
            if (OnDeviceAiSupport.isSupported) {
                applicationScope.launch {
                    try {
                        generatedSentenceBank.load()
                    } catch (e: Exception) {
                        Timber.w("Generated sentence material load failed: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                baseKoreanVocabulary.load()
                if (credentialStorageAvailable) {
                    baseKoreanVocabulary.forEachWord(BaseKoreanVocabulary.TYPO_VOCAB_LIMIT) { word, prior -> typoCorrector.addWord(word, prior) }
                    personalNgramModel.forEachUnigram { word, count ->
                        typoCorrector.addWord(word, PersonalNgramModel.personalPrior(count))
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to warm up language assets")
            }
        }
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val started = SystemClock.elapsedRealtime()
                bundledKoreanNgram = assets.open(BundledKoreanNgram.ASSET_PATH).use(BundledKoreanNgram::read)
                Timber.d("Bundled Korean n-gram loaded in ${SystemClock.elapsedRealtime() - started}ms")
            } catch (e: Exception) {
                Timber.w(e, "Failed to load bundled Korean n-gram")
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
        if (!isDirectBootMode) {
            purgeExternalAiCredentialsIfNeeded(sharedPrefs)
        }
        BackgroundProgressNotifier.ensureChannels(ctx)
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
        if (!isDirectBootMode) {
            // A cloud/device-transfer restore can bring back automatic-learning's `enabled=true`
            // preference without the Gemma model itself (it lives under noBackupFilesDir and is
            // never restored). Force it back off in that case so the toggle never shows "on" with
            // nothing behind it.
            applicationScope.launch {
                try {
                    org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaModelInstaller
                        .enforceAutomaticLearningRequiresModel(ctx)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.w(error, "Failed to enforce automatic-learning model requirement")
                }
            }
            // Creating the controller re-registers the on-device graph enrichment schedule, on any
            // device OnDeviceAiSupport reports as supported (create() returns null otherwise).
            org.fcitx.fcitx5.android.ui.main.ai.dashboard.GemmaPreparationFactory.create(this)
        }
    }

    /**
     * Removes on-disk files and Keystore aliases left by the removed external writing-AI feature.
     * Guarded by a one-shot [SharedPreferences] flag; a partial failure is logged and retried on
     * the next normal (non-direct-boot) start instead of being silently swallowed.
     */
    private fun purgeExternalAiCredentialsIfNeeded(sharedPrefs: android.content.SharedPreferences) {
        if (sharedPrefs.getBoolean(ExternalAiCredentialPurge.PREF_KEY, false)) return
        val purged = runCatching {
            ExternalAiCredentialPurge.purge(noBackupFilesDir) { alias ->
                val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
            }
        }.onFailure {
            Timber.w("external AI credential purge failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        if (purged) {
            sharedPrefs.edit { putBoolean(ExternalAiCredentialPurge.PREF_KEY, true) }
        }
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
