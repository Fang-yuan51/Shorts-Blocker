package dev.atick.shorts.utils

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map


class UserPreferencesProvider(private val context: Context) {

    val userPreferencesKey = stringPreferencesKey("dev.atick.shorts.preferences")

    fun getTrackedPackages(): Flow<List<String>> {
        return context.dataStore.data.map { preferences ->
            preferences[userPreferencesKey]?.split(",") ?: emptyList()
        }
    }

    suspend fun setTrackedPackages(packages: List<String>) {
        val packagesString = packages.joinToString(",")
        context.dataStore.updateData {
            it.toMutablePreferences().also { preferences ->
                preferences[userPreferencesKey] = packagesString
            }
        }
    }
}


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
