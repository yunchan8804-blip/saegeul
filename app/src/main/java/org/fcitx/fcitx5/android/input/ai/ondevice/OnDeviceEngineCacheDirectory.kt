/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.ondevice

/**
 * litertlm [com.google.ai.edge.litertlm.EngineConfig.cacheDir]에 넘겨 XNNPack 가중치 캐시를
 * 비활성화하는 값. 캐시를 켜면 XNNPack이 모델 크기(수 GB)만큼 디스크에 가중치 캐시를 쓰다 여유
 * 공간이 부족할 때 native 쪽에서 `cannot append buffer to cache file`로 실패해 프로세스가
 * SIGABRT로 죽을 수 있다. 온디바이스 Gemma 엔진을 만드는 모든 지점([OnDeviceSuggestionEngine],
 * [org.fcitx.fcitx5.android.input.ai.ondevice.gemma.GemmaMaterialGenerator])이 이 값을 공유한다.
 */
const val NO_CACHE_DIRECTORY = ":nocache"
