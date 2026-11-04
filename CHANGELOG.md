# ReShift Changelog

All notable changes, design system unification, IL2CPP Inspector features, Frida binary upgrades, launcher fixes, and Autonomous AI Agent capabilities are documented in this file.

<<<<<<< HEAD
---

## [1.2.0] - Universal UE4/UE5 Dumper, KernelSU Next & Performance Overhaul

### 🎮 Universal UE4 / UE5 Dumper Integration (`assets/UE4_DUMP.js`)
- **Engine Selection (`DumperEngine`)**: Added dual dumper engine support allowing seamless switching between Unity IL2CPP (`il2cpp_inspector_dumper.js`) and Unreal Engine 4/5 (`assets/UE4_DUMP.js`).
- **In-Memory SDK Extraction**: High-speed traversal of `GUObjectArray`, `GNames` / `FNamePool`, `UClass`, `UScriptStruct`, `UFunction` (with RVA offsets calculation), `UProperty`/`FProperty`, and `UEnum` directly from memory.
- **Engine Version Compatibility**: Dynamic memory structure adaptation handling Unreal Engine 4.18 up to 5.4+ (including UE 4.25+ `FProperty` shift and `FNamePool` offset structures).
- **Dual-Stream SQLite Cache**: Streaming batch RPC protocol (`UE4_RPC_STREAM_BATCH`) syncing dumped objects directly into `il2cpp_dumper.db` SQLite repository for rapid search, filtering, and offline inspection.
- **Frida Toolbox & Inspector UI**: Dedicated "UE4 Dumper" Action Card in Frida Toolbox, dynamic top bar title ("UE4 / UE5 Inspector" vs "IL2CPP Inspector"), Engine badge in metrics banner, and Engine selection chips in Control Center with "NEW" badge.

### 🔍 Pure Kotlin DEX & ELF Inspector Engine (`DexDecompilerEngine.kt`)
- **Pure Kotlin DEX Parser (`DexParser.kt`)**: Zero-dependency parser for Android Dalvik Executable (`.dex`) files & multi-DEX APKs (`classes.dex`, `classes2.dex`, etc.). Extracts Class Descriptors, Superclasses, Method signatures, Access flags, Fields, String/Type/Proto pools, and Cross-References (XREFs).
- **Pure Kotlin ELF Parser (`ElfParser.kt`)**: Native Shared Library (`.so`) parser supporting both 32-bit (ELF32) and 64-bit (ELF64) binaries. Extracts ELF headers, Section headers (`.text`, `.rodata`, `.dynsym`, `.symtab`), dynamic symbols, export/import function offsets, and PLT/GOT tables.
- **Disassembly & Scripting Automation (`Arm64Disassembler.kt` & `HexDumpEngine.kt`)**: Integrated ARM64 opcode disassembly, Hex Dump viewer, and automated generator for Pseudo-C code, radare2 (`r2frida`) CLI commands (`pdf`, `axt`, `axf`, `px`), and Ghidra scripts.
- **Interactive Inspection UI (`MemoryInspectorScreen.kt`)**: Interactive nested package tree hierarchy (`DexPackageTree`), instant search/filter toolbar across DEX classes and ELF symbols, offset hex dumper, and live package decompiler interface.

### 🛡️ Xposed Hardware Spoofer Engine (`DeviceSpooferManager.kt` & `FingerprintSpooferHook.kt`)
- **Multi-Layer Fingerprint Hooking (`FingerprintSpooferHook.kt`)**: Low-level Xposed/LSPosed hooks intercepting `android.os.Build` constants (`BOARD`, `BOOTLOADER`, `BRAND`, `DEVICE`, `DISPLAY`, `FINGERPRINT`, `HARDWARE`, `MANUFACTURER`, `MODEL`, `PRODUCT`, `SERIAL`, etc.), `SystemProperties.get()`, `Settings.Secure.android_id`, and `WifiInfo`/`TelephonyManager` identifiers.
- **Native JNI & Shell Command Hooking (`spoofer-native.cpp` & `SpoofedProcess`)**: C/C++ native library (`reshift_spoofer_native`) intercepting `__system_property_get` / `__system_property_read` in NDK native runtime. Hooks `Runtime.exec()` and `ProcessBuilder` via dummy `SpoofedProcess` streams to return spoofed outputs for `getprop` shell executions and `/proc/cpuinfo` / `/proc/version` file reads.
- **Profile Management & GUI Control (`DeviceSpooferScreen.kt`)**: Full GUI dashboard with pre-configured device presets (Pixel 8 Pro, Galaxy S24 Ultra, ROG Phone 8, Xiaomi 14 Pro, Custom/Randomized), per-package targeting, persistent JSON configuration (`spoofer_config.json`), and live LSPosed / KernelSU profile synchronization.

### 🚀 Frida Launch & Timeout Fixes
- **Parallel Co-Launcher Helper**: Fires a parallel launcher intent (`monkey`) 600ms after Frida `-f` spawn starts to wake up Android ActivityManager and eliminate spawn timeouts.
- **Auto-Attach Fallback**: Detects spawn timeouts in stderr, verifies target process PID, and automatically connects via `attachToRunningProcess`.

### 🛑 Inspector Control & Reset
- **Dynamic Stop Button**: "Launch & Inspect" transforms into a prominent red "Stop Inspection" button when dumping is active.
- **Clear All Button**: Added one-tap reset button to stop inspection, clear SQLite database cache for the target package, and reset search filters and status messages.

### 🎨 Layout & UI Bugfixes
- **2-Row Action Controls**: Redesigned Action Controls to prevent button squeezing and horizontal text wrapping:
  - Row 1 (Full Width): Primary button (`Launch & Inspect` / `Stop Inspection`).
  - Row 2 (50%/50% Split): Secondary buttons (`Cached Dump` & `Clear All`).

### 📱 Root Module Status & Badges (Home Screen & Settings)
- **KernelSU Next Detection**: Added specific detection and display labels for KernelSU Next (`KernelSU Next Module`), Magisk, KernelSU, and APatch.
- **Status Badges**: Added `(latest)` badge for up-to-date modules and `(need update)` badge for pending updates, rendered directly under the version number in the System Environment card.

### ⚡ Performance Optimization (100x Faster Settings)
- **Batched Root Shell Commands**: Consolidated status and module queries into single-line batched root shell executions.
- **Cached Root Detection**: Cached `getRootType()` and optimized detection into a single-line shell check.
- **Asynchronous IO**: Moved all system & module status loading to `Dispatchers.IO`, making the Settings screen open instantly with zero UI freezes or ANRs.

### ❌ App Force-Close ("Close") in 3-Dots Menus
- **Root Force Close**: Added "Close" option in 3-dots top bar dropdown menus across all main screens (Home, Apps, Inspector, Modules, Logs) for immediate root force-stop (`am force-stop`, `pkill -9`, `kill -9`) of active target applications.

### 💫 System Boot Sequence (Splash Animation)
- **Interactive Cold-Start Boot Animation (`ReShiftBootAnimation.kt`)**: Displays a high-tech cyberpunk splash animation with glowing rotating orbit rings, pulsing ReShift brand logo badge, and animated progress bar on cold start.
- **Live Boot Checks**: Sequentially checks Root Access, Repositories, Root Module installation status, and Frida Core Engine, smoothly fading into the main dashboard upon completion.

### 🔧 Code Refactoring & Memory Leak Fixes
- **C++ Native Memory & KV Cache Lifecycle (`OnDeviceLlmEngine.kt` & `llama-bridge.cpp`)**:
  - Implemented `AutoCloseable` interface and JVM `finalize()` fallback guard on `OnDeviceLlmEngine` to guarantee `nativeFreeModel()` execution and prevent native RAM leaks upon ViewModel teardown or garbage collection.
  - Added explicit `llama_kv_cache_clear()` calls prior to token generation in `llama-bridge.cpp` to prevent KV cache context pollution across consecutive inference queries.
- **Coroutines & Non-Blocking I/O (`FridaManager.kt`)**:
  - Replaced all synchronous blocking `Thread.sleep()` calls (1000ms, 2000ms, 600ms, 1500ms) across process spawn and attach routines with non-blocking coroutine `delay()` calls.
  - Converted background watcher threads to structured coroutine scopes (`scope.launch(Dispatchers.IO)`).
- **Dead Code & Unused Parameter Cleanup**:
  - Processed `candidateScript` in `Il2CppAiAssistant.getSeniorTemplateScript()` to embed user/candidate base scripts directly into template headers.
  - Wired `scripts`, `pkg`, and `mode` parameters in `FridaManager.executeMultipleScripts()` to delegate directly to `MultiScriptLoader`.
  - Exposed `getClassWithAncestors()` in `Il2CppRepository` and `Il2CppViewModel` (`getClassHierarchy()`) for class inheritance inspection.
- **SQLite JSON Parsing & Query Optimization (`Il2CppDatabaseHelper.kt`)**:
  - Implemented `parseJsonList()` helper with fast checks for `null`, empty, and `"[]"` strings to bypass expensive Gson reflection calls on empty method/field arrays.
  - Consolidated duplicate cursor row mapping into `extractClassFromCursor()`, removing 100+ lines of redundant database code and accelerating SQLite metadata queries.

---

## [1.1.1] - Maintenance & Engine Upgrades

### 📦 Frida Engine & Binaries Upgrade
- **Frida v17.18.0 Upgrade**:
  - Updated bundled Magisk/KernelSU/APatch module binaries (`frida-server`, `frida-inject`, `frida-gadget.so`) from 17.16.4 to **17.18.0**.
  - Synchronized embedded app assets (`app/src/main/assets/ReShift.zip`) to bundle Frida 17.18.0 natively for direct in-app module installations.
  - Updated `module.prop` and version metadata.

### 📱 Launcher & Manifest Fixes
- **Duplicate App Icon Fix**:
  - Resolved double launcher icon issue when running `./gradlew installRelease`.
  - Removed duplicate `LAUNCHER` intent filter from `MainActivity` in `AndroidManifest.xml`.
  - Unified app launcher management exclusively through `.LauncherDefault` and `.LauncherMasked` activity aliases for stealth icon masking.

### 🤖 LLM Model Restoration Enhancements
- **Enhanced GGUF Restoration Path Search**:
  - Upgraded `restoreGgufModelsFromDownloads` in `SettingsViewModel.kt` to dynamically resolve `/Download` and `/Downloads` directories using official `Environment` APIs.
  - Resolved Android Lint warning regarding hardcoded `/sdcard/` paths.

---

=======
>>>>>>> 54ed380d48805d46ef1601f694a2866aa4fd9089
## [1.1.0] - Design System & Feature Overhaul

### 🎨 Unified Design System & UI/UX Redesign
- **Design System Tokens (`ui/theme/` & `ui/components/CommonUI.kt`)**:
  - **Standardized Shape Tokens**: Established `ReShiftCardShape` (`16.dp`), `ReShiftDialogShape` (`20.dp`), `ReShiftButtonShape` (`12.dp`), `ReShiftChipShape` (`8.dp`), and `ReShiftBadgeShape` (`6.dp`).
  - **Semantic Color Tokens**: Added unified status colors in `Color.kt` (`SuccessGreen`, `ErrorRed`, `WarningOrange`, `InfoBlue`, `PurpleAccent`, `CyanAccent`).
  - **Brand TopAppBar (`ReShiftTopAppBar`)**: Standardized top app bar with `"RE"` + `"SHIFT"` brand mark and active status dot on main screens, and clean back navigation on detail screens.
  - **Reusable Components**:
    - `ReShiftCard`: Unified card wrapper with consistent padding, shape, and border tokens across all screens.
    - `SectionHeader`: Bold uppercased section headers with uniform typography and letter spacing (`1.2.sp`).
    - `StatusBadge` & `StatusRow`: Standardized key-value indicators and status pills.
    - `BadgePill`: High-tech monospace code and type badges.
- **Complete Screen Harmonization**:
  - Refactored all 17 screens and sub-screens (`StatusScreen`, `AppsScreen`, `ModulesScreen`, `LogsScreen`, `SettingsScreen`, `FridaToolboxScreen`, `Il2CppScreen`, `MemoryInspectorScreen`, `ZygiskSettingsScreen`, `StalkerToolboxScreen`, `AdvancedFridaScreen`, `AppDetailsScreen`, `ModuleDetailsScreen`, `ScriptEditorScreen`, `RepoBrowserScreen`, `CodeShareBrowserScreen`, `AssetBrowserScreen`).
  - Standardized all application dialogs (`AppUpdateDialog`, `CommunityDialog`, `AppPickerDialog`, `MemoryDumpDialog`, `MemoryWriteDialog`, etc.).

---

### 🎮 IL2CPP Inspector (Unity Game Reverse Engineering)
- **Metadata Dumping & SQLite Caching**:
  - Native dump extraction from `libil2cpp.so` and `global-metadata.dat`.
  - Automatic persistent SQLite caching in `il2cpp_dumper.db` for instant reloads and offline analysis.
- **Dashboard Overview Metrics Banner**:
  - Real-time display of total parsed C# classes, active target application, and database engine status.
- **Unified Control Center**:
  - Target application picker with app icon preview and package details.
  - Configurable Frida injection delay slider (0 to 30 seconds) with quick preset chips (`0s`, `3s`, `5s`, `7s`, `10s`, `15s`, `20s`).
  - Actions for *Launch & Inspect* and *Cached Dump* loading.
- **Search & Filter Toolbar**:
  - Real-time text search across class names, namespaces, methods, fields, and RVA offsets.
  - Type filter chips: `All`, `Classes`, `Structs`, `Interfaces`, `Enums`.
  - `Prune DB`: One-click database cleaning to eliminate noise and compiler-generated internal entries.
- **Interactive Class Cards & Multi-Tab Details**:
  - Color-accented class headers (Green = Class, Blue = Interface, Purple = Struct, Orange = Enum).
  - Quick actions per class: Copy C# pseudocode stub, Hook All Methods, and expand/collapse details.
  - **Tab 1: Methods**:
    - Displays method name, return type badge, parameters, RVA offset, and pointer address.
    - Inner filter options (`All`, `With Offset`, `Getters/Setters`).
    - Quick actions: `HOOK` code snippet generator, copy offset to clipboard, and AI Assistant trigger.
  - **Tab 2: Fields**:
    - Displays field type, field name, and memory offset badge.
  - **Tab 3: Properties**:
    - Displays declared C# properties with `{ get; set; }` badges.
  - **Tab 4: C# Code View**:
    - Renders auto-generated C# pseudocode stubs in a high-tech dual-scrolling code preview with line numbers and one-click copy.
- **Export Capabilities**:
  - Export full metadata dumps as structured JSON or C# Header files (`.cs`).

---

### 🤖 ReShift Autonomous AI Agent & LLM Engine
- **Multi-Provider LLM Support**:
  - **Gemini Cloud**: Integrates Gemini 3.6 Flash and 3.5 Flash Lite via Google Gemini API.
  - **Ollama Server**: Connects to local/network Ollama instances (`http://127.0.0.1:11434` or PC IP).
  - **On-Device GGUF (Local Inference)**: Executes GGUF models directly on the phone via JNI `libreshift_llama.so` C++ backend.
  - Preset models: Qwen 2.5 Coder 1.5B Instruct, Qwen 2.5 Coder 3B Instruct (Uncensored), and Qwen 2.5 Coder 7B Instruct (Uncensored).
- **GGUF Backup & Restore**:
  - Flexible, case-insensitive, recursive search across `/Download`, `/Downloads`, and subfolders (e.g., `RESHIFT_LLM_MODELS`, `ReShift_LLM_Models`).
  - Root Shell (`su`) `find` and `cp` fallbacks for 100% reliable model restoration under Scoped Storage restrictions on Android 10+.
- **Autonomous Closed-Loop Reverse Engineering Agent**:
  - **Full-Screen Agent Interface**: Complete orchestration modal with live execution tracking.
  - **Telemetry & Hardware Dashboard**: Displays free RAM, total RAM, inference latency (ms), and toggles between CPU NEON and Vulkan GPU acceleration.
  - **Goal Presets & Custom Instructions**: One-tap goal setup (`Anti-Cheat Bypass`, `God Mode & HP`, `Unlimited Money`) or custom reverse engineering instructions.
  - **Live Generator Stream**: Dual-scrolling code view showing live token generation in real time.
  - **Visual Process-Timeline Stepper**: Highlights current loop stage (`Build Prompt` ➔ `Inference` ➔ `Pre-Audit` ➔ `Inject` ➔ `Dual-Stream Monitor` ➔ `Success/Repair`).
  - **Multi-Tab Inspection Suite**:
    - *Terminal Log*: Color-coded step-by-step agent activity log.
    - *Frida Console*: Live runtime output and console logs from Frida script execution.
    - *sql.db AST Context*: Inspection of SQLite metadata queried by the agent.
  - **Interactive User Evaluation Loop**:
    - Modal decision pop-up asking if the reverse engineering goal was achieved in-game.
    - Prominent YES/NO actions with optional prompt refinement input for iterative self-correction.
  - **Agent Configuration**:
    - Execution mode selection (`Auto`, `Spawn (-f)`, `Attach (-n)`).
    - Adjustable injection delay slider (0 to 60 seconds).
    - Optional Cloud Static Analysis Validation.
