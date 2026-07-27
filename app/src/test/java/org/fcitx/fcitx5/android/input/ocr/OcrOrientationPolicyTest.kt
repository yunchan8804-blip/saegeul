/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrOrientationPolicyTest {
    @Test
    fun `exif orientations map to rotation and reflection`() {
        assertEquals(OcrImageTransform(), OcrExifOrientation.transformFor(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(
            OcrImageTransform(flipHorizontal = true),
            OcrExifOrientation.transformFor(ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        )
        assertEquals(
            OcrImageTransform(90),
            OcrExifOrientation.transformFor(ExifInterface.ORIENTATION_ROTATE_90)
        )
        assertEquals(
            OcrImageTransform(-90, flipHorizontal = true),
            OcrExifOrientation.transformFor(ExifInterface.ORIENTATION_TRANSVERSE)
        )
    }

    @Test
    fun `clear Korean recognition does not trigger expensive rotation fallback`() {
        assertFalse(
            OcrOrientationPolicy.needsFallback(
                OcrRecognitionCandidate("안녕하세요 반갑습니다", confidence = 82, rotationDegrees = 0)
            )
        )
    }

    @Test
    fun `weak or non Korean recognition triggers rotation fallback`() {
        assertTrue(
            OcrOrientationPolicy.needsFallback(
                OcrRecognitionCandidate("[]", confidence = 18, rotationDegrees = 0)
            )
        )
        assertTrue(
            OcrOrientationPolicy.needsFallback(
                OcrRecognitionCandidate("rotated text", confidence = 91, rotationDegrees = 0)
            )
        )
        assertTrue(
            OcrOrientationPolicy.needsFallback(
                OcrRecognitionCandidate("하세요\n미0\n1\n}\n니다", confidence = 78, rotationDegrees = 0)
            )
        )
    }

    @Test
    fun `best orientation rewards Korean content without ignoring confidence`() {
        val result = OcrOrientationPolicy.best(
            listOf(
                OcrRecognitionCandidate("random upright text", confidence = 90, rotationDegrees = 0),
                OcrRecognitionCandidate(
                    "회의는 오후 세 시에 시작합니다",
                    confidence = 74,
                    rotationDegrees = 90
                ),
                OcrRecognitionCandidate("ㅣㅣ", confidence = 20, rotationDegrees = -90)
            )
        )

        assertEquals(90, result.rotationDegrees)
    }
}
