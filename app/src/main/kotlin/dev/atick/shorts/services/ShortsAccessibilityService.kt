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
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

@SuppressLint("AccessibilityPolicy")
class ShortsAccessibilityService : AccessibilityService() {

    private val lastActionTimestamps = ConcurrentHashMap<String, Long>()
    private val actionCooldownMillis = 1500L // rate-limit actions

    override fun onServiceConnected() {
        Timber.d("ShortsAccessibilityService connected")
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            packageNames = arrayOf("com.google.android.youtube")
            notificationTimeout = 100
        }
        serviceInfo = info
        Timber.d("AccessibilityServiceInfo configured for YouTube")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName != "com.google.android.youtube") return

        Timber.v("Accessibility event: type=${event.eventType}, className=${event.className}")

        // Prefer window-based inspection (more reliable)
        val windows = windows // AccessibilityService.getWindows()
        Timber.v("Inspecting ${windows.size} windows")
        for (win in windows) {
            val root = win.root ?: continue
            if (isShortsView(root)) {
                Timber.i("Shorts view detected")
                val key = "shorts_detected"
                if (shouldPerformAction(key)) {
                    handleShortsDetected(root)
                } else {
                    Timber.d("Action skipped due to cooldown")
                }
                break
            }
        }
    }

    override fun onInterrupt() {
        Timber.w("ShortsAccessibilityService interrupted")
    }

    private fun isShortsView(node: AccessibilityNodeInfo): Boolean {
        // More precise detection to avoid false positives
        var hasShortIndicator = false
        var hasVerticalVideo = false

        // 1) check view-id patterns specifically for shorts player/reels container
        val viewId = node.viewIdResourceName
        if (viewId != null && (
            viewId.contains("shorts", ignoreCase = true) ||
            viewId.contains("reel", ignoreCase = true) ||
            viewId.contains("short_player", ignoreCase = true)
        )) {
            Timber.d("Shorts indicator found in view ID: $viewId")
            hasShortIndicator = true
        }

        // 2) Check for structural heuristic: vertical video container (height >> width)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val aspect = if (rect.width() == 0) 0f else rect.height().toFloat() / rect.width()

        // More strict aspect ratio and size check to avoid false positives
        if (aspect > 1.7f && rect.height() > 800) {
            Timber.v("Vertical container found with aspect ratio: $aspect, height: ${rect.height()}")
            hasVerticalVideo = true

            // Check for shorts-specific UI patterns
            if (hasShortsUiPattern(node)) {
                Timber.d("Shorts UI pattern confirmed in vertical container")
                hasShortIndicator = true
            }
        }

        // Require BOTH vertical video layout AND shorts-specific indicators
        val isShortsScreen = hasVerticalVideo && hasShortIndicator

        if (isShortsScreen) {
            Timber.i("Shorts screen confirmed with both indicators")
        } else {
            Timber.v("Not Shorts: hasVerticalVideo=$hasVerticalVideo, hasShortIndicator=$hasShortIndicator")
        }

        return isShortsScreen
    }

    private fun hasShortsUiPattern(node: AccessibilityNodeInfo): Boolean {
        // Scan for specific Shorts player UI elements, avoiding navigation tabs
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        var nodesScanned = 0
        var foundShortsIndicators = 0

        // Look for specific Shorts player elements
        val shortsPlayerIds = listOf(
            "shorts_player",
            "reel_player",
            "shorts_video",
            "reel_video_player",
            "reel_dyn_remix",
            "shorts_comment",
            "shorts_like_button",
            "reel_pivot_button"
        )

        while (stack.isNotEmpty()) {
            val n = stack.removeFirst()
            nodesScanned++

            // Limit scan depth to avoid performance issues
            if (nodesScanned > 100) break

            val id = n.viewIdResourceName
            if (id != null) {
                // Check for specific Shorts player component IDs
                for (playerId in shortsPlayerIds) {
                    if (id.contains(playerId, ignoreCase = true)) {
                        Timber.d("Shorts player component found: $id")
                        foundShortsIndicators++
                    }
                }
            }

            // Check content description for player-specific patterns (not tab labels)
            val desc = n.contentDescription?.toString()
            if (!desc.isNullOrBlank() && desc.length > 20) { // Avoid short tab labels
                if (desc.contains("Shorts", true) &&
                    (desc.contains("video", true) || desc.contains("playing", true) || desc.contains("paused", true))) {
                    Timber.d("Shorts video description found: $desc")
                    foundShortsIndicators++
                }
            }

            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }

        val hasPattern = foundShortsIndicators > 0
        Timber.v("Scanned $nodesScanned nodes, found $foundShortsIndicators Shorts indicators")
        return hasPattern
    }

    private fun handleShortsDetected(root: AccessibilityNodeInfo) {
        Timber.i("Handling Shorts detection - performing BACK action")
        // Respect user's preference: autoClose / overlay / notify.
        // Example deterministic action: back (safe & generic)
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
