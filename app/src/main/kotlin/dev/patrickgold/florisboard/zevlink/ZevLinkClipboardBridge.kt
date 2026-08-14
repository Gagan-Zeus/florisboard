/*
 * Copyright (C) 2026 ZevLink Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * ZevLinkClipboardBridge - Proof of Concept
 *
 * This module runs inside the FlorisBoard IME process. Because the current default IME
 * is treated specially by Android with respect to background clipboard access, we can
 * reliably detect clipboard changes using only the public Android ClipboardManager API
 * (OnPrimaryClipChangedListener) without requiring an AccessibilityService.
 *
 * Architecture:
 *
 *   User copies text ->
 *     Android system clipboard ->
 *       ClipboardManager.OnPrimaryClipChangedListener (registered from IME) ->
 *         getPrimaryClip() ->
 *           ZevLinkClipboardBridge extracts text + metadata ->
 *             onClipboardItemCaptured callback fires for ZevLink to consume.
 *
 * This file is intentionally self-contained. All ZevLink-specific additions to
 * FlorisBoard live in the `zevlink` package so that upstream FlorisBoard changes
 * can be merged cleanly.
 */

package dev.patrickgold.florisboard.zevlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/**
 * The result of extracting the latest clipboard item.
 *
 * @property text            The text content (null if clipboard was empty/null/non-text).
 * @property contentLength   Length of the extracted text in characters.
 * @property timestampMs     Wall-clock timestamp (System.currentTimeMillis()) of the capture.
 * @property elapsedRealtimeMs Elapsed realtime of the capture, useful for relative timing.
 * @property messageId       Unique identifier for this specific capture.
 * @property isDuplicate     Whether the same text was already the most recent capture.
 * @property mimeTypes       The MIME types reported by the ClipDescription.
 * @property itemCount       Number of ClipData.Item entries in the primary clip.
 * @property imeWindowVisible Whether the IME input window was visible when the change fired.
 * @property debugMode       True if debug logging / test mode is enabled.
 */
data class ZevLinkClipboardItem(
    val text: String?,
    val contentLength: Int,
    val timestampMs: Long,
    val elapsedRealtimeMs: Long,
    val messageId: String,
    val isDuplicate: Boolean,
    val mimeTypes: List<String>,
    val itemCount: Int,
    val imeWindowVisible: Boolean,
    val debugMode: Boolean,
)

/**
 * Callback interface for consumers (the ZevLink Android app, or tests) that
 * want to receive the latest captured clipboard item.
 *
 * Implementations MUST be lightweight. Do NOT perform blocking network I/O
 * inside the callback; dispatch to a background executor instead.
 */
fun interface ZevLinkClipboardConsumer {
    fun onClipboardItemCaptured(item: ZevLinkClipboardItem)
}

/**
 * Transport abstraction for future LAN delivery. Right now this is only an
 * interface so the architecture is clean; actual TCP/WebSocket transport
 * will be added in a later phase after the proof-of-concept is verified.
 */
interface ZevLinkTransport {
    fun send(item: ZevLinkClipboardItem)
}

/**
 * Debug/dev-only logger. When debugMode is true this logs to logcat with
 * clearly visible tags so the proof-of-concept can be verified end-to-end.
 *
 * Production builds MUST set debugMode=false to avoid logging clipboard contents.
 */
object ZevLinkDebugLogger {
    const val TAG = "ZevLinkClipboardBridge"
    @Volatile var debugMode: Boolean = true
    @Volatile var imeActive: Boolean = false
    @Volatile var listenerRegistered: Boolean = false
    @Volatile var imeWindowVisible: Boolean = false

    fun statusSnapshot(): String = buildString {
        append("IME active: ").append(if (imeActive) "YES" else "NO").append(" | ")
        append("Clipboard listener registered: ").append(if (listenerRegistered) "YES" else "NO").append(" | ")
        append("IME window visible: ").append(if (imeWindowVisible) "YES" else "NO").append(" | ")
        append("Debug mode: ").append(if (debugMode) "YES" else "NO")
    }

    fun logStatus() {
        if (debugMode) {
            Log.i(TAG, "STATUS: ${statusSnapshot()}")
        }
    }

    fun logEvent(message: String) {
        if (debugMode) {
            Log.i(TAG, "EVENT: $message")
        }
    }

    fun logSuccess(message: String) {
        if (debugMode) {
            Log.i(TAG, "SUCCESS: $message")
        }
    }

    fun logWarn(message: String) {
        if (debugMode) {
            Log.w(TAG, "WARN: $message")
        }
    }

    fun logClipboardCapture(item: ZevLinkClipboardItem) {
        if (!debugMode) return
        val textPreview = item.text
            ?.replace("\n", "\\n")
            ?.replace("\r", "\\r")
            ?.take(80)
            ?: "<null>"
        Log.i(TAG, buildString {
            append("CAPTURE: ")
            append("id=").append(item.messageId).append(" | ")
            append("ts=").append(item.timestampMs).append(" | ")
            append("len=").append(item.contentLength).append(" chars | ")
            append("duplicate=").append(item.isDuplicate).append(" | ")
            append("imeVisible=").append(item.imeWindowVisible).append(" | ")
            append("mimeTypes=[").append(item.mimeTypes.joinToString(",")).append("] | ")
            append("itemCount=").append(item.itemCount).append(" | ")
            append("preview=\"").append(textPreview).append(if ((item.text?.length ?: 0) > 80) "…" else "").append("\"")
        })
    }
}

/**
 * The main bridge. Registers its own independent OnPrimaryClipChangedListener
 * (separate from FlorisBoard's internal ClipboardManager listener).
 *
 * Usage (from FlorisImeService.onCreate):
 *   val bridge = ZevLinkClipboardBridge(this)
 *   bridge.attachConsumer { item -> ... }
 *   bridge.attachTransport(laneTransport)
 *   bridge.start()
 *
 * From FlorisImeService.onDestroy:
 *   bridge.stop()
 */
class ZevLinkClipboardBridge(
    private val context: Context,
) : ClipboardManager.OnPrimaryClipChangedListener {

    private val systemClipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private val consumers = mutableListOf<ZevLinkClipboardConsumer>()
    private val transports = mutableListOf<ZevLinkTransport>()

    private var lastSentHash: String? = null
    private var lastSentText: String? = null
    private var started = false

    private var debounceElapsed: Long = 0L

    companion object {
        private const val DEBOUNCE_MS = 300L
        private const val SAME_TEXT_WINDOW_MS = 15_000L
        private var lastSameTextSeenAt: Long = 0L
    }

    fun attachConsumer(consumer: ZevLinkClipboardConsumer) {
        synchronized(consumers) { consumers.add(consumer) }
    }

    fun removeConsumer(consumer: ZevLinkClipboardConsumer) {
        synchronized(consumers) { consumers.remove(consumer) }
    }

    fun attachTransport(transport: ZevLinkTransport) {
        synchronized(transports) { transports.add(transport) }
    }

    fun removeTransport(transport: ZevLinkTransport) {
        synchronized(transports) { transports.remove(transport) }
    }

    fun setImeWindowVisible(visible: Boolean) {
        ZevLinkDebugLogger.imeWindowVisible = visible
        ZevLinkDebugLogger.logStatus()
    }

    /**
     * Register the clipboard listener with Android. Safe to call multiple times.
     */
    fun start() {
        if (started) {
            ZevLinkDebugLogger.logWarn("start() called but already started; ignoring")
            return
        }

        ZevLinkDebugLogger.imeActive = true

        try {
            systemClipboardManager.addPrimaryClipChangedListener(this)
            started = true
            ZevLinkDebugLogger.listenerRegistered = true
            ZevLinkDebugLogger.logEvent("OnPrimaryClipChangedListener registered via IME context")
            ZevLinkDebugLogger.logStatus()
        } catch (t: Throwable) {
            ZevLinkDebugLogger.listenerRegistered = false
            ZevLinkDebugLogger.logWarn("Failed to register clipboard listener: ${t.message}")
            Log.e(ZevLinkDebugLogger.TAG, "Registration failure", t)
        }
    }

    /**
     * Unregister the clipboard listener. Safe to call multiple times.
     */
    fun stop() {
        if (!started) return
        try {
            systemClipboardManager.removePrimaryClipChangedListener(this)
        } catch (t: Throwable) {
            Log.e(ZevLinkDebugLogger.TAG, "Unregistration failure", t)
        }
        started = false
        ZevLinkDebugLogger.imeActive = false
        ZevLinkDebugLogger.listenerRegistered = false
        ZevLinkDebugLogger.logEvent("OnPrimaryClipChangedListener UNregistered")
        ZevLinkDebugLogger.logStatus()
    }

    /**
     * Called by Android (via the IME process) whenever the primary clipboard changes.
     *
     * This is the critical proof-of-concept entry point. It verifies that:
     *   (1) The IME process receives this callback even when the soft-keyboard UI
     *       window is not currently visible.
     *   (2) getPrimaryClip() inside the IME context reliably returns the latest item
     *       without AccessibilityService.
     */
    override fun onPrimaryClipChanged() {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()

        if (nowElapsed - debounceElapsed < DEBOUNCE_MS) {
            ZevLinkDebugLogger.logWarn("Debouncing duplicate clipboard signal (${nowElapsed - debounceElapsed}ms < ${DEBOUNCE_MS}ms)")
            return
        }
        debounceElapsed = nowElapsed

        ZevLinkDebugLogger.logEvent("Clipboard change detected (via IME listener)")

        val primaryClip: ClipData? = try {
            systemClipboardManager.primaryClip
        } catch (se: SecurityException) {
            ZevLinkDebugLogger.logWarn("SecurityException reading primaryClip (IME context): ${se.message}")
            Log.w(ZevLinkDebugLogger.TAG, "primaryClip read denied", se)
            null
        } catch (t: Throwable) {
            ZevLinkDebugLogger.logWarn("Unexpected error reading primaryClip: ${t.message}")
            Log.w(ZevLinkDebugLogger.TAG, "primaryClip read error", t)
            null
        }

        val mimeTypes: List<String> = primaryClip?.description
            ?.let { desc -> (0 until desc.mimeTypeCount).mapNotNull { i -> desc.getMimeType(i) } }
            .orEmpty()

        val itemCount = primaryClip?.itemCount ?: 0

        val rawText: String? = run extract@{
            if (primaryClip == null) return@extract null
            if (primaryClip.itemCount <= 0) return@extract null

            if (!mimeTypes.any { mimeTypeMatchesText(it) }) {
                ZevLinkDebugLogger.logWarn("Skipping non-text ClipData; mimeTypes=$mimeTypes")
                return@extract null
            }

            val firstItem = primaryClip.getItemAt(0)
            val coerced = firstItem.coerceToText(context.applicationContext)
            coerced?.toString()?.takeIf { it.isNotBlank() }
        }

        val contentLength = rawText?.length ?: 0
        val isDuplicate = computeDuplicate(rawText, nowElapsed)

        val item = ZevLinkClipboardItem(
            text = rawText,
            contentLength = contentLength,
            timestampMs = nowWall,
            elapsedRealtimeMs = nowElapsed,
            messageId = UUID.randomUUID().toString(),
            isDuplicate = isDuplicate,
            mimeTypes = mimeTypes,
            itemCount = itemCount,
            imeWindowVisible = ZevLinkDebugLogger.imeWindowVisible,
            debugMode = ZevLinkDebugLogger.debugMode,
        )

        ZevLinkDebugLogger.logClipboardCapture(item)

        if (rawText != null && contentLength > 0 && !isDuplicate) {
            rememberSent(rawText)
            dispatchToConsumers(item)
            dispatchToTransports(item)
            ZevLinkDebugLogger.logSuccess(
                "Captured $contentLength chars (IME window visible=${item.imeWindowVisible}). " +
                    "Would send to ZevLink transport."
            )
        } else if (isDuplicate) {
            ZevLinkDebugLogger.logEvent("Detected duplicate clipboard item; not dispatching to consumers")
        } else {
            ZevLinkDebugLogger.logWarn("No text content to dispatch (length=$contentLength)")
        }
    }

    /**
     * Poll for the current clipboard immediately. Used by tests / settings UI to
     * assert the IME context can read getPrimaryClip(). Returns the captured item.
     */
    fun pollCurrentClipboardNow(): ZevLinkClipboardItem {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()

        val primaryClip: ClipData? = try {
            systemClipboardManager.primaryClip
        } catch (t: Throwable) {
            Log.w(ZevLinkDebugLogger.TAG, "poll read error", t)
            null
        }

        val mimeTypes: List<String> = primaryClip?.description
            ?.let { desc -> (0 until desc.mimeTypeCount).mapNotNull { i -> desc.getMimeType(i) } }
            .orEmpty()
        val itemCount = primaryClip?.itemCount ?: 0

        val rawText: String? = if (primaryClip != null && primaryClip.itemCount > 0 &&
            mimeTypes.any { mimeTypeMatchesText(it) }) {
            primaryClip.getItemAt(0).coerceToText(context.applicationContext)?.toString()?.takeIf { it.isNotBlank() }
        } else null

        val contentLength = rawText?.length ?: 0
        val isDuplicate = computeDuplicate(rawText, nowElapsed)

        return ZevLinkClipboardItem(
            text = rawText,
            contentLength = contentLength,
            timestampMs = nowWall,
            elapsedRealtimeMs = nowElapsed,
            messageId = UUID.randomUUID().toString(),
            isDuplicate = isDuplicate,
            mimeTypes = mimeTypes,
            itemCount = itemCount,
            imeWindowVisible = ZevLinkDebugLogger.imeWindowVisible,
            debugMode = ZevLinkDebugLogger.debugMode,
        ).also { ZevLinkDebugLogger.logClipboardCapture(it) }
    }

    private fun dispatchToConsumers(item: ZevLinkClipboardItem) {
        val snapshot: List<ZevLinkClipboardConsumer> = synchronized(consumers) { consumers.toList() }
        for (consumer in snapshot) {
            try {
                consumer.onClipboardItemCaptured(item)
            } catch (t: Throwable) {
                Log.w(ZevLinkDebugLogger.TAG, "Consumer threw", t)
            }
        }
    }

    private fun dispatchToTransports(item: ZevLinkClipboardItem) {
        val snapshot: List<ZevLinkTransport> = synchronized(transports) { transports.toList() }
        for (transport in snapshot) {
            try {
                transport.send(item)
            } catch (t: Throwable) {
                Log.w(ZevLinkDebugLogger.TAG, "Transport threw", t)
            }
        }
    }

    private fun computeDuplicate(text: String?, nowElapsed: Long): Boolean {
        if (text == null) return false
        if (text.isBlank()) return false
        if (lastSentText == null || lastSentHash == null) return false
        val sameText = lastSentText == text || lastSentHash == sha256(text)
        if (!sameText) return false
        if (nowElapsed - lastSameTextSeenAt > SAME_TEXT_WINDOW_MS) return false
        return true
    }

    private fun rememberSent(text: String) {
        lastSentText = text
        lastSentHash = sha256(text)
        lastSameTextSeenAt = SystemClock.elapsedRealtime()
    }

    private fun mimeTypeMatchesText(mime: String): Boolean {
        if (mime == "text/*") return true
        if (mime == "*/*") return true
        return mime.startsWith("text/")
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xff))
        }
        return sb.toString()
    }
}
