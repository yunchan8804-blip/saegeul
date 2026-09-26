/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice.gemma

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.HttpURLConnection

class GemmaModelDownloadDecisionsTest {

    @Test
    fun noRangeHeaderWhenNothingIsOnDiskYet() {
        assertNull(rangeHeaderFor(0L))
    }

    @Test
    fun rangeHeaderResumesFromTheExistingPartLength() {
        assertEquals("bytes=1048576-", rangeHeaderFor(1_048_576L))
    }

    @Test
    fun aPartialContentResponseToARangeRequestKeepsTheExistingBytes() {
        assertEquals(
            GemmaResumeOutcome.KEEP_EXISTING_BYTES,
            decideResumeOutcome(requestedRange = true, responseCode = HttpURLConnection.HTTP_PARTIAL)
        )
    }

    @Test
    fun aPlainOkResponseToARangeRequestDiscardsTheExistingBytes() {
        // The server ignored the Range header and is sending the whole file from byte 0 again.
        assertEquals(
            GemmaResumeOutcome.DISCARD_EXISTING_BYTES,
            decideResumeOutcome(requestedRange = true, responseCode = HttpURLConnection.HTTP_OK)
        )
    }

    @Test
    fun noRangeWasRequestedSoAnOkResponseDiscardsWhateverWasOnDisk() {
        assertEquals(
            GemmaResumeOutcome.DISCARD_EXISTING_BYTES,
            decideResumeOutcome(requestedRange = false, responseCode = HttpURLConnection.HTTP_OK)
        )
    }

    @Test
    fun freeSpaceIsEnoughWhenAvailableMeetsOrExceedsRequired() {
        assert(hasEnoughFreeSpace(availableBytes = 100L, requiredBytes = 100L))
        assert(hasEnoughFreeSpace(availableBytes = 101L, requiredBytes = 100L))
    }

    @Test
    fun freeSpaceIsNotEnoughWhenAvailableFallsShort() {
        assert(!hasEnoughFreeSpace(availableBytes = 99L, requiredBytes = 100L))
    }

    @Test
    fun whileRunningTheWorkersOwnProgressBytesWinOverTheRawPartFileLength() {
        // The worker's setProgress value is what pushes a new Flow emission in the first place; a
        // stale `.part` length read a moment earlier would visibly lag behind it.
        assertEquals(
            500L,
            resolveDownloadedBytes(WorkInfo.State.RUNNING, progressDownloadedBytes = 500L, partFileBytes = 480L)
        )
    }

    @Test
    fun fallsBackToThePartFileLengthWhenNotRunning() {
        // ENQUEUED/CANCELLED/FAILED/no-WorkInfo-at-all: any leftover progress data from a previous
        // generation of the work item is not trustworthy once it stops actively running.
        assertEquals(
            480L,
            resolveDownloadedBytes(WorkInfo.State.ENQUEUED, progressDownloadedBytes = 500L, partFileBytes = 480L)
        )
        assertEquals(
            480L,
            resolveDownloadedBytes(null, progressDownloadedBytes = 500L, partFileBytes = 480L)
        )
    }

    @Test
    fun aMissingPartFileAlwaysWinsRegardlessOfStaleProgressData() {
        assertNull(resolveDownloadedBytes(WorkInfo.State.RUNNING, progressDownloadedBytes = 500L, partFileBytes = null))
    }

    @Test
    fun modelInstalledWinsOverAnyPartOrWorkState() {
        // Covers the Downloading -> Installed transition: the instant the model file lands (the
        // worker's rename right after verifyAndInstallPart), Installed is reported even against a
        // WorkInfo snapshot that still says RUNNING with in-flight Downloading-shaped progress data -
        // e.g. a `WorkManager` query that raced the transition. GemmaModelInstaller has nothing left to
        // poll for once this flips: the next `getWorkInfosForUniqueWorkFlow` emission (the worker's own
        // terminal `Result.success()`) is the last one for this work generation.
        assertEquals(
            GemmaInstallState.Installed,
            deriveInstallState(
                modelInstalled = true,
                partFileBytes = 500L,
                workState = WorkInfo.State.RUNNING,
                verifying = false,
                bytesPerSecond = 999L,
                allowMobileData = true,
                failureReason = GemmaInstallFailure.NETWORK,
                totalBytes = 1000L
            )
        )
    }

    @Test
    fun noPartAndNoWorkIsNotInstalled() {
        assertEquals(
            GemmaInstallState.NotInstalled,
            deriveInstallState(
                modelInstalled = false,
                partFileBytes = null,
                workState = null,
                verifying = false,
                bytesPerSecond = 0L,
                allowMobileData = false,
                failureReason = null,
                totalBytes = 1000L
            )
        )
    }

    @Test
    fun noPartButAFailedWorkReportsTheFailure() {
        assertEquals(
            GemmaInstallState.Failed(GemmaInstallFailure.SERVER, 0L, 1000L),
            deriveInstallState(
                modelInstalled = false,
                partFileBytes = null,
                workState = WorkInfo.State.FAILED,
                verifying = false,
                bytesPerSecond = 0L,
                allowMobileData = false,
                failureReason = GemmaInstallFailure.SERVER,
                totalBytes = 1000L
            )
        )
    }

    @Test
    fun runningWithAPartIsDownloading() {
        assertEquals(
            GemmaInstallState.Downloading(500L, 1000L, 250L, allowMobileData = true),
            deriveInstallState(
                modelInstalled = false,
                partFileBytes = 500L,
                workState = WorkInfo.State.RUNNING,
                verifying = false,
                bytesPerSecond = 250L,
                allowMobileData = true,
                failureReason = null,
                totalBytes = 1000L
            )
        )
    }

    @Test
    fun runningWhileVerifyingReportsVerifyingInstead() {
        assertEquals(
            GemmaInstallState.Verifying,
            deriveInstallState(
                modelInstalled = false,
                partFileBytes = 1000L,
                workState = WorkInfo.State.RUNNING,
                verifying = true,
                bytesPerSecond = 0L,
                allowMobileData = false,
                failureReason = null,
                totalBytes = 1000L
            )
        )
    }

    @Test
    fun enqueuedWithAPartIsWaitingForNetwork() {
        assertEquals(
            GemmaInstallState.WaitingForNetwork(500L, 1000L, wifiOnly = true),
            deriveInstallState(
                modelInstalled = false,
                partFileBytes = 500L,
                workState = WorkInfo.State.ENQUEUED,
                verifying = false,
                bytesPerSecond = 0L,
                allowMobileData = false,
                failureReason = null,
                totalBytes = 1000L
            )
        )
    }

    @Test
    fun blockedWithAPartIsAlsoWaitingForNetwork() {
        assertEquals(
            GemmaInstallState.WaitingForNetwork(500L, 1000L, wifiOnly = false),
            deriveInstallState(
                modelInstalled = false,
                partFileBytes = 500L,
                workState = WorkInfo.State.BLOCKED,
                verifying = false,
                bytesPerSecond = 0L,
                allowMobileData = true,
                failureReason = null,
                totalBytes = 1000L
            )
        )
    }

    @Test
    fun failedWithAPartKeepsTheDownloadedBytes() {
        assertEquals(
            GemmaInstallState.Failed(GemmaInstallFailure.VERIFY_FAILED, 500L, 1000L),
            deriveInstallState(
                modelInstalled = false,
                partFileBytes = 500L,
                workState = WorkInfo.State.FAILED,
                verifying = false,
                bytesPerSecond = 0L,
                allowMobileData = false,
                failureReason = GemmaInstallFailure.VERIFY_FAILED,
                totalBytes = 1000L
            )
        )
    }

    @Test
    fun failedWithAPartButNoRecordedReasonFallsBackToUnknown() {
        assertEquals(
            GemmaInstallFailure.UNKNOWN,
            (deriveInstallState(
                modelInstalled = false,
                partFileBytes = 500L,
                workState = WorkInfo.State.FAILED,
                verifying = false,
                bytesPerSecond = 0L,
                allowMobileData = false,
                failureReason = null,
                totalBytes = 1000L
            ) as GemmaInstallState.Failed).reason
        )
    }

    @Test
    fun cancelledWithAPartIsPaused() {
        assertEquals(
            GemmaInstallState.Paused(500L, 1000L),
            deriveInstallState(
                modelInstalled = false,
                partFileBytes = 500L,
                workState = WorkInfo.State.CANCELLED,
                verifying = false,
                bytesPerSecond = 0L,
                allowMobileData = false,
                failureReason = null,
                totalBytes = 1000L
            )
        )
    }

    @Test
    fun aLeftoverPartWithNoKnownWorkAtAllIsAlsoPaused() {
        // A process restart with a `.part` file on disk but nothing currently scheduled: resuming it
        // is exactly what GemmaModelInstaller.start does for a Paused state.
        assertEquals(
            GemmaInstallState.Paused(500L, 1000L),
            deriveInstallState(
                modelInstalled = false,
                partFileBytes = 500L,
                workState = null,
                verifying = false,
                bytesPerSecond = 0L,
                allowMobileData = false,
                failureReason = null,
                totalBytes = 1000L
            )
        )
    }

    @Test
    fun aShaMismatchMessageClassifiesAsVerifyFailed() {
        assertEquals(
            GemmaDownloadFailureClassification.VERIFY_FAILED,
            classifyDownloadFailure("모델 SHA-256 검증에 실패했습니다.")
        )
    }

    @Test
    fun aSizeMismatchMessageClassifiesAsVerifyFailed() {
        assertEquals(
            GemmaDownloadFailureClassification.VERIFY_FAILED,
            classifyDownloadFailure("모델 파일 크기가 고정 크기와 다릅니다: 10")
        )
    }

    @Test
    fun aGenericNetworkErrorMessageIsRetryable() {
        assertEquals(
            GemmaDownloadFailureClassification.RETRYABLE,
            classifyDownloadFailure("모델 다운로드 HTTP 503")
        )
    }

    @Test
    fun aNullMessageIsRetryable() {
        assertEquals(GemmaDownloadFailureClassification.RETRYABLE, classifyDownloadFailure(null))
    }

    @Test
    fun speedTrackerAveragesOverTheTrailingWindow() {
        val tracker = GemmaDownloadSpeedTracker(windowMs = 5_000L)
        assertEquals(0L, tracker.sample(0L, 0L))
        assertEquals(200_000L, tracker.sample(1_000L, 200_000L))
        // 1MB total over 2s so far -> 500,000 bytes/s.
        assertEquals(500_000L, tracker.sample(2_000L, 1_000_000L))
        // Once the window is full, only the trailing 5s of samples count.
        assertEquals(200_000L, tracker.sample(7_000L, 2_000_000L))
    }
}
