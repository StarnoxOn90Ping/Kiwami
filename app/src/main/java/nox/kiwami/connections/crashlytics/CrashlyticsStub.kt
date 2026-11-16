package nox.kiwami.connections.crashlytics

import android.content.Context
import nox.kiwami.util.Logger

class CrashlyticsStub : CrashlyticsInterface {
    override fun initialize(context: Context) {
    }

    override fun logException(e: Throwable) {
        Logger.log(e)
    }

    override fun log(message: String) {
        Logger.log(message)
    }

    override fun setUserId(id: String) {
    }

    override fun setCustomKey(key: String, value: String) {
    }

    override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
    }

}