package nox.kiwami.util

import android.content.Context
import android.content.res.Configuration
import nox.kiwami.settings.saving.PrefManager
import nox.kiwami.settings.saving.PrefName

object ThemeSurface {

    fun isOled(context: Context): Boolean {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return night == Configuration.UI_MODE_NIGHT_YES && PrefManager.getVal<Boolean>(PrefName.UseOLED)
    }
}
