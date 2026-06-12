package com.rfsat.dms

import android.app.Application
import com.rfsat.dms.util.DLog

class DbmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DLog.init(this)
        DLog.i("App", "DBM ${BuildConfig.VERSION_NAME} starting, SDK ${android.os.Build.VERSION.SDK_INT}, " +
            "device ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }
}
