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

package dev.atick.shorts.services.detectors

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * Detector implementation for Instagram Reels.
 *
 * Identifies when users are actively watching Instagram Reels by checking:
 * - Whether the Reels tab is selected and active
 * - Presence of Reels-specific fragment containers
 * - Active video playback indicators
 *
 * Distinguishes between Reels in the dedicated tab versus Stories or feed videos.
 */
class InstagramReelsDetector : ShortFormContentDetector {

    override fun getPackageName(): String = "com.instagram.android"

    override fun isShortFormContent(
        event: AccessibilityEvent,
        rootNode: AccessibilityNodeInfo,
        resources: Resources,
    ): Boolean {
        // Instagram uses clips_viewer for everything, so we need to be very specific
        // Check if we're in the Reels tab/activity specifically

        val className = event.className?.toString()
        Timber.v("[Instagram] Event className: $className")

        // Check if this is specifically a Reels fragment/activity
        val isReelsActivity = isInReelsActivity(rootNode, event)

        if (!isReelsActivity) {
            Timber.v("[Instagram] Not in Reels activity/tab - allowing")
            return false
        }

        // If we're in the Reels section, check for actual video playback
        val hasActiveVideo = hasActiveVideoPlayback(rootNode)

        if (hasActiveVideo) {
            Timber.i("[Instagram] ✓ User is actively watching Reels in Reels tab")
            return true
        } else {
            Timber.v("[Instagram] ✗ In Reels tab but no active video playback")
            return false
        }
    }

    /**
     * Checks if the user is currently in the Reels activity or tab.
     *
     * Uses multiple detection methods:
     * 1. Selected Reels tab in bottom navigation
     * 2. Reels fragment container presence
     * 3. Activity/fragment class name analysis
     *
     * @param node Root accessibility node
     * @param event Current accessibility event
     * @return true if user is in Reels section
     */
    private fun isInReelsActivity(node: AccessibilityNodeInfo, event: AccessibilityEvent): Boolean {
        // Method 1: Check for SELECTED Reels tab (not just present in bottom nav)
        val hasSelectedReelsTab = isReelsTabSelected(node)
        if (hasSelectedReelsTab) {
            Timber.d("[Instagram] Reels tab is SELECTED/ACTIVE")
        }

        // Method 2: Check for clips_fragment_container (indicates we're IN the Reels viewer)
        val hasReelsFragmentContainer = hasViewIdPattern(
            node,
            listOf(
                "clips_fragment_container",
                "clips_tab_container",
                "clips_viewer_fragment_container",
            ),
        )
        if (hasReelsFragmentContainer) {
            Timber.d("[Instagram] Found Reels fragment container - in Reels viewer")
        }

        // Method 3: Check activity/fragment class name
        val className = event.className?.toString() ?: ""
        val isReelsClass = className.contains("Clips", ignoreCase = true) ||
            className.contains("Reel", ignoreCase = true)
        if (isReelsClass) {
            Timber.d("[Instagram] Event className indicates Reels: $className")
        }

        val inReelsSection = hasSelectedReelsTab || hasReelsFragmentContainer || isReelsClass
        Timber.v("[Instagram] isInReelsActivity = $inReelsSection (selectedTab=$hasSelectedReelsTab, container=$hasReelsFragmentContainer, class=$isReelsClass)")

        return inReelsSection
    }

    /**
     * Checks if the Reels tab in bottom navigation is selected.
     *
     * @param node Root accessibility node
     * @return true if Reels tab is actively selected
     */
    private fun isReelsTabSelected(node: AccessibilityNodeInfo): Boolean {
        // Look for a Reels tab/button that is SELECTED or ACTIVATED
        // Must be an actual clickable tab, not just text containing "Reels"
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        var nodesScanned = 0

        while (stack.isNotEmpty() && nodesScanned < 150) {
            val n = stack.removeFirst()
            nodesScanned++

            val id = n.viewIdResourceName

            // ONLY check nodes that are explicitly tab IDs (not any text/description)
            val isTabNode = id != null && (
                id.contains("clips_tab", ignoreCase = true) ||
                    id.contains("reels_tab", ignoreCase = true) ||
                    id.contains("tab_button", ignoreCase = true) ||
                    id.contains("navigation_bar_item", ignoreCase = true)
                )

            if (isTabNode) {
                val text = n.text?.toString()
                val desc = n.contentDescription?.toString()

                // Verify it's the Reels tab (by text/description)
                val hasReelsLabel = (text != null && text.equals("Reels", ignoreCase = true)) ||
                    (desc != null && desc.contains("Reels", ignoreCase = true))

                if (hasReelsLabel) {
                    // Check if it's selected/activated
                    if (n.isSelected) {
                        Timber.d("[Instagram] Found SELECTED Reels TAB: selected=${n.isSelected}, id=$id, desc=$desc")
                        return true
                    } else {
                        Timber.v("[Instagram] Found Reels tab but NOT selected: id=$id")
                    }
                }
            }

            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }

        Timber.v("[Instagram] No selected Reels tab found")
        return false
    }

    /**
     * Checks if there is active video playback in the Reels viewer.
     *
     * Looks for video player components, texture views, and surface views
     * that indicate a Reel is currently being displayed.
     *
     * @param node Root accessibility node
     * @return true if video playback indicators are found (requires at least 2 indicators)
     */
    private fun hasActiveVideoPlayback(node: AccessibilityNodeInfo): Boolean {
        // Look for ANY significant indicator that we're in the Reels viewer
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        var foundReelsIndicators = 0
        var nodesScanned = 0

        // Patterns that indicate we're IN the Reels viewer (not just feed)
        val reelsViewerPatterns = listOf(
            "clips_video_player",
            "clips_viewer_video",
            "video_player_container",
            "reel_video_player",
            "clips_media_view",
            "video_view",
            "texture_view", // Video rendering surface
            "surface_view", // Video rendering surface
        )

        while (stack.isNotEmpty() && nodesScanned < 100) {
            val n = stack.removeFirst()
            nodesScanned++

            val id = n.viewIdResourceName
            if (id != null) {
                // Check for Reels-specific video components
                for (pattern in reelsViewerPatterns) {
                    if (id.contains(pattern, ignoreCase = true)) {
                        Timber.d("[Instagram] Reels video component found: $id")
                        foundReelsIndicators++
                    }
                }

                // Also check for clips pager being active
                if (id.contains("clips_viewer_pager", ignoreCase = true) ||
                    id.contains("clips_viewer_view_pager", ignoreCase = true)
                ) {
                    // The pager exists, but we need to check if it's actually showing content
                    val rect = Rect()
                    n.getBoundsInScreen(rect)
                    if (rect.height() > 800) {
                        Timber.d("[Instagram] Large clips viewer pager found (likely playing): $id, height=${rect.height()}")
                        foundReelsIndicators += 2 // Strong indicator
                    }
                }
            }

            // Check class names for video views
            val className = n.className?.toString() ?: ""
            if (className.contains("VideoView", ignoreCase = true) ||
                className.contains("TextureView", ignoreCase = true) ||
                className.contains("SurfaceView", ignoreCase = true)
            ) {
                val rect = Rect()
                n.getBoundsInScreen(rect)
                if (rect.height() > 500 && rect.width() > 300) {
                    Timber.d("[Instagram] Video view found: class=$className, size=${rect.width()}x${rect.height()}")
                    foundReelsIndicators++
                }
            }

            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }

        val hasVideo = foundReelsIndicators >= 2 // Need at least 2 indicators
        Timber.v("[Instagram] hasActiveVideoPlayback = $hasVideo (found $foundReelsIndicators indicators)")
        return hasVideo
    }

    /**
     * Checks if any node in the tree has a view ID matching the given patterns.
     *
     * @param node Root accessibility node
     * @param patterns List of string patterns to match against view IDs
     * @return true if any pattern is found
     */
    private fun hasViewIdPattern(node: AccessibilityNodeInfo, patterns: List<String>): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        var nodesScanned = 0

        while (stack.isNotEmpty() && nodesScanned < 100) {
            val n = stack.removeFirst()
            nodesScanned++

            val id = n.viewIdResourceName
            if (id != null) {
                for (pattern in patterns) {
                    if (id.contains(pattern, ignoreCase = true)) {
                        Timber.d("[Instagram] Found view ID pattern: $id")
                        return true
                    }
                }
            }

            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.add(it) }
            }
        }

        return false
    }
}
