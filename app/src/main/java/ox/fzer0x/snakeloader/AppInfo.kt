package ox.fzer0x.snakeloader

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isSystemApp: Boolean = false
)
