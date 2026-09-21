# ReShift Changelog

All notable changes, design system unification, IL2CPP Inspector features, Frida binary upgrades, launcher fixes, and Autonomous AI Agent capabilities are documented in this file.

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
