package ox.fzer0x.snakeloader.spoofer

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * Data model representing a comprehensive hardware fingerprint profile used by the ReShift Spoofer engine.
 */
data class DeviceProfile(
    @SerializedName("profileId") val profileId: String = UUID.randomUUID().toString(),
    @SerializedName("name") val name: String = "Custom Profile",
    @SerializedName("brand") val brand: String = "google",
    @SerializedName("manufacturer") val manufacturer: String = "Google",
    @SerializedName("model") val model: String = "Pixel 8 Pro",
    @SerializedName("product") val product: String = "husky",
    @SerializedName("device") val device: String = "husky",
    @SerializedName("board") val board: String = "husky",
    @SerializedName("hardware") val hardware: String = "zuma",
    @SerializedName("fingerprint") val fingerprint: String = "google/husky/husky:14/UD1A.230803.041/10822088:user/release-keys",
    @SerializedName("serial") val serial: String = "8B1X100001",
    @SerializedName("androidId") val androidId: String = "a1b2c3d4e5f60708",
    @SerializedName("gsfId") val gsfId: String = "3a1b2c3d4e5f6789",
    @SerializedName("advertisingId") val advertisingId: String = "38400000-8cf0-11bd-b23e-10b96e40000d",
    @SerializedName("imei") val imei: String = "354123456789012",
    @SerializedName("macAddress") val macAddress: String = "02:00:00:00:00:00",
    @SerializedName("releaseVersion") val releaseVersion: String = "14",
    @SerializedName("sdkInt") val sdkInt: Int = 34,
    @SerializedName("securityPatch") val securityPatch: String = "2024-05-05",
    @SerializedName("glVendor") val glVendor: String = "ARM",
    @SerializedName("glRenderer") val glRenderer: String = "Mali-G715",
    @SerializedName("drmDeviceId") val drmDeviceId: String = "0123456789abcdef",
    @SerializedName("isGlobal") val isGlobal: Boolean = false,
    @SerializedName("targetPackages") val targetPackages: List<String> = emptyList()
)
