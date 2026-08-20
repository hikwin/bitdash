package com.bitdash.app

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.bitdash.app.market.Prefs

/**
 * 所有页面的基类，统一应用用户在设置里保存的屏幕方向及屏幕常亮配置。
 *
 * 之所以在代码里 setRequestedOrientation 而不是写在 manifest：
 * manifest 的 screenOrientation 是编译期固定值，无法按用户设置切换。
 * manifest 里已改为 unspecified，实际方向完全由这里决定。
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyTheme(Prefs.getThemeMode(this))
        applyOrientation()
        applyKeepScreenOn()
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        Prefs.applyTheme(Prefs.getThemeMode(this))
        applyOrientation()
        applyKeepScreenOn()
    }

    fun applyOrientation() {
        val want = Prefs.getOrientation(this)
        if (requestedOrientation != want) {
            requestedOrientation = want
        }
    }

    /** 供设置页调用：保存并立即生效 */
    fun updateOrientation(value: Int) {
        Prefs.saveOrientation(this, value)
        applyOrientation()
    }

    /** 应用屏幕常亮设置 */
    fun applyKeepScreenOn() {
        if (Prefs.getKeepScreenOn(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
