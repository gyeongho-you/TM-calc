package net.g1project.tmcalc

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.view.Display
import android.view.accessibility.AccessibilityEvent

/**
 * 계산 버튼을 누를 때만 스크린샷 한 장을 찍기 위한 접근성 서비스 (Android 11+).
 * 화면 공유(MediaProjection)처럼 계속 화면을 복사하지 않아 게임이 느려지지 않는다.
 * 접근성 이벤트는 받지 않는다 (res/xml/screenshot_service.xml).
 */
class ScreenshotService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 현재 화면을 찍어 메인 스레드로 돌려준다. 실패하면 bitmap 은 null, errorCode 는 ERROR_TAKE_SCREENSHOT_* */
    fun capture(onResult: (bitmap: Bitmap?, errorCode: Int) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hb = result.hardwareBuffer
                // 하드웨어 비트맵은 getPixel 이 안 되므로 일반 비트맵으로 복사한다
                val bmp = runCatching {
                    val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                    hw?.copy(Bitmap.Config.ARGB_8888, false).also { hw?.recycle() }
                }.getOrNull()
                hb.close()
                onResult(bmp, 0)
            }

            override fun onFailure(errorCode: Int) = onResult(null, errorCode)
        })
    }

    companion object {
        @Volatile
        var instance: ScreenshotService? = null
    }
}
