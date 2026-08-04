package ox.fzer0x.snakeloader.ui.viewmodels

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ox.fzer0x.snakeloader.AppInfo
import ox.fzer0x.snakeloader.ScriptManager
import ox.fzer0x.snakeloader.ui.screens.loadInstalledApps

class AppsViewModel(
    private val scriptManager: ScriptManager
) : BaseViewModel() {

    private var allInstalledApps = emptyList<AppInfo>()
    
    var filteredApps by mutableStateOf<List<AppInfo>>(emptyList())
        private set

    var searchQuery by mutableStateOf("")
        private set

    fun onSearchQueryChange(query: String) {
        searchQuery = query
        filterApps()
    }

    var selectedFilter by mutableStateOf("User")
        private set

    fun onFilterChange(filter: String) {
        selectedFilter = filter
        filterApps()
    }

    fun loadApps(context: Context, forceReload: Boolean = false) {
        if (allInstalledApps.isNotEmpty() && !forceReload) return
        
        launchWithLoading {
            allInstalledApps = loadInstalledApps(context, includeSystemApps = true)
            filterApps()
        }
    }

    fun toggleSystemApps(context: Context) {
    }

    private fun filterApps() {
        val activePackages = scriptManager.getAppsWithActiveScripts().toSet()
        
        val baseList = allInstalledApps.filter { app ->
            val matchesSearch = if (searchQuery.isEmpty()) true else {
                app.label.contains(searchQuery, ignoreCase = true) || 
                app.packageName.contains(searchQuery, ignoreCase = true)
            }

            val matchesFilter = when (selectedFilter) {
                "User" -> !app.isSystemApp
                "System" -> app.isSystemApp
                "Active" -> app.packageName in activePackages
                else -> true
            }

            matchesSearch && matchesFilter
        }

        filteredApps = baseList.sortedWith(
            compareByDescending<AppInfo> { it.packageName in activePackages }
                .thenBy { it.label.lowercase() }
        )
    }
}
