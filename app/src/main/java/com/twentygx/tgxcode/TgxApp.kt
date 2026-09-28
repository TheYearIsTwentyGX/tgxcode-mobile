package com.twentygx.tgxcode

import android.app.Application
import com.twentygx.tgxcode.di.AppContainer
import com.twentygx.tgxcode.service.Channels

class TgxApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Channels.create(this)
    }
}
