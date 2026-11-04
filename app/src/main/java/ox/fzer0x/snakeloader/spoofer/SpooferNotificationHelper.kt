package ox.fzer0x.snakeloader.spoofer

import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Helper to manage Hardware Spoofer status notifications.
 * All automatic notifications for Hardware Spoofer activations and hooks are disabled.
 */
object SpooferNotificationHelper {

    private const val CHANNEL_ID = "reshift_spoofer_channel"
    private const val NOTIFICATION_ID_ACTIVATION = 102
    private const val NOTIFICATION_ID_HOOK = 103

    fun createNotificationChannel(context: Context) {
        cancelAllNotifications(context)
    }

    fun cancelAllNotifications(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.cancel(NOTIFICATION_ID_ACTIVATION)
            notificationManager?.cancel(NOTIFICATION_ID_HOOK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager?.deleteNotificationChannel(CHANNEL_ID)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun notifyTargetAppSelected(
        context: Context,
        packageName: String,
        appLabel: String?,
        profile: DeviceProfile?
    ) {
        // Disabled per user setting
    }

    fun notifyHookAttached(
        context: Context,
        packageName: String,
        profileName: String,
        brand: String,
        model: String
    ) {
        // Disabled per user setting
    }

    fun notifyProfileActivated(
        context: Context,
        profileName: String,
        isEngineEnabled: Boolean,
        brand: String? = null,
        model: String? = null
    ) {
        // Disabled per user setting
    }
}
