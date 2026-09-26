/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 YunChan
 */
package org.fcitx.fcitx5.android.input.ai.daemon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * Client-side IPC bridge and death-recipient supervisor for the `:ai_daemon` isolated process.
 * Guarantees zero crash propagation to the 120Hz main IME process through [executeWithFallback].
 */
class AiDaemonClient(private val defaultContext: Context? = null) : IBinder.DeathRecipient {

    @Volatile
    var service: IAiDaemonService? = null
        @VisibleForTesting set

    @Volatile
    var isBound: Boolean = false
        @VisibleForTesting set

    private var boundContext: Context? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) {
                Log.w(TAG, "onServiceConnected received null binder")
                return
            }
            try {
                binder.linkToDeath(this@AiDaemonClient, 0)
                service = IAiDaemonService.Stub.asInterface(binder)
                isBound = true
                Log.i(TAG, "AiDaemon connected: PID=${service?.daemonPid}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to link death recipient or initialize AiDaemonService", e)
                service = null
                isBound = false
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "AiDaemon disconnected unexpectedly")
            service = null
            isBound = false
        }
    }

    /**
     * Triggered when the remote `:ai_daemon` process crashes or is killed (`kill -9`).
     * Guaranteed never to throw exceptions into the calling UI thread.
     */
    override fun binderDied() {
        Log.e(TAG, "CRITICAL: :ai_daemon process died! Isolating IPC and enabling graceful fallback.")
        try {
            service?.asBinder()?.unlinkToDeath(this, 0)
        } catch (_: Exception) {
            // Ignore unlinking errors during remote death
        }
        service = null
        isBound = false
    }

    /**
     * Binds to [AiDaemonService].
     */
    @Synchronized
    fun bind(context: Context? = null): Boolean {
        if (isBound && service != null) {
            return true
        }
        val targetContext = context ?: defaultContext ?: run {
            Log.e(TAG, "Context required to bindService")
            return false
        }
        boundContext = targetContext.applicationContext
        val intent = Intent(targetContext, AiDaemonService::class.java)
        return try {
            val result = targetContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService requested, returned=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "bindService threw exception", e)
            false
        }
    }

    /**
     * Unbinds from [AiDaemonService].
     */
    @Synchronized
    fun unbind(context: Context? = null) {
        if (!isBound) return
        val targetContext = context ?: boundContext ?: defaultContext ?: return
        try {
            service?.asBinder()?.unlinkToDeath(this, 0)
        } catch (_: Exception) {}
        try {
            targetContext.unbindService(connection)
        } catch (e: Exception) {
            Log.w(TAG, "unbindService encountered exception", e)
        } finally {
            service = null
            isBound = false
            boundContext = null
            Log.i(TAG, "AiDaemonService unbound cleanly")
        }
    }

    /**
     * Executes an IPC call against [IAiDaemonService].
     * If the remote process is dead, disconnected, or throws a [RemoteException],
     * gracefully returns the result of [fallback] with 0ms UI jank.
     */
    fun <T> executeWithFallback(block: (IAiDaemonService) -> T, fallback: () -> T): T {
        val s = service
        if (s == null || !isBound) {
            return fallback()
        }
        return try {
            block(s)
        } catch (e: RemoteException) {
            Log.w(TAG, "RemoteException caught in executeWithFallback -> fallback invoked", e)
            fallback()
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected exception in executeWithFallback -> fallback invoked", e)
            fallback()
        }
    }

    companion object {
        private const val TAG = "AiDaemonClient"

        @Volatile
        private var INSTANCE: AiDaemonClient? = null

        fun getInstance(context: Context): AiDaemonClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AiDaemonClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
