package app.factfilter.probe

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
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
 *  │ 02:14에 나온 주장            ‹  3/6  ›    －  │  ← 머리줄: 끌면 창이 위아래로 움직이고, 누르면 접힌다
 *  ├──────────────────────────────────────────────┤
 *  │ 주장 문장                                     │
 *  │ [≠ 원문 조건 일부 미포함]                      │  ← 본문: 내용이 길면 위아래로 밀어서 본다
 *  │ 한 줄 결론 …                                  │
 *  └──────────────────────────────────────────────┘
 *
 * 창 안쪽의 터치는 창이 받고, 창 바깥은 그대로 유튜브로 전달된다.
 */
class OverlayPanel(
    private val context: Context,
    private val onPrev: () -> Unit,
    private val onNext: () -> Unit,
    private val log: (String) -> Unit,
) {
    /** 창에 보여줄 내용 한 장 */
    class Content(
        val header: String,
        val title: String,
        val chip: String? = null,
        val chipColor: Int = 0,
        val detail: String? = null,
        /** "3/6" 같은 순번. null 이면 앞뒤 넘김 버튼을 숨긴다. */
        val counter: String? = null,
        /** 내용이 바뀌었는지 판단하는 값. 바뀌면 본문을 맨 위로 되돌린다. */
        val key: String = "",
    )

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var headerText: TextView? = null
    private var counterText: TextView? = null
    private var prevButton: TextView? = null
    private var nextButton: TextView? = null
    private var toggleButton: TextView? = null
    private var scroll: ScrollView? = null
    private var titleText: TextView? = null
    private var chipText: TextView? = null
    private var detailText: TextView? = null

    private var collapsed = prefs.getBoolean("collapsed", false)
    private var lastKey: String? = null
    private var builtForWidth = 0

    val isShown: Boolean get() = root != null

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun show(content: Content) {
        // 화면을 돌리거나 폴드를 펼치면 폭이 달라지므로 다시 만든다.
        val screenWidth = context.resources.displayMetrics.widthPixels
        if (root != null && builtForWidth != screenWidth) hide()
        if (root == null && !create(screenWidth)) return

        headerText?.text = content.header
        counterText?.apply {
            text = content.counter.orEmpty()
            visibility = if (content.counter == null) View.GONE else View.VISIBLE
        }
        val nav = if (content.counter == null) View.GONE else View.VISIBLE
        prevButton?.visibility = nav
        nextButton?.visibility = nav

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

        if (content.key != lastKey) {
            lastKey = content.key
            scroll?.scrollTo(0, 0)
        }
        applyCollapsed()
    }

    fun hide() {
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
        }
        root = null
        params = null
        lastKey = null
    }

    private fun applyCollapsed() {
        scroll?.visibility = if (collapsed) View.GONE else View.VISIBLE
        toggleButton?.text = if (collapsed) "＋" else "－"
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

    private fun create(screenWidth: Int): Boolean {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                // 창 전체가 아니라 바탕색만 반투명하게 해서 글자는 또렷하게 둔다.
                setColor(Color.argb(224, 12, 19, 34))
                cornerRadius = dp(12f).toFloat()
            }
        }

        // ── 머리줄 ──
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(2f), dp(4f), dp(2f))
        }
        val header = TextView(context).apply {
            setTextColor(Color.parseColor("#C3CCE0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val prev = smallButton("‹", "이전 주장") { onPrev() }
        val counter = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val next = smallButton("›", "다음 주장") { onNext() }
        val toggle = smallButton("－", "창 접기 또는 펼치기") {
            collapsed = !collapsed
            prefs.edit().putBoolean("collapsed", collapsed).apply()
            applyCollapsed()
        }
        headerRow.addView(header)
        headerRow.addView(prev)
        headerRow.addView(counter)
        headerRow.addView(next)
        headerRow.addView(toggle)

        // ── 본문 (길면 위아래로 밀어서 본다) ──
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
        content.addView(title)
        content.addView(chip)
        content.addView(detail)

        val scroller = ScrollView(context).apply {
            // 14~15sp 글자로 6줄 남짓 보이는 높이. 그보다 길면 밀어서 본다.
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(156f))
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(content)
        }

        box.addView(headerRow)
        box.addView(scroller)

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

        // 머리줄을 끌면 창이 위아래로 움직이고, 짧게 누르면 접힌다.
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawY = 0f
        var downY = 0
        var moved = false
        headerRow.setOnTouchListener { _, event ->
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
                    } else {
                        collapsed = !collapsed
                        prefs.edit().putBoolean("collapsed", collapsed).apply()
                        applyCollapsed()
                    }
                    true
                }
                else -> false
            }
        }

        return try {
            windowManager.addView(box, layout)
            root = box
            params = layout
            headerText = header
            counterText = counter
            prevButton = prev
            nextButton = next
            toggleButton = toggle
            scroll = scroller
            titleText = title
            chipText = chip
            detailText = detail
            builtForWidth = screenWidth
            true
        } catch (e: Exception) {
            log("투명 창을 띄우지 못함: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }
}
