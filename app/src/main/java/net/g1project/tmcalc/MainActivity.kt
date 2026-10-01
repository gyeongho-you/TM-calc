package net.g1project.tmcalc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var overlayBtn: Button
    private lateinit var a11yBtn: Button
    private lateinit var startBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (32 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
        }
        root.addView(TextView(this).apply {
            text = "테이밍마스터2 성장률 계산 레이어"
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "\n게임에서 소환수 정보 화면을 띄운 뒤 떠 있는 '계산' 버튼을 누르면 " +
                "화면을 읽어 총 성장률(계산기 기준)과 총 능력치 점수를 보여줍니다. " +
                "'자세히'를 열면 강화별(최대 +5강) 수치와 등급도 볼 수 있습니다.\n\n" +
                "· 끌 때는 '계산' 버튼을 꾹 눌러 아래에 나오는 쓰레기통에 끌어다 놓거나, 이 화면의 '레이어 끄기'를 누르세요.\n" +
                "· 버튼은 끌어서 옮길 수 있습니다.\n" +
                "· 잘못 읽힌 값은 결과창에서 직접 고치면 바로 다시 계산됩니다.\n" +
                "· 화면은 '계산'을 누를 때만 한 장 찍습니다 (계속 화면 공유하지 않아 게임이 느려지지 않음).\n" +
                "· 찍은 화면은 폰 안에서만 처리되며 어디에도 전송되지 않습니다.\n"
            textSize = 14f
        })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, (16 * dp).toInt()) }
        root.addView(status)
        overlayBtn = Button(this).apply {
            text = "1. 다른 앱 위에 표시 권한 허용"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        // 버튼 사이 간격
        fun spaced() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() }
        root.addView(overlayBtn, spaced())
        a11yBtn = Button(this).apply {
            text = "2. 접근성에서 화면 캡처 켜기"
            setOnClickListener { openA11ySettings() }
        }
        root.addView(a11yBtn, spaced())
        startBtn = Button(this).apply {
            text = "3. 계산 레이어 시작"
            setOnClickListener { startOverlay() }
        }
        root.addView(startBtn, spaced())
        root.addView(Button(this).apply {
            text = "도감 관리 (추가/수정)"
            setOnClickListener { startActivity(Intent(this@MainActivity, PetEditorActivity::class.java)) }
        }, spaced())
        root.addView(Button(this).apply {
            text = "설정"
            setOnClickListener { openSettings() }
        }, spaced())
        root.addView(Button(this).apply {
            text = "레이어 끄기"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                // 서비스 onDestroy 가 끝난 뒤에 상태 문구를 갱신한다
                postDelayed({ refresh() }, 400)
            }
        }, spaced())
        root.gravity = Gravity.TOP

        // Android 15부터는 앱이 상태바 뒤까지 그려진다(edge-to-edge). 모든 버전에서 똑같이 동작하도록
        // 직접 edge-to-edge 로 두고, 상태바/내비게이션바 높이만큼 여백을 준다 (제목이 가려지지 않게).
        val scroll = ScrollView(this).apply { addView(root) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
            scroll.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                root.setPadding(base[0] + bars.left, base[1] + bars.top, base[2] + bars.right, base[3] + bars.bottom)
                insets
            }
        }
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val canOverlay = Settings.canDrawOverlays(this)
        val canShot = ScreenshotService.instance != null
        overlayBtn.isEnabled = !canOverlay
        a11yBtn.isEnabled = !canShot
        startBtn.isEnabled = canOverlay && canShot
        status.text = when {
            !canOverlay -> "먼저 '다른 앱 위에 표시' 권한을 켜 주세요."
            !canShot -> "접근성 설정의 '설치된 앱'에서 '${getString(R.string.app_name)}'을(를) 켜 주세요."
            OverlayService.running -> "레이어 실행 중입니다. 게임으로 돌아가세요."
            else -> "준비 완료. 시작을 누르고 게임으로 돌아가세요."
        }
    }

    /**
     * 직접 설치한(APK) 앱은 Android 13부터 접근성이 '제한된 설정'으로 막혀 회색으로 보인다.
     * 그럴 때 푸는 방법을 먼저 알려주고 접근성 설정을 연다.
     */
    private fun openA11ySettings() {
        AlertDialog.Builder(this)
            .setTitle("접근성에서 켜기")
            .setMessage("접근성 → 설치된 앱 → '${getString(R.string.app_name)}' 을 켜 주세요.\n\n" +
                "회색으로 눌리지 않거나 '제한된 설정' 창이 뜨면:\n" +
                "앱 정보 화면 오른쪽 위 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 켜면 됩니다.")
            .setPositiveButton("접근성 설정 열기") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("앱 정보 열기") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .show()
    }

    /** 설정: 내 성장증폭 (종 대비 전투 능력치 계산에 씀) */
    private fun openSettings() {
        val dp = resources.displayMetrics.density
        val input = EditText(this).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(AppPrefs.pctText(AppPrefs.growthAmp(this@MainActivity)))
            setSelectAllOnFocus(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
            addView(TextView(context).apply { text = "내 성장증폭 (%)"; textSize = 15f })
            addView(input)
            addView(TextView(context).apply {
                text = "캐릭터 능력치의 성장증폭을 넣으면 '종 대비 전투 능력치'가 내 캐릭터 기준으로 계산됩니다."
                textSize = 12f
            })
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("설정")
            .setView(box)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()
        dlg.setOnShowListener {
            // 잘못된 값이면 창을 닫지 않고 알려 준다
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val v = input.text.toString().trim().removeSuffix("%").trim().toDoubleOrNull()
                if (v == null || v !in 0.0..100.0) input.error = "0 ~ 100 사이 숫자를 넣어 주세요"
                else { AppPrefs.setGrowthAmp(this, v); dlg.dismiss() }
            }
        }
        dlg.show()
    }

    private fun startOverlay() {
        startForegroundService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }
}
