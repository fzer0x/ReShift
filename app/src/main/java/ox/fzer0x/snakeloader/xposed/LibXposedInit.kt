package ox.fzer0x.snakeloader.xposed

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Libxposed API 102 entry point for ReShift Hardware Spoofer.
 * Compatible with LSPosed v2.0+, libxposed framework environments, and ART runtime hooks.
 */
class LibXposedInit : XposedModule() {

    override fun onPackageLoaded(param: PackageLoadedParam) {
        val packageName = param.packageName

        // Ignore system packages and self
        if (packageName == "com.android.systemui" || packageName == "ox.fzer0x.snakeloader") {
            return
        }

        try {
            val profile = XposedInit.loadProfileForPackage(packageName, this) ?: return

            // Apply low-level ART hooks for hardware fingerprinting using the target app's default ClassLoader
            val classLoader = param.defaultClassLoader
            FingerprintSpooferHook.applyHooksWithClassLoader(classLoader, profile)

            // Dispatch Vector automatic activation event broadcast
            XposedInit.sendActivationBroadcast(packageName, profile)
        } catch (_: Throwable) { }
    }
}
