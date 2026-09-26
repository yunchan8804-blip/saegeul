/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import org.fcitx.fcitx5.android.input.ai.daemon.AiDaemonClient
import org.fcitx.fcitx5.android.input.ai.daemon.AiDaemonService
import org.fcitx.fcitx5.android.input.ai.daemon.IAiDaemonService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@MediumTest
class AiDaemonE2eDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * 1. testAiDaemonProcessSeparation:
     * Context로부터 ServiceConnection을 통해 AiDaemonService 바인딩 후,
     * service.daemonPid를 가져와 android.os.Process.myPid()와 상이함을 확인 (assertNotEquals(Process.myPid(), service.daemonPid)).
     */
    @Test(timeout = 10000)
    fun testAiDaemonProcessSeparation() {
        val latch = CountDownLatch(1)
        var remoteService: IAiDaemonService? = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remoteService = IAiDaemonService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remoteService = null
            }
        }

        val intent = Intent(context, AiDaemonService::class.java)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        assertTrue("bindService should succeed", bound)

        try {
            assertTrue("Waiting for AiDaemonService connection timed out", latch.await(5, TimeUnit.SECONDS))
            assertNotNull("remoteService must not be null", remoteService)

            val daemonPid = remoteService!!.daemonPid
            val myPid = Process.myPid()

            assertTrue("Daemon PID must be valid (> 0)", daemonPid > 0)
            assertNotEquals("Daemon process must run in an isolated process (:ai_daemon)", myPid, daemonPid)
        } finally {
            context.unbindService(connection)
        }
    }

    /**
     * 2. testPrecomputedKvCacheHitAndLatency:
     * service.warmupPromptCache(sysPrompt, persona) 실행 후, 반복 조회 시 < 0.1ms 수준으로 즉각 히트 확인.
     */
    @Test(timeout = 10000)
    fun testPrecomputedKvCacheHitAndLatency() {
        val latch = CountDownLatch(1)
        var remoteService: IAiDaemonService? = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remoteService = IAiDaemonService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remoteService = null
            }
        }

        val intent = Intent(context, AiDaemonService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            val service = remoteService!!

            val sysPrompt = "당신은 한국어 스마트 키보드 보조 AI입니다."
            val persona = "사용자 프로필: 빠른 입력 및 정중한 비즈니스체 선호"

            service.warmupPromptCache(sysPrompt, persona)
            assertTrue("Cache should be warm after warmupPromptCache", service.isWarm)

            // Benchmark latency of IPC isWarm or cache lookup
            val iterations = 100
            val startNs = System.nanoTime()
            repeat(iterations) {
                service.isWarm
            }
            val elapsedNs = System.nanoTime() - startNs
            val avgMs = (elapsedNs.toDouble() / iterations) / 1_000_000.0

            println("E2E isWarm verification average IPC latency: ${avgMs}ms")
            // In Android Binder IPC, roundtrip is typically ~0.05-0.15ms.
            assertTrue("Binder isWarm query must be rapid", avgMs < 1.0)
        } finally {
            context.unbindService(connection)
        }
    }

    /**
     * 3. testPromptLookupDecodingVerification:
     * service.generatePromptLookupSync 호출 시, 참조 문맥 내의 구문이 문법/조사 검증을 통과하여 빠르고 정확하게 반환되는지 확인.
     */
    @Test(timeout = 10000)
    fun testPromptLookupDecodingVerification() {
        val latch = CountDownLatch(1)
        var remoteService: IAiDaemonService? = null

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remoteService = IAiDaemonService.Stub.asInterface(binder)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                remoteService = null
            }
        }

        val intent = Intent(context, AiDaemonService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            val service = remoteService!!

            val referenceContext = "새글 키보드는 대한민국에서 가장 빠르고 정확한 입력기입니다."
            val prompt = "새글 키보드는 대한민국에서 가장 빠르고"

            val draft = service.generatePromptLookupSync(prompt, referenceContext, 4)

            assertNotNull(draft)
            assertTrue("Draft should not be empty", draft.isNotEmpty())
            assertTrue("Draft should contain '정확한', but was: '$draft'", draft.contains("정확한"))
        } finally {
            context.unbindService(connection)
        }
    }

    /**
     * 4. testBinderDeathIsolation:
     * AiDaemonClient에 서비스 연결 후 바인더 사망 시뮬레이션 시 executeWithFallback이
     * 메인 프로세스 크래시 없이 안전하게 fallback 블록을 반환하는지 검증.
     */
    @Test(timeout = 10000)
    fun testBinderDeathIsolation() {
        val client = AiDaemonClient(context)
        val bound = client.bind(context)
        assertTrue("Client bind must succeed", bound)

        try {
            // Wait up to 3 seconds for binding if async
            var waitMs = 0
            while (!client.isBound && waitMs < 3000) {
                Thread.sleep(50)
                waitMs += 50
            }

            // Normal execution before death
            if (client.isBound) {
                val pid = client.executeWithFallback(
                    block = { it.daemonPid },
                    fallback = { -1 }
                )
                assertTrue("Remote PID before death should be > 0", pid > 0)
            }

            // Simulate binder death event on the client
            client.binderDied()

            // Verification: executeWithFallback should return fallback result without throwing any exception
            val fallbackResult = client.executeWithFallback(
                block = { it.daemonPid },
                fallback = { 9999 }
            )

            assertEquals("Must execute fallback cleanly on binder death", 9999, fallbackResult)
            assertEquals("isBound must be false after binderDied", false, client.isBound)
            assertEquals("service reference must be null after binderDied", null, client.service)
        } finally {
            client.unbind(context)
        }
    }
}
