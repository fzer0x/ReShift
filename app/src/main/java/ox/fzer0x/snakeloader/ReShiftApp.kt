package ox.fzer0x.snakeloader

import android.app.Application
import android.util.Log
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import ox.fzer0x.snakeloader.di.appModule
import ox.fzer0x.snakeloader.security.SecurityStateManager
import ox.fzer0x.snakeloader.security.EncryptionManager

class ReShiftApp : Application() {
    companion object {
        private const val TAG = "ReShiftApp"
    }

    override fun onCreate() {
        super.onCreate()
        
        SecurityStateManager.initialize(this)
        EncryptionManager.initialize(this)
        
        startKoin {
            androidContext(this@ReShiftApp)
            modules(appModule)
        }
        
        Log.i(TAG, "ReShift application initialized")
    }
}
