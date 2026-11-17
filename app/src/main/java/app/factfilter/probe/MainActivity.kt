package app.factfilter.probe

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 설정 화면
 * 권한 두 개를 켜면 준비가 끝난다. 이후에는 유튜브만 쓰면 된다.
 *   - 긴 영상: 재생하면 자동으로 분석해 장면에 맞춰 결과를 띄운다.
 *   - 쇼츠: 유튜브의 공유 → "FACT FILTER로 확인" 을 누른다.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var readyText: TextView
    private lateinit var listenerButton: Button
    private lateinit var overlayButton: Button
    private lateinit var troubleBox: LinearLayout
    private lateinit var live: TextView
    private lateinit var log: TextView

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(18f)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad * 2)
        }

        column.addView(text("FACT FILTER", 22f, bold = true, color = NAVY))
        column.addView(text("유튜브 영상 속 주장을 공식 원문 옆에 나란히 놓아 드립니다.", 14f, color = MUTED, top = 4f))

        // ── 준비 상태 ──
        readyText = text("", 15f, bold = true, top = 20f)
        column.addView(readyText)
        listenerButton = button("알림 접근 권한 켜기") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        column.addView(listenerButton)
        column.addView(
            text("유튜브가 재생 중인 영상의 제목과 재생 위치를 읽는 데만 씁니다. 알림 내용은 읽거나 저장하지 않습니다.", 12f, color = MUTED, top = 4f),
        )
        overlayButton = button("다른 앱 위에 표시 권한 켜기") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        column.addView(overlayButton)
        column.addView(text("유튜브 화면 위에 결과 창을 띄우는 데 씁니다.", 12f, color = MUTED, top = 4f))

        // ── 사용법 ──
        column.addView(text("사용법", 16f, bold = true, top = 24f))
        column.addView(
            text(
                "긴 영상\n유튜브에서 재생하면 자동으로 분석합니다. 주장이 나오는 장면에 맞춰 결과가 창에 나타납니다.\n\n" +
                    "쇼츠\n유튜브의 공유 → \"FACT FILTER로 확인\"을 누르세요.\n\n" +
                    "결과 창\n맨 윗줄을 끌면 위아래로 움직이고, 누르면 접힙니다. ‹ ›로 다른 주장을 보고, ×로 닫습니다.",
                14f,
                top = 6f,
            ),
        )

        // ── 설정 ──
        column.addView(text("설정", 16f, bold = true, top = 24f))
        column.addView(
            Switch(this).apply {
                text = "긴 영상 자동 분석"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(INK)
                isChecked = Config.autoEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, checked -> Config.setAutoEnabled(this@MainActivity, checked) }
                layoutParams = rowParams(8f)
            },
        )
        column.addView(text("끄면 공유로 보낸 영상만 분석합니다.", 12f, color = MUTED, top = 2f))

        column.addView(button("유튜브 열기") {
            val launch = packageManager.getLaunchIntentForPackage(MediaWatchService.YOUTUBE)
            if (launch != null) startActivity(launch) else toast("유튜브 앱을 찾지 못했습니다.")
        })

        // ── 고지 ──
        column.addView(
            text(
                "공개된 1차 자료 검색 결과를 나란히 보여드릴 뿐 진위를 최종 단정하지 않습니다. " +
                    "색은 자료를 찾았는지와 차이가 있는지를 나타내며, 진위 판정이 아닙니다. " +
                    "의학적 진단이나 투자 권유가 아닙니다.",
                12f,
                color = MUTED,
                top = 20f,
            ),
        )
        column.addView(link("개인정보 처리방침") {
            val server = Config.server(this)
            if (server.isNotBlank()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$server/privacy")))
        })

        // ── 문제 해결 (평소에는 접어 둔다) ──
        troubleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        column.addView(link("문제 해결 정보 보기") {
            troubleBox.visibility = if (troubleBox.visibility == View.GONE) View.VISIBLE else View.GONE
        })

        troubleBox.addView(text("서버 주소", 14f, bold = true, top = 12f))
        val serverInput = EditText(this).apply {
            setText(Config.server(this@MainActivity))
            hint = "https://…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        troubleBox.addView(serverInput)
        troubleBox.addView(button("서버 주소 저장") {
            val value = serverInput.text.toString().trim().trimEnd('/')
            if (value.isNotEmpty() && !value.startsWith("https://")) {
                toast("https:// 로 시작하는 주소를 넣어 주세요.")
            } else {
                Config.saveServer(this, value)
                toast(if (value.isEmpty()) "기본 주소로 되돌렸습니다." else "저장했습니다.")
            }
        })
        troubleBox.addView(text("지금 유튜브가 알려주는 정보", 14f, bold = true, top = 16f))
        live = text("", 13f, top = 4f).apply { typeface = Typeface.MONOSPACE }
        troubleBox.addView(live)
        troubleBox.addView(button("기록 복사하기") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("FACT FILTER 기록", buildReport()))
            toast("기록을 복사했습니다.")
        })
        log = text("", 12f, top = 8f).apply { typeface = Typeface.MONOSPACE }
        troubleBox.addView(log)
        column.addView(troubleBox)

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun listenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val self = ComponentName(this, MediaWatchService::class.java).flattenToString()
        return enabled.split(":").any { it == self }
    }

    private fun render() {
        val listener = listenerEnabled()
        val overlay = Settings.canDrawOverlays(this)

        if (listener && overlay) {
            readyText.text = "✓ 준비됐습니다. 유튜브에서 영상을 재생해 보세요."
            readyText.setTextColor(FOUND)
        } else {
            readyText.text = "아래 권한을 켜야 사용할 수 있습니다."
            readyText.setTextColor(DIFF)
        }
        listenerButton.text = if (listener) "✓ 알림 접근 권한 켜짐" else "알림 접근 권한 켜기"
        overlayButton.text = if (overlay) "✓ 다른 앱 위에 표시 권한 켜짐" else "다른 앱 위에 표시 권한 켜기"

        if (troubleBox.visibility == View.VISIBLE) {
            val s = ProbeState.current
            live.text = if (s == null) {
                "유튜브 재생 정보 없음"
            } else {
                "제목   ${s.title ?: "(없음)"}\n" +
                    "채널   ${s.artist ?: "(없음)"}\n" +
                    "길이   ${ProbeState.formatMs(s.durationMs)}\n" +
                    "위치   ${ProbeState.formatMs(s.positionMs)}\n" +
                    "상태   ${s.state}"
            }
            log.text = ProbeState.logText().ifEmpty { "아직 기록이 없습니다." }
        }
    }

    private fun buildReport(): String = buildString {
        appendLine("[FACT FILTER 기록]")
        appendLine("버전: ${BuildConfig.VERSION_NAME} / 열쇠: ${if (BuildConfig.APP_KEY.isEmpty()) "없음" else "있음"}")
        appendLine("서버: ${Config.server(this@MainActivity).ifEmpty { "(없음)" }}")
        appendLine("기기: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine(
            "알림 접근: ${listenerEnabled()} / 연결: ${ProbeState.listenerConnected} / " +
                "다른 앱 위에 표시: ${Settings.canDrawOverlays(this@MainActivity)} / 자동 분석: ${Config.autoEnabled(this@MainActivity)}",
        )
        val s = ProbeState.current
        appendLine("--- 현재 ---")
        appendLine(
            if (s == null) "유튜브 재생 정보 없음"
            else "${s.title ?: "(제목 없음)"} | ${s.artist ?: "(채널 없음)"} | ${ProbeState.formatMs(s.positionMs)} / ${ProbeState.formatMs(s.durationMs)} | ${s.state}",
        )
        appendLine("--- 기록 ---")
        append(ProbeState.logText())
    }

    // ── 화면 요소 ─────────────────────────────────────────────

    private companion object {
        val INK = Color.parseColor("#0F1B33")
        val MUTED = Color.parseColor("#56617A")
        val NAVY = Color.parseColor("#1B2F5E")
        val FOUND = Color.parseColor("#1A56C4")
        val DIFF = Color.parseColor("#B0520A")
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun rowParams(top: Float) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun text(content: String, sp: Float, bold: Boolean = false, color: Int = INK, top: Float = 12f): TextView =
        TextView(this).apply {
            text = content
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2f).toFloat(), 1f)
            layoutParams = rowParams(top)
        }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = rowParams(10f)
    }

    private fun link(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(NAVY)
        paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        setPadding(0, dp(10f), 0, dp(10f))
        setOnClickListener { onClick() }
        layoutParams = rowParams(8f)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
