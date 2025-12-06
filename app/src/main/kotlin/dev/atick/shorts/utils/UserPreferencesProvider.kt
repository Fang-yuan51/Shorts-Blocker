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


class UserPreferencesProvider(private val context: Context) {

    private val userPreferencesKey = stringPreferencesKey("dev.atick.shorts.preferences")

    fun getTrackedPackages(): Flow<List<String>> {
        return context.dataStore.data.map { preferences ->
            val packages = preferences[userPreferencesKey]?.split(",")?.filter { it.isNotBlank() }
                ?: PackageConstants.DEFAULT_ENABLED_PACKAGES
            Timber.d("Getting tracked packages: $packages")
            packages
        }
    }

    suspend fun setTrackedPackages(packages: List<String>) {
        val packagesString = packages.joinToString(",")
        Timber.d("Setting tracked packages: $packagesString")
        context.dataStore.edit { preferences ->
            preferences[userPreferencesKey] = packagesString
        }
    }

    fun getTrackedPackagesWithStatus(): Flow<List<TrackedPackage>> {
        return getTrackedPackages().map { enabledPackages ->
            PackageConstants.AVAILABLE_PACKAGES.map { pkg ->
                pkg.copy(isEnabled = enabledPackages.contains(pkg.packageName))
            }
        }
    }

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

    suspend fun initializeDefaultPackages() {
        val currentPackages = getTrackedPackages().first()
        if (currentPackages.isEmpty()) {
            Timber.i("Initializing default packages")
            setTrackedPackages(PackageConstants.DEFAULT_ENABLED_PACKAGES)
        }
    }
}


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
