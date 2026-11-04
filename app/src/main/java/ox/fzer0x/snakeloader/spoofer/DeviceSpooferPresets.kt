package ox.fzer0x.snakeloader.spoofer

import java.util.UUID
import kotlin.random.Random

/**
 * Pre-defined real-world device profiles and randomizer engine for ReShift Hardware Spoofer.
 */
object DeviceSpooferPresets {

    val PIXEL_8_PRO = DeviceProfile(
        profileId = "preset_pixel_8_pro",
        name = "Google Pixel 8 Pro",
        brand = "google",
        manufacturer = "Google",
        model = "Pixel 8 Pro",
        product = "husky",
        device = "husky",
        board = "husky",
        hardware = "zuma",
        fingerprint = "google/husky/husky:14/UD1A.230803.041/10822088:user/release-keys",
        serial = "2C261FDH2001A",
        androidId = "9774d56d682e549c",
        gsfId = "3a1b2c3d4e5f6789",
        advertisingId = "38400000-8cf0-11bd-b23e-10b96e40000d",
        imei = "354109101234567",
        macAddress = "9C:9A:80:A1:B2:C3",
        releaseVersion = "14",
        sdkInt = 34,
        securityPatch = "2024-05-05",
        glVendor = "ARM",
        glRenderer = "Mali-G715",
        drmDeviceId = "41524d2d473731352d44524d30303031",
        isGlobal = false
    )

    val GALAXY_S24_ULTRA = DeviceProfile(
        profileId = "preset_galaxy_s24_ultra",
        name = "Samsung Galaxy S24 Ultra",
        brand = "samsung",
        manufacturer = "samsung",
        model = "SM-S928B",
        product = "e3q",
        device = "e3q",
        board = "kalama",
        hardware = "qcom",
        fingerprint = "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXU1AXB5:user/release-keys",
        serial = "R58N10A234B",
        androidId = "8a2f5e3d7c1b0a9f",
        gsfId = "8a7f6e5d4c3b2a10",
        advertisingId = "2a8e1001-9bc0-41ed-a111-90c128384912",
        imei = "352984119876543",
        macAddress = "D8:12:5E:A4:B5:C6",
        releaseVersion = "14",
        sdkInt = 34,
        securityPatch = "2024-04-01",
        glVendor = "Qualcomm",
        glRenderer = "Adreno (TM) 750",
        drmDeviceId = "51434f4d2d416472656e6f3735300000",
        isGlobal = false
    )

    val XIAOMI_14_PRO = DeviceProfile(
        profileId = "preset_xiaomi_14_pro",
        name = "Xiaomi 14 Pro",
        brand = "Xiaomi",
        manufacturer = "Xiaomi",
        model = "23116PN5BC",
        product = "shennong",
        device = "shennong",
        board = "shennong",
        hardware = "qcom",
        fingerprint = "Xiaomi/shennong/shennong:14/UKQ1.230804.001/V816.0.4.0.UNBCNXM:user/release-keys",
        serial = "3a4b5c6d7e8f",
        androidId = "7c6b5a43210edcba",
        gsfId = "7c6b5a43210edcba",
        advertisingId = "7f329002-3110-48ef-b812-108229384011",
        imei = "863124061234567",
        macAddress = "64:CC:2E:11:22:33",
        releaseVersion = "14",
        sdkInt = 34,
        securityPatch = "2024-03-01",
        glVendor = "Qualcomm",
        glRenderer = "Adreno (TM) 750",
        drmDeviceId = "5849414f4d492d44524d303030303031",
        isGlobal = false
    )

    val ONEPLUS_12 = DeviceProfile(
        profileId = "preset_oneplus_12",
        name = "OnePlus 12",
        brand = "OnePlus",
        manufacturer = "OnePlus",
        model = "CPH2581",
        product = "OP595DL1",
        device = "OP595DL1",
        board = "kalama",
        hardware = "qcom",
        fingerprint = "OnePlus/CPH2581/OP595DL1:14/UKQ1.230924.001/S.1634567-0-1234:user/release-keys",
        serial = "1A2B3C4D5E6F",
        androidId = "5f4e3d2c1b0a9876",
        gsfId = "5f4e3d2c1b0a9876",
        advertisingId = "99182310-4100-4aef-c110-881290384122",
        imei = "864501069876543",
        macAddress = "A4:C7:DE:33:44:55",
        releaseVersion = "14",
        sdkInt = 34,
        securityPatch = "2024-04-05",
        glVendor = "Qualcomm",
        glRenderer = "Adreno (TM) 750",
        drmDeviceId = "4f4e45504c55532d44524d3030303031",
        isGlobal = false
    )

    val ALL_PRESETS = listOf(PIXEL_8_PRO, GALAXY_S24_ULTRA, XIAOMI_14_PRO, ONEPLUS_12)

    /**
     * Generates a realistic, randomized profile matching valid hardware patterns.
     */
    fun generateRandomProfile(base: DeviceProfile? = null): DeviceProfile {
        val basePreset = base ?: ALL_PRESETS.random()
        val randomSerial = generateRandomHex(12).uppercase()
        val randomAndroidId = generateRandomHex(16).lowercase()
        val randomGsfId = generateRandomHex(16).lowercase()
        val randomAdvertisingId = UUID.randomUUID().toString()
        val randomMac = generateRandomMac()
        val randomImei = generateRandomValidImei()
        val randomDrmId = generateRandomHex(32).lowercase()

        val isPreset = base == null || ALL_PRESETS.any { it.profileId == base.profileId }
        val targetProfileId = if (isPreset) UUID.randomUUID().toString() else basePreset.profileId

        val cleanName = basePreset.name.replace(Regex("""(\s*\(Randomized\))+""", RegexOption.IGNORE_CASE), "").trim()
        val baseName = cleanName.ifEmpty { basePreset.model }
        val generatedName = "$baseName (Randomized)"

        return basePreset.copy(
            profileId = targetProfileId,
            name = generatedName,
            serial = randomSerial,
            androidId = randomAndroidId,
            gsfId = randomGsfId,
            advertisingId = randomAdvertisingId,
            macAddress = randomMac,
            imei = randomImei,
            drmDeviceId = randomDrmId,
            isGlobal = false
        )
    }

    private fun generateRandomHex(length: Int): String {
        val chars = "0123456789abcdef"
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun generateRandomMac(): String {
        val octets = List(6) { "%02X".format(Random.nextInt(0, 256)) }
        return octets.joinToString(":")
    }

    /**
     * Generates a 15-digit valid IMEI using the Luhn algorithm checksum.
     */
    private fun generateRandomValidImei(): String {
        // Standard TAC prefix for modern 5G devices (e.g. 354109)
        val tac = "35" + (100000..999999).random().toString()
        val snr = (100000..999999).random().toString()
        val first14 = (tac + snr).take(14)
        val checkDigit = calculateLuhnCheckDigit(first14)
        return first14 + checkDigit
    }

    private fun calculateLuhnCheckDigit(numberStr: String): Int {
        var sum = 0
        for (i in numberStr.indices.reversed()) {
            var digit = numberStr[i] - '0'
            if ((numberStr.length - i) % 2 == 1) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
        }
        return (10 - (sum % 10)) % 10
    }
}
