package ox.fzer0x.snakeloader.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import ox.fzer0x.snakeloader.SettingsManager

object StealthUtils {

    fun toggleAppIcon(context: Context, hide: Boolean) {
        val settings = SettingsManager(context)
        val packageManager = context.packageManager
        
        val defaultAlias = ComponentName(context, "ox.fzer0x.snakeloader.LauncherDefault")
        val maskedAlias = ComponentName(context, "ox.fzer0x.snakeloader.LauncherMasked")

        if (hide) {
            packageManager.setComponentEnabledSetting(
                defaultAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                maskedAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d("StealthUtils", "App icons hidden (Total Stealth)")
        } else {
            packageManager.setComponentEnabledSetting(
                defaultAlias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                maskedAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            Log.d("StealthUtils", "Default app icon restored")
        }
        settings.isIconHidden = hide
    }

    fun setAppMasked(context: Context, masked: Boolean) {
        val packageManager = context.packageManager
        val defaultAlias = ComponentName(context, "ox.fzer0x.snakeloader.LauncherDefault")
        val maskedAlias = ComponentName(context, "ox.fzer0x.snakeloader.LauncherMasked")

        if (masked) {
            packageManager.setComponentEnabledSetting(
                defaultAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                maskedAlias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            packageManager.setComponentEnabledSetting(
                defaultAlias,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            packageManager.setComponentEnabledSetting(
                maskedAlias,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
