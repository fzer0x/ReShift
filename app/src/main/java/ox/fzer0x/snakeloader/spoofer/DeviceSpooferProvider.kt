package ox.fzer0x.snakeloader.spoofer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.google.gson.Gson

/**
 * ContentProvider enabling high-speed cross-process IPC config querying from Xposed hooks inside target app sandboxes.
 */
class DeviceSpooferProvider : ContentProvider() {

    private val gson = Gson()

    companion object {
        const val AUTHORITY = "ox.fzer0x.snakeloader.spoofer.provider"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/profile")
        const val COLUMN_PACKAGE_NAME = "package_name"
        const val COLUMN_PROFILE_JSON = "profile_json"
    }

    override fun onCreate(): Boolean {
        return true
    }

    private fun isCallerAuthorized(): Boolean {
        val callingUid = android.os.Binder.getCallingUid()
        val myUid = android.os.Process.myUid()
        if (callingUid == myUid || callingUid == 0 || callingUid == 1000) return true

        val context = context ?: return false
        val permissionResult = context.checkCallingOrSelfPermission("ox.fzer0x.snakeloader.PERMISSION_SPOOFER_IPC")
        return permissionResult == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (!isCallerAuthorized()) {
            return null
        }
        val context = context ?: return null
        val packageName = when {
            !selectionArgs.isNullOrEmpty() && selectionArgs[0].isNotEmpty() -> selectionArgs[0]
            uri.lastPathSegment != null && uri.lastPathSegment != "profile" -> uri.lastPathSegment!!
            !selection.isNullOrEmpty() -> selection
            else -> ""
        }

        val manager = DeviceSpooferManager(context)
        val profile = manager.getProfileForPackage(packageName)

        val cursor = MatrixCursor(arrayOf(COLUMN_PACKAGE_NAME, COLUMN_PROFILE_JSON))
        if (profile != null) {
            val json = gson.toJson(profile)
            cursor.addRow(arrayOf(packageName, json))
        }

        return cursor
    }

    override fun getType(uri: Uri): String? {
        return "vnd.android.cursor.item/vnd.ox.fzer0x.snakeloader.spoofer.profile"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
