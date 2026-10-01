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
        runCatching { wm.removeView(bubble) }
        runCatching { wm.removeView(panel) }
        runCatching { wm.removeView(trash) }
        recognizer.close()
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
                lastParsed = parsed
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
        val open = panel.visibility == View.VISIBLE
        bubble.text = if (open) "✕" else "계산"
        bubble.textSize = if (open) 20f else 13f
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
        if (p.pet != null) nameEdit.setText(p.pet.name) else nameEdit.setText(p.nameRaw?.let { PetDb.key(it) } ?: "")
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
        filling = false
        recompute()
    }

    // ---------------------------------------------------------------- calc + render

    private fun recompute() {
        if (filling) return
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
            totalGradeView.text = realGrade
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
            } else { combatView.visibility = View.GONE; combatHelp.visibility = View.GONE }
            renderEnhance(cur, b, breaks, pet, level, real, realGrade)
        } else {
            totalView.text = "-"
            totalGradeView.text = ""
            totalSubView.text = "능력치와 (도감 대비) 값을 확인해 주세요"
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
        enhanceTable.addView(sectionTitle("강화별 (최대 +${MAX_BREAKS}강, 예상)"))
        enhanceTable.addView(row(listOf("강", "공", "방", "순", "체", if (showCombat()) "총점\n전투" else "총점", "성장률"), header = true))
        for (k in breaks..maxOf(breaks, MAX_BREAKS)) {
            val s = Calculator.statsAtBreaks(cur, breaks, k)
            val t = Calculator.total(s, base)
            // 등급 = 능력치 ÷ 도감값 (도감에 없는 소환수도 화면의 도감값으로 똑같이 계산)
            val grades = Calculator.statGrades(s, base)
            val combat = (if (showCombat()) Calculator.combat(s, base, growthAmp()) else null)
            val cells = listOf(if (k == breaks) "${k}강\n현재" else "${k}강") +
                (0..3).map { i -> "${s[i]}\n${grades[i]}" } +
                (fmt(Calculator.abilityScore(s), 1) + (combat?.let { "\n" + combatPct(it.avg, it.approx) } ?: "")) +
                // 현재 강은 위의 총 성장률(화면 인게임 값으로 맞춘 값)과 같게
                if (k == breaks) "${fmt(curReal, 2)}%\n$curGrade" else "${fmt(t.realTotal, 2)}%\n${t.realTotalGrade}"
            enhanceTable.addView(row(cells, small = true))
        }
        if (breaks >= MAX_BREAKS) enhanceTable.addView(note("이미 최대 강화(+${MAX_BREAKS}강)입니다."))
        else enhanceTable.addView(note("강화 수치는 게임과 1 정도 차이 날 수 있어요. (게임은 소수까지 계산하고 화면엔 정수만 보여서)"))
        if (showCombat()) enhanceTable.addView(note("전투 = 종 대비 전투 능력치: 같은 종 도감(S 100%) 개체보다 전투 능력치가 몇 % 높은지. 총 능력치 점수가 높을수록 크게 오릅니다."))
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
            onTap = { if (panel.visibility == View.VISIBLE) showPanel(false) else scan() },
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
        val maxS = if (levelEdit.text.toString().toIntOrNull() == MAX_LEVEL) {
            val b = List(4) { i -> num(statEdits[i]!!)?.let { s -> num(deltaEdits[i]!!)?.let { s - it } } }
            if (b.all { it != null && it > 0 }) IntArray(4) { b[it]!! } else null
        } else null
        showPanel(false)
        startActivity(PetEditorActivity.intent(this, name, maxS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    private fun row(cells: List<String>, header: Boolean = false, small: Boolean = false) = LinearLayout(ui).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(2), 0, dp(2))
        cells.forEachIndexed { i, c ->
            addView(TextView(ui).apply {
                text = c
                textSize = when { small -> 10.5f; header -> 11f; else -> 13f }
                gravity = if (i == 0) Gravity.START else Gravity.CENTER
                setTextColor(if (header) Color.parseColor("#9A9DA3") else gradeColor(c))
                if (i == 0 && !header) setTextColor(Color.parseColor("#C9CBD0"))
            }, LinearLayout.LayoutParams(0, -2, if (i == 0) 0.8f else 1f))
        }
    }

    private fun gradeColor(c: String): Int {
        val g = c.substringAfterLast(' ').substringAfterLast('\n').removePrefix("≈")
        return Color.parseColor(
            when {
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
    }
}
