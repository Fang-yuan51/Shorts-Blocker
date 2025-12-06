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

package dev.atick.shorts.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.atick.shorts.models.TrackedPackage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * Manages user preferences for tracked packages using DataStore.
 *
 * Provides methods to store and retrieve which app packages are enabled
 * for short-form content blocking.
 *
 * @property context Application context for DataStore access
 */
class UserPreferencesProvider(private val context: Context) {

    private val userPreferencesKey = stringPreferencesKey("dev.atick.shorts.preferences")

    /**
     * Gets the list of currently tracked (enabled) package names.
     *
     * @return Flow emitting list of package names that are enabled for blocking
     */
    fun getTrackedPackages(): Flow<List<String>> {
        return context.dataStore.data.map { preferences ->
            val packages = preferences[userPreferencesKey]?.split(",")?.filter { it.isNotBlank() }
                ?: PackageConstants.DEFAULT_ENABLED_PACKAGES
            Timber.d("Getting tracked packages: $packages")
            packages
        }
    }

    /**
     * Updates the list of tracked packages.
     *
     * @param packages List of package names to enable for blocking
     */
    suspend fun setTrackedPackages(packages: List<String>) {
        val packagesString = packages.joinToString(",")
        Timber.d("Setting tracked packages: $packagesString")
        context.dataStore.edit { preferences ->
            preferences[userPreferencesKey] = packagesString
        }
    }

    /**
     * Gets all available packages with their current enabled status.
     *
     * @return Flow emitting list of [TrackedPackage] with updated enabled state
     */
    fun getTrackedPackagesWithStatus(): Flow<List<TrackedPackage>> {
        return getTrackedPackages().map { enabledPackages ->
            PackageConstants.AVAILABLE_PACKAGES.map { pkg ->
                pkg.copy(isEnabled = enabledPackages.contains(pkg.packageName))
            }
        }
    }

    /**
     * Toggles blocking for a specific package.
     *
     * @param packageName Package identifier to toggle
     * @param enabled true to enable blocking, false to disable
     */
    suspend fun togglePackage(packageName: String, enabled: Boolean) {
        Timber.d("Toggling package: $packageName, enabled: $enabled")
        val currentPackages = getTrackedPackages().first().toMutableList()

        if (enabled && !currentPackages.contains(packageName)) {
            currentPackages.add(packageName)
        } else if (!enabled) {
            currentPackages.remove(packageName)
        }

        setTrackedPackages(currentPackages)
    }

    /**
     * Initializes preferences with default packages if none are set.
     */
    suspend fun initializeDefaultPackages() {
        val currentPackages = getTrackedPackages().first()
        if (currentPackages.isEmpty()) {
            Timber.i("Initializing default packages")
            setTrackedPackages(PackageConstants.DEFAULT_ENABLED_PACKAGES)
        }
    }
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
