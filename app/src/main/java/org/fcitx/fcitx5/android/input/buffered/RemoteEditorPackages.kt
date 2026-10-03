/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.buffered

/**
 * 조합 중 글자(composing span)를 화면에 그리지 않는 원격·터미널 앱.
 */
object RemoteEditorPackages {

    private val PACKAGES = setOf(
        "com.google.chromeremotedesktop",
        "com.microsoft.rdc.androidx",
        "com.microsoft.rdc.android",
        "com.microsoft.a3rdc",
        "com.realvnc.viewer.android",
        "com.teamviewer.teamviewer.market.mobile",
        "com.anydesk.anydeskandroid",
        "com.rustdesk.rustdesk",
        "com.freerdp.afreerdp",
        "com.iiordanov.bVNC",
        "com.iiordanov.freebVNC",
        "com.iiordanov.aRDP",
        "com.iiordanov.freeaRDP",
        "com.splashtop.remote.pad.v2",
        "com.parsec.app",
        "com.termux",
        "com.sonelli.juicessh",
        "com.server.auditor.ssh.client",
        "org.connectbot"
    )

    fun isRemote(packageName: String?): Boolean = packageName != null && packageName in PACKAGES
}
