package com.dji.dronecontrol.rcpro

import android.app.Application
import android.content.Context
import com.cySdkyc.clx.Helper

class RcProApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Helper.install(this)
    }
}
