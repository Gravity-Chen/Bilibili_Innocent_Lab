package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Bitmap
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HostHomeCardSpacingDialogInstrumentedTest {
    private fun click() = object : androidx.test.espresso.ViewAction {
        override fun getConstraints() = isEnabled()
        override fun getDescription() = "Click home spacing dialog control"
        override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
            generateSequence(view) { it.parent as? View }.first { it.isClickable }.performClick()
            uiController.loopMainThreadUntilIdle()
        }
    }

    @Test fun inputsValidateSaveIndependentlyCancelAndRestoreDefaults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.modulePreferences()
        val keys = listOf(FeaturePreferences.HOST_HOME_CARD_HORIZONTAL_DP, FeaturePreferences.HOST_HOME_CARD_VERTICAL_DP)
        val originals = keys.associateWith { prefs.all[it] }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var saved = 0
                fun open() { scenario.onActivity { it.showHostHomeCardSpacingDialog(it.window.decorView) { saved++ } } }
                fun input(title: Int, text: String) {
                    onView(allOf(isAssignableFrom(EditText::class.java), withContentDescription(context.getString(title))))
                        .inRoot(isDialog()).perform(replaceText(text))
                }
                fun button(title: Int) = onView(withText(context.getString(title))).inRoot(isDialog()).perform(click())
                open()
                input(R.string.host_home_card_horizontal, "0")
                input(R.string.host_home_card_vertical, "40")
                button(R.string.dialog_confirm)
                assertEquals(0, prefs.getInt(keys[0], -2))
                assertEquals(40, prefs.getInt(keys[1], -2))
                open()
                input(R.string.host_home_card_horizontal, "41")
                button(R.string.dialog_confirm)
                assertEquals(1, saved)
                input(R.string.host_home_card_horizontal, "8")
                button(R.string.dialog_cancel)
                assertEquals(0, prefs.getInt(keys[0], -2))
                open()
                input(R.string.host_home_card_horizontal, "")
                input(R.string.host_home_card_vertical, "12")
                button(R.string.dialog_confirm)
                assertEquals(-1, prefs.getInt(keys[0], -2))
                assertEquals(12, prefs.getInt(keys[1], -2))
                open()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "home-card-spacing-dialog.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                button(R.string.host_home_card_spacing_default)
                assertEquals(-1, prefs.getInt(keys[0], -2))
                assertEquals(-1, prefs.getInt(keys[1], -2))
                assertEquals(3, saved)
            }
        } finally {
            prefs.edit().apply { keys.forEach { key ->
                val original = originals[key]
                if (original is Int) putInt(key, original) else remove(key)
            } }.commit()
        }
    }
}
