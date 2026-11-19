package app.factfilter.probe

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * 유튜브 위에 뜨는 반투명 창.
 *
 *  ┌──────────────────────────────────────────────┐
 *  │                   ▬▬▬▬                       │  ← 손잡이: 여기(와 아래 머리줄)를 끌면 창이 움직인다
 *  │ FACT FILTER                          －   ×  │  ← 머리줄: 짧게 누르면 접힌다
 *  ├──────────────────────────────────────────────┤
 *  │ 지금 이야기                                   │  ← 위 칸: 재생 위치에 해당하는 구간 요약
 *  │ 비타민 B3 복용 시간을 설명합니다.              │
 *  ├──────────────────────────────────────────────┤
 *  │ 주장 확인 · 02:14                 ‹  3/6  ›   │  ← 아래 칸: 주장과 대조 결과
 *  │ 주장 문장                                     │     좌우로 밀면 다른 주장, 위아래로 밀면 긴 내용
 *  │ [≠ 원문 조건 일부 미포함]                      │
 *  │ 한 줄 결론 …                                  │
 *  └──────────────────────────────────────────────┘
 *
 * 창 안쪽의 터치는 창이 받고, 창 바깥은 그대로 유튜브로 전달된다.
 */
class OverlayPanel(
    private val context: Context,
    private val onPrev: () -> Unit,
    private val onNext: () -> Unit,
    private val onClose: () -> Unit,
    private val onDetail: () -> Unit,
    private val log: (String) -> Unit,
) {
    /** 창에 보여줄 내용 한 장 */
    class Content(
        /** 위 칸: 지금 이야기하는 내용 요약. null 이면 위 칸을 숨긴다. */
        val summary: String? = null,
        /** 아래 칸의 작은 제목. 예: "주장 확인 · 02:14" */
        val claimLabel: String = "",
        val title: String,
        val chip: String? = null,
        val chipColor: Int = 0,
        val detail: String? = null,
        /** "3/6" 같은 순번. null 이면 앞뒤 넘김을 숨긴다. */
        val counter: String? = null,
        /** 대조표 전체를 볼 수 있는 상태인지 (대조가 끝난 주장) */
        val hasDetail: Boolean = false,
        /** 내용이 바뀌었는지 판단하는 값. 바뀌면 본문을 맨 위로 되돌린다. */
        val key: String = "",
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    private var root: LinearLayout? = null
    private var hintText: TextView? = null
    private var toggleButton: TextView? = null
    private var body: LinearLayout? = null
    private var summaryBox: LinearLayout? = null
    private var summaryText: TextView? = null
    private var claimLabelText: TextView? = null
    private var counterText: TextView? = null
    private var prevButton: TextView? = null
    private var nextButton: TextView? = null
    private var scroll: ScrollView? = null
    private var titleText: TextView? = null
    private var chipText: TextView? = null
    private var detailText: TextView? = null
    private var linkText: TextView? = null

    private var collapsed = prefs.getBoolean("collapsed", false)
    private var lastKey: String? = null
    private var lastCounter: String? = null
    private var builtForWidth = 0

    val isShown: Boolean get() = root != null

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun show(content: Content) {
        // 화면을 돌리거나 폴드를 펼치면 폭이 달라지므로 다시 만든다.
        val screenWidth = context.resources.displayMetrics.widthPixels
        if (root != null && builtForWidth != screenWidth) hide()
        if (root == null && !create(screenWidth)) return

        summaryText?.text = content.summary.orEmpty()
        summaryBox?.visibility = if (content.summary.isNullOrBlank()) View.GONE else View.VISIBLE

        claimLabelText?.text = content.claimLabel
        counterText?.apply {
            text = content.counter.orEmpty()
            visibility = if (content.counter == null) View.GONE else View.VISIBLE
        }
        val nav = if (content.counter == null) View.GONE else View.VISIBLE
        prevButton?.visibility = nav
        nextButton?.visibility = nav
        lastCounter = content.counter

        titleText?.text = content.title
        chipText?.apply {
            if (content.chip == null) {
                visibility = View.GONE
            } else {
                text = content.chip
                (background as? GradientDrawable)?.setColor(content.chipColor)
                visibility = View.VISIBLE
            }
        }
        detailText?.apply {
            text = content.detail.orEmpty()
            visibility = if (content.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        linkText?.visibility = if (content.hasDetail) View.VISIBLE else View.GONE

        if (content.key != lastKey) {
            lastKey = content.key
            scroll?.scrollTo(0, 0)
        }
        applyState()
    }

    fun hide() {
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        root = null
        lastKey = null
    }

    /** 접힘 상태와 처음 쓰는 사람을 위한 안내 문구를 반영한다. */
    private fun applyState() {
        body?.visibility = if (collapsed) View.GONE else View.VISIBLE
        toggleButton?.text = if (collapsed) "＋" else "－"

        // 아직 해 보지 않은 조작만 알려준다. 한 번 해 보면 사라진다.
        val tips = mutableListOf<String>()
        if (!prefs.getBoolean("did_drag", false)) tips.add("↕ 이 윗부분을 끌면 창이 움직입니다")
        if (!collapsed && lastCounter != null && !prefs.getBoolean("did_swipe", false)) tips.add("↔ 좌우로 밀면 다른 주장")
        hintText?.apply {
            text = tips.joinToString("   ")
            visibility = if (tips.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun markDone(key: String) {
        if (!prefs.getBoolean(key, false)) prefs.edit().putBoolean(key, true).apply()
    }

    private fun smallButton(label: String, description: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        contentDescription = description
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        minWidth = dp(40f)
        minHeight = dp(36f)
        setOnClickListener { onClick() }
    }

    private fun sectionLabel(label: String): TextView = TextView(context).apply {
        text = label
        setTextColor(Color.parseColor("#9FB0D0"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(Color.argb(48, 255, 255, 255))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1f))
    }

    private fun create(screenWidth: Int): Boolean {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                // 창 전체가 아니라 바탕색만 반투명하게 해서 글자는 또렷하게 둔다.
                setColor(Color.argb(224, 12, 19, 34))
                cornerRadius = dp(12f).toFloat()
            }
        }

        // ── 끌어서 옮기는 영역: 손잡이 + 머리줄 + 안내 ──
        val dragArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val grip = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(170, 255, 255, 255))
                cornerRadius = dp(3f).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(44f), dp(5f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(8f)
            }
        }
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), 0, dp(4f), 0)
        }
        val brand = TextView(context).apply {
            text = "FACT FILTER"
            setTextColor(Color.parseColor("#C3CCE0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toggle = smallButton("－", "창 접기 또는 펼치기") {
            collapsed = !collapsed
            prefs.edit().putBoolean("collapsed", collapsed).apply()
            applyState()
        }
        headerRow.addView(brand)
        headerRow.addView(toggle)
        headerRow.addView(smallButton("×", "이 영상에서 창 닫기") { onClose() })
        val hint = TextView(context).apply {
            setTextColor(Color.parseColor("#FFD9A0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(14f), 0, dp(14f), dp(8f))
        }
        dragArea.addView(grip)
        dragArea.addView(headerRow)
        dragArea.addView(hint)

        // ── 접으면 사라지는 본문 ──
        val bodyBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // 위 칸: 지금 이야기하는 내용
        val summarySection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(8f), dp(14f), dp(10f))
        }
        val summary = TextView(context).apply {
            setTextColor(Color.parseColor("#E6EBF6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(2f).toFloat(), 1f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3f), 0, 0)
        }
        summarySection.addView(sectionLabel("지금 이야기"))
        summarySection.addView(summary)
        val summaryWrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        summaryWrap.addView(divider())
        summaryWrap.addView(summarySection)

        // 아래 칸: 주장 확인
        val claimHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), 0, dp(4f), 0)
            minimumHeight = dp(36f)
        }
        val claimLabel = sectionLabel("주장 확인").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val prev = smallButton("‹", "이전 주장") { onPrev() }
        val counter = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val next = smallButton("›", "다음 주장") { onNext() }
        claimHeader.addView(claimLabel)
        claimHeader.addView(prev)
        claimHeader.addView(counter)
        claimHeader.addView(next)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), 0, dp(14f), dp(12f))
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2f).toFloat(), 1f)
        }
        val chip = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
            background = GradientDrawable().apply { cornerRadius = dp(4f).toFloat() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8f) }
        }
        val detail = TextView(context).apply {
            setTextColor(Color.parseColor("#E6EBF6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(dp(3f).toFloat(), 1f)
            setPadding(0, dp(8f), 0, 0)
        }
        val link = TextView(context).apply {
            text = "대조표와 공식 원문 보기"
            setTextColor(Color.parseColor("#A9C4FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(0, dp(12f), 0, dp(4f))
            setOnClickListener { onDetail() }
        }
        content.addView(title)
        content.addView(chip)
        content.addView(detail)
        content.addView(link)

        val scroller = ScrollView(context).apply {
            // 14~15sp 글자로 6줄 남짓 보이는 높이. 그보다 길면 위아래로 밀어서 본다.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150f))
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(content)
        }

        // 좌우로 밀면 앞뒤 주장으로 넘어간다. 위아래 밀기는 ScrollView 가 그대로 처리한다.
        val swipe = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    val start = e1 ?: return false
                    val dx = e2.x - start.x
                    val dy = e2.y - start.y
                    if (abs(dx) < dp(48f) || abs(dx) < abs(dy) * 1.5f) return false
                    markDone("did_swipe")
                    // 왼쪽으로 밀면 다음, 오른쪽으로 밀면 이전
                    if (dx < 0) onNext() else onPrev()
                    return true
                }
            },
        )
        scroller.setOnTouchListener { _, event ->
            swipe.onTouchEvent(event)
            false
        }
        claimHeader.setOnTouchListener { _, event -> swipe.onTouchEvent(event) }

        bodyBox.addView(summaryWrap)
        bodyBox.addView(divider())
        bodyBox.addView(claimHeader)
        bodyBox.addView(scroller)

        box.addView(dragArea)
        box.addView(bodyBox)

        val layout = WindowManager.LayoutParams(
            // 폴드를 펼친 넓은 화면에서는 너무 넓어지지 않게 제한한다.
            min(screenWidth - dp(20f), dp(560f)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 창 바깥의 터치는 유튜브로 그대로 전달된다. 키보드 입력은 가로채지 않는다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = prefs.getInt("y", dp(110f))
        }

        // 손잡이·머리줄을 끌면 창이 위아래로 움직이고, 짧게 누르면 접힌다.
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawY = 0f
        var downY = 0
        var moved = false
        dragArea.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    downY = layout.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downRawY
                    if (abs(dy) > slop) moved = true
                    if (moved) {
                        val maxY = context.resources.displayMetrics.heightPixels - dp(80f)
                        // 아래쪽 기준 배치라 위로 끌수록 y 가 커진다.
                        layout.y = (downY - dy.toInt()).coerceIn(0, maxY)
                        try {
                            windowManager.updateViewLayout(box, layout)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit().putInt("y", layout.y).apply()
                        markDone("did_drag")
                    } else {
                        collapsed = !collapsed
                        prefs.edit().putBoolean("collapsed", collapsed).apply()
                    }
                    applyState()
                    true
                }
                else -> false
            }
        }

        return try {
            windowManager.addView(box, layout)
            root = box
            hintText = hint
            toggleButton = toggle
            body = bodyBox
            summaryBox = summaryWrap
            summaryText = summary
            claimLabelText = claimLabel
            counterText = counter
            prevButton = prev
            nextButton = next
            scroll = scroller
            titleText = title
            chipText = chip
            detailText = detail
            linkText = link
            builtForWidth = screenWidth
            true
        } catch (e: Exception) {
            log("투명 창을 띄우지 못함: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }
}
