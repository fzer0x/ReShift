# 🐍 ReShift

**ReShift** is a high-performance Android instrumentation and security research toolkit. It integrates the power of **Frida** and **Zygisk** into a unified, mobile-first interface designed for advanced dynamic analysis, reversing, and stealthy app research.

<p align="center">
  <img src="screenshot/screenshot-1.jpg" width="350" alt="ReShift Screenshot">
</p>

---

## 🚀 Key Features

### 🛠️ Dual-Engine Injection
ReShift offers two distinct injection methods to suit different research needs:
- **Zygisk Loader:** A custom C++ module that authoritatively injects `frida-gadget` at process spawn. Perfect for early-stage hooks and bypassing detection that triggers post-startup.
- **Frida-Server / CLI:** Full support for standard Frida instrumentation. Manage `frida-server`, `frida-inject`, and `frida-cli` binaries with one tap.

### 🥷 Advanced Stealth & Anti-Detection
Engineered to remain undetected by most modern security solutions:
- **Dynamic Binary Renaming:** Automatically renames Frida binaries (e.g., `frida-server` → `x7y2z9`) to evade simple process name checks.
- **Stealth Pathing:** Operates from hidden, root-only directories like `/data/adb/reshift`.
- **Custom Ports:** Randomized communication ports to avoid detection of standard Frida ports (27042).
- **Security Control:** Integrated management for SELinux (Enforcing/Permissive) and `ptrace_scope` configuration.

### 🔍 Research & Debugging Arsenal
- **Memory Inspector:** Browse, dump, and modify process memory in real-time via a dedicated RPC bridge.
- **Stalker Toolbox:** Leverage Frida's Stalker engine for instruction-level tracing and execution flow analysis.
- **Il2Cpp Helpers:** Built-in utilities for analyzing and hooking Unity games using the Il2Cpp scripting backend.
- **Live Overlays:** Monitor Logcat and Frida script output directly over the target application.

### 📦 Ecosystem Integration
- **GitHub & CodeShare Browser:** Instant access to thousands of community scripts from Frida CodeShare and curated GitHub repositories.
- **Multi-Script Loader:** Merge multiple scripts into a single deployment with priority-based execution and a shared registry.
- **Built-in Editor:** Syntax-highlighted script editor for quick on-device adjustments.

---

## 🏗️ Project Architecture

The project is divided into three main components:

| Component | Description |
| :--- | :--- |
| **[:app](file:///C:/Android_Projects/Snakeloader/app)** | A modern Android application built with Jetpack Compose, handling UI, script management, and RPC communication. |
| **[Zygisk Module](file:///C:/Android_Projects/Snakeloader/ZygiskModuleScr)** | Native C++ module for Magisk/Zygisk that facilitates early-stage injection. |
| **[Magisk Module](file:///C:/Android_Projects/Snakeloader/MagiskModule)** | The distribution package containing Frida binaries, startup scripts (`service.sh`), and configuration templates. |

---

## 🛠️ Technical Details

### RPC Bridge API
ReShift injects a global utility registry into scripts, enabling powerful RPC methods:
- `listRegisteredHooks()`: Enumerates all active hooks in the current process.
- `getMemoryDump(address, size)`: Retrieves raw memory from a specific address.
- `listModules()`: Lists all loaded shared libraries and their base addresses.
- `toggleHook(hookName)`: Dynamically enables or disables specific hooks at runtime.

### Stealth Configuration
Managed via `StealthConfigManager`, the toolkit generates a `config.sh` used by the Magisk boot scripts to ensure environment consistency across reboots and injections.

---

## 🚦 Getting Started

1. **Install Magisk/KSU/KSU Next:** Ensure your device is rooted.
2. **Flash the Module:** Install the ReShift Magisk/KSU module via the app or Magisk Manager.
3. **Configure Zygisk:** Enable Zygisk in Magisk settings and select your target apps in the ReShift UI.
4. **Inject & Analyze:** Choose a script from the repository or write your own, start app.

---

## 📜 License
This project is for educational and research purposes only. Refer to the [LICENSE](file:///C:/Android_Projects/Snakeloader/LICENSE) for more details.

> [!CAUTION]
> Reverse engineering apps may violate their Terms of Service. Use this tool responsibly.
