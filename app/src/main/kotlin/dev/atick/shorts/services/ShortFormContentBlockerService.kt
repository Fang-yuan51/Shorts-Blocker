/*
 * Copyright 2025 Atick Faisal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.atick.shorts.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.atick.shorts.utils.UserPreferencesProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("AccessibilityPolicy")
class ShortFormContentBlockerService : AccessibilityService() {

    private val lastActionTimestamps = ConcurrentHashMap<String, Long>()
    private val actionCooldownMillis = 1500L
    private val userPreferencesProvider = UserPreferencesProvider(applicationContext)

    override fun onServiceConnected() {
        Timber.d("ShortFormContentBlockerService connected")

        CoroutineScope(Dispatchers.IO).launch {
            val packages = userPreferencesProvider.getTrackedPackages().first()
            val info = AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                packageNames = packages.toTypedArray()
                notificationTimeout = 100
            }
            serviceInfo = info
            Timber.d("AccessibilityServiceInfo configured for tracked packages: ${packages.joinToString()}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        Timber.v("Accessibility event: type=${event.eventType}, className=${event.className}, package=${event.packageName}")

        val windows = windows
        Timber.v("Inspecting ${windows.size} windows")
        for (win in windows) {
            val root = win.root ?: continue
            if (isShortFormContent(root)) {
                Timber.i("Short-form content detected")
                val key = "content_detected"
                if (shouldPerformAction(key)) {
                    handleShortFormContentDetected(root)
                } else {
                    Timber.d("Action skipped due to cooldown")
                }
                break
            }
        }
    }

    override fun onInterrupt() {
        Timber.w("ShortFormContentBlockerService interrupted")
    }

    private fun isShortFormContent(node: AccessibilityNodeInfo): Boolean {
        var hasShortIndicator = false
        var hasVerticalVideo = false

        // 1) check view-id patterns for shorts/reels player container
        val viewId = node.viewIdResourceName
        if (viewId != null && (
                viewId.contains("shorts", ignoreCase = true) ||
                    viewId.contains("reel", ignoreCase = true) ||
                    viewId.contains("short_player", ignoreCase = true) ||
                    viewId.contains("clips", ignoreCase = true)
                )
        ) {
            Timber.d("Short-form content indicator found in view ID: $viewId")
            hasShortIndicator = true
        }

        // 2) Check for structural heuristic: vertical video container (height >> width)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val aspect = if (rect.width() == 0) 0f else rect.height().toFloat() / rect.width()

        if (aspect > 1.7f && rect.height() > 800) {
            Timber.v("Vertical container found with aspect ratio: $aspect, height: ${rect.height()}")
            hasVerticalVideo = true

            if (hasShortFormUiPattern(node)) {
                Timber.d("Short-form content UI pattern confirmed in vertical container")
                hasShortIndicator = true
            }
        }

        // Require BOTH vertical video layout AND short-form content indicators
        val isShortFormContent = hasVerticalVideo && hasShortIndicator

        if (isShortFormContent) {
            Timber.i("Short-form content screen confirmed with both indicators")
        } else {
            Timber.v("Not short-form content: hasVerticalVideo=$hasVerticalVideo, hasShortIndicator=$hasShortIndicator")
        }

        return isShortFormContent
    }

    private fun hasShortFormUiPattern(node: AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        var nodesScanned = 0
        var foundShortFormIndicators = 0

        // Look for short-form content player elements (Shorts, Reels, etc.)
        val shortFormPlayerIds = listOf(
            "shorts_player",
            "reel_player",
            "shorts_video",
            "reel_video_player",
            "reel_dyn_remix",
            "shorts_comment",
            "shorts_like_button",
            "reel_pivot_button",
            "clips_viewer",
            "clips_player",
        )

        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            nodesScanned++

            if (nodesScanned > 100) break

            val id = n.viewIdResourceName
            if (id != null) {
                for (playerId in shortFormPlayerIds) {
                    if (id.contains(playerId, ignoreCase = true)) {
                        Timber.d("Short-form content player component found: $id")
                        foundShortFormIndicators++
                    }
                }
            }

            val desc = n.contentDescription?.toString()
            if (!desc.isNullOrBlank() && desc.length > 20) {
                if ((desc.contains("Shorts", true) || desc.contains("Reel", true)) &&
                    (
                        desc.contains("video", true) || desc.contains(
                            "playing",
                            true,
                        ) || desc.contains("paused", true)
                        )
                ) {
                    Timber.d("Short-form content video description found: $desc")
                    foundShortFormIndicators++
                }
            }

            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }

        val hasPattern = foundShortFormIndicators > 0
        Timber.v("Scanned $nodesScanned nodes, found $foundShortFormIndicators short-form indicators")
        return hasPattern
    }

    private fun handleShortFormContentDetected(root: AccessibilityNodeInfo) {
        Timber.i("Handling short-form content detection - performing BACK action")
        val success = performGlobalAction(GLOBAL_ACTION_BACK)
        if (success) {
            Timber.d("BACK action performed successfully")
        } else {
            Timber.w("BACK action failed")
        }
    }

    private fun shouldPerformAction(key: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val last = lastActionTimestamps[key] ?: 0L
        val timeSinceLastAction = now - last
        if (timeSinceLastAction < actionCooldownMillis) {
            Timber.v("Action '$key' cooldown active: ${timeSinceLastAction}ms since last action")
            return false
        }
        lastActionTimestamps[key] = now
        Timber.d("Action '$key' allowed (cooldown: ${actionCooldownMillis}ms)")
        return true
    }
}
