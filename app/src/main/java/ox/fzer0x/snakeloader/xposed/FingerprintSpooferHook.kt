package ox.fzer0x.snakeloader.xposed

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import ox.fzer0x.snakeloader.spoofer.DeviceProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Custom dummy Process returning spoofed command output for shell execution hooks (getprop, cat /proc/cpuinfo).
 */
class SpoofedProcess(private val outputText: String) : Process() {
    override fun getInputStream(): InputStream = outputText.byteInputStream(Charsets.UTF_8)
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
    override fun waitFor(): Int = 0
    override fun exitValue(): Int = 0
    override fun destroy() {}
}

/**
 * Executes comprehensive low-level ART method, field, NDK native library, and shell execution hooks
 * via XposedHelpers for target process space.
 */
object FingerprintSpooferHook {

    @JvmStatic
    private external fun initNativeSpoofer(
        brand: String,
        manufacturer: String,
        model: String,
        product: String,
        device: String,
        board: String,
        hardware: String,
        fingerprint: String,
        serial: String,
        releaseVersion: String,
        sdkInt: Int,
        securityPatch: String
    )

    private var isNativeLoaded = false

    private fun tryLoadNativeLibrary(profile: DeviceProfile) {
        if (isNativeLoaded) return
        try {
            System.loadLibrary("reshift_spoofer_native")
            initNativeSpoofer(
                profile.brand,
                profile.manufacturer,
                profile.model,
                profile.product,
                profile.device,
                profile.board,
                profile.hardware,
                profile.fingerprint,
                profile.serial,
                profile.releaseVersion,
                profile.sdkInt,
                profile.securityPatch
            )
            isNativeLoaded = true
            XposedBridge.log("[ReShift Vector Spoofer] Native C/C++ libc spoofer initialized successfully")
        } catch (t: Throwable) {
            // Native library loading optional depending on architecture / process context
        }
    }

    fun applyHooks(lpparam: XC_LoadPackage.LoadPackageParam, profile: DeviceProfile) {
        val classLoader = lpparam.classLoader ?: return
        applyHooksWithClassLoader(classLoader, profile)
    }

    fun applyHooksWithClassLoader(classLoader: ClassLoader, profile: DeviceProfile) {
        tryLoadNativeLibrary(profile)
        hookBuildFieldsAndMethods(classLoader, profile)
        hookSystemProperties(classLoader, profile)
        hookSettingsProviders(classLoader, profile)
        hookTelephonyManager(classLoader, profile)
        hookWifiAndNetwork(classLoader, profile)
        hookMediaDrm(classLoader, profile)
        hookAdvertisingAndGsfId(classLoader, profile)
        hookOpenGL(classLoader, profile)
        hookProcessExecution(classLoader, profile)
    }

    private fun hookBuildFieldsAndMethods(classLoader: ClassLoader, profile: DeviceProfile) {
        try {
            val buildClass = XposedHelpers.findClass("android.os.Build", classLoader)
            setStaticFieldSafe(buildClass, "BRAND", profile.brand)
            setStaticFieldSafe(buildClass, "MANUFACTURER", profile.manufacturer)
            setStaticFieldSafe(buildClass, "MODEL", profile.model)
            setStaticFieldSafe(buildClass, "PRODUCT", profile.product)
            setStaticFieldSafe(buildClass, "DEVICE", profile.device)
            setStaticFieldSafe(buildClass, "BOARD", profile.board)
            setStaticFieldSafe(buildClass, "HARDWARE", profile.hardware)
            setStaticFieldSafe(buildClass, "FINGERPRINT", profile.fingerprint)
            setStaticFieldSafe(buildClass, "SERIAL", profile.serial)
            setStaticFieldSafe(buildClass, "BOOTLOADER", profile.board)
            setStaticFieldSafe(buildClass, "RADIO", profile.board)
            setStaticFieldSafe(buildClass, "DISPLAY", profile.fingerprint.split(":").getOrNull(2) ?: "user-release")
            setStaticFieldSafe(buildClass, "ID", profile.fingerprint.split("/").getOrNull(3)?.split(":")?.getOrNull(0) ?: "UKQ1.230804.001")

            val isQcom = profile.hardware.lowercase().contains("qcom") || profile.board.lowercase().contains("kalama") || profile.board.lowercase().contains("shennong")
            setStaticFieldSafe(buildClass, "SOC_MANUFACTURER", if (isQcom) "Qualcomm" else profile.manufacturer)
            setStaticFieldSafe(buildClass, "SOC_MODEL", profile.board)

            val versionClass = XposedHelpers.findClass("android.os.Build\$VERSION", classLoader)
            setStaticFieldSafe(versionClass, "RELEASE", profile.releaseVersion)
            setStaticFieldSafe(versionClass, "SECURITY_PATCH", profile.securityPatch)
            setStaticFieldSafe(versionClass, "INCREMENTAL", profile.fingerprint.split(":").getOrNull(1) ?: "10822088")

            // Hook getSerial method (API 26+)
            hookMethodSafe(buildClass, "getSerial", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = profile.serial
                }
            })

            // Hook getRadioVersion method
            hookMethodSafe(buildClass, "getRadioVersion", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = profile.board
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("[ReShift Vector Spoofer] Error hooking Build fields: " + t.message)
        }
    }

    private fun hookSystemProperties(classLoader: ClassLoader, profile: DeviceProfile) {
        try {
            val sysPropClass = XposedHelpers.findClass("android.os.SystemProperties", classLoader)

            val getHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String ?: return
                    val spoofed = getSpoofedSysPropValue(key, profile)
                    if (spoofed != null) {
                        param.result = spoofed
                    }
                }
            }

            XposedHelpers.findAndHookMethod(sysPropClass, "get", String::class.java, getHook)
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String::class.java, String::class.java, getHook)
        } catch (t: Throwable) {
            XposedBridge.log("[ReShift Vector Spoofer] Error hooking SystemProperties: " + t.message)
        }
    }

    fun getSpoofedSysPropValue(key: String, profile: DeviceProfile): String? {
        if (key.isEmpty()) return null
        val k = key.lowercase().trim()
        val isQcom = profile.hardware.lowercase().contains("qcom") || profile.board.lowercase().contains("kalama") || profile.board.lowercase().contains("shennong")
        val socManufacturer = if (isQcom) "Qualcomm" else profile.manufacturer

        return when {
            // Model & Market Name
            k.endsWith(".model") || k == "ro.product.marketname" || k.contains("product.model") -> profile.model

            // Brand
            k.endsWith(".brand") || k.contains("product.brand") -> profile.brand

            // Manufacturer
            k.endsWith(".manufacturer") || k.contains("product.manufacturer") -> profile.manufacturer

            // Device
            k.endsWith(".device") || k.contains("product.device") -> profile.device

            // Product / Name
            (k.endsWith(".name") && (k.startsWith("ro.product") || k.contains("product."))) || k.endsWith(".product.name") -> profile.product

            // Board & Platform
            k == "ro.product.board" || k == "ro.board.platform" || k == "ro.mediatek.platform" -> profile.board

            // Hardware & Chipset
            k == "ro.hardware" || k == "ro.boot.hardware" || k == "ro.chipname" || k == "ro.hardware.keystore" -> profile.hardware

            // Fingerprint
            k.endsWith(".fingerprint") || k == "ro.build.fingerprint" -> profile.fingerprint

            // Serial
            k == "ro.serialno" || k == "ro.boot.serialno" || k == "ro.ril.oem.imei" -> profile.serial

            // Release Version
            k.endsWith(".version.release") || k == "ro.build.version.release" -> profile.releaseVersion

            // Security Patch
            k.endsWith(".version.security_patch") || k == "ro.build.version.security_patch" -> profile.securityPatch

            // SOC Info
            k == "ro.soc.manufacturer" -> socManufacturer
            k == "ro.soc.model" -> profile.board

            // OEM Specific Overrides
            k == "ro.miui.ui.version.name" -> ""
            k == "ro.build.version.oppo" || k == "ro.build.version.oneplus" -> ""

            else -> null
        }
    }

    private fun hookProcessExecution(classLoader: ClassLoader, profile: DeviceProfile) {
        try {
            val processBuilderClass = XposedHelpers.findClass("java.lang.ProcessBuilder", classLoader)
            XposedHelpers.findAndHookMethod(processBuilderClass, "start", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val pb = param.thisObject as? ProcessBuilder ?: return
                    val command = pb.command() ?: return
                    val spoofedProc = checkAndInterceptCommand(command, profile)
                    if (spoofedProc != null) {
                        param.result = spoofedProc
                    }
                }
            })

            val runtimeClass = XposedHelpers.findClass("java.lang.Runtime", classLoader)
            val execHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val firstArg = param.args.firstOrNull() ?: return
                    val commandList = when (firstArg) {
                        is Array<*> -> firstArg.filterIsInstance<String>()
                        is String -> firstArg.split("\\s+".toRegex())
                        else -> emptyList()
                    }
                    val spoofedProc = checkAndInterceptCommand(commandList, profile)
                    if (spoofedProc != null) {
                        param.result = spoofedProc
                    }
                }
            }

            hookMethodSafe(runtimeClass, "exec", arrayOf(Array<String>::class.java), execHook)
            hookMethodSafe(runtimeClass, "exec", arrayOf(String::class.java), execHook)
            hookMethodSafe(runtimeClass, "exec", arrayOf(Array<String>::class.java, Array<String>::class.java), execHook)
            hookMethodSafe(runtimeClass, "exec", arrayOf(Array<String>::class.java, Array<String>::class.java, File::class.java), execHook)
            hookMethodSafe(runtimeClass, "exec", arrayOf(String::class.java, Array<String>::class.java, File::class.java), execHook)

        } catch (t: Throwable) {
            XposedBridge.log("[ReShift Vector Spoofer] Error hooking Process execution: " + t.message)
        }
    }

    private fun checkAndInterceptCommand(command: List<String>, profile: DeviceProfile): Process? {
        if (command.isEmpty()) return null
        val cmdString = command.joinToString(" ").lowercase()

        if (cmdString.contains("getprop")) {
            val key = command.getOrNull(1)
            if (!key.isNullOrEmpty()) {
                val spoofedVal = getSpoofedSysPropValue(key, profile)
                if (spoofedVal != null) {
                    return SpoofedProcess("$spoofedVal\n")
                }
            } else {
                // Return comprehensive spoofed getprop output
                val getpropOutput = StringBuilder().apply {
                    append("[ro.product.model]: [${profile.model}]\n")
                    append("[ro.product.brand]: [${profile.brand}]\n")
                    append("[ro.product.manufacturer]: [${profile.manufacturer}]\n")
                    append("[ro.product.device]: [${profile.device}]\n")
                    append("[ro.product.name]: [${profile.product}]\n")
                    append("[ro.product.board]: [${profile.board}]\n")
                    append("[ro.hardware]: [${profile.hardware}]\n")
                    append("[ro.build.fingerprint]: [${profile.fingerprint}]\n")
                    append("[ro.serialno]: [${profile.serial}]\n")
                    append("[ro.build.version.release]: [${profile.releaseVersion}]\n")
                    append("[ro.build.version.sdk]: [${profile.sdkInt}]\n")
                    append("[ro.build.version.security_patch]: [${profile.securityPatch}]\n")
                }.toString()
                return SpoofedProcess(getpropOutput)
            }
        }

        // CPU Frequency sysfs property interception (return valid float/numeric string)
        if (cmdString.contains("cpuinfo_max_freq") || cmdString.contains("scaling_max_freq")) {
            return SpoofedProcess("2840000\n")
        }
        if (cmdString.contains("cpuinfo_min_freq") || cmdString.contains("scaling_min_freq")) {
            return SpoofedProcess("300000\n")
        }
        if (cmdString.contains("cpuinfo_cur_freq") || cmdString.contains("scaling_cur_freq")) {
            return SpoofedProcess("1800000\n")
        }

        // Exact /proc/cpuinfo interception
        if (cmdString.contains("/proc/cpuinfo") || cmdString == "cat cpuinfo" || cmdString.endsWith("/cpuinfo")) {
            val cpuInfoOutput = """
                processor	: 0
                BogoMIPS	: 38.40
                Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop sha3 asimddp sha512 asimdfhm dib uscat ilrcpc flagm pauth
                CPU implementer	: 0x41
                CPU architecture: 8
                CPU variant	: 0x3
                CPU part	: 0xd47
                CPU revision	: 1
                
                Hardware	: ${profile.hardware}
                Processor	: ${profile.hardware}
            """.trimIndent() + "\n"
            return SpoofedProcess(cpuInfoOutput)
        }

        return null
    }

    private fun hookSettingsProviders(classLoader: ClassLoader, profile: DeviceProfile) {
        try {
            val settingsHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val name = param.args.getOrNull(1) as? String
                    when (name) {
                        Settings.Secure.ANDROID_ID -> param.result = profile.androidId
                        "advertising_id", "google_advertising_id" -> param.result = profile.advertisingId
                        "gsf_id" -> param.result = profile.gsfId
                        "device_name", "bluetooth_name" -> param.result = profile.model
                    }
                }
            }

            for (settingsClass in listOf("android.provider.Settings\$Secure", "android.provider.Settings\$System", "android.provider.Settings\$Global")) {
                hookMethodSafe(settingsClass, classLoader, "getString", arrayOf(ContentResolver::class.java, String::class.java), settingsHook)
                hookMethodSafe(settingsClass, classLoader, "getStringForUser", arrayOf(ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType), settingsHook)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[ReShift Vector Spoofer] Error hooking Settings providers: " + t.message)
        }
    }

    private fun hookAdvertisingAndGsfId(classLoader: ClassLoader, profile: DeviceProfile) {
        try {
            val infoClass = XposedHelpers.findClass("com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info", classLoader)
            XposedHelpers.findAndHookMethod(infoClass, "getId", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = profile.advertisingId
                }
            })
        } catch (_: Throwable) { }

        try {
            val adClientClass = XposedHelpers.findClass("com.google.android.gms.ads.identifier.AdvertisingIdClient", classLoader)
            XposedHelpers.findAndHookMethod(adClientClass, "getAdvertisingIdInfo", Context::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val infoClass = XposedHelpers.findClass("com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info", classLoader)
                    val infoObj = XposedHelpers.newInstance(infoClass, profile.advertisingId, false)
                    param.result = infoObj
                }
            })
        } catch (_: Throwable) { }
    }

    private fun hookTelephonyManager(classLoader: ClassLoader, profile: DeviceProfile) {
        try {
            val telephonyClass = XposedHelpers.findClass("android.telephony.TelephonyManager", classLoader)

            val imeiHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = profile.imei
                }
            }

            hookMethodSafe(telephonyClass, "getDeviceId", imeiHook)
            hookMethodSafe(telephonyClass, "getDeviceId", arrayOf(Int::class.javaPrimitiveType), imeiHook)
            hookMethodSafe(telephonyClass, "getImei", imeiHook)
            hookMethodSafe(telephonyClass, "getImei", arrayOf(Int::class.javaPrimitiveType), imeiHook)
            hookMethodSafe(telephonyClass, "getMeid", imeiHook)
            hookMethodSafe(telephonyClass, "getMeid", arrayOf(Int::class.javaPrimitiveType), imeiHook)

            val subIdHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = profile.imei + "00000"
                }
            }
            hookMethodSafe(telephonyClass, "getSubscriberId", subIdHook)
            hookMethodSafe(telephonyClass, "getSubscriberId", arrayOf(Int::class.javaPrimitiveType), subIdHook)

            val simSerialHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = "89014103211118510720"
                }
            }
            hookMethodSafe(telephonyClass, "getSimSerialNumber", simSerialHook)
            hookMethodSafe(telephonyClass, "getSimSerialNumber", arrayOf(Int::class.javaPrimitiveType), simSerialHook)

            val phoneNumHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = "+15550192834"
                }
            }
            hookMethodSafe(telephonyClass, "getLine1Number", phoneNumHook)
            hookMethodSafe(telephonyClass, "getLine1Number", arrayOf(Int::class.javaPrimitiveType), phoneNumHook)

            val simOperatorHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = "310260"
                }
            }
            hookMethodSafe(telephonyClass, "getSimOperator", simOperatorHook)
            hookMethodSafe(telephonyClass, "getSimOperator", arrayOf(Int::class.javaPrimitiveType), simOperatorHook)

            val simOperatorNameHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = "T-Mobile"
                }
            }
            hookMethodSafe(telephonyClass, "getSimOperatorName", simOperatorNameHook)
            hookMethodSafe(telephonyClass, "getSimOperatorName", arrayOf(Int::class.javaPrimitiveType), simOperatorNameHook)
            hookMethodSafe(telephonyClass, "getNetworkOperatorName", simOperatorNameHook)
            hookMethodSafe(telephonyClass, "getNetworkOperatorName", arrayOf(Int::class.javaPrimitiveType), simOperatorNameHook)

        } catch (t: Throwable) {
            XposedBridge.log("[ReShift Vector Spoofer] Error hooking TelephonyManager: " + t.message)
        }
    }

    private fun hookOpenGL(classLoader: ClassLoader, profile: DeviceProfile) {
        val glHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val name = param.args.lastOrNull() as? Int ?: return
                if (name == 0x1F00) { // GL_VENDOR
                    param.result = profile.glVendor
                } else if (name == 0x1F01) { // GL_RENDERER
                    param.result = profile.glRenderer
                }
            }
        }

        hookMethodSafe("javax.microedition.khronos.opengles.GL10", classLoader, "glGetString", arrayOf(Int::class.javaPrimitiveType), glHook)
        hookMethodSafe("android.opengl.GLES10", classLoader, "glGetString", arrayOf(Int::class.javaPrimitiveType), glHook)
        hookMethodSafe("android.opengl.GLES20", classLoader, "glGetString", arrayOf(Int::class.javaPrimitiveType), glHook)
        hookMethodSafe("android.opengl.GLES30", classLoader, "glGetString", arrayOf(Int::class.javaPrimitiveType), glHook)
    }

    private fun hookWifiAndNetwork(classLoader: ClassLoader, profile: DeviceProfile) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.net.wifi.WifiInfo",
                classLoader,
                "getMacAddress",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = profile.macAddress
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "java.net.NetworkInterface",
                classLoader,
                "getHardwareAddress",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val macBytes = parseMacToBytes(profile.macAddress)
                        if (macBytes != null) {
                            param.result = macBytes
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[ReShift Vector Spoofer] Error hooking WifiInfo: " + t.message)
        }
    }

    private fun hookMediaDrm(classLoader: ClassLoader, profile: DeviceProfile) {
        try {
            val mediaDrmClass = XposedHelpers.findClass("android.media.MediaDrm", classLoader)

            XposedHelpers.findAndHookMethod(
                mediaDrmClass,
                "getPropertyByteArray",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val property = param.args[0] as? String
                        if (property == "deviceUniqueId") {
                            param.result = profile.drmDeviceId.toByteArray(Charsets.UTF_8)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                mediaDrmClass,
                "getPropertyString",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val property = param.args[0] as? String
                        if (property == "deviceUniqueId") {
                            param.result = profile.drmDeviceId
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[ReShift Vector Spoofer] Error hooking MediaDrm: " + t.message)
        }
    }

    private fun setStaticFieldSafe(clazz: Class<*>, fieldName: String, value: Any?) {
        try {
            XposedHelpers.setStaticObjectField(clazz, fieldName, value)
        } catch (_: Throwable) { }
    }

    private fun hookMethodSafe(clazz: Class<*>, methodName: String, hook: XC_MethodHook) {
        try {
            XposedHelpers.findAndHookMethod(clazz, methodName, hook)
        } catch (_: Throwable) { }
    }

    private fun hookMethodSafe(clazz: Class<*>, methodName: String, parameterTypes: Array<Class<*>?>, hook: XC_MethodHook) {
        try {
            val args = arrayOf(*parameterTypes, hook)
            XposedHelpers.findAndHookMethod(clazz, methodName, *args)
        } catch (_: Throwable) { }
    }

    private fun hookMethodSafe(className: String, classLoader: ClassLoader, methodName: String, parameterTypes: Array<Class<*>?>, hook: XC_MethodHook) {
        try {
            val clazz = XposedHelpers.findClass(className, classLoader)
            val args = arrayOf(*parameterTypes, hook)
            XposedHelpers.findAndHookMethod(clazz, methodName, *args)
        } catch (_: Throwable) { }
    }

    private fun parseMacToBytes(mac: String): ByteArray? {
        return try {
            val parts = mac.split(":")
            if (parts.size != 6) return null
            ByteArray(6) { parts[it].toInt(16).toByte() }
        } catch (_: Throwable) {
            null
        }
    }
}
