package ox.fzer0x.snakeloader.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ox.fzer0x.snakeloader.AppInfo
import ox.fzer0x.snakeloader.spoofer.DeviceSpooferManager
import ox.fzer0x.snakeloader.FridaManager
import ox.fzer0x.snakeloader.decompiler.*
import ox.fzer0x.snakeloader.ui.screens.loadInstalledApps

enum class DecompilerMode(val label: String, val badge: String) {
    DEX_CLASSES("DEX Classes & XREFs", "JAVA/SMALI"),
    NATIVE_ELF("Native Shared Libraries (.so)", "R2/ELF")
}

enum class SymbolFilter(val label: String) {
    ALL("All Symbols"),
    EXPORTS_ONLY("Exports Only"),
    IMPORTS_ONLY("Imports Only"),
    JNI_ONLY("JNI Methods Only")
}

class DexDecompilerViewModel(
    private val decompilerEngine: DexDecompilerEngine,
    private val spooferManager: DeviceSpooferManager? = null
) : ViewModel() {

    companion object {
        private const val TAG = "DexDecompilerViewModel"
    }

    private val _installedApps = MutableStateFlow<List<AppInfo>>(emptyList())
    val installedApps: StateFlow<List<AppInfo>> = _installedApps.asStateFlow()

    private val _selectedApp = MutableStateFlow<AppInfo?>(null)
    val selectedApp: StateFlow<AppInfo?> = _selectedApp.asStateFlow()

    private val _decompilerMode = MutableStateFlow(DecompilerMode.DEX_CLASSES)
    val decompilerMode: StateFlow<DecompilerMode> = _decompilerMode.asStateFlow()

    private val _symbolFilter = MutableStateFlow(SymbolFilter.ALL)
    val symbolFilter: StateFlow<SymbolFilter> = _symbolFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to decompile installed DEX and ELF binaries")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    // DEX Classes Data
    private val _dexClasses = MutableStateFlow<List<DexClassData>>(emptyList())
    val dexClasses: StateFlow<List<DexClassData>> = _dexClasses.asStateFlow()

    private val _packageTree = MutableStateFlow<DexPackageNode?>(null)
    val packageTree: StateFlow<DexPackageNode?> = _packageTree.asStateFlow()

    private val _expandedPackages = MutableStateFlow<Set<String>>(emptySet())
    val expandedPackages: StateFlow<Set<String>> = _expandedPackages.asStateFlow()

    private val _selectedDexClass = MutableStateFlow<DexClassData?>(null)
    val selectedDexClass: StateFlow<DexClassData?> = _selectedDexClass.asStateFlow()

    private val _selectedDexMethod = MutableStateFlow<DexMethodData?>(null)
    val selectedDexMethod: StateFlow<DexMethodData?> = _selectedDexMethod.asStateFlow()

    fun togglePackageExpansion(packagePath: String) {
        val current = _expandedPackages.value.toMutableSet()
        if (current.contains(packagePath)) {
            current.remove(packagePath)
        } else {
            current.add(packagePath)
        }
        _expandedPackages.value = current
    }

    // ELF Native Libraries Data
    private val _elfLibraries = MutableStateFlow<List<ElfModuleData>>(emptyList())
    val elfLibraries: StateFlow<List<ElfModuleData>> = _elfLibraries.asStateFlow()

    private val _selectedElfLibrary = MutableStateFlow<ElfModuleData?>(null)
    val selectedElfLibrary: StateFlow<ElfModuleData?> = _selectedElfLibrary.asStateFlow()

    private val _selectedElfSymbol = MutableStateFlow<ElfSymbolData?>(null)
    val selectedElfSymbol: StateFlow<ElfSymbolData?> = _selectedElfSymbol.asStateFlow()

    // Frida Hook Customization Options
    private val _hookOptions = MutableStateFlow(FridaHookOptions())
    val hookOptions: StateFlow<FridaHookOptions> = _hookOptions.asStateFlow()

    // Generated Artifacts
    private val _generatedScript = MutableStateFlow<String?>(null)
    val generatedScript: StateFlow<String?> = _generatedScript.asStateFlow()

    private val _r2Commands = MutableStateFlow<List<String>>(emptyList())
    val r2Commands: StateFlow<List<String>> = _r2Commands.asStateFlow()

    fun loadApps(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = loadInstalledApps(context)
            _installedApps.value = apps
            if (_selectedApp.value == null && apps.isNotEmpty()) {
                val targetApp = apps.find { it.packageName.contains("settings") || it.packageName.contains("target") } ?: apps.first()
                selectApp(context, targetApp)
            }
        }
    }

    fun selectApp(context: Context, app: AppInfo) {
        _selectedApp.value = app
        _selectedDexClass.value = null
        _selectedDexMethod.value = null
        _selectedElfLibrary.value = null
        _selectedElfSymbol.value = null
        _statusMessage.value = "Selected target app: ${app.label} (${app.packageName})"
        spooferManager?.onTargetAppSelected(app.packageName, app.label)
        refreshDecompilerData(context)
    }

    fun setMode(context: Context, mode: DecompilerMode) {
        _decompilerMode.value = mode
        refreshDecompilerData(context)
    }

    fun setSymbolFilter(context: Context, filter: SymbolFilter) {
        _symbolFilter.value = filter
        refreshDecompilerData(context)
    }

    fun setSearchQuery(context: Context, query: String) {
        _searchQuery.value = query
        refreshDecompilerData(context)
    }

    fun updateHookOptions(options: FridaHookOptions) {
        _hookOptions.value = options
        generateSnippetForSelection()
    }

    fun selectDexClass(klass: DexClassData) {
        _selectedDexClass.value = klass
        _selectedDexMethod.value = klass.methods.firstOrNull()
        generateSnippetForSelection()
    }

    fun selectDexMethod(method: DexMethodData) {
        _selectedDexMethod.value = method
        generateSnippetForSelection()
    }

    fun selectElfLibrary(context: Context, lib: ElfModuleData, onReady: (() -> Unit)? = null) {
        _selectedElfLibrary.value = lib
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            val pkg = _selectedApp.value?.packageName ?: return@launch
            val libIdentifier = if (lib.path.isNotBlank()) lib.path else lib.name
            val fullModule = decompilerEngine.inspectElfSymbols(pkg, libIdentifier, _searchQuery.value)
            _selectedElfLibrary.value = fullModule

            val filteredSymbols = applySymbolFilter(fullModule.symbols, _symbolFilter.value)
            _selectedElfSymbol.value = filteredSymbols.firstOrNull()
            _selectedElfSymbol.value?.let { sym ->
                _r2Commands.value = decompilerEngine.generateR2FridaCommands(sym)
            }
            generateSnippetForSelection()
            _isLoading.value = false
            if (onReady != null) {
                withContext(Dispatchers.Main) {
                    onReady()
                }
            }
        }
    }

    fun selectElfSymbol(sym: ElfSymbolData) {
        _selectedElfSymbol.value = sym
        _r2Commands.value = decompilerEngine.generateR2FridaCommands(sym)
        generateSnippetForSelection()
    }

    private fun generateSnippetForSelection() {
        val app = _selectedApp.value ?: return
        val snippet = decompilerEngine.generateFridaHookSnippet(
            packageName = app.packageName,
            dexClass = _selectedDexClass.value,
            dexMethod = _selectedDexMethod.value,
            elfLibrary = _selectedElfLibrary.value,
            elfSymbol = _selectedElfSymbol.value,
            isNativeMode = (_decompilerMode.value == DecompilerMode.NATIVE_ELF),
            options = _hookOptions.value
        )
        _generatedScript.value = snippet
    }

    private fun applySymbolFilter(symbols: List<ElfSymbolData>, filter: SymbolFilter): List<ElfSymbolData> {
        return when (filter) {
            SymbolFilter.EXPORTS_ONLY -> symbols.filter { it.bind == "GLOBAL" && it.section != "UNDEF" }
            SymbolFilter.IMPORTS_ONLY -> symbols.filter { it.section == "UNDEF" || it.bind == "WEAK" }
            SymbolFilter.JNI_ONLY -> symbols.filter { it.name.startsWith("Java_") || it.name == "JNI_OnLoad" }
            SymbolFilter.ALL -> symbols
        }
    }

    fun refreshDecompilerData(context: Context) {
        val app = _selectedApp.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            _statusMessage.value = "Decompiling ${app.label} binaries..."

            try {
                if (_decompilerMode.value == DecompilerMode.DEX_CLASSES) {
                    val classes = decompilerEngine.inspectDexClasses(app.packageName, _searchQuery.value)
                    _dexClasses.value = classes
                    val tree = DexPackageTreeBuilder.buildPackageTree(classes)
                    _packageTree.value = tree
                    _expandedPackages.value = tree.subpackages.map { it.fullPackagePath }.toSet()
                    _selectedDexClass.value = classes.firstOrNull()
                    _selectedDexMethod.value = classes.firstOrNull()?.methods?.firstOrNull()
                    _statusMessage.value = "Decompiled ${classes.size} DEX classes & XREFs"
                } else {
                    val libs = decompilerEngine.getNativeLibraries(app.packageName)
                    _elfLibraries.value = libs
                    if (libs.isNotEmpty()) {
                        val activeLib = _selectedElfLibrary.value ?: libs.first()
                        val fullLib = decompilerEngine.inspectElfSymbols(app.packageName, activeLib.name, _searchQuery.value)
                        _selectedElfLibrary.value = fullLib

                        val filteredSymbols = applySymbolFilter(fullLib.symbols, _symbolFilter.value)
                        _selectedElfSymbol.value = filteredSymbols.firstOrNull()
                        _selectedElfSymbol.value?.let { sym ->
                            _r2Commands.value = decompilerEngine.generateR2FridaCommands(sym)
                        }
                    } else {
                        _selectedElfLibrary.value = null
                        _selectedElfSymbol.value = null
                        _r2Commands.value = emptyList()
                    }
                    _statusMessage.value = "Extracted ${libs.size} native .so libraries & R2Frida symbol offsets"
                }
                generateSnippetForSelection()
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing decompiler data", e)
                _statusMessage.value = "Decompilation warning: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun injectGeneratedScript(fridaManager: FridaManager, onResult: (Boolean) -> Unit) {
        val app = _selectedApp.value ?: return
        val script = _generatedScript.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val success = fridaManager.executeScriptContent(app.packageName, script)
            withContext(Dispatchers.Main) {
                onResult(success)
            }
        }
    }
}
