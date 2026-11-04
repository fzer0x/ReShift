package ox.fzer0x.snakeloader.spoofer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver listening for Vector Hardware Spoofer activation events sent from
 * Xposed / libxposed API 102 process hooks in target applications.
 */
class SpooferActivationReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_VECTOR_ACTIVATED = "ox.fzer0x.snakeloader.action.VECTOR_ACTIVATED"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
        const val EXTRA_PROFILE_NAME = "extra_profile_name"
        const val EXTRA_BRAND = "extra_brand"
        const val EXTRA_MODEL = "extra_model"

        fun sendActivationBroadcast(context: Context, packageName: String, profile: DeviceProfile) {
            try {
                val intent = Intent(ACTION_VECTOR_ACTIVATED).apply {
                    setPackage("ox.fzer0x.snakeloader")
                    putExtra(EXTRA_PACKAGE_NAME, packageName)
                    putExtra(EXTRA_PROFILE_NAME, profile.name)
                    putExtra(EXTRA_BRAND, profile.brand)
                    putExtra(EXTRA_MODEL, profile.model)
                }
                context.sendBroadcast(intent)
            } catch (e: Throwable) {
                Log.e("VectorSpoofer", "Error broadcasting activation: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_VECTOR_ACTIVATED) {
            val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: "target.app"
            val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "Custom Profile"
            val brand = intent.getStringExtra(EXTRA_BRAND) ?: "Google"
            val model = intent.getStringExtra(EXTRA_MODEL) ?: "Pixel 8 Pro"

            SpooferNotificationHelper.notifyHookAttached(
                context = context,
                packageName = packageName,
                profileName = profileName,
                brand = brand,
                model = model
            )
        }
    }
}
