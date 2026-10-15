package com.dji.dronecontrol.phone

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper

class PhoneApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Helper.install(this)
    }
}
