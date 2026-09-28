package net.g1project.tmcalc

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import java.io.File

/** adb run-as 로 넣은 캡처(filesDir/<name>)를 화면 전체에 1:1 로 띄운다. */
class ShotViewerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra("name") ?: "shot.jpg"
        val bmp = BitmapFactory.decodeFile(File(filesDir, name).path)
        setContentView(ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
        })
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
