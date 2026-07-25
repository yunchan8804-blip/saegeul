/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.gif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GifLicensePolicyTest {
    @Test
    fun acceptsExplicitOpenLicenses() {
        assertTrue(GifLicensePolicy.isAllowed("CC0", "", "safe animation"))
        assertTrue(GifLicensePolicy.isAllowed("CC BY-SA 4.0", "", "safe animation"))
        assertTrue(GifLicensePolicy.isAllowed("Public domain", "", "safe animation"))
    }

    @Test
    fun rejectsUnknownRestrictedOrSuspiciousFiles() {
        assertFalse(GifLicensePolicy.isAllowed("Copyrighted", "", "safe animation"))
        assertFalse(GifLicensePolicy.isAllowed("CC BY 4.0", "personality rights", "safe"))
        assertFalse(GifLicensePolicy.isAllowed("CC BY 4.0", "", "Deletion candidate"))
    }
}
