package net.g1project.tmcalc

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import org.json.JSONObject
import java.util.Locale

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var ui: Context
    private lateinit var db: PetDb
    /** 읽어 둔 도감의 PetStore.version — 도감 관리에서 고치면 다시 읽는다 */
    private var dbVersion = -1
    private lateinit var recognizer: TextRecognizer
    private val main = Handler(Looper.getMainLooper())

    private var overlayShown = false

    private lateinit var bubble: TextView
    /** 버튼을 꾹 누르면 화면 아래에 나타나는 종료용 쓰레기통 */
    private lateinit var trash: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var panel: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams

    private lateinit var nameEdit: EditText
    private lateinit var levelEdit: EditText
    private lateinit var breakEdit: EditText
    /** 게임 화면의 "총 성장 점수" (인게임 %) — 스캔으로 채우고, 잘못 읽혔으면 고칠 수 있다 */
    private lateinit var gameEdit: EditText
    private val statEdits = arrayOfNulls<EditText>(4)
    private val deltaEdits = arrayOfNulls<EditText>(4)
    private lateinit var totalView: TextView
    private lateinit var totalGradeView: TextView
    private lateinit var totalSubView: TextView
    private lateinit var scoreView: TextView
    private lateinit var combatView: TextView
    private lateinit var combatHelp: TextView
    private lateinit var enhanceTable: LinearLayout
    /** 이름 추천 버튼들 (2개씩 줄). 스크롤 안에 가로 스크롤을 또 넣으면 터치가 스크롤로 먹혀서 눌리지 않았다 */
    private lateinit var suggestBox: LinearLayout
    private lateinit var warnView: TextView
    private lateinit var results: LinearLayout

    /** 스캔 결과를 채우는 중에는 TextWatcher 재계산을 막는다. */
    private var filling = false
    private var busy = false
    private var lastParsed: ParsedScreen? = null
    /** 강화별 표의 '최저치로 보기' / '이전 강화 보기'. 기억하지 않고 새로 계산할 때마다 꺼진 상태로 */
    private var worstOn = false
    private var lowerOn = false

    private lateinit var compareBox: LinearLayout
    private lateinit var presetBox: LinearLayout
    /** 비교 기준(A). 지금 계산이나 저장 목록에서 고정한다 */
    private var pinned: Preset? = null
    /** A 를 볼 강 / B 를 볼 강 (B 가 null 이면 찍힌 강) */
    private var pinTarget = 0
    private var curTarget: Int? = null
    /** 비교 대상(B)을 저장 목록에서 골랐으면 그 소환수, 아니면 null(= 지금 계산) */
    private var compareB: Preset? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WindowManager::class.java)
        ui = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        reloadDb()
        recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (!overlayShown) {
            overlayShown = true
            showOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        getSharedPreferences("settings", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
        labPanel?.let { p -> runCatching { wm.removeView(p) } }
        optPanel?.let { p -> flushSpec(); runCatching { wm.removeView(p) } }
        runCatching { wm.removeView(bubble) }
        runCatching { wm.removeView(panel) }
        runCatching { wm.removeView(trash) }
        recognizer.close()
        runCatching { engine?.destroy() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- foreground

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "계산 레이어", NotificationManager.IMPORTANCE_LOW))
        // 알림을 누르면 앱 화면이 열린다 (끄기는 앱의 '레이어 끄기' 버튼으로만)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("테이밍 계산 레이어 실행 중")
            .setContentText("끄려면 '계산' 버튼을 꾹 눌러 아래 쓰레기통에 놓으세요")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    private fun reloadDb() {
        dbVersion = PetStore.version
        db = PetStore.load(this)
    }

    // ---------------------------------------------------------------- scan

    private fun scan() {
        if (busy) return
        if (dbVersion != PetStore.version) reloadDb()
        val shooter = ScreenshotService.instance
        if (shooter == null) {
            toast("접근성 설정에서 '${getString(R.string.app_name)}'을(를) 켜 주세요.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        busy = true
        bubble.text = "…"
        // 레이어가 스크린샷에 찍히지 않도록 잠깐 숨기고, 숨긴 화면이 그려진 뒤에 한 장만 찍는다
        val panelWasShown = panel.visibility == View.VISIBLE
        bubble.visibility = View.INVISIBLE
        panel.visibility = View.INVISIBLE
        main.postDelayed({
            shooter.capture { bmp, err ->
                bubble.visibility = View.VISIBLE
                if (bmp == null) {
                    if (panelWasShown) showPanel(true)
                    finishScan()
                    toast(when (err) {
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "너무 빨리 눌렀습니다. 잠시 후 다시 눌러 주세요."
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "게임이 화면 캡처를 막고 있습니다."
                        else -> "화면을 찍지 못했습니다. 다시 눌러 주세요."
                    })
                    return@capture
                }
                runOcr(bmp)
            }
        }, HIDE_DELAY_MS)
    }

    private fun runOcr(bmp: Bitmap) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                if (!running) return@addOnSuccessListener // 인식 중에 레이어를 끈 경우
                val lines = text.textBlocks.flatMap { b -> b.lines }.mapNotNull { l ->
                    val r = l.boundingBox ?: return@mapNotNull null
                    OcrLine(l.text, r.left, r.top, r.right, r.bottom)
                }
                // 테스트 빌드에서만: 인식된 줄을 로그로 남겨 회귀 테스트 자료로 쓴다
                if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    lines.forEach { Log.d("tmcalc-ocr", "${it.text}|${it.left}|${it.top}|${it.right}|${it.bottom}") }
                    Log.d("tmcalc-ocr", "--end--")
                }
                val parsed = OcrParser.parse(lines, db)
                // 최적화 모드: 룬 비교 화면이면 룬 비교, 그 밖의 화면이면 내 스펙 입력 창
                if (AppPrefs.runeMode(this)) {
                    // 룬 비교 화면 글자('주 옵션' 2개 + '부 옵션' + '장착중'/'○○의 룬')가 다 있을 때만 룬 비교.
                    // 전투·도감 등 다른 화면은 숫자·Lv 글자가 룬 상자로 잘못 잡혀도 내 스펙 창
                    if (RuneParser.hasCompareMarks(lines)) {
                        val rs0 = RuneParser.parse(lines)
                        RuneLog.add(this, rs0.take(2)) // 임시: 수치 공식 찾기용 데이터 모으기 (정식 배포 전 삭제)
                        if (rs0.size >= 2 && rs0.take(2).all { it.atk != null }) {
                            showRuneCompare(rs0[0], rs0[1])
                            return@addOnSuccessListener
                        }
                        val miss = if (rs0.size < 2) "위(장착중)·아래(새 룬) 상자 ${rs0.size}개만 찾음" else "공격력 값을 못 읽음"
                        overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("룬 비교 화면을 못 읽었어요")
                            .setMessage(miss + "\n위 장착중 / 아래 새 룬이 다 보이게 띄우고 다시 눌러 주세요.")
                            .setPositiveButton("닫기", null)
                            .setNeutralButton("내 스펙") { _, _ -> showSpecInput() })
                        return@addOnSuccessListener
                    }
                    showSpecInput()
                    return@addOnSuccessListener
                }
                lastParsed = parsed
                worstOn = false; lowerOn = false
                // 괄호 숫자의 +/- 는 OCR이 놓치기 쉬우므로 글자색(빨강/초록)으로 한 번 더 확인
                val deltas = Array(4) { i ->
                    val d = parsed.deltas[i] ?: return@Array null
                    val box = parsed.deltaBoxes[i] ?: return@Array d
                    when (colorSign(bmp, box)) {
                        -1 -> -Math.abs(d)
                        1 -> Math.abs(d)
                        else -> d
                    }
                }
                fill(parsed, deltas)
                showPanel(true)
                if (parsed.pet == null && parsed.stats.all { it == null }) {
                    toast("소환수 정보 화면을 찾지 못했습니다.")
                }
            }
            .addOnFailureListener { e -> if (running) toast("글자 인식 실패: ${e.message}") }
            .addOnCompleteListener {
                bmp.recycle()
                finishScan()
            }
    }

    private fun finishScan() {
        busy = false
        updateBubble()
    }

    private fun showPanel(show: Boolean) {
        if (!show) setPanelFocusable(false)
        panel.visibility = if (show) View.VISIBLE else View.GONE
        updateBubble()
    }

    /** 결과창이 떠 있으면 버튼을 ✕ 로, 아니면 "계산" 으로 */
    private fun updateBubble() {
        if (busy) return
        val lab = labMode()
        val open = panel.visibility == View.VISIBLE || labOpen() || optOpen()
        val rune = !lab && AppPrefs.runeMode(this)
        bubble.text = if (open) "✕" else if (lab) "연구" else if (rune) "최적화" else "계산"
        bubble.textSize = if (open) 20f else 13f
        (bubble.background as? GradientDrawable)?.setColor(Color.parseColor(if (lab) "#B39DDB" else if (rune) "#80CBC4" else "#F2B84B"))
    }

    /** 박스 안의 채도 높은 픽셀이 빨강 계열이면 -1, 초록 계열이면 +1, 애매하면 0. */
    private fun colorSign(bmp: Bitmap, box: IntArray): Int {
        if (bmp.isRecycled) return 0
        val l = box[0].coerceIn(0, bmp.width - 1); val r = box[2].coerceIn(l + 1, bmp.width)
        val t = box[1].coerceIn(0, bmp.height - 1); val b = box[3].coerceIn(t + 1, bmp.height)
        var red = 0; var green = 0
        for (y in t until b step 2) for (x in l until r step 2) {
            val c = bmp.getPixel(x, y)
            val cr = Color.red(c); val cg = Color.green(c); val cb = Color.blue(c)
            if (maxOf(cr, cg, cb) - minOf(cr, cg, cb) < 60) continue // 흰 글자/어두운 배경은 제외
            if (cr > cg + 50 && cr > cb) red++ else if (cg > cr + 40 && cg >= cb) green++
        }
        return when {
            red >= 8 && red > green * 2 -> -1
            green >= 8 && green > red * 2 -> 1
            else -> 0
        }
    }

    private fun fill(p: ParsedScreen, deltas: Array<Int?>) {
        filling = true
        if (p.pet != null) nameEdit.setText(p.pet.name) else nameEdit.setText(p.nameRaw?.let { PetDb.displayName(it) } ?: "")
        // 못 읽은 칸은 이전 소환수 값이 남지 않도록 비운다
        levelEdit.setText(p.level?.toString() ?: "")
        breakEdit.setText((p.breaks ?: 0).toString())
        gameEdit.setText(p.gameTotal?.toString() ?: "")
        for (i in 0..3) {
            val s = p.stats[i]
            var d = deltas[i]
            // 시트 도감은 만렙(150) 기준이라 150일 때만 보정에 쓴다:
            // 부호만 틀리게 읽혔거나 괄호를 못 읽었으면 시트 도감으로 채운다
            if (p.pet != null && s != null && p.level == MAX_LEVEL) {
                val sheet = s - p.pet.maxS[i]
                if (d == null || Math.abs(d) == Math.abs(sheet)) d = sheet
            }
            statEdits[i]!!.setText(s?.toString() ?: "")
            deltaEdits[i]!!.setText(d?.toString() ?: "")
        }
        curTarget = null; compareB = null
        filling = false
        recompute()
    }

    // ---------------------------------------------------------------- 저장 목록 / 비교

    /** 지금 계산 화면의 값 (능력치 4개와 도감값을 알 수 있을 때만). 도감값 = 현재 − 도감 대비, 비었으면 150레벨 도감 */
    private fun currentPreset(forSave: Boolean = false): Preset? {
        val pet = db.find(nameEdit.text.toString())
        val level = levelEdit.text.toString().toIntOrNull() ?: return null
        val breaks = (breakEdit.text.toString().toIntOrNull() ?: 0).coerceIn(0, Calculator.MAX_BREAKS)
        val stats = statEdits.map { num(it!!) }
        val deltas = deltaEdits.map { num(it!!) }
        if (stats.any { it == null || it <= 0 }) return null
        val base = IntArray(4) { i ->
            deltas[i]?.let { stats[i]!! - it } ?: pet?.maxS?.get(i)?.takeIf { level == MAX_LEVEL } ?: return null
        }
        if (base.any { it <= 0 }) return null
        val name = pet?.name ?: nameEdit.text.toString().trim()
        val game = num(gameEdit)
        // 저장할 때만 같은 이름 뒤에 (2), (3) 을 붙인다
        return Preset(Preset.defaultLabel(name, breaks, game, if (forSave) AppPrefs.presets(this) else emptyList()), name, level, breaks,
            IntArray(4) { stats[it]!! }, base, game)
    }

    private fun savePreset() {
        val p = currentPreset(forSave = true) ?: return toast("능력치와 도감 대비를 먼저 채워 주세요 (150레벨이고 도감에 있으면 도감 대비는 비워도 됨)")
        val list = AppPrefs.presets(this)
        if (list.size >= Preset.MAX_COUNT) return toast("저장은 ${Preset.MAX_COUNT}개까지입니다. 목록에서 지워 주세요.")
        AppPrefs.setPresets(this, list + p)
        toast("저장했습니다: ${p.label}")
        if (presetBox.visibility == View.VISIBLE) renderPresets()
        renderCompare() // 비교 칸의 고르기 목록에도 바로 나오게
    }

    private fun pin(p: Preset) {
        pinned = p; pinTarget = p.breaks
        toast("기준 고정: ${p.label}\n다른 소환수를 계산하면 나란히 비교됩니다")
        renderCompare()
    }

    /** 저장한 값을 계산 화면에 그대로 띄운다 (게임에서 다시 찍지 않아도 됨) */
    private fun loadPreset(p: Preset) {
        filling = true
        nameEdit.setText(p.name)
        levelEdit.setText(p.level.toString())
        breakEdit.setText(p.breaks.toString())
        gameEdit.setText(p.gameTotal?.toString() ?: "")
        val d = p.deltas
        for (i in 0..3) { statEdits[i]!!.setText(p.stats[i].toString()); deltaEdits[i]!!.setText(d[i].toString()) }
        lastParsed = null
        worstOn = false; lowerOn = false; curTarget = null; compareB = null
        filling = false
        recompute()
    }

    private fun renderPresets() {
        presetBox.removeAllViews()
        val list = AppPrefs.presets(this)
        if (list.isEmpty()) {
            presetBox.addView(note("저장한 소환수가 없습니다. 계산한 뒤 [저장]을 누르세요."))
            return
        }
        for (p in list) {
            presetBox.addView(LinearLayout(ui).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
                // 이름을 누르면 이름·메모 고치기
                addView(TextView(ui).apply {
                    text = p.label + "  ✎\n" + "Lv.${p.level} · 총능 ${fmt(Calculator.abilityScore(p.stats), 1)}" +
                        (if (p.memo.isNotEmpty()) "\n" + p.memo else "")
                    textSize = 12f
                    setTextColor(Color.parseColor("#C9CBD0"))
                    setOnClickListener { editPreset(p) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(headerButton("보기") { loadPreset(p) })
                addView(headerButton("기준") { pin(p) })
                addView(headerButton("비교") { setCompareB(p) })
                addView(headerButton("삭제") { confirmDelete(p) })
            })
        }
    }

    /** 저장한 소환수의 이름·메모 고치기 (레이어 위에 뜨는 창) */
    private fun editPreset(p: Preset) {
        val pad = dp(20)
        val nameE = EditText(ui).apply { setText(p.label); hint = "이름"; setSingleLine() }
        val memoE = EditText(ui).apply { setText(p.memo); hint = "메모 (예: PVP용, 재료 후보)"; setSingleLine() }
        val box = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0)
            addView(nameE); addView(memoE)
        }
        android.app.AlertDialog.Builder(ui)
            .setTitle("이름 / 메모")
            .setView(box)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val list = AppPrefs.presets(this)
                val others = list.filterNot { it.label == p.label }
                val label = Preset.uniqueLabel(nameE.text.toString(), others)
                AppPrefs.setPresets(this, list.map { if (it.label == p.label) it.renamed(label, memoE.text.toString()) else it })
                if (pinned?.label == p.label) pinned = pinned!!.renamed(label, memoE.text.toString())
                renderPresets(); renderCompare()
            }
            .create()
            .apply { window?.setType(overlayType()) }
            .show()
    }

    /** 실수로 지우지 않게 한 번 묻는다 (레이어 위에 뜨는 창) */
    private fun confirmDelete(p: Preset) {
        android.app.AlertDialog.Builder(ui)
            .setTitle("삭제")
            .setMessage("'${p.label}' 을(를) 저장 목록에서 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                AppPrefs.setPresets(this, AppPrefs.presets(this).filterNot { it.label == p.label })
                renderPresets(); renderCompare()
            }
            .create()
            .apply { window?.setType(overlayType()) }
            .show()
    }

    private fun setCompareB(p: Preset?) {
        if (pinned == null) return toast("먼저 [기준]으로 기준을 고정해 주세요")
        compareB = p; curTarget = null
        renderCompare()
    }

    /** 세로로 뜨는 선택 목록 (안드로이드 PopupMenu, 레이어 창 위에 뜬다) */
    private fun choose(anchor: View, items: List<String>, onPick: (Int) -> Unit) {
        android.widget.PopupMenu(ui, anchor).apply {
            items.forEachIndexed { i, t -> menu.add(0, i, i, t) }
            setOnMenuItemClickListener { onPick(it.itemId); true }
        }.show()
    }

    /**
     * 기준(A)과 비교 대상(B)을 나란히: 능력치와 "B 의 실제 전투 능력치가 A 보다 몇 %".
     * B 는 지금 계산 화면이 기본이고, 이름을 누르면 저장 목록에서 고를 수 있다 (A 도 마찬가지).
     * 강은 찍힌 강이 기본이고, 강 버튼을 누르면 0~5강 목록에서 골라 그 강의 예상 능력치로 비교한다.
     */
    private fun renderCompare() {
        compareBox.removeAllViews()
        val a = pinned ?: run { compareBox.visibility = View.GONE; return }
        compareBox.visibility = View.VISIBLE
        compareBox.addView(sectionTitle("비교"))
        val b = compareB ?: currentPreset()
        val bT = b?.let { curTarget ?: it.breaks }
        val c = b?.let { Calculator.compare(a.stats, a.base, a.breaks, pinTarget, it.stats, it.base, it.breaks, bT!!, growthAmp()) }
        val aStats = c?.a ?: Calculator.statsAtBreaks(a.stats, a.base, a.breaks, pinTarget)
        val saved = AppPrefs.presets(this)
        // 열 머리: 이름 (누르면 저장 목록에서 고르기)
        compareBox.addView(row(listOf("", "${a.name}(기준) ▾", (b?.name?.ifBlank { "지금 계산" } ?: "고르기") + " ▾", "실전투"), header = true).apply {
            for (i in 1..2) (getChildAt(i) as TextView).apply { setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }
            getChildAt(1).setOnClickListener { v ->
                if (saved.isEmpty()) toast("저장한 소환수가 없습니다") else choose(v, saved.map { it.label }) { pin(saved[it]) }
            }
            getChildAt(2).setOnClickListener { v ->
                choose(v, listOf("지금 계산") + saved.map { it.label }) { setCompareB(if (it == 0) null else saved[it - 1]) }
            }
        })
        // 그 아래 강 (누르면 0~5강 목록)
        val breakItems = (0..Calculator.MAX_BREAKS).map { "${it}강" }
        compareBox.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(ui), LinearLayout.LayoutParams(0, 0, 0.8f))
            for ((k, onPick) in listOf<Pair<Int?, (Int) -> Unit>>(pinTarget to { v -> pinTarget = v }, bT to { v -> curTarget = v })) {
                addView(LinearLayout(ui).apply {
                    gravity = Gravity.CENTER
                    if (k != null) addView(headerButton("${k}강 ▾") {}.apply {
                        (layoutParams as LinearLayout.LayoutParams).marginStart = 0
                        setOnClickListener { v -> choose(v, breakItems) { onPick(it); renderCompare() } }
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(View(ui), LinearLayout.LayoutParams(0, 0, 1f))
        })
        for (i in 0..3) {
            val p = c?.pct?.get(i)
            val line = row(listOf(STAT_NAMES[i], "${aStats[i]}", c?.b?.get(i)?.toString() ?: "-", p?.let { combatPct(it, c.approx) } ?: "-"))
            (line.getChildAt(3) as TextView).apply {
                setTextColor(Color.parseColor(if (p == null) "#9A9DA3" else if (p >= 0) "#7FD4A0" else "#F28B82"))
                typeface = Typeface.DEFAULT_BOLD
            }
            compareBox.addView(line)
        }
        compareBox.addView(row(listOf("총능", fmt(c?.scoreA ?: Calculator.abilityScore(aStats), 1), c?.let { fmt(it.scoreB, 1) } ?: "-", ""), small = true))
        compareBox.addView(note(when {
            b == null -> "다른 소환수를 계산하거나, 오른쪽 이름을 눌러 저장한 소환수를 고르면 기준과 비교됩니다."
            c?.reason != null -> c.reason!!
            else -> "실전투 = 성장증폭 ${ampText(growthAmp())}%와 총능 보너스를 넣은 실제 전투 능력치가 기준보다 몇 % 높은지. 이름을 누르면 저장 목록에서, 강을 누르면 0~5강에서 고를 수 있습니다."
        }))
        compareBox.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            if (b != null) addView(headerButton("A↔B 바꾸기") {
                val oldA = pinned!!; val oldAT = pinTarget
                pinned = b; pinTarget = bT!!
                if (compareB != null) { compareB = oldA; curTarget = oldAT; renderCompare() }
                else { loadPreset(oldA); curTarget = oldAT; renderCompare() }
            })
            addView(headerButton("기준 해제") { pinned = null; compareB = null; renderCompare() })
        })
    }

    // ---------------------------------------------------------------- calc + render

    private fun recompute() {
        if (filling) return
        Calculator.showBeyondGame = AppPrefs.showSss(this)
        if (dbVersion != PetStore.version) reloadDb()
        val pet = db.find(nameEdit.text.toString())
        updateSuggestions(pet)
        val level = levelEdit.text.toString().toIntOrNull()
        val breaks = breakEdit.text.toString().toIntOrNull() ?: 0
        val stats = statEdits.map { num(it!!) }
        val deltas = deltaEdits.map { num(it!!) }
        // 도감 값 = 현재 능력치 - 도감 대비. 도감 대비가 비어 있고 만렙이면 시트 도감 사용.
        val atMax = level == MAX_LEVEL
        val base = List(4) { i ->
            val s = stats[i] ?: return@List null
            deltas[i]?.let { s - it } ?: pet?.maxS?.get(i)?.takeIf { atMax }
        }

        val warns = ArrayList<String>()
        lastParsed?.let { lp ->
            if (lp.pet != null && lp.nameScore < 0.99 && pet == lp.pet)
                warns += "이름을 '${lp.nameRaw}'(으)로 읽어 ${lp.pet.name}(으)로 맞췄습니다."
        }
        pet?.let { p -> PetDb.oddValues(p).takeIf { it.isNotEmpty() }?.let {
            warns += "도감 값 확인 필요: ${it.joinToString(", ")}"
        } }
        // 게임의 "도감 대비"는 그 레벨의 도감값 기준이고 시트 도감은 만렙(150) 기준이라, 150일 때만 비교한다
        if (pet != null && atMax) for (i in 0..3) {
            val b = base[i] ?: continue
            if (b != pet.maxS[i]) warns += "${STAT_NAMES[i]}: 화면 기준 도감값 $b ≠ 시트 도감 ${pet.maxS[i]} (숫자나 이름을 확인해 주세요)"
        }

        val screenGame = num(gameEdit)
        if (base.all { it != null && it > 0 }) {
            val cur = IntArray(4) { stats[it]!! }
            val b = IntArray(4) { base[it]!! }
            val t = Calculator.total(cur, b)
            // 화면의 인게임 값이 있으면 그걸 믿고, 실제 값은 그 인게임 값이 나올 수 있는 범위 안으로 맞춘다
            // (화면의 도감값은 반올림된 정수라 계산만으로는 인게임 값이 1% 어긋날 수 있음)
            val range = screenGame?.let { Calculator.realRangeForGame(cur, b, it) }
            val real = range?.let { t.realTotal.coerceIn(it.min, it.max) } ?: t.realTotal
            val realGrade = range?.let {
                val lo = Calculator.totalGrade(it.min); val hi = Calculator.totalGrade(it.max)
                if (lo == hi) lo else "$lo~$hi"
            } ?: t.realTotalGrade
            val game = screenGame?.toDouble() ?: t.gameTotal
            totalView.text = "${fmt(real, 2)}%"
            totalGradeView.text = gradeText(realGrade)
            totalGradeView.setTextColor(gradeColor(realGrade))
            totalSubView.text = "인게임 ${fmt(game, 0)}% ${Calculator.totalGrade(game)}" +
                (if (range != null && fmt(range.min, 2) != fmt(range.max, 2))
                    " · 실제 ${fmt(range.min, 2)}~${fmt(range.max, 2)}%" else "") +
                "\n" + (0..3).joinToString("  ") { "${STAT_NAMES[it]} ${fmt(t.percentile[it], 1)}" }
            if (screenGame != null && range == null)
                warns += "화면의 인게임 ${screenGame}%와 능력치·도감 대비 값이 맞지 않습니다 (숫자를 확인해 주세요)"
            scoreView.text = "총 능력치 점수 ${fmt(Calculator.abilityScore(cur), 1)}  ·  " +
                "기본(도감) ${fmt(Calculator.abilityScore(b), 1)}"
            val combat = (if (showCombat()) Calculator.combat(cur, b, growthAmp()) else null)
            if (combat != null) {
                combatView.text = "종 대비 전투 능력치 ${combatPct(combat.avg, combat.approx)}  (성장증폭 ${ampText(growthAmp())}%)  ⓘ"
                combatView.setTextColor(Color.parseColor(if (combat.avg >= 0) "#7FD4A0" else "#F28B82"))
                combatView.visibility = View.VISIBLE
            } else if (showCombat()) {
                // 숨기지 않고 왜 안 나오는지 알려 준다 (총능이 계산 범위 밖 등)
                combatView.text = "종 대비 전투 능력치: " + (Calculator.combatUnavailableReason(cur, b) ?: "계산할 수 없음")
                combatView.setTextColor(Color.parseColor("#9A9DA3"))
                combatView.visibility = View.VISIBLE
            } else { combatView.visibility = View.GONE; combatHelp.visibility = View.GONE }
            renderEnhance(cur, b, breaks, pet, level, real, realGrade)
        } else {
            totalView.text = "-"
            totalGradeView.text = ""
            totalSubView.text = "능력치와 (도감 대비)를 채워 주세요. 150레벨이고 도감에 있는 소환수면 도감 대비는 비워도 됩니다"
            scoreView.text = ""
            combatView.visibility = View.GONE; combatHelp.visibility = View.GONE
            enhanceTable.removeAllViews()
        }

        results.removeAllViews()
        when {
            pet == null -> results.addView(note("성장률 등급·총성 상세는 도감(${db.pets.size}종)에 있는 소환수만 계산됩니다. " +
                "아래 '도감에 추가/수정'으로 넣으면 정확하게 계산됩니다."))
            level == null || level < 2 -> results.addView(note("상세 계산에는 레벨(2 이상)이 필요합니다."))
            stats.any { it == null } -> {}
            else -> {
                // 등급 기준 도감값: 화면에서 읽은 값(현재 - 도감 대비)이 있으면 그걸 쓴다
                val b = if (base.all { it != null && it > 0 }) IntArray(4) { base[it]!! } else pet.maxS
                val cur = IntArray(4) { stats[it]!! }
                renderResult(pet, Calculator.compute(Calculator.Input(pet, level, cur, breaks, b)), screenGame, (if (showCombat()) Calculator.combat(cur, b, growthAmp()) else null))
            }
        }
        warnView.text = warns.joinToString("\n")
        warnView.visibility = if (warns.isEmpty()) View.GONE else View.VISIBLE
        renderCompare()
    }

    /**
     * 현재 강부터 최대 +5강까지 능력치·등급·총 성장률 (강화 1번당 1%, 게임 강화 화면 기준). 강화는 150레벨부터라 150일 때만 표시.
     * 능력치 등급: 능력치 ÷ 도감값. 도감값은 화면(현재 - 도감 대비)으로 알 수 있어 도감에 없는 소환수도 같은 방식.
     */
    private fun renderEnhance(cur: IntArray, base: IntArray, breaks: Int, pet: Pet?, level: Int?,
                              curReal: Double, curGrade: String) {
        enhanceTable.removeAllViews()
        if (level != MAX_LEVEL) {
            enhanceTable.addView(sectionTitle("강화별"))
            enhanceTable.addView(note("강화는 ${MAX_LEVEL}레벨부터라 강화 예상은 ${MAX_LEVEL}레벨일 때만 표시됩니다."))
            return
        }
        val worst = worstOn
        enhanceTable.addView(sectionTitle("강화별 (최대 +${MAX_BREAKS}강, " + (if (worst) "최저치)" else "예상)")))
        if (breaks < MAX_BREAKS) enhanceTable.addView(worstToggle(worst))
        enhanceTable.addView(row(listOf("강", "공", "방", "순", "체", if (showCombat()) "총점\n전투" else "총점", "성장률"), header = true))
        // 현재 강보다 아래(0강~): 강마다 도감값의 1% 를 빼면 된다 (위로 갈 때와 같은 정확도). 누르면 펼치고 접는다
        val lower = breaks > 0 && lowerOn
        if (breaks > 0) enhanceTable.addView(TextView(ui).apply {
            text = if (lower) "▼ 이전 강화 접기" else "▶ 이전 강화 보기"
            textSize = 12f
            setTextColor(Color.parseColor("#4FC3F7"))
            setPadding(0, dp(4), 0, dp(4))
            setOnClickListener { lowerOn = !lower; recompute() }
        })
        for (k in (if (lower) 0 else breaks)..maxOf(breaks, MAX_BREAKS)) {
            // 최저치는 위로 올릴 때만 (아래 강에 쓰면 반대로 '가장 높았을 경우'가 된다)
            val s = Calculator.statsAtBreaks(cur, base, breaks, k, worst && k > breaks)
            val t = Calculator.total(s, base)
            // 등급 = 능력치 ÷ 도감값 (도감에 없는 소환수도 화면의 도감값으로 똑같이 계산)
            val grades = Calculator.statGrades(s, base)
            val combat = (if (showCombat()) Calculator.combat(s, base, growthAmp()) else null)
            val cells = listOf(if (k == breaks) "${k}강\n현재" else "${k}강") +
                (0..3).map { i -> "${s[i]}\n${grades[i]}" } +
                (fmt(Calculator.abilityScore(s), 1) + (combat?.let { "\n" + combatPct(it.avg, it.approx) } ?: "")) +
                // 현재 강은 위의 총 성장률(화면 인게임 값으로 맞춘 값)과 같게
                if (k == breaks) "${fmt(curReal, 2)}%\n$curGrade" else "${fmt(t.realTotal, 2)}%\n${t.realTotalGrade}"
            // 현재 강은 굵게, 이전 강화는 연하게 (이후 강화는 그대로)
            val line = row(cells, small = true, bold = k == breaks).apply { if (k < breaks) alpha = 0.45f }
            enhanceTable.addView(line)
            // 줄을 누르면 그 강의 능력치별 전투가 아래에 펼쳐진다
            if (combat != null) {
                // 위 표의 공·방·순·체 칸에 맞춰 숫자만
                val detail = row(listOf("전투") + (0..3).map { combatPct(combat.pct[it], combat.approx) } + listOf("", ""), small = true)
                    .apply { visibility = View.GONE }
                line.setOnClickListener { detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
                enhanceTable.addView(detail)
            }
        }
        if (breaks >= MAX_BREAKS) enhanceTable.addView(note("이미 최대 강화(+${MAX_BREAKS}강)입니다."))
        else if (worst) enhanceTable.addView(note("최저치: 게임 속 소수점을 모두 낮게 잡은 값입니다. 실제 강화 결과는 이보다 낮게 나오지 않습니다."))
        else enhanceTable.addView(note("강화 수치는 게임과 1 정도 차이 날 수 있어요. (게임은 소수까지 계산하고 화면엔 정수만 보여서) 위 '최저치로 보기'를 켜면 가장 낮은 경우로 보여 줍니다."))
        if (showCombat()) enhanceTable.addView(note("전투 = 종 대비 전투 능력치: 같은 종 도감(S 100%) 개체보다 전투 능력치가 몇 % 높은지. 총 능력치 점수가 높을수록 크게 오릅니다. 줄을 누르면 능력치별 전투가 나옵니다."))
    }

    /** 강화별 표 위의 '최저치로 보기' 켜기/끄기 (새로 계산하면 다시 꺼짐) */
    private fun worstToggle(on: Boolean) = TextView(ui).apply {
        val blue = Color.parseColor("#4FC3F7")
        text = (if (on) "■ " else "□ ") + "최저치로 보기  " + (if (on) "켜짐" else "꺼짐")
        textSize = 13f
        setTextColor(if (on) blue else Color.parseColor("#C9CBD0"))
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), if (on) blue else Color.parseColor("#6A6D73"))
        }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(4) }
        setOnClickListener { worstOn = !on; recompute() }
    }

    /** 설정에서 끄면 종 대비 전투 능력치를 아예 보여 주지 않는다 */
    private fun showCombat() = AppPrefs.showCombat(this)

    /** 종 대비 전투 능력치 % 표시. 곡선을 확인한 범위 밖이면 앞에 ≈ */
    private fun combatPct(v: Double, approx: Boolean): String {
        val n = Math.round(v)
        return (if (approx) "≈" else "") + (if (n > 0) "+" else "") + "$n%"
    }

    /** 앱 첫 화면에서 넣은 내 성장증폭 (안 넣었으면 기본값) */
    private fun growthAmp() = AppPrefs.growthAmp(this)

    private fun ampText(a: Double) = AppPrefs.pctText(a)

    private fun num(e: EditText) = e.text.toString().replace(",", "").replace("+", "").trim().toIntOrNull()

    private fun note(t: String) = TextView(ui).apply {
        text = t
        textSize = 12f
        setTextColor(Color.parseColor("#9A9DA3"))
        setPadding(0, dp(6), 0, dp(4))
    }

    private fun renderResult(pet: Pet, r: Calculator.Result, screenGame: Int?, combat: Calculator.Combat?) {
        results.addView(sectionTitle("${pet.name} · ${pet.element}/${pet.type}"))
        results.addView(row(listOf("", "성장률", "등급", "분위") + (if (combat != null) listOf("전투") else emptyList()), header = true))
        for (i in 0..3) {
            results.addView(row(listOf(STAT_NAMES[i], fmt(r.growth[i], 2), r.statGrades[i], fmt(r.percentile[i], 1)) +
                (combat?.let { listOf(combatPct(it.pct[i], it.approx)) } ?: emptyList())))
        }

        results.addView(sectionTitle("총성 ${fmt(r.myTotal, 1)}"))
        results.addView(row(listOf("현 등급", r.curGrade, r.curGradeTotal?.let { fmt(it, 1) } ?: "-", "")))
        results.addView(row(listOf("다음성장", "${screenGame?.plus(1) ?: fmt(r.nextGrowthPct, 0)}%", r.nextGrowthTotal?.let { fmt(it, 1) } ?: "-",
            r.toNextGrowth?.let { "${fmt(it * 100, 1)}%" } ?: "-")))
        results.addView(row(listOf("다음등급", r.nextGrade, r.nextGradeTotal?.let { fmt(it, 1) } ?: "-",
            r.toNextGrade?.let { "${fmt(it * 100, 1)}%" } ?: "-")))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun updateSuggestions(exact: Pet?) {
        suggestBox.removeAllViews()
        val q = nameEdit.text.toString()
        val list = if (exact != null || q.isBlank()) emptyList() else db.suggest(q, limit = 4)
        suggestBox.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (list.isEmpty()) return
        suggestBox.addView(TextView(ui).apply {
            text = "혹시 이 소환수인가요? (눌러서 선택)"
            textSize = 11f
            setTextColor(Color.parseColor("#9A9DA3"))
        })
        for (pair in list.chunked(2)) {
            suggestBox.addView(LinearLayout(ui).apply {
                orientation = LinearLayout.HORIZONTAL
                for (p in pair) addView(TextView(ui).apply {
                    text = p.name
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    minHeight = dp(40) // 손가락으로 누르기 쉬운 크기
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    background = round(0xFF3A3D44.toInt(), 10)
                    // 누르는 동안 바깥 스크롤이 터치를 가져가지 않게
                    setOnTouchListener { v, ev ->
                        if (ev.action == MotionEvent.ACTION_DOWN) v.parent.requestDisallowInterceptTouchEvent(true)
                        false
                    }
                    setOnClickListener {
                        nameEdit.setText(p.name)
                        setPanelFocusable(false) // 키보드 닫기
                    }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) })
                if (pair.size == 1) addView(View(ui), LinearLayout.LayoutParams(0, 0, 1f))
            })
        }
    }

    // ---------------------------------------------------------------- overlay views

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        bubble = TextView(ui).apply {
            text = "계산"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#2B2D31"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F2B84B"))
                setStroke(dp(2), Color.parseColor("#2B2D31"))
            }
            elevation = dp(4).toFloat()
        }
        bubbleParams = WindowManager.LayoutParams(
            dp(56), dp(56), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(72)
            y = resources.displayMetrics.heightPixels / 3
        }
        // 짧게 누르면: 결과창이 떠 있으면 ✕(닫기), 닫혀 있으면 계산
        // 꾹 누르면: 아래에 쓰레기통이 나타나고, 거기에 끌어다 놓으면 레이어 종료
        bubble.setOnTouchListener(BubbleTouchListener(
            onTap = {
                when {
                    labOpen() -> showLab(false)
                    optOpen() -> showOpt(false)
                    panel.visibility == View.VISIBLE -> showPanel(false)
                    labMode() -> showLab(true)
                    else -> scan()
                }
            },
        ))
        wm.addView(bubble, bubbleParams)

        trash = TextView(ui).apply {
            text = "🗑\n끄기"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(TRASH_IDLE)
                setStroke(dp(2), Color.WHITE)
            }
            visibility = View.GONE
        }
        wm.addView(trash, WindowManager.LayoutParams(
            dp(72), dp(72), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(64)
        })

        buildPanel()
        wm.addView(panel, panelParams)
        bringBubbleToFront()
        updateBubble()
        // 앱에서 연구실 모드를 켜고 끄면 버튼 글자/색을 바로 바꾼다
        getSharedPreferences("settings", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
    }

    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "runeMode") main.post { if (running) { if (optOpen()) showOpt(false); if (panel.visibility == View.VISIBLE) showPanel(false); updateBubble() } }
        if (key == "labActive") main.post {
            if (!running) return@post
            if (labOpen()) showLab(false)
            if (optOpen()) showOpt(false)
            if (panel.visibility == View.VISIBLE) showPanel(false)
            updateBubble()
        }
    }

    /** 진행 중인 연구 세션이 있으면 버튼이 [연구] */
    /** 버튼(계산/연구/✕)은 창보다 항상 위에: 창이 버튼 자리를 덮어도 눌러서 닫을 수 있게 (창이 생긴 뒤 버튼을 다시 올린다) */
    private fun bringBubbleToFront() {
        runCatching { wm.removeViewImmediate(bubble); wm.addView(bubble, bubbleParams) }
    }

    private fun labMode() = AppPrefs.labActive(this) != null

    /** 누르고 있는 동안 창을 거의 투명하게 (뒤의 게임 화면 확인용) */
    @SuppressLint("ClickableViewAccessibility")
    private fun eyeButton(target: View) = TextView(ui).apply {
        text = "👁"
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = round(0xFF3A3D44.toInt(), 8)
        setPadding(dp(8), dp(2), dp(8), dp(2))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
        setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> target.alpha = 0.12f
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> target.alpha = 1f
            }
            true
        }
    }

    // ---------------------------------------------------------------- 연구실: 게임 위 기록 입력 창
    // 진행 중인 세션의 틀대로: 종류(분류) 고르기 → 변경 값(값/%) → 잰 값들 → [저장]. 아래 '기록 내역 ▼' 에서 수정/삭제.

    private var labPanel: LinearLayout? = null
    private var labParams: WindowManager.LayoutParams? = null
    private lateinit var labTitle: TextView
    private lateinit var labBody: LinearLayout
    /** 고른 분류 id (null = 기준, 변경 없음) */
    private var labGroup: String? = null
    private var labPct = false
    /** 고른 항목 id (분류에 항목이 있을 때) */
    private var labItem: String? = null
    /** 수정 중인 기록 id (null = 새 기록) */
    private var labEditId: String? = null
    private var labListOpen = false
    /** 아이템 끼기(+1)/빼기(-1), 이미 넣은 변화 줄, 이번만 */
    private val labActs = HashMap<String, Int>()
    /** 바뀐 값 입력 줄들 (분류·항목, 값, 값/%, 이번만) */
    private class LabRow(var gid: String?, var iid: String?, var pct: Boolean, var text: String, var once: Boolean)
    private val labRows = ArrayList<LabRow>()
    /** 다시 그려도 적던 잰 값·메모가 안 지워지게 */
    private val labDraft = HashMap<String, String>()
    /** 이 기록에서 바꿀 소환수 (null = 안 바꿈) */
    private var labPetDraft: JSONObject? = null
    // 시작 상태 입력 중인 값: 창을 닫거나 다시 그려도(앱이 다시 켜져도) [저장] 전까지 남게
    private fun startDraftPrefs() = getSharedPreferences("lab_start_draft", MODE_PRIVATE)
    private fun startDraftGet(sid: String, key: String): String? = startDraftPrefs().getString("$sid|$key", null)
    private fun startDraftSet(sid: String, key: String, v: String) = startDraftPrefs().edit().putString("$sid|$key", v).apply()
    private fun startDraftClear(sid: String) { val p = startDraftPrefs(); p.edit().apply { p.all.keys.filter { it.startsWith("$sid|") }.forEach { remove(it) } }.apply() }
    private var labOnce = false
    private var labLoadedEdit: String? = "-"

    private var labStartOpen = false

    private fun labOpen() = labPanel?.visibility == View.VISIBLE

    private fun showLab(show: Boolean) {
        if (show) {
            if (labPanel == null) buildLab()
            renderLab()
        }
        val v = labPanel ?: return
        val p = labParams ?: return
        v.visibility = if (show) View.VISIBLE else View.GONE
        // 글자를 입력하려면 창이 포커스를 받아야 한다. 닫으면 다시 게임에 돌려준다 (창 밖 터치는 늘 게임으로)
        p.flags = if (show) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(v, p) }
        if (!show) getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
        updateBubble()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildLab() {
        val dm = resources.displayMetrics
        val box = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            background = round(0xFF2B2D31.toInt(), 14)
            setPadding(dp(12), dp(8), dp(12), dp(10))
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            minOf(dm.widthPixels - dp(16), dp(420)), ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(40)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        val header = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        header.addView(eyeButton(box))
        labTitle = TextView(ui).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(LAB_ACC)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        header.addView(labTitle)
        header.addView(headerButton("접기") { showLab(false) })
        header.setOnTouchListener(DragListener(params, box, onTap = {}, onLongPress = {}))
        box.addView(header)

        val maxH = (dm.heightPixels * 0.66).toInt()
        val scroll = ScrollView(ui)
        labBody = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(labBody)
        box.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        // 기록 내역을 펼쳐 길어지면 화면의 66%까지만 늘리고 스크롤
        box.viewTreeObserver.addOnGlobalLayoutListener {
            val want = if (labBody.height > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
            if (scroll.layoutParams.height != want) scroll.layoutParams = scroll.layoutParams.apply { height = want }
        }
        labPanel = box; labParams = params
        wm.addView(box, params)
        bringBubbleToFront()
    }

    /** 항목(또는 항목 없는 분류)의 단위: flat / pct / both (안 정한 예전 것) */
    private fun labUnit(g: JSONObject?, it: JSONObject?): String = (it ?: g)?.optString("unit")?.takeIf { it == "flat" || it == "pct" } ?: if (g?.optString("id") == "crit") "pct" else "both"

    /** 새 분류·항목 단위 묻기 */
    private fun labAskUnit(onOk: (String) -> Unit) {
        overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("단위 (한 번 정하면 다시 안 골라요)")
            .setItems(arrayOf("값 (+1200 처럼)", "% (+4.38% 처럼)")) { _, w -> onOk(if (w == 1) "pct" else "flat") })
    }

    private fun labArr(o: JSONObject?, key: String): List<JSONObject> {
        val a = o?.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    /** 기록의 변화 목록 (예전 기록은 한 줄짜리) */
    private fun labChanges(r: JSONObject): List<JSONObject> {
        r.optJSONArray("changes")?.let { a -> return (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        val gid = r.optString("groupId").takeIf { !r.isNull("groupId") && it.isNotEmpty() } ?: return emptyList()
        if (r.optDouble("change", 0.0) == 0.0) return emptyList()
        return listOf(JSONObject().put("groupId", gid).put("itemId", r.opt("itemId") ?: JSONObject.NULL)
            .put("unit", r.optString("unit", "flat")).put("change", r.optDouble("change")))
    }

    private fun labChangeText(ch: JSONObject, groups: List<JSONObject>): String {
        val g = groups.firstOrNull { it.optString("id") == ch.optString("groupId") }
        val iid = ch.optString("itemId").takeIf { !ch.isNull("itemId") && it.isNotEmpty() }
        val item = iid?.let { id -> labArr(g, "items").firstOrNull { it.optString("id") == id }?.optString("name") ?: "(지운 항목)" }
        val c = ch.optDouble("change", 0.0)
        return "${g?.optString("name") ?: "(지운 분류)"}${item?.let { " $it" } ?: ""} ${if (c >= 0) "+" else ""}${LabData.fmt(c)}" +
            (if (ch.optString("unit") == "pct") "%" else "") + if (ch.optBoolean("once")) " (이번만)" else ""
    }

    /** 기록 한 줄 글자: "#3 스킬A 끼기, 룬 +5% → 데미지 12,345 (메모)" */
    private fun labRecText(no: Int, r: JSONObject, groups: List<JSONObject>, measures: List<JSONObject>, items: List<JSONObject>): String {
        val parts = ArrayList<String>()
        if (r.has("reset") || r.has("equipSet")) parts += "상태 다시 적기"
        for (a in labArr(r, "items")) parts += (items.firstOrNull { it.optString("id") == a.optString("itemId") }?.optString("name") ?: "(지운 아이템)") +
            if (a.optInt("act") > 0) " 끼기" else " 빼기"
        for (ch in labChanges(r)) if (!ch.has("from")) parts += labChangeText(ch, groups)
        val kind = parts.joinToString(", ").ifEmpty { "기준" }
        val vals = r.optJSONObject("values")
        val sm = r.optJSONObject("samples")
        val vs = measures.mapNotNull { m -> val id = m.optString("id")
            vals?.takeIf { it.has(id) }?.let { v ->
                val n = sm?.optJSONArray(id)?.length() ?: 0
                "${m.optString("name")} ${LabData.fmt(Math.round(v.optDouble(id) * 100) / 100.0)}" + if (n > 1) "(${n}회)" else ""
            } }
        val cv = r.optJSONObject("critValues")
        val cs = measures.mapNotNull { m -> val id = m.optString("id"); cv?.takeIf { it.has(id) }?.let { "${m.optString("name")}(치명) ${LabData.fmt(Math.round(it.optDouble(id) * 100) / 100.0)}" } }
        val note = r.optString("note")
        return "#$no $kind → ${(vs + cs).joinToString(" · ")}" + if (note.isNotBlank()) "  ($note)" else ""
    }

    /** 지금 낀 아이템 개수: 시작 때 낀 것 + 기록의 끼기/빼기 (상태 다시 적기는 덮어씀) */
    private fun labEquipNow(s: JSONObject, exclude: String?): HashMap<String, Int> {
        val e = HashMap<String, Int>()
        s.optJSONObject("equip0")?.let { o -> o.keys().forEach { key -> e[key] = o.optDouble(key).toInt() } }
        for (r in labArr(s, "records")) {
            if (r.optString("id") == exclude) continue
            r.optJSONObject("equipSet")?.let { o -> e.clear(); o.keys().forEach { key -> e[key] = o.optDouble(key).toInt() } }
            for (a in labArr(r, "items")) { val id = a.optString("itemId"); e[id] = maxOf(0, (e[id] ?: 0) + a.optInt("act")) }
        }
        return e
    }

    /** 아이템 끼기/빼기로 실제로 바뀌는 값들 (효과 중복 '하나만'이면 0↔1개일 때만) */
    private fun labItemChanges(it: JSONObject, act: Int, had: Int): List<JSONObject> {
        if (it.optString("dupEffect") == "one" && !((act > 0 && had == 0) || (act < 0 && had == 1))) return emptyList()
        return labArr(it, "effects").mapNotNull { e ->
            val p = e.optString("vid").split(":")
            val n = LabData.number(e.optString("val")) ?: return@mapNotNull null
            if (n == 0.0 || p.size < 4) return@mapNotNull null
            JSONObject().put("groupId", p[1]).put("itemId", if (p[2].isEmpty()) JSONObject.NULL else p[2])
                .put("unit", p[3]).put("change", n * act).put("from", it.optString("id"))
        }
    }

    private fun labChip(label: String, on: Boolean, onClick: () -> Unit) = TextView(ui).apply {
        text = label
        textSize = 13f
        setTextColor(if (on) Color.parseColor("#17181B") else Color.WHITE)
        typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = round(if (on) LAB_ACC else 0xFF3A3D44.toInt(), 16)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
        setOnClickListener { onClick() }
    }

    private fun labInput(hint: String, text: String = "") = EditText(ui).apply {
        this.hint = hint
        setText(text)
        textSize = 15f
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#777A80"))
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun labLabel(t: String) = TextView(ui).apply {
        text = t
        textSize = 11f
        setTextColor(Color.parseColor("#9A9DA3"))
        setPadding(0, dp(6), 0, 0)
    }

    private fun renderLab() {
        val o = LabData.load(this)
        val s = LabData.session(o, AppPrefs.labActive(this))
        labBody.removeAllViews()
        if (s == null) {
            labTitle.text = "연구실"
            labBody.addView(note("진행 중인 세션이 없습니다. 앱 > 메뉴 > 연구실에서 세션을 시작하세요."))
            return
        }
        val t = LabData.template(o, s.optString("templateId"))
        val groups = labArr(t, "groups")
        val measures = labArr(t, "measures")
        val recs = labArr(s, "records")
        val editing = labEditId?.let { id -> recs.firstOrNull { it.optString("id") == id } }
        if (labEditId != null && editing == null) labEditId = null
        if (labGroup != null && groups.none { it.optString("id") == labGroup }) labGroup = null
        val itemsOf = { gid: String? -> labArr(groups.firstOrNull { it.optString("id") == gid }, "items") }
        if (labItem != null && itemsOf(labGroup).none { it.optString("id") == labItem }) labItem = null
        if (labItem == null) labItem = itemsOf(labGroup).firstOrNull()?.optString("id")
        labTitle.text = "연구 · " + s.optString("name")
        if (t == null || measures.isEmpty()) {
            labBody.addView(note("이 세션의 틀이 없거나 '잴 것'이 비어 있습니다. 앱 > 메뉴 > 연구실에서 틀을 고쳐 주세요."))
            return
        }
        if (labLoadedEdit != labEditId) {
            labLoadedEdit = labEditId
            labRows.clear(); labActs.clear(); labDraft.clear(); labOnce = false; labGroup = null
            labPetDraft = editing?.optJSONObject("pet")
            if (editing != null && editing.has("amp")) labDraft["amp"] = LabData.fmt(editing.optDouble("amp")).replace(",", "")
            if (editing != null) {
                labChanges(editing).filter { !it.has("from") }.forEach { ch ->
                    labRows += LabRow(ch.optString("groupId"), ch.optString("itemId").takeIf { !ch.isNull("itemId") && it.isNotEmpty() },
                        ch.optString("unit") == "pct", LabData.fmt(ch.optDouble("change")).replace(",", ""), ch.optBoolean("once"))
                }
                labArr(editing, "items").forEach { labActs[it.optString("itemId")] = it.optInt("act") }
            }
        }
        if (editing != null) labBody.addView(note("#${recs.indexOf(editing) + 1} 기록 수정 중"))
        val tItems = labArr(t, "items")
        val tSlots = labArr(t, "slots")
        val eqNow = labEquipNow(s, editing?.optString("id"))

        // 아이템: 누를 때마다 끼기 → 빼기 → 안 함
        if (tItems.isNotEmpty() || tSlots.isNotEmpty()) {
            labBody.addView(labLabel("아이템 (누를 때마다 끼기 → 빼기 → 안 함)"))
            val slotIds = (tSlots.map { it.optString("id") } + tItems.map { it.optString("slotId") }).distinct()
            for (sid in slotIds) {
                val slotName = tSlots.firstOrNull { it.optString("id") == sid }?.optString("name") ?: "기타"
                val row = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(ui).apply { text = slotName; textSize = 11f; setTextColor(Color.parseColor("#9A9DA3")); setPadding(0, 0, dp(6), 0) })
                for (it in tItems.filter { x -> x.optString("slotId") == sid }) {
                    val id = it.optString("id"); val a = labActs[id] ?: 0; val had = eqNow[id] ?: 0
                    row.addView(labChip((if (a > 0) "+ " else if (a < 0) "− " else "") + it.optString("name") + if (had > 0) " (낌$had)" else "", a != 0) {
                        labActs[id] = if (a == 0) 1 else if (a > 0) -1 else 0
                        renderLab()
                    })
                }
                // 새 아이템 이름만 바로 추가 (효과는 앱 > 연구실 > 틀 > 아이템에서)
                row.addView(labChip("+ 아이템", false) {
                    labAskName("'$slotName' 칸에 아이템 추가 (이름)") { name ->
                        LabData.update(this@OverlayService) { d ->
                            val tt = LabData.template(d, t.optString("id")) ?: return@update
                            (tt.optJSONArray("items") ?: org.json.JSONArray().also { tt.put("items", it) }).put(JSONObject().put("id", LabData.newId())
                                .put("name", name).put("slotId", sid).put("owned", "1").put("dupMax", "1").put("dupEffect", "all")
                                .put("effects", org.json.JSONArray()).put("rules", org.json.JSONArray()))
                        }
                        toast("'$name' 추가. 효과는 앱 > 연구실 > 틀 > 아이템에서 적어 주세요.")
                        renderLab()
                    }
                })
                labBody.addView(android.widget.HorizontalScrollView(ui).apply { isHorizontalScrollBarEnabled = false; addView(row) })
            }
        }

        // 바뀐 값: 줄마다 [분류·항목 ▾] [값] [값|%] [이번만] [✕], [+ 줄]로 늘린다 (한 기록에 여러 개)
        val tid = t.optString("id")
        val gis = groups.flatMap { g -> labArr(g, "items").ifEmpty { listOf(null) }.map { it -> Triple(g.optString("id"), it?.optString("id"), g.optString("name") + (it?.let { " " + it.optString("name") } ?: "")) } }
        val giName = { r: LabRow -> gis.firstOrNull { it.first == r.gid && it.second == r.iid }?.third ?: "분류·항목 고르기" }
        val giUnit = { gid: String?, iid: String? -> val g = groups.firstOrNull { it.optString("id") == gid }; labUnit(g, iid?.let { id -> labArr(g, "items").firstOrNull { it.optString("id") == id } }) }
        // 단위가 정해진 항목은 값/% 를 자동으로
        for (r in labRows) when (giUnit(r.gid, r.iid)) { "pct" -> r.pct = true; "flat" -> r.pct = false }
        labBody.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(labLabel(if (tItems.isNotEmpty()) "바뀐 값 (아이템 말고 직접)" else "바뀐 값"), LinearLayout.LayoutParams(0, -2, 1f))
            // 룬 비교 화면(위 장착중 / 아래 바꿀 룬)을 띄우고 누르면 차이를 자동으로
            addView(headerButton("룬 읽기") { labReadRune(tid) })
        })
        labRows.toList().forEachIndexed { i, r ->
            val box = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(2), 0, dp(4)) }
            box.addView(LinearLayout(ui).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(labChip(giName(r) + " ▾", r.gid != null) {
                    // 분류·항목 고르기 (+ 새로 만들기)
                    labItemPicker(groups, gis, listOf("+ 새 분류", "+ 새 항목")) { w ->
                        when {
                            w < gis.size -> { r.gid = gis[w].first; r.iid = gis[w].second; renderLab() }
                            w == gis.size -> labAskName("분류 추가 (예: 스킬, 장비)") { name -> labAskUnit { unit ->
                                val nid = LabData.newId()
                                LabData.update(this@OverlayService) { d ->
                                    val tt = LabData.template(d, tid) ?: return@update
                                    (tt.optJSONArray("groups") ?: org.json.JSONArray().also { tt.put("groups", it) })
                                        .put(JSONObject().put("id", nid).put("name", name).put("unit", unit).put("items", org.json.JSONArray()))
                                }
                                r.gid = nid; r.iid = null; r.pct = unit == "pct"; renderLab()
                            } }
                            else -> overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("어느 분류에 항목을 넣을까요?")
                                .setItems(groups.map { it.optString("name") }.toTypedArray()) { _, gi ->
                                    val g = groups[gi].optString("id")
                                    labAskName("'${groups[gi].optString("name")}'에 항목 추가") { name -> labAskUnit { unit ->
                                        val nid = LabData.newId()
                                        LabData.update(this@OverlayService) { d ->
                                            val gg = LabData.template(d, tid)?.optJSONArray("groups")?.let { a2 -> (0 until a2.length()).mapNotNull { a2.optJSONObject(it) }.firstOrNull { it.optString("id") == g } } ?: return@update
                                            (gg.optJSONArray("items") ?: org.json.JSONArray().also { gg.put("items", it) }).put(JSONObject().put("id", nid).put("name", name).put("unit", unit))
                                        }
                                        r.gid = g; r.iid = nid; r.pct = unit == "pct"; renderLab()
                                    } }
                                })
                        }
                    }
                })
                addView(View(ui), LinearLayout.LayoutParams(0, 1, 1f))
                addView(headerButton("✕") { labRows.removeAt(i); renderLab() })
            })
            box.addView(LinearLayout(ui).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(labInput("+5, -100, 1.2만", r.text).apply {
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
                        override fun onTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
                        override fun afterTextChanged(e: Editable?) { r.text = e?.toString() ?: "" }
                    })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(headerButton("±") {
                    // 지금 낀 것 → 바꿀 것 (여러 개는 띄어서) = 변화
                    val e1 = labInput("지금 값들 (23213 23142)"); val e2 = labInput("바꿀 값들 (20000 23142)")
                    val res = TextView(ui).apply { textSize = 15f; setTextColor(LAB_ACC); setPadding(0, dp(8), 0, 0); text = "변화: -" }
                    fun sum(t: String) = t.trim().split(Regex("[\\s/+]+")).mapNotNull { LabData.number(it) }.sum()
                    fun d() = Math.round((sum(e2.text.toString()) - sum(e1.text.toString())) * 1e6) / 1e6
                    val w = object : TextWatcher {
                        override fun beforeTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
                        override fun onTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
                        override fun afterTextChanged(x: Editable?) { val v = d(); res.text = "변화: " + (if (v >= 0) "+" else "") + LabData.fmt(v) }
                    }
                    e1.addTextChangedListener(w); e2.addTextChangedListener(w)
                    overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("변화 계산")
                        .setView(LinearLayout(ui).apply {
                            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0)
                            addView(labLabel("지금 낀 것")); addView(e1, LinearLayout.LayoutParams(-1, -2))
                            addView(labLabel("바꿀 것")); addView(e2, LinearLayout.LayoutParams(-1, -2)); addView(res)
                        })
                        .setPositiveButton("적용") { _, _ -> r.text = LabData.fmt(d()).replace(",", ""); renderLab() }
                        .setNegativeButton("닫기", null))
                })
                if (giUnit(r.gid, r.iid) == "both") {
                    addView(labChip("값", !r.pct) { r.pct = false; renderLab() })
                    addView(labChip("%", r.pct) { r.pct = true; renderLab() })
                } else addView(TextView(ui).apply { text = if (r.pct) " % " else " 값 "; textSize = 13f; setTextColor(Color.parseColor("#9A9DA3")) })
                addView(android.widget.CheckBox(ui).apply {
                    text = "이번만"; textSize = 12f; setTextColor(Color.parseColor("#D0D2D6")); isChecked = r.once
                    setOnCheckedChangeListener { _, c -> r.once = c }
                })
            })
            labBody.addView(box)
        }
        labBody.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(headerButton("+ 줄") {
                val prev = labRows.lastOrNull()
                labRows += LabRow(prev?.gid, prev?.iid, prev?.pct ?: false, "", false)
                renderLab()
            }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
            if (labRows.isEmpty() && labActs.values.all { it == 0 }) addView(TextView(ui).apply {
                text = "  아무것도 안 바꿨으면 '기준' 기록"; textSize = 11f; setTextColor(Color.parseColor("#9A9DA3"))
            })
        })

        // 성장증폭(모든 소환수 공통 상태값) · 소환수 (연구실 실험과 같음: 바뀐 기록에만 남고, 그다음 기록들로 이어짐)
        val (petNow, ampNow) = labPetAmpAt(s, editing?.optString("id"))
        val petShow = labPetDraft ?: petNow
        labBody.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(labLabel("성장증폭 % (모든 소환수 공통)"))
            addView(labInput(ampNow?.let { "지금 " + LabData.fmt(it) } ?: "게임 상태창 값", labDraft["amp"] ?: ampNow?.let { LabData.fmt(it).replace(",", "") } ?: "").apply {
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
                    override fun onTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
                    override fun afterTextChanged(x: Editable?) { labDraft["amp"] = x?.toString() ?: "" }
                })
            }, LinearLayout.LayoutParams(dp(90), -2).apply { marginStart = dp(8) })
        })
        labBody.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ui).apply {
                text = "소환수: " + (petShow?.let { p -> listOf(p.optString("name"), listOf(p.optString("elem"), p.optString("type")).filter { it.isNotEmpty() }.joinToString("·"),
                        p.optJSONObject("sk")?.let { k -> "스킬 " + LabData.fmt(k.optDouble("pct")) + "% (직접)" } ?: "").filter { it.isNotEmpty() }.joinToString(" · ") } ?: "안 정함") +
                    if (labPetDraft != null) " (이 기록에서 바꿈)" else ""
                textSize = 12f; setTextColor(if (labPetDraft != null) LAB_ACC else Color.parseColor("#C9CBD0"))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (labPetDraft != null) addView(headerButton("안 바꿈") { labPetDraft = null; renderLab() })
            addView(headerButton(if (petNow == null) "정하기" else "바꾸기") { labPickPet() })
        })
        // 잰 값들 (2칸씩). 치명 모드: 치명타 X → 일반 / 가능 → 일반·치명 / 확정 → 치명
        val useCrit = t.optBoolean("useCrit", true)
        val slots = ArrayList<Triple<String, String, String>>()   // (m|c, 잴 것 id, 이름)
        for (m in measures) {
            // 공격 그룹마다: 치명 있는 게임이면 항상 비치명 / 치명 두 칸 (본 것만 적기)
            val md = if (useCrit) "can" else "none"
            val id = m.optString("id"); val nm = m.optString("name")
            if (md != "always") slots += Triple("m", id, nm + if (md == "can") " (비치명)" else "")
            if (md != "none") slots += Triple("c", id, "$nm (치명)")
        }
        val lastRec = recs.lastOrNull()
        val measureEdits = LinkedHashMap<String, EditText>()   // "m:id" / "c:id"
        var line: LinearLayout? = null
        slots.forEachIndexed { i, (kind, id, nm) ->
            val vKey = if (kind == "m") "values" else "critValues"; val sKey = if (kind == "m") "samples" else "critSamples"
            if (i % 2 == 0) { line = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL }; labBody.addView(line) }
            val prev = lastRec?.optJSONObject(vKey)?.takeIf { it.has(id) }?.let { "이전 " + LabData.fmt(Math.round(it.optDouble(id) * 100) / 100.0) } ?: nm
            // 여러 번 잰 값은 띄어서 (1234 1250 1241)
            val old = editing?.optJSONObject(sKey)?.optJSONArray(id)?.let { a -> (0 until a.length()).joinToString(" ") { LabData.fmt(a.optDouble(it)).replace(",", "") } }
                ?: editing?.optJSONObject(vKey)?.takeIf { it.has(id) }?.let { LabData.fmt(it.optDouble(id)).replace(",", "") } ?: ""
            val dk = "$kind:$id"
            val e = labInput(prev, labDraft[dk] ?: old).apply {
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
                    override fun onTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
                    override fun afterTextChanged(x: Editable?) { labDraft[dk] = x?.toString() ?: "" }
                })
            }
            measureEdits[dk] = e
            line!!.addView(LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, if (i % 2 == 0) dp(6) else 0, 0)
                addView(labLabel(nm))
                addView(e, LinearLayout.LayoutParams(-1, -2))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        if (slots.size % 2 == 1) line?.addView(View(ui), LinearLayout.LayoutParams(0, 1, 1f))
        labBody.addView(LinearLayout(ui).apply {
            gravity = Gravity.END
            // 1초 간격으로 몇 장 찍을지 (기본 3, 1~10)
            val sp = getSharedPreferences("lab_shot", MODE_PRIVATE)
            val n = sp.getInt("count", 3)
            addView(headerButton("−") { sp.edit().putInt("count", maxOf(1, n - 1)).apply(); renderLab() })
            addView(TextView(ui).apply { text = "${n}장"; textSize = 13f; setTextColor(Color.WHITE); setPadding(dp(4), 0, dp(4), 0) })
            addView(headerButton("+") { sp.edit().putInt("count", minOf(10, n + 1)).apply(); renderLab() })
            addView(headerButton("데미지 찍기 (1초 간격)") { labShootDamage(measures, useCrit) })
        })
        labBody.addView(TextView(ui).apply {
            text = "한 방 데미지. 치명 터진 건 치명 칸에, 안 터진 건 비치명 칸에. 흔들리면 여러 번 재서 띄어 적기 (1234 1250). 1억210만처럼 줄여 보이면 그대로 적기 (범위로 계산)."
            textSize = 11f
            setTextColor(Color.parseColor("#9A9DA3"))
            setPadding(0, dp(4), 0, 0)
        })
        val noteEdit = labInput("메모 (선택)", labDraft["note"] ?: editing?.optString("note") ?: "").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
                override fun onTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
                override fun afterTextChanged(x: Editable?) { labDraft["note"] = x?.toString() ?: "" }
            })
        }
        labBody.addView(noteEdit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // 저장
        val btnRow = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), 0, 0) }
        if (editing != null) btnRow.addView(headerButton("취소") { labEditId = null; renderLab() })
        else if (labRows.isNotEmpty() || labActs.values.any { it != 0 } || labDraft.values.any { it.isNotBlank() })
            btnRow.addView(headerButton("비우기") { labRows.clear(); labActs.clear(); labDraft.clear(); renderLab() })
        btnRow.addView(TextView(ui).apply {
            text = if (editing != null) "수정 저장" else "저장"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#17181B"))
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = round(LAB_ACC, 10)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) }
            setOnClickListener {
                val changes = org.json.JSONArray()
                for (r in labRows) {
                    if (r.text.isBlank()) continue
                    if (r.gid == null) { toast("줄마다 분류·항목을 골라 주세요."); return@setOnClickListener }
                    val ch = LabData.number(r.text) ?: run { toast("바뀐 값에 숫자를 적어 주세요: ${r.text}"); return@setOnClickListener }
                    if (ch != 0.0) changes.put(JSONObject().put("groupId", r.gid).put("itemId", r.iid ?: JSONObject.NULL)
                        .put("unit", if (r.pct) "pct" else "flat").put("change", ch).put("once", r.once))
                }
                val acts = org.json.JSONArray()
                for ((id, a) in labActs) {
                    if (a == 0) continue
                    val it = tItems.firstOrNull { x -> x.optString("id") == id } ?: continue
                    val had = eqNow[id] ?: 0
                    if (a < 0 && had <= 0) { toast("'${it.optString("name")}'은(는) 지금 안 끼고 있어서 뺄 수 없어요."); return@setOnClickListener }
                    acts.put(JSONObject().put("itemId", id).put("act", a))
                    labItemChanges(it, a, had).forEach { c -> changes.put(c) }
                }
                val first = (0 until changes.length()).map { changes.getJSONObject(it) }.let { l -> l.firstOrNull { !it.optBoolean("once") } ?: l.firstOrNull() }
                val values = JSONObject(); val samples = JSONObject(); val critValues = JSONObject(); val critSamples = JSONObject()
                measureEdits.forEach { (dk, e) ->
                    val crit = dk.startsWith("c:"); val id = dk.substring(2)
                    // 1억210만처럼 뒷자리가 숨겨진 값은 범위 양 끝을 같이 넣음 (평균 = 가운데, 흔들림 = 범위)
                    // '1억 4688만'처럼 띄어 쓴 큰 단위는 한 숫자로 붙임
                    val toks = e.text.toString().trim().split(Regex("[\\s/]+")).filter { it.isNotEmpty() }.fold(mutableListOf<String>()) { o, t ->
                        if (o.isNotEmpty() && Regex("[억조경]$").containsMatchIn(o.last()) && t[0].isDigit() && !Regex("[억조경]").containsMatchIn(t)) o[o.size - 1] = o.last() + t else o += t; o }
                    val a = toks.flatMap { tok ->
                        val rn = RuneParser.num(tok)
                        if (rn != null && !rn.exact && !rn.pct) listOf(rn.lo, rn.hi) else listOfNotNull(LabData.number(tok)) }
                    if (a.isNotEmpty()) {
                        (if (crit) critValues else values).put(id, a.average())
                        if (a.size > 1) (if (crit) critSamples else samples).put(id, org.json.JSONArray(a))
                    }
                }
                if (values.length() == 0 && critValues.length() == 0) { toast("잰 값을 하나 이상 적어 주세요."); return@setOnClickListener }
                val ampText = labDraft["amp"]?.trim().orEmpty()
                val ampNew = if (ampText.isEmpty()) null else (LabData.number(ampText) ?: run { toast("성장증폭에 숫자를 적어 주세요."); return@setOnClickListener })
                val sid = s.optString("id")
                val rid = editing?.optString("id") ?: LabData.newId()
                val rec = JSONObject()
                    .put("id", rid)
                    .put("time", editing?.optLong("time") ?: System.currentTimeMillis())
                    .put("changes", changes)
                    .put("items", acts)
                    .put("groupId", first?.opt("groupId") ?: JSONObject.NULL)
                    .put("itemId", first?.opt("itemId") ?: JSONObject.NULL)
                    .put("change", first?.optDouble("change") ?: 0.0)
                    .put("unit", first?.optString("unit") ?: "flat")
                    .put("values", values)
                    .put("samples", samples)
                    .put("critValues", critValues)
                    .put("critSamples", critSamples)
                    .put("skip", editing?.optBoolean("skip") ?: false)
                    .put("note", noteEdit.text.toString().trim())
                labPetDraft?.let { p -> if (p.toString() != petNow?.toString()) rec.put("pet", p) }
                if (ampNew != null && ampNew != ampNow) rec.put("amp", ampNew)
                var no = 0
                LabData.update(this@OverlayService) { d ->
                    val ss = LabData.session(d, sid) ?: return@update
                    val arr = ss.optJSONArray("records") ?: org.json.JSONArray().also { ss.put("records", it) }
                    val idx = (0 until arr.length()).firstOrNull { arr.optJSONObject(it)?.optString("id") == rid }
                    if (idx != null) { arr.put(idx, rec); no = idx + 1 } else { arr.put(rec); no = arr.length() }
                }
                toast(if (editing != null) "#$no 수정했습니다." else "#$no 저장했습니다.")
                labEditId = null; labLoadedEdit = "-"; labRows.clear(); labActs.clear(); labDraft.clear(); labPetDraft = null
                renderLab()
            }
        })
        labBody.addView(btnRow)

        // 시작 상태: 세션을 시작할 때 이미 붙어 있던 양 (분류·항목 × 값/%)
        val start = s.optJSONObject("start") ?: JSONObject()
        labBody.addView(TextView(ui).apply {
            text = "시작 상태 ${if (start.length() > 0) "(${start.length()}칸 적음) " else "(안 적음) "}" + if (labStartOpen) "▲" else "▼"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { labStartOpen = !labStartOpen; renderLab() }
        })
        if (labStartOpen) {
            labBody.addView(note("처음에 이미 붙어 있던 값을 적어요 (상태창 등). 모르면 비워 두세요. 적어 두면 공식이 더 정확해져요."))
            val edits = LinkedHashMap<String, EditText>()
            // 검색: 줄을 숨기기만 함 (다시 그리지 않아 입력 중 칸이 유지됨)
            val stRows = ArrayList<Triple<View, String, View>>()   // (줄, 검색 글자, 분류 제목)
            labBody.addView(labInput("항목 검색 (예: 화염, 피해)").apply {
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(x: Editable?) {
                        val q = x?.toString().orEmpty().replace(" ", "")
                        val shown = HashSet<View>()
                        for ((row, key, head) in stRows) { val on = q.isEmpty() || key.contains(q); row.visibility = if (on) View.VISIBLE else View.GONE; if (on) shown += head }
                        stRows.map { it.third }.distinct().forEach { h -> h.visibility = if (h in shown) View.VISIBLE else View.GONE }
                    }
                })
            }, LinearLayout.LayoutParams(-1, -2))
            for (g in groups) {
                // 테마: 치명 확률은 최적화에서, 기본 치명 피해는 소환수마다 달라 못 적음 → 치명 분류는 안 보임
                if (t.optBoolean("tm2") && g.optString("id") == "crit") continue
                val its = labArr(g, "items").ifEmpty { listOf(null) }
                val head = TextView(ui).apply { text = g.optString("name"); textSize = 12f; setTextColor(LAB_ACC); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(8), 0, dp(2)) }
                labBody.addView(head)
                for (it in its) {
                    if (g.optString("id") == "crit" && it?.optString("id") == "rate") continue   // 치명 확률은 최적화에서
                    val key = g.optString("id") + ":" + (it?.optString("id") ?: "")
                    val label = if (g.optString("id") == "crit" && it?.optString("id") == "dmg") "치명 피해 (1.5배=150)"
                        else it?.optString("name") ?: g.optString("name")
                    fun cur(u: String) = startDraftGet(s.optString("id"), "$key:$u")
                        ?: if (start.has("$key:$u")) LabData.fmt(start.optDouble("$key:$u")).replace(",", "") else ""
                    val unit = labUnit(g, it)
                    val ef = labInput("값", cur("flat")).apply { if (unit == "pct") { isEnabled = false; hint = "-" } }
                    val ep = labInput("%", cur("pct")).apply { if (unit == "flat") { isEnabled = false; hint = "-" } }
                    for ((u, e) in listOf("flat" to ef, "pct" to ep)) e.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(x: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(x: Editable?) { startDraftSet(s.optString("id"), "$key:$u", x?.toString() ?: "") }
                    })
                    edits["$key:flat"] = ef; edits["$key:pct"] = ep
                    labBody.addView(LinearLayout(ui).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(ui).apply {
                            text = label; textSize = 12f; setTextColor(Color.parseColor("#D0D2D6"))
                            layoutParams = LinearLayout.LayoutParams(0, -2, 1.2f)
                        })
                        addView(ef, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(ep, LinearLayout.LayoutParams(0, -2, 0.7f))
                    }.also { row -> stRows += Triple(row, (g.optString("name") + label).replace(" ", ""), head) })
                }
            }
            labBody.addView(LinearLayout(ui).apply {
                gravity = Gravity.END
                addView(headerButton("시작 상태 저장") {
                    val o = JSONObject()
                    edits.forEach { (key, e) -> LabData.number(e.text.toString())?.let { o.put(key, it) } }
                    val sid = s.optString("id")
                    LabData.update(this@OverlayService) { d -> LabData.session(d, sid)?.put("start", o) }
                    startDraftClear(sid)
                    toast("시작 상태를 저장했습니다.")
                    labStartOpen = false
                    renderLab()
                })
            })
        }

        // 기록 내역
        labBody.addView(TextView(ui).apply {
            text = "기록 내역 ${recs.size}개 " + if (labListOpen) "▲" else "▼"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { labListOpen = !labListOpen; renderLab() }
        })
        if (labListOpen) {
            if (recs.isEmpty()) labBody.addView(note("아직 기록이 없습니다."))
            recs.withIndex().reversed().forEach { (i, r) ->
                labBody.addView(LinearLayout(ui).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                    addView(TextView(ui).apply {
                        text = labRecText(i + 1, r, groups, measures, labArr(t, "items"))
                        textSize = 12f
                        setTextColor(if (r.optString("id") == labEditId) LAB_ACC else Color.parseColor("#D0D2D6"))
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    })
                    addView(headerButton("수정") {
                        labEditId = r.optString("id")
                        labGroup = r.optString("groupId").takeIf { !r.isNull("groupId") && it.isNotEmpty() }
                        labItem = r.optString("itemId").takeIf { !r.isNull("itemId") && it.isNotEmpty() }
                        labPct = r.optString("unit") == "pct"
                        renderLab()
                    })
                    addView(headerButton("삭제") {
                        overlayDialog(android.app.AlertDialog.Builder(ui)
                            .setMessage("#${i + 1} 기록을 지울까요?")
                            .setPositiveButton("지우기") { _, _ ->
                                val rid = r.optString("id")
                                LabData.update(this@OverlayService) { d ->
                                    val arr = LabData.session(d, s.optString("id"))?.optJSONArray("records") ?: return@update
                                    (0 until arr.length()).firstOrNull { arr.optJSONObject(it)?.optString("id") == rid }?.let { arr.remove(it) }
                                }
                                if (labEditId == rid) labEditId = null
                                renderLab()
                            }
                            .setNegativeButton("취소", null))
                    })
                })
            }
        }
    }

    /** 이름 하나 묻기 (분류/항목 바로 추가) */
    // ---------------------------------------------------------------- TM2 룬 도우미: 룬 비교 화면 읽기 · 데미지 찍기

    /** 레이어를 숨기고 n장 찍기 (간격 ms). 다 찍으면 레이어를 다시 보이고 cb */
    private fun labCapture(n: Int, gapMs: Long, cb: (List<Bitmap>) -> Unit) {
        val shooter = ScreenshotService.instance
        if (shooter == null) {
            toast("접근성 설정에서 '${getString(R.string.app_name)}'을(를) 켜 주세요.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (busy) return
        busy = true
        val labWas = labOpen()
        labPanel?.visibility = View.INVISIBLE
        bubble.visibility = View.INVISIBLE
        val out = ArrayList<Bitmap>()
        // 여러 장 찍는 동안: 위쪽에 "찍는 중 k/n · 눌러서 취소" (찍는 순간에는 숨겨서 사진에 안 나오게)
        var cancelled = false
        val ind: TextView? = if (n > 1) TextView(ui).apply {
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#17181B"))
            setPadding(dp(14), dp(8), dp(14), dp(8)); background = round(0xFF80CBC4.toInt(), 18)
            setOnClickListener { cancelled = true; text = "취소하는 중…" }
        } else null
        ind?.let { v ->
            runCatching { wm.addView(v, WindowManager.LayoutParams(-2, -2, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT)
                .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(24) }) }
            v.visibility = View.INVISIBLE
        }
        fun done(ok: Boolean = true) {
            ind?.let { v -> runCatching { wm.removeView(v) } }
            bubble.visibility = View.VISIBLE
            if (labWas) labPanel?.visibility = View.VISIBLE
            busy = false
            // 중간에 멈춰도 그때까지 찍은 사진으로 창을 띄움 (한 장도 없으면 그냥 끝)
            if (ok) cb(out) else if (out.isNotEmpty()) { toast("${out.size}장까지 찍고 멈췄습니다."); cb(out) } else toast("찍기를 취소했습니다.")
        }
        fun shot(k: Int) {
            if (cancelled) return done(false)
            ind?.visibility = View.INVISIBLE
            main.postDelayed({
                if (cancelled) return@postDelayed done(false)
                shooter.capture { bmp, err ->
                    if (bmp != null) out += bmp
                    else if (err == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) { toast("게임이 화면 캡처를 막고 있습니다."); done(); return@capture }
                    if (cancelled) return@capture done(false)
                    if (k + 1 < n) {
                        ind?.apply { text = "📸 찍는 중 ${k + 1}/$n · 눌러서 취소"; visibility = View.VISIBLE }
                        main.postDelayed({ shot(k + 1) }, gapMs - HIDE_DELAY_MS)
                    } else done()
                }
            }, HIDE_DELAY_MS)
        }
        main.post { shot(0) }
    }

    private fun ocrLines(bmp: Bitmap, ok: (List<OcrLine>) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { text ->
                ok(text.textBlocks.flatMap { it.lines }.mapNotNull { l -> val r = l.boundingBox ?: return@mapNotNull null; OcrLine(l.text, r.left, r.top, r.right, r.bottom) })
            }
            .addOnFailureListener { e -> toast("글자 인식 실패: ${e.message}") }
    }

    /** 룬 수치 표 (등급|옵션 → 레벨 → 정확한 값) 로 만 단위 표시값 추정. 표가 모자라면 범위 가운데 */
    private fun runeValue(table: JSONObject?, grade: String?, label: String, level: Int?, n: RuneNum): Pair<Double, Boolean> {
        if (n.exact) return n.lo to false
        val pts = table?.optJSONObject("$grade|$label")?.let { o -> o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it.toDouble() to o.optDouble(k) } }.toList() } ?: emptyList()
        if (level != null && pts.map { it.first }.distinct().size >= 2) {
            // 레벨 → 값 직선 맞추기 (최소제곱), 화면 범위 안으로
            val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
            val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
            if (sxx > 0) {
                val b = pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
                val v = Math.round(my + b * (level - mx)).toDouble().coerceIn(n.lo, n.hi)
                return v to true
            }
        }
        return Math.floor(n.mid) to true
    }

    private fun labReadRune(tid: String) {
        labCapture(1, 0) { bs ->
            val bmp = bs.firstOrNull() ?: run { toast("화면을 찍지 못했습니다. 잠시 후 다시 눌러 주세요."); return@labCapture }
            ocrLines(bmp) { lines ->
                bmp.recycle()
                val runes = RuneParser.parse(lines)
                if (runes.size < 2 || runes.any { it.atk == null && it.subs.isEmpty() }) {
                    // 못 읽으면 읽힌 글자를 그대로 보여 줌 (이 화면을 찍어 보내면 고치기 쉬움)
                    val nl = "\n"
                    val txt = lines.sortedBy { it.top }.joinToString(nl) { "${it.text}  [${it.left},${it.top}]" }
                    overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("룬 비교 화면을 못 읽었어요 (상자 ${runes.size}개)")
                        .setMessage("위 장착중 / 아래 바꿀 룬이 같이 보이는 화면에서 눌러 주세요.$nl${nl}읽힌 글자:$nl" + txt.take(3000))
                        .setPositiveButton("닫기", null))
                    return@ocrLines
                }
                RuneLog.add(this, runes.take(2)) // 임시: 수치 공식 찾기용 (정식 배포 전 삭제)
                labApplyRune(tid, runes[0], runes[1])
            }
        }
    }

    /** 옛 룬 → 새 룬 차이를 바뀐 값 줄로. 처음 보는 옵션 이름은 어느 값인지 한 번 물어보고 기억 (게임 설정 runeMap) */
    private fun labApplyRune(tid: String, a: RuneInfo, b: RuneInfo) {
        val o = LabData.load(this)
        val t = LabData.template(o, tid) ?: return
        val map = t.optJSONObject("runeMap") ?: JSONObject()
        val table = t.optJSONObject("runeTable")
        val labels = (listOf("공격력") + a.subs.map { it.first } + b.subs.map { it.first }).distinct()
        val unknown = labels.filter { !map.has(it) }
        if (unknown.isNotEmpty()) {
            val groups = labArr(t, "groups")
            val gis = groups.flatMap { g -> labArr(g, "items").ifEmpty { listOf(null) }.map { it -> Triple(g.optString("id"), it?.optString("id"), g.optString("name") + (it?.let { " " + it.optString("name") } ?: "")) } }
            val lab = unknown.first()
            val isPct = (b.subs + a.subs).firstOrNull { it.first == lab }?.second?.pct ?: false
            val gu = { gid: String, iid: String? -> val g = groups.firstOrNull { it.optString("id") == gid }; labUnit(g, iid?.let { id -> labArr(g, "items").firstOrNull { it.optString("id") == id } }) }
            val ok = gis.filter { val u = gu(it.first, it.second); u == "both" || u == (if (isPct) "pct" else "flat") }
            val names = ok.map { it.third + if (isPct) " %" else "" } + listOf("계산 안 함 (참고만)")
            overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("룬 '$lab' 은(는) 어느 값인가요? (한 번만 물어봐요)")
                .setItems(names.toTypedArray()) { _, w ->
                    val v = if (w < ok.size) "g:${ok[w].first}:${ok[w].second ?: ""}:${if (isPct) "pct" else "flat"}" else "skip"
                    LabData.update(this@OverlayService) { d -> val tt = LabData.template(d, tid) ?: return@update
                        (tt.optJSONObject("runeMap") ?: JSONObject().also { tt.put("runeMap", it) }).put(lab, v) }
                    labApplyRune(tid, a, b)
                }.setNegativeButton("취소", null))
            return
        }
        // 정확히 읽힌 값은 수치 표에 쌓기 (나중에 만 단위 복원)
        LabData.update(this) { d ->
            val tt = LabData.template(d, tid) ?: return@update
            val tb = tt.optJSONObject("runeTable") ?: JSONObject().also { tt.put("runeTable", it) }
            for (r in listOf(a, b)) {
                if (r.grade == null || r.level == null) continue
                for ((lab, n) in listOfNotNull(r.atk?.let { "공격력" to it }) + r.subs) if (n.exact)
                    (tb.optJSONObject("${r.grade}|$lab") ?: JSONObject().also { tb.put("${r.grade}|$lab", it) }).put(r.level.toString(), n.lo)
            }
        }
        data class D(val label: String, val old: Double, val new: Double, val est: Boolean, val vid: String?, val pct: Boolean)
        val rows = labels.map { lab ->
            val na = if (lab == "공격력") a.atk else a.subs.firstOrNull { it.first == lab }?.second
            val nb = if (lab == "공격력") b.atk else b.subs.firstOrNull { it.first == lab }?.second
            val va = na?.let { runeValue(table, a.grade, lab, a.level, it) }; val vb = nb?.let { runeValue(table, b.grade, lab, b.level, it) }
            val vid = map.optString(lab).takeIf { it.startsWith("g:") }
            D(lab, va?.first ?: 0.0, vb?.first ?: 0.0, (va?.second ?: false) || (vb?.second ?: false), vid, (na ?: nb)?.pct ?: false)
        }
        val fmtV = { v: Double, p: Boolean -> LabData.fmt(Math.round(v * 100) / 100.0) + if (p) "%" else "" }
        val sb = StringBuilder("${a.name ?: "?"} [${a.grade ?: "?"}] Lv.${a.level ?: "?"} → [${b.grade ?: "?"}] Lv.${b.level ?: "?"}\n\n")
        for (r in rows) {
            val d = r.new - r.old
            sb.append("${r.label}: ${fmtV(r.old, r.pct)} → ${fmtV(r.new, r.pct)}  (${if (d >= 0) "+" else ""}${fmtV(d, r.pct)})")
            if (r.est) sb.append(" ≈추정")
            if (r.vid == null) sb.append("  · 계산 안 함")
            sb.append("\n")
        }
        for ((lab, nb) in b.others) { val na = a.others.firstOrNull { it.first == lab }?.second
            if (na != null) sb.append("$lab: ${na.text} → ${nb.text}  · 참고 (계산 안 함)\n") }
        if (rows.any { it.est }) sb.append("\n≈추정: 1.7만처럼 줄여 보인 값. 같은 등급의 정확한 값이 쌓이면 더 정확해져요.")
        fun apply(once: Boolean) {
            val sum = LinkedHashMap<String, Double>()
            for (r in rows) if (r.vid != null && r.new != r.old) sum[r.vid] = (sum[r.vid] ?: 0.0) + (r.new - r.old)
            if (sum.isEmpty()) { toast("계산에 쓰는 값은 안 바뀌어요."); return }
            for ((vid, d) in sum) {
                val p = vid.split(":")
                labRows += LabRow(p[1], p[2].ifEmpty { null }, p[3] == "pct", LabData.fmt(Math.round(d * 100) / 100.0).replace(",", ""), once)
            }
            renderLab()
            toast("바뀐 값 ${sum.size}줄 넣었어요. 이제 [데미지 찍기]로 데미지를 넣고 저장하세요.")
        }
        overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("룬 바꾸기").setMessage(sb.toString())
            .setPositiveButton("영구 적용") { _, _ -> apply(false) }
            .setNeutralButton("이번만") { _, _ -> apply(true) }
            .setNegativeButton("취소", null))
    }

    /** 1초 간격 3장: 버프(캐릭터 위)까지 보이게 넓게 잘라 보여 주고, 읽은 데미지 숫자를 후보로 (빨강 = 치명) */
    private fun labShootDamage(measures: List<JSONObject>, useCrit: Boolean) {
        labCapture(getSharedPreferences("lab_shot", MODE_PRIVATE).getInt("count", 3).coerceIn(1, 10), 1100) { bs ->
            if (bs.isEmpty()) { toast("화면을 찍지 못했습니다. 다시 눌러 주세요."); return@labCapture }
            val crops = bs.map { full ->
                val y0 = (full.height * 0.12).toInt(); val y1 = (full.height * 0.58).toInt()
                val c = Bitmap.createBitmap(full, 0, y0, full.width, y1 - y0)
                val small = Bitmap.createScaledBitmap(full, full.width / 2, full.height / 2, true)
                full.recycle()
                Triple(c, small, null)
            }
            val box = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), 0) }
            // 넣을 칸들: 공격 그룹마다 비치명 / 치명. 칸을 눌러 고르고 → 아래 추천 숫자를 누르면 그 칸에 들어감. 직접 적어도 됨
            val fields = LinkedHashMap<String, EditText>()
            var sel: String? = null
            val selInfo = TextView(ui).apply { textSize = 12f; setTextColor(LAB_ACC) }
            fun hl() { fields.forEach { (k, e) -> e.background = round(if (k == sel) 0xFF4A3F66.toInt() else 0xFF3A3D44.toInt(), 8) }
                selInfo.text = "고른 칸: " + (sel?.let { k -> fields[k]?.tag as? String } ?: "-") }
            box.addView(labLabel("넣을 칸을 누르고 → 사진 아래 숫자를 누르거나 직접 적기. [확인]을 눌러야 기록 칸에 들어가요."))
            for (m in measures) for (crit in if (useCrit) listOf(false, true) else listOf(false)) {
                val dk = (if (crit) "c:" else "m:") + m.optString("id")
                val label = m.optString("name") + if (useCrit) (if (crit) " (치명)" else " (비치명)") else ""
                val e = labInput(label, labDraft[dk] ?: "").apply {
                    tag = label
                    setOnFocusChangeListener { _, f -> if (f) { sel = dk; hl() } }
                    setOnClickListener { sel = dk; hl() }
                }
                fields[dk] = e
                box.addView(LinearLayout(ui).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2))
                    addView(TextView(ui).apply { text = label; textSize = 12f; setTextColor(Color.parseColor("#D0D2D6")); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                    addView(e, LinearLayout.LayoutParams(0, -2, 1.3f))
                })
            }
            sel = fields.keys.firstOrNull(); hl()
            box.addView(selInfo)
            box.addView(labLabel("사진을 누르면 전체 화면. 버프가 걸린 장은 쓰지 마세요. 빨간 숫자 = 치명으로 보임 (확인하고 고르기)."))
            val imgs = ArrayList<android.widget.ImageView>()
            box.addView(LinearLayout(ui).apply { gravity = Gravity.END
                addView(headerButton("사진 모두 숨기기/보이기") { val show = imgs.all { it.visibility != View.VISIBLE }; imgs.forEach { it.visibility = if (show) View.VISIBLE else View.GONE } }) })
            crops.forEachIndexed { k, (crop, small, _) ->
                val img = android.widget.ImageView(ui).apply {
                    setImageBitmap(crop); adjustViewBounds = true
                    setOnClickListener { overlayDialog(android.app.AlertDialog.Builder(ui).setView(android.widget.ImageView(ui).apply { setImageBitmap(small); adjustViewBounds = true }).setPositiveButton("닫기", null)) }
                }
                imgs += img
                box.addView(LinearLayout(ui).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(labLabel("${k + 1}번째 (${k}초)"), LinearLayout.LayoutParams(0, -2, 1f))
                    // 공간 차지하는 사진은 숨기기 (추천 숫자는 그대로)
                    addView(headerButton("숨기기") { img.visibility = if (img.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
                })
                box.addView(img, LinearLayout.LayoutParams(-1, -2))
                val chips = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL }
                box.addView(android.widget.HorizontalScrollView(ui).apply { addView(chips) })
                ocrLines(crop) { lines ->
                    val found = lines.mapNotNull { l ->
                        val n = RuneParser.num(l.text) ?: return@mapNotNull null
                        if (n.pct || n.lo < 1000 || !l.text.any { it.isDigit() }) return@mapNotNull null
                        val crit = colorSign(crop, intArrayOf(l.left, l.top, l.right, l.bottom)) < 0
                        Triple(l.text.filter { it.isDigit() || it in "만억조경." }, n, crit)
                    }.distinctBy { it.first }
                    if (found.isEmpty()) chips.addView(TextView(ui).apply { text = "숫자를 못 읽었어요 — 사진 보고 직접 적기"; textSize = 12f; setTextColor(Color.parseColor("#9A9DA3")) })
                    for ((txt, n, crit) in found) chips.addView(labChip((if (crit && useCrit) "🔴 " else "") + txt + if (!n.exact) " (범위)" else "", false) {
                        val e = sel?.let { fields[it] } ?: run { toast("넣을 칸을 먼저 누르세요."); return@labChip }
                        e.setText(txt); e.setSelection(e.text.length)
                    })
                }
            }
            overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("데미지 찍기")
                .setView(ScrollView(ui).apply { addView(box) })
                .setPositiveButton("확인") { _, _ -> fields.forEach { (k, e) -> labDraft[k] = e.text.toString().trim() }; renderLab() }
                .setNeutralButton("다시 찍기") { _, _ -> labShootDamage(measures, useCrit) }
                .setNegativeButton("취소", null)
                )
        }
    }

    // ---------------------------------------------------------------- 룬 비교: [계산]이 룬 비교 화면을 찍었을 때 (최적화 화면과 같은 계산 코드를 화면 없이 돌림)
    private var engine: android.webkit.WebView? = null
    private var engineReady = false
    private val engineQueue = ArrayList<() -> Unit>()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun withEngine(job: () -> Unit) {
        if (engineReady) return job()
        engineQueue += job
        if (engine != null) return
        engine = android.webkit.WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface fun load(): String = LabData.load(this@OverlayService).toString()
                @android.webkit.JavascriptInterface fun save(json: String) { runCatching { LabData.save(this@OverlayService, JSONObject(json)) } }
                @android.webkit.JavascriptInterface fun getActive(): String = AppPrefs.labActive(this@OverlayService).orEmpty()
                @android.webkit.JavascriptInterface fun setActive(id: String) {}
                @android.webkit.JavascriptInterface fun toast(m: String) {}
                @android.webkit.JavascriptInterface fun copy(t: String) {}
                @android.webkit.JavascriptInterface fun saveFile(n: String, t: String) {}
                @android.webkit.JavascriptInterface fun pickFile() {}
                @android.webkit.JavascriptInterface fun petDb(): String {
                    val arr = org.json.JSONArray()
                    for (pt in db.pets) arr.put(JSONObject().put("n", pt.name).put("e", pt.element).put("t", pt.type).put("m", org.json.JSONArray(pt.maxS.toList())).put("k", PetDb.skillObj(pt)))
                    return arr.toString()
                }
            }, "TMCalc")
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    engineReady = true
                    val q = engineQueue.toList(); engineQueue.clear(); q.forEach { it() }
                }
            }
            loadUrl("file:///android_asset/lab.html")
        }
    }

    private fun runeJson(r: RuneInfo): JSONObject {
        fun n(x: RuneNum) = JSONObject().put("lo", x.lo).put("hi", x.hi).put("pct", x.pct)
        return JSONObject().put("grade", r.grade ?: JSONObject.NULL).put("name", r.name ?: JSONObject.NULL).put("level", r.level ?: JSONObject.NULL)
            .put("atk", r.atk?.let { n(it) } ?: JSONObject.NULL)
            .put("subs", org.json.JSONArray(r.subs.map { (l, x) -> org.json.JSONArray().put(l).put(n(x)) }))
    }

    private fun showRuneCompare(a: RuneInfo, b: RuneInfo) {
        withEngine {
            val js = "runeCompare(${JSONObject.quote(runeJson(a).toString())},${JSONObject.quote(runeJson(b).toString())})"
            engine?.evaluateJavascript(js) { raw ->
                val res = runCatching { JSONObject(org.json.JSONTokener(raw).nextValue() as String) }.getOrNull()
                if (res == null) { toast("룬 비교 계산을 못 했습니다."); return@evaluateJavascript }
                flushSpec(); specEdits.clear()
                if (optPanel == null) buildOpt()
                optBody.removeAllViews()
                val decks = res.optJSONArray("decks") ?: org.json.JSONArray()
                val btns = ArrayList<Pair<String, () -> Unit>>()
                btns += "내 스펙" to { showSpecInput() }
                val presets = res.optJSONArray("presets") ?: org.json.JSONArray()
                if (presets.length() > 0) btns += "프리셋" to {
                    val names = (0 until presets.length()).map { presets.getJSONObject(it).optString("name") }
                    overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("프리셋 (캐릭터) 고르기").setItems(names.toTypedArray()) { _, w ->
                        engine?.evaluateJavascript("runeSetPreset(${JSONObject.quote(presets.getJSONObject(w).optString("id"))})") { showRuneCompare(a, b) }
                    })
                }
                if (decks.length() > 1) btns += "덱" to {
                    val names = (0 until decks.length()).map { decks.getJSONObject(it).let { d -> "${d.optString("name")} (${d.optInt("n")}마리)" } }
                    overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("덱 고르기").setItems(names.toTypedArray()) { _, w ->
                        engine?.evaluateJavascript("runeSetDeck(${JSONObject.quote(decks.getJSONObject(w).optString("id"))})") { showRuneCompare(a, b) }
                    })
                }
                optHeader("룬 비교", btns)
                fun line(t: String, size: Float = 13f, color: Int = Color.WHITE, bold: Boolean = false) = optBody.addView(TextView(ui).apply {
                    text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(2), 0, dp(2))
                })
                if (res.has("err")) {
                    line(res.optString("err"))
                    showOpt(true); return@evaluateJavascript
                }
                val g = res.optDouble("g")
                val fmtV = { v: Double, p: Boolean -> LabData.fmt(Math.round(v * 100) / 100.0) + if (p) "%" else "" }
                val ra = res.optJSONObject("a"); val rb = res.optJSONObject("b")
                val verdict = when { g > 0.05 -> "바꾸기 추천"; g < -0.05 -> "바꾸지 마세요"; else -> "비슷함" }
                val col = Color.parseColor(when { g > 0.05 -> "#81C784"; g < -0.05 -> "#E57373"; else -> "#9A9DA3" })
                line("새 룬으로 바꾸면 덱 데미지", 12f, Color.parseColor("#9A9DA3"))
                line("${if (g >= 0) "+" else ""}${String.format(java.util.Locale.US, "%.2f", g)}%  $verdict", 22f, col, true)
                line("${ra?.optString("name")} [${ra?.optString("grade")}] Lv.${ra?.opt("level")} → [${rb?.optString("grade")}] Lv.${rb?.opt("level")}", 12f)
                line("${if (res.isNull("preset")) "지금 값" else "프리셋: " + res.optString("preset")} · 덱: ${if (res.isNull("deck")) "없음" else res.optString("deck")} · 공식: ${res.optString("set")}", 11f, Color.parseColor("#9A9DA3"))
                val rows = res.optJSONArray("rows") ?: org.json.JSONArray()
                for (i in 0 until rows.length()) {
                    val r = rows.getJSONObject(i); val p = r.optBoolean("pct"); val d = r.optDouble("d")
                    val sb = StringBuilder("${r.optString("label")}: ${fmtV(r.optDouble("old"), p)} → ${fmtV(r.optDouble("new"), p)} (${if (d >= 0) "+" else ""}${fmtV(d, p)})")
                    if (r.optBoolean("est")) sb.append(" ≈추정")
                    r.optString("why").takeIf { it.isNotEmpty() }?.let { sb.append(" · $it") }
                    line(sb.toString(), 12f)
                }
                showOpt(true)
            }
        }
    }

    /** 소환수도 확실한 룬 비교 화면도 아닌데 룬 글자가 보이면: 무엇을 읽었는지 보여 줌 (조용히 실패하지 않게) */
    private fun looksLikeRune(lines: List<OcrLine>): Boolean {
        val t = lines.map { it.text.replace(" ", "") }
        return t.any { (it.startsWith("주") || it.startsWith("부")) && it.contains("옵") && it.length <= 5 } || t.any { it.contains("의룬") || it.contains("룬[") }
    }

    /** [uptoId] 기록 바로 전까지의 소환수·성장증폭 (시작 상태 pet0 / amp0 → 기록의 pet / amp 로 바뀜) */
    private fun labPetAmpAt(s: JSONObject, uptoId: String?): Pair<JSONObject?, Double?> {
        var pet = s.optJSONObject("pet0")
        fun ampOf(o: JSONObject?, k: String) = o?.takeIf { it.has(k) && !it.isNull(k) }?.optDouble(k)?.takeIf { it.isFinite() }
        var amp = ampOf(s, "amp0") ?: ampOf(pet, "amp")
        for (r in labArr(s, "records")) {
            if (r.optString("id") == uptoId) break
            r.optJSONObject("pet")?.let { pet = it; ampOf(it, "amp")?.let { a -> amp = a } }
            ampOf(r, "amp")?.let { amp = it }
        }
        return pet to amp
    }

    /** 도감에 스킬이 없는 소환수: 스킬 배율 %·타수를 이 실험에서만 쓰게 직접 (건너뛰면 그 기록의 스킬은 공식에서 빠짐) */
    private fun labAskSkill(name: String, onDone: (Double?, Double?) -> Unit) {
        val pe = labInput("스킬 배율 % (예: 300)"); val he = labInput("스킬 타수 (기본 1)")
        overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("'$name' 스킬 배율")
            .setView(LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0)
                addView(TextView(ui).apply { text = "도감에 스킬이 없어서 이 실험에서만 써요 (도감에는 저장 안 함). 모르면 건너뛰기 — 그 기록의 스킬은 공식에서 빠져요."; textSize = 12f; setTextColor(Color.parseColor("#9A9DA3")) })
                addView(pe, LinearLayout.LayoutParams(-1, -2)); addView(he, LinearLayout.LayoutParams(-1, -2))
            })
            .setPositiveButton("확인") { _, _ -> onDone(LabData.number(pe.text.toString().trim())?.takeIf { it > 0 }, LabData.number(he.text.toString().trim())?.takeIf { it > 0 }) }
            .setNegativeButton("건너뛰기") { _, _ -> onDone(null, null) })
    }

    /** 저장한 소환수에서 고르기 → 이름·속성·타입·원래 능력치 */
    private fun labPickPet() {
        val list = AppPrefs.presets(this)
        if (list.isEmpty()) { toast("저장한 소환수가 없습니다. [계산]으로 소환수를 읽고 저장해 주세요."); return }
        if (dbVersion != PetStore.version) reloadDb()
        val names = list.map { p -> "${p.label}  (${p.name} · ${p.stats.joinToString("/")})" + (if (p.memo.isNotEmpty()) "\n" + p.memo else "") }
        overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("이 기록의 소환수").setItems(names.toTypedArray()) { _, w ->
            val p = list[w]; val pet = db.find(p.name)
            val draft = JSONObject().put("name", p.name)
                .put("elem", (pet?.element ?: "").let { if (it == "불") "화염" else it }).put("type", pet?.type ?: "")
                .put("st", org.json.JSONArray(p.stats.toList()))
            labPetDraft = draft
            // 도감에 스킬 배율이 없으면 이 실험에서만 쓸 값을 직접 (도감에는 저장 안 함)
            if ((pet?.skill?.get("pct") ?: 0.0) > 0) renderLab() else labAskSkill(p.name) { pct, hits ->
                if (pct != null) draft.put("sk", JSONObject().put("pct", pct).put("hits", hits ?: 1.0))
                renderLab()
            }
        }.setNegativeButton("닫기", null))
    }

    private fun labAskName(title: String, onOk: (String) -> Unit) {
        val e = labInput("이름")
        overlayDialog(android.app.AlertDialog.Builder(ui)
            .setTitle(title)
            .setView(LinearLayout(ui).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e, LinearLayout.LayoutParams(-1, -2)) })
            .setPositiveButton("추가") { _, _ -> e.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("취소", null))
    }

    // ---------------------------------------------------------------- 최적화 창: 내 스펙 입력 · 룬 비교 결과 (끌어서 옮기는 창 + 👁)
    private var optPanel: LinearLayout? = null
    private var optParams: WindowManager.LayoutParams? = null
    private lateinit var optTitle: TextView
    private lateinit var optBtns: LinearLayout
    private lateinit var optBody: LinearLayout
    private var specTab = 0
    private val specEdits = LinkedHashMap<String, EditText>()
    private val specSave = Runnable { flushSpec() }

    private fun optOpen() = optPanel?.visibility == View.VISIBLE

    private fun showOpt(show: Boolean) {
        if (show && optPanel == null) buildOpt()
        val v = optPanel ?: return
        val p = optParams ?: return
        if (!show) flushSpec()
        v.visibility = if (show) View.VISIBLE else View.GONE
        // 칸에 적으려면 창이 포커스를 받아야 한다. 닫으면 게임에 돌려준다
        p.flags = if (show) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(v, p) }
        if (!show) getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
        updateBubble()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOpt() {
        val dm = resources.displayMetrics
        val box = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            background = round(0xFF2B2D31.toInt(), 14)
            setPadding(dp(12), dp(8), dp(12), dp(10))
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            minOf(dm.widthPixels - dp(16), dp(400)), ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(40)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        val header = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        header.addView(eyeButton(box))
        optTitle = TextView(ui).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(OPT_ACC)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        header.addView(optTitle)
        optBtns = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(optBtns)
        header.setOnTouchListener(DragListener(params, box, onTap = {}, onLongPress = {}))
        box.addView(header)
        val maxH = (dm.heightPixels * 0.6).toInt()
        val scroll = ScrollView(ui)
        optBody = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(optBody)
        box.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        box.viewTreeObserver.addOnGlobalLayoutListener {
            val want = if (optBody.height > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
            if (scroll.layoutParams.height != want) scroll.layoutParams = scroll.layoutParams.apply { height = want }
        }
        optPanel = box; optParams = params
        wm.addView(box, params)
        bringBubbleToFront()
    }

    private fun optHeader(title: String, btns: List<Pair<String, () -> Unit>> = emptyList()) {
        optTitle.text = title
        optBtns.removeAllViews()
        for ((l, f) in btns) optBtns.addView(headerButton(l, f))
        optBtns.addView(headerButton("접기") { showOpt(false) })
    }

    /** 적은 값을 최적화 › 내 스펙(spec.st)에 저장. 칠 때마다 잠깐 뒤에, 탭을 바꾸거나 닫을 때 바로 */
    private fun flushSpec() {
        main.removeCallbacks(specSave)
        if (specEdits.isEmpty()) return
        val vals = specEdits.map { (k, e) -> k to e.text.toString().trim() }
        LabData.update(this) { d ->
            val sp = d.optJSONObject("spec") ?: JSONObject().also { d.put("spec", it) }
            val s2 = sp.optJSONObject("st") ?: JSONObject().also { sp.put("st", it) }
            for ((k, t) in vals) {
                val n = if (t.isEmpty()) null else LabData.number(t)
                if (n == null) s2.remove(k) else s2.put(k, n)
            }
        }
    }

    /**
     * 게임 위 '내 스펙': 최적화 › 내 스펙(테이밍마스터 기본 공식)과 같은 값을 게임 화면을 보면서 적는다.
     * 키는 lab.html TM2_SET 입력 id 와 같고, 속성·타입별은 'id@화염' 처럼 저장 (spec.st).
     */
    private fun showSpecInput(tab: Int = specTab) {
        flushSpec()
        specEdits.clear()
        specTab = tab.coerceIn(0, 4)
        if (optPanel == null) buildOpt()
        val elems = listOf("화염", "물", "바람", "빛", "어둠")
        val types = listOf("야수", "마법", "자연", "던전", "수중", "비행")
        val grades = listOf("광휘", "초월", "신화", "전설", "영웅", "유일", "희귀", "일반")
        // (탭 이름, 어디서 보는지, 칸들) — 칸: (키, 이름, 0 = 한 칸 / 1 = 속성마다 / 2 = 타입마다)
        val groups = listOf(
            Triple("상태창", "캐릭터 상태창에 보이는 값 (게임 화면 순서)", listOf(Triple("", "속성 강화", -1),
                Triple("p:elemS:pct", "속성 강화 % (도감 제외)", 1), Triple("g:elv:all:flat", "모든능력치", 0),
                Triple("", "특수 버프", -1), Triple("g:buff:party:pct", "파티 피해 증가 %", 0), Triple("p:amp", "성장증폭 %", 0),
                Triple("", "룬 옵션", -1), Triple("g:rune:atk:flat", "룬 공격", 0), Triple("g:rune:dmg:pct", "룬 피해증가 %", 0), Triple("g:rune:ae:pct", "룬 모든속성 강화 %", 0),
                Triple("g:rune:at:pct", "룬 모든타입 강화 %", 0), Triple("g:rune:cd:pct", "룬 치명타 피해 %", 0),
                Triple("p:elem:pct", "룬 속성 강화 %", 1), Triple("p:type:pct", "룬 타입 강화 %", 2))),
            Triple("무기", "무기 상세 (상태창에 안 보여요)", listOf(Triple("g:wpn:atkp:pct", "공격 %", 0))),
            Triple("코스튬", "코스튬 효과", listOf(Triple("g:cos:at:pct", "모든 속성 % (타입에 더해짐)", 0), Triple("p:elemC:pct", "파티 속성 강화 %", 1))),
            Triple("도감", "소환수 › 도감 미션 › 누적 보너스", listOf(Triple("g:dogam:atk:flat", "기본 능력치 공격 (+800 같은 값)", 0), Triple("g:dogam:dmg:pct", "피해증가 %", 0), Triple("g:dogam:ae:pct", "모든 속성 강화 %", 0),
                Triple("g:dogam:at:pct", "모든 타입 강화 %", 0), Triple("g:dogam:cd:pct", "치명타 피해 %", 0),
                Triple("p:elemD:pct", "속성 강화 %", 1), Triple("p:typeD:pct", "타입 강화 %", 2))),
            Triple("코어", "상태창에 안 보이는 값 · 적힌 경로에서 그대로", listOf(Triple("g:core:at:pct", "소환 › 스킬 — 모든 타입 증가 %", 0), Triple("core:n", "소환수 › 훈련소 › 스킬 코어 — 등급별 보유 개수", 3))))
        optHeader("내 스펙")
        optBody.removeAllViews()
        // 탭
        optBody.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            groups.forEachIndexed { i, g ->
                val on = i == specTab
                addView(TextView(ui).apply {
                    text = g.first; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(if (on) Color.parseColor("#17181B") else Color.parseColor("#C8CACF"))
                    typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    setPadding(0, dp(7), 0, dp(7))
                    background = round(if (on) OPT_ACC else 0xFF1E1F22.toInt(), 8)
                    setOnClickListener { if (!on) showSpecInput(i) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i < groups.size - 1) marginEnd = dp(4) })
            }
        })
        val (_, where, fields) = groups[specTab]
        optBody.addView(TextView(ui).apply { text = "$where · 적으면 바로 저장돼요"; textSize = 11f; setTextColor(Color.parseColor("#9A9DA3")); setPadding(0, dp(6), 0, dp(2)) })
        val st = LabData.load(this).optJSONObject("spec")?.optJSONObject("st") ?: JSONObject()
        fun cur(k: String) = if (st.has(k)) LabData.fmt(st.optDouble(k)).replace(",", "") else ""
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: Editable?) { main.removeCallbacks(specSave); main.postDelayed(specSave, 600) }
        }
        fun numInput(k: String) = labInput("0", cur(k)).also {
            specEdits[k] = it
            it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            it.gravity = Gravity.END
            it.addTextChangedListener(watcher)
        }
        // 소제목(kind < 0)마다 둥근 칸으로 나눠서 구별이 쉽게
        var sec: LinearLayout = optBody
        for ((key, name, kind) in fields) {
            if (kind < 0) {
                sec = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL; background = round(0xFF1E1F22.toInt(), 10); setPadding(dp(10), dp(6), dp(10), dp(8)) }
                optBody.addView(sec, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                sec.addView(TextView(ui).apply { text = name; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(OPT_ACC); setPadding(0, 0, 0, dp(2)) })
                continue
            }
            if (kind == 0) {
                sec.addView(LinearLayout(ui).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(ui).apply { text = name; textSize = 13f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(numInput(key), LinearLayout.LayoutParams(dp(110), -2))
                })
            } else {
                sec.addView(labLabel(name))
                sec.addView(LinearLayout(ui).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (n in when (kind) { 1 -> elems; 3 -> grades; else -> types }) addView(LinearLayout(ui).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(ui).apply { text = n; textSize = 10f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#9A9DA3")) }, LinearLayout.LayoutParams(-1, -2))
                        addView(numInput("$key@$n").also { it.textSize = 13f; it.gravity = Gravity.CENTER; it.setPadding(dp(2), dp(4), dp(2), dp(4)) }, LinearLayout.LayoutParams(-1, -2))
                    }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(2) })
                })
            }
        }
        showOpt(true)
    }

    private fun overlayDialog(b: android.app.AlertDialog.Builder): android.app.AlertDialog {
        val d = b.create()
        d.window?.setType(overlayType())
        d.show()
        return d
    }

    /** 바뀐 항목 고르기: 분류(룬 옵션·무기·속성 강화…)별로 나눠 보이고 검색. [onPick] 은 gis 번호, gis.size 부터는 [extra] 번호 */
    private fun labItemPicker(groups: List<JSONObject>, gis: List<Triple<String, String?, String>>, extra: List<String>, onPick: (Int) -> Unit) {
        val search = labInput("검색 (예: 화염, 피해)")
        val list = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
        var dlg: android.app.AlertDialog? = null
        fun row(text: String, color: Int, onClick: () -> Unit) = TextView(ui).apply {
            this.text = text; textSize = 14f; setTextColor(color); setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { dlg?.dismiss(); onClick() }
        }
        fun draw(q: String) {
            list.removeAllViews()
            val key = q.replace(" ", "")
            for (g in groups) {
                val gid = g.optString("id"); val gname = g.optString("name")
                val idx = gis.indices.filter { gis[it].first == gid && (key.isEmpty() || gis[it].third.replace(" ", "").contains(key)) }
                if (idx.isEmpty()) continue
                list.addView(TextView(ui).apply { text = gname; textSize = 12f; setTextColor(LAB_ACC); setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(10), 0, dp(2)) })
                for (i in idx) list.addView(row(gis[i].third.removePrefix(gname).trim().ifEmpty { gname }, Color.parseColor("#E6E7EA")) { onPick(i) })
            }
            if (list.childCount == 0) list.addView(TextView(ui).apply { text = "찾는 항목이 없어요."; setTextColor(Color.parseColor("#9A9DA3")) })
            if (key.isEmpty()) extra.forEachIndexed { k, t -> list.addView(row(t, Color.parseColor("#9A9DA3")) { onPick(gis.size + k) }) }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
            override fun onTextChanged(s2: CharSequence?, a2: Int, b2: Int, c2: Int) {}
            override fun afterTextChanged(x: Editable?) { draw(x?.toString() ?: "") }
        })
        draw("")
        val box = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), 0)
            addView(search, LinearLayout.LayoutParams(-1, -2))
            addView(android.widget.ScrollView(ui).apply { addView(list) }, LinearLayout.LayoutParams(-1, dp(360)))
        }
        dlg = overlayDialog(android.app.AlertDialog.Builder(ui).setTitle("바뀐 항목").setView(box).setNegativeButton("닫기", null))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPanel() {
        panel = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            background = round(0xFF2B2D31.toInt(), 14)
            setPadding(dp(12), dp(8), dp(12), dp(10))
            visibility = View.GONE
        }
        val maxH = (resources.displayMetrics.heightPixels * 0.62).toInt()
        panelParams = WindowManager.LayoutParams(
            dp(330), ViewGroup.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(60)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        // 헤더: 끌어서 이동 / 다시 읽기 / 접기 / 끄기
        val header = LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(eyeButton(panel))
        header.addView(TextView(ui).apply {
            text = "성장률 계산"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        header.addView(headerButton("다시읽기") { scan() })
        header.addView(headerButton("접기") { showPanel(false) })
        header.setOnTouchListener(DragListener(panelParams, panel, onTap = {}, onLongPress = {}))
        panel.addView(header)

        val scroll = ScrollView(ui)
        val body = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        // 내용이 길면 화면의 62%까지만 늘리고 스크롤, 짧아지면(자세히 접기 등) 다시 내용 크기로 줄인다
        panel.viewTreeObserver.addOnGlobalLayoutListener {
            val want = if (body.height > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
            if (scroll.layoutParams.height != want) scroll.layoutParams = scroll.layoutParams.apply { height = want }
        }

        // 제일 중요한 값: 총 성장률 (계산기 기준, 인게임 반올림 전)
        totalView = TextView(ui).apply {
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        totalGradeView = TextView(ui).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, 0, 0)
        }
        body.addView(TextView(ui).apply { text = "총 성장률 (계산기)"; textSize = 11f; setTextColor(Color.parseColor("#9A9DA3")) })
        body.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(totalView)
            addView(totalGradeView)
        })
        totalSubView = TextView(ui).apply { textSize = 11f; setTextColor(Color.parseColor("#C9CBD0")) }
        body.addView(totalSubView)
        // 같은 종 도감(S 100%) 개체보다 전투 능력치가 몇 % 높은지 (총 능력치 점수 보너스 포함)
        // 누르면 아래에 설명이 펼쳐진다 (앱만 받아 쓰는 사람도 뜻을 알 수 있게)
        combatHelp = TextView(ui).apply {
            text = Calculator.COMBAT_HELP
            textSize = 12f
            setTextColor(Color.parseColor("#C9CBD0"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = round(0xFF2A2D33.toInt(), 8)
            visibility = View.GONE
        }
        combatView = TextView(ui).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(2))
            visibility = View.GONE
            setOnClickListener { combatHelp.visibility = if (combatHelp.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }
        body.addView(combatView)
        body.addView(combatHelp)
        scoreView = TextView(ui).apply { textSize = 12f; setTextColor(Color.WHITE); setPadding(0, dp(2), 0, dp(2)) }
        body.addView(scoreView)
        warnView = TextView(ui).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#FFD479"))
            setPadding(0, dp(4), 0, dp(4))
            visibility = View.GONE
        }
        body.addView(warnView)

        // 비교: 기준(A)을 고정하면 지금 계산(B)과 나란히 보여 준다
        compareBox = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        body.addView(compareBox)

        // ---- 입력칸: 결과 아래. 잘못 읽혔으면 여기서 고친다 (이름 / 레벨 / 강 → 능력치 → 인게임 %)
        body.addView(View(ui).apply { setBackgroundColor(Color.parseColor("#3A3D44")) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(2) })

        // 이름 추천은 입력칸 위에 둔다 (아래에 두면 키보드에 가려짐)
        suggestBox = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        body.addView(suggestBox)
        nameEdit = input("이름", InputType.TYPE_CLASS_TEXT)
        levelEdit = input("레벨", InputType.TYPE_CLASS_NUMBER)
        breakEdit = input("0", InputType.TYPE_CLASS_NUMBER)
        body.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            addView(labeled("이름", nameEdit), LinearLayout.LayoutParams(0, -2, 2.2f))
            addView(labeled("레벨", levelEdit), LinearLayout.LayoutParams(0, -2, 1f))
            addView(labeled("현재 강", breakEdit), LinearLayout.LayoutParams(0, -2, 1f))
        })

        // 능력치 / (도감 대비)
        val statRow = LinearLayout(ui).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0..3) {
            val e = input(STAT_NAMES[i], InputType.TYPE_CLASS_NUMBER)
            val d = input("대비", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED).apply {
                textSize = 12f
                setTextColor(Color.parseColor("#7FD18B"))
            }
            statEdits[i] = e
            deltaEdits[i] = d
            statRow.addView(LinearLayout(ui).apply {
                orientation = LinearLayout.VERTICAL
                addView(labeled(STAT_NAMES[i], e))
                addView(d)
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(statRow)

        // 게임 화면의 총 성장 점수 (인게임 %). 비워 두면 계산값을 쓴다
        gameEdit = input("화면 값", InputType.TYPE_CLASS_NUMBER)
        body.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(labeled("인게임 총 성장 점수 (%)", gameEdit), LinearLayout.LayoutParams(0, -2, 1f))
            addView(View(ui), LinearLayout.LayoutParams(0, 0, 1f))
        })

        // 저장 / 비교 기준 / 저장 목록
        presetBox = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        body.addView(LinearLayout(ui).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
            addView(headerButton("저장") { savePreset() }.apply { (layoutParams as LinearLayout.LayoutParams).marginStart = 0 })
            addView(headerButton("기준으로 고정") { currentPreset()?.let { pin(it) } ?: toast("능력치와 도감 대비를 먼저 채워 주세요") })
            addView(headerButton("저장 목록 ▾") {}.apply {
                setOnClickListener {
                    val show = presetBox.visibility != View.VISIBLE
                    presetBox.visibility = if (show) View.VISIBLE else View.GONE
                    text = if (show) "저장 목록 ▴" else "저장 목록 ▾"
                    if (show) renderPresets()
                }
            })
        })
        body.addView(presetBox)

        // 자세히: 능력치별 성장률·등급, 다음 등급까지, 도감 추가, 강화별 표
        val detail = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        body.addView(headerButton("자세히 ▾") {}.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
            gravity = Gravity.CENTER
            setOnClickListener {
                val show = detail.visibility != View.VISIBLE
                detail.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "자세히 ▴" else "자세히 ▾"
            }
        })
        body.addView(detail)

        results = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
        detail.addView(results)

        // 지금 이름으로 도감 관리 열기. 150레벨이면 화면의 도감값(현재 - 도감 대비)을 만렙S 칸에 채워 준다
        detail.addView(headerButton("도감에 추가/수정") { openPetEditor() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
            gravity = Gravity.CENTER
        })

        // 강화별 능력치 / 등급 / 총 성장률 표 (상세 아래)
        enhanceTable = LinearLayout(ui).apply { orientation = LinearLayout.VERTICAL }
        detail.addView(enhanceTable)

        // 패널 바깥을 누르면 키보드 포커스를 게임에 돌려준다
        panel.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) setPanelFocusable(false)
            false
        }
    }

    private fun openPetEditor() {
        val name = nameEdit.text.toString().trim().ifEmpty { null }
        // 게임 도감 화면이면 성장 평균(= S성장률)으로 초기치·만렙S 까지 채운다. 아니면 150레벨일 때 만렙S(현재 − 도감 대비)만
        val lp = lastParsed
        val dex = PetDb.dexFromScreen(levelEdit.text.toString().toIntOrNull(), statEdits.map { num(it!!) }, deltaEdits.map { num(it!!) },
            lp?.growthAvgs?.toList() ?: List(4) { null })
        val draft = PetDraft(dex.maxS, dex.init, dex.growth, lp?.grade, lp?.element, lp?.type,
            dex.notes.takeIf { it.isNotEmpty() }?.joinToString(", "))
        showPanel(false)
        startActivity(PetEditorActivity.intent(this, name, draft).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private var panelFocusable = false

    private fun setPanelFocusable(on: Boolean) {
        if (panelFocusable == on) return
        panelFocusable = on
        panelParams.flags = if (on) panelParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(panel, panelParams) }
        if (!on) {
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(panel.windowToken, 0)
            panel.findFocus()?.clearFocus()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun input(hint: String, type: Int): EditText = EditText(ui).apply {
        this.hint = hint
        inputType = type
        textSize = 14f
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_DONE
        setTextColor(Color.WHITE)
        setHintTextColor(Color.parseColor("#777A80"))
        setPadding(dp(6), dp(4), dp(6), dp(4))
        setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN && !panelFocusable) {
                setPanelFocusable(true)
                v.post {
                    v.requestFocus()
                    getSystemService(InputMethodManager::class.java).showSoftInput(v, 0)
                }
            }
            false
        }
        setOnEditorActionListener { _, _, _ -> setPanelFocusable(false); true }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = recompute()
        })
    }

    private fun labeled(label: String, v: View) = LinearLayout(ui).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), 0, dp(2), 0)
        addView(TextView(ui).apply { text = label; textSize = 10f; setTextColor(Color.parseColor("#9A9DA3")) })
        addView(v, LinearLayout.LayoutParams(-1, -2))
    }

    private fun headerButton(label: String, onClick: () -> Unit) = TextView(ui).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = round(0xFF3A3D44.toInt(), 10)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun sectionTitle(t: String) = TextView(ui).apply {
        text = t
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(0, dp(10), 0, dp(4))
    }

    private fun row(cells: List<String>, header: Boolean = false, small: Boolean = false, bold: Boolean = false) = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(2), 0, dp(2))
        cells.forEachIndexed { i, c ->
            addView(TextView(ui).apply {
                text = gradeText(c)
                textSize = when { small -> 10.5f; header -> 11f; else -> 13f }
                gravity = if (i == 0) Gravity.START else Gravity.CENTER
                setTextColor(if (header) Color.parseColor("#9A9DA3") else gradeColor(c))
                if (i == 0 && !header) setTextColor(Color.parseColor(if (bold) "#FFFFFF" else "#C9CBD0"))
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, -2, if (i == 0) 0.8f else 1f))
        }
    }

    /** 글자 안의 EX · EX+ · EX++ 를 밝은 실버로 (나머지는 그대로) */
    private fun gradeText(c: String): CharSequence {
        val found = Regex("EX\\+{0,2}|SSS\\+{0,2}").findAll(c).toList()
        if (found.isEmpty()) return c
        return android.text.SpannableString(c).apply {
            found.forEach { setSpan(RainbowSpan(), it.range.first, it.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
    }

    private fun gradeColor(c: String): Int {
        val g = c.substringAfterLast(' ').substringAfterLast('\n').removePrefix("≈")
        return Color.parseColor(
            when {
                Calculator.isBeyondGame(g) -> "#E9EDF2" // 실제로는 실버 (gradeText)
                g.startsWith("SS") -> "#F2B84B"
                g.startsWith("S") -> "#FF8A7A"
                g.startsWith("A") -> "#8FD48A"
                g.startsWith("B") || g == "C" || g == "D" || g == "Bad" -> "#9A9DA3"
                else -> "#FFFFFF"
            }
        )
    }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /**
     * "계산" 버튼: 짧게 누르면 탭, 끌면 이동.
     * 꾹 누르면(0.5초) 아래에 쓰레기통이 나타나고, 쓰레기통 위에서 손을 떼면 레이어를 끈다.
     */
    private inner class BubbleTouchListener(private val onTap: () -> Unit) : View.OnTouchListener {
        private var sx = 0; private var sy = 0
        private var tx = 0f; private var ty = 0f
        private var moved = false
        private var armed = false // 꾹 눌러서 쓰레기통이 나온 상태
        private var overTrash = false
        private val arm = Runnable {
            if (moved) return@Runnable
            armed = true
            bubble.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            setTrashHover(false)
            trash.visibility = View.VISIBLE
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = bubbleParams.x; sy = bubbleParams.y; tx = ev.rawX; ty = ev.rawY
                    moved = false; armed = false; overTrash = false
                    main.postDelayed(arm, 500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - tx; val dy = ev.rawY - ty
                    if (armed || moved || Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        if (!armed && !moved) main.removeCallbacks(arm) // 꾹 누르기 전에 끌기 시작 → 그냥 이동
                        moved = true
                        bubbleParams.x = sx + dx.toInt()
                        bubbleParams.y = sy + dy.toInt()
                        runCatching { wm.updateViewLayout(bubble, bubbleParams) }
                        if (armed) {
                            val hit = isOverTrash(ev.rawX, ev.rawY)
                            if (hit != overTrash) { overTrash = hit; setTrashHover(hit) }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(arm)
                    trash.visibility = View.GONE
                    when {
                        armed && overTrash && ev.actionMasked == MotionEvent.ACTION_UP -> stopSelf()
                        !moved && !armed && ev.actionMasked == MotionEvent.ACTION_UP -> onTap()
                    }
                    armed = false
                }
            }
            return true
        }

        private fun isOverTrash(x: Float, y: Float): Boolean {
            val loc = IntArray(2).also { trash.getLocationOnScreen(it) }
            val cx = loc[0] + trash.width / 2f; val cy = loc[1] + trash.height / 2f
            val r = trash.width * 0.9f // 쓰레기통 근처면 인정
            return (x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r
        }

        private fun setTrashHover(on: Boolean) {
            (trash.background as GradientDrawable).setColor(if (on) TRASH_HOT else TRASH_IDLE)
        }
    }

    /** 끌어서 이동, 짧게 누르면 탭, 오래 누르면 롱프레스. */
    private inner class DragListener(
        private val params: WindowManager.LayoutParams,
        private val target: View,
        private val onTap: () -> Unit,
        private val onLongPress: () -> Unit,
    ) : View.OnTouchListener {
        private var sx = 0; private var sy = 0
        private var tx = 0f; private var ty = 0f
        private var moved = false
        private var downAt = 0L

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = params.x; sy = params.y; tx = ev.rawX; ty = ev.rawY
                    moved = false; downAt = ev.eventTime
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - tx; val dy = ev.rawY - ty
                    if (moved || Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        moved = true
                        params.x = sx + dx.toInt()
                        params.y = sy + dy.toInt()
                        runCatching { wm.updateViewLayout(target, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (ev.eventTime - downAt > 500) onLongPress() else onTap()
                }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- utils

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun fmt(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)

    companion object {
        /** 레이어를 숨긴 화면이 실제로 그려질 때까지 기다리는 시간 */
        private const val HIDE_DELAY_MS = 150L
        private const val CHANNEL = "overlay"
        private const val NOTI_ID = 1
        private val STAT_NAMES = listOf("공", "방", "순", "체")
        /** 게임의 최대 강화(돌파) 단계 */
        private const val MAX_BREAKS = 5
        private val TRASH_IDLE = Color.parseColor("#CC3A3D44")
        private val TRASH_HOT = Color.parseColor("#E5E5484D")
        private const val MAX_LEVEL = Calculator.MAX_LEVEL

        @Volatile
        var running = false

        private val LAB_ACC = Color.parseColor("#B39DDB")
        private val OPT_ACC = Color.parseColor("#80CBC4")
    }
}

/** SS++ 를 넘는 계산기 전용 등급 EX · EX+ · EX++ 를 흰 은색 + 굵게 + 은은한 빛으로 그린다 */
private class RainbowSpan : android.text.style.ReplacementSpan() {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        val bold = paint.isFakeBoldText; paint.isFakeBoldText = true
        val w = paint.measureText(text, start, end); paint.isFakeBoldText = bold
        return Math.ceil(w.toDouble()).toInt() + 2
    }

    override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int,
                      paint: android.graphics.Paint) {
        // 거의 흰 은색 + 굵게 + 은은한 하늘빛 번짐 (테두리 없음)
        val oldColor = paint.color; val oldBold = paint.isFakeBoldText; val oldShader = paint.shader
        paint.shader = null; paint.isFakeBoldText = true; paint.color = TEXT
        paint.setShadowLayer(paint.textSize * 0.38f, 0f, 0f, GLOW_WIDE)
        canvas.drawText(text, start, end, x + 1f, y.toFloat(), paint)
        paint.setShadowLayer(paint.textSize * 0.17f, 0f, 0f, GLOW_NEAR)
        canvas.drawText(text, start, end, x + 1f, y.toFloat(), paint)
        paint.clearShadowLayer()
        paint.color = oldColor; paint.isFakeBoldText = oldBold; paint.shader = oldShader
    }

    companion object {
        val TEXT = 0xFFF8FAFD.toInt()
        val GLOW_NEAR = 0xCCE1ECFF.toInt()   // rgba(225,236,255,.8)
        val GLOW_WIDE = 0x73B9D0FF           // rgba(185,208,255,.45)
    }
}
