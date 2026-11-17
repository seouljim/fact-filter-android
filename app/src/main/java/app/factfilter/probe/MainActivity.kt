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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 1단계 시험 화면
 * 권한 두 개를 켜고 유튜브를 재생하면, 유튜브가 알려주는 재생 정보가 이 화면과 투명 창에 나타난다.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var live: TextView
    private lateinit var log: TextView

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16f)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        column.addView(text("FACT FILTER 1단계 시험", 20f, bold = true))
        column.addView(
            text(
                "유튜브 앱이 재생 중인 영상의 제목과 재생 위치를 알려주는지 확인합니다.\n" +
                    "아래 두 권한을 켠 뒤 유튜브에서 영상을 재생해 보세요.",
                14f,
            ),
        )

        status = text("", 14f)
        column.addView(status)

        column.addView(button("1. 알림 접근 권한 켜기") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        column.addView(button("2. 다른 앱 위에 표시 권한 켜기") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        column.addView(button("3. 유튜브 열기") {
            val launch = packageManager.getLaunchIntentForPackage(MediaWatchService.YOUTUBE)
            if (launch != null) startActivity(launch) else toast("유튜브 앱을 찾지 못했습니다.")
        })

        column.addView(text("지금 유튜브가 알려주는 정보", 16f, bold = true))
        live = text("", 14f).apply { typeface = Typeface.MONOSPACE }
        column.addView(live)

        column.addView(button("기록 복사하기 (Claude에게 붙여넣기)") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("FACT FILTER 기록", buildReport()))
            toast("기록을 복사했습니다.")
        })

        column.addView(text("기록", 16f, bold = true))
        log = text("", 12f).apply { typeface = Typeface.MONOSPACE }
        column.addView(log)

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
        status.text = buildString {
            append(if (listener) "✓ 알림 접근 권한 켜짐" else "✗ 알림 접근 권한 꺼짐")
            if (listener && !ProbeState.listenerConnected) append(" (연결 대기 중)")
            append("\n")
            append(if (overlay) "✓ 다른 앱 위에 표시 권한 켜짐" else "✗ 다른 앱 위에 표시 권한 꺼짐")
        }
        status.setTextColor(if (listener && overlay) Color.parseColor("#1A56C4") else Color.parseColor("#B0520A"))

        val s = ProbeState.current
        live.text = if (s == null) {
            "유튜브 재생 정보 없음\n활성 세션: ${ProbeState.sessionPackages.joinToString().ifEmpty { "없음" }}"
        } else {
            "제목   ${s.title ?: "(없음)"}\n" +
                "채널   ${s.artist ?: "(없음)"}\n" +
                "길이   ${ProbeState.formatMs(s.durationMs)}\n" +
                "위치   ${ProbeState.formatMs(s.positionMs)}\n" +
                "상태   ${s.state} (속도 ${s.speed})\n" +
                "ID     ${s.mediaId ?: "(없음)"}\n" +
                "URI    ${s.mediaUri ?: "(없음)"}"
        }
        log.text = ProbeState.logText().ifEmpty { "아직 기록이 없습니다." }
    }

    private fun buildReport(): String = buildString {
        appendLine("[FACT FILTER 1단계 기록]")
        appendLine("기기: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        appendLine("알림 접근: ${listenerEnabled()} / 연결: ${ProbeState.listenerConnected} / 다른 앱 위에 표시: ${Settings.canDrawOverlays(this@MainActivity)}")
        appendLine("활성 세션: ${ProbeState.sessionPackages.joinToString().ifEmpty { "없음" }}")
        appendLine("--- 현재 ---")
        appendLine(live.text)
        appendLine("--- 기록 ---")
        append(ProbeState.logText())
    }

    // ── 화면 요소 ─────────────────────────────────────────────

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun text(content: String, sp: Float, bold: Boolean = false): TextView = TextView(this).apply {
        text = content
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(Color.parseColor("#0F1B33"))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(true)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12f) }
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8f) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
