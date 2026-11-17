package app.factfilter.probe

import android.content.Context
import android.graphics.Color

/** 서버 주소 등 앱 설정 */
object Config {
    /** 빌드에 기본으로 넣어 두는 서버 주소. 비어 있으면 앱 화면에서 입력한다. */
    const val DEFAULT_SERVER = "https://ai-fact-filter.vercel.app"
    private const val PREFS = "factfilter"

    fun server(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("server", null)
        return (saved?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER).trim().trimEnd('/')
    }

    fun saveServer(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("server", value.trim()).apply()
    }

    /** 제목·길이로 찾아낸 영상 주소를 기기에 기억해 둔다. (같은 영상을 다시 볼 때 검색을 생략) */
    fun cachedUrl(context: Context, key: String): String? =
        context.getSharedPreferences("resolved", Context.MODE_PRIVATE).getString(key, null)

    fun cacheUrl(context: Context, key: String, url: String) {
        context.getSharedPreferences("resolved", Context.MODE_PRIVATE).edit().putString(key, url).apply()
    }
}

/**
 * 색으로 구분하는 세 묶음 (웹과 같은 규칙).
 * 색은 "맞다/틀리다"가 아니라 "공식 자료를 찾았는지, 차이가 있는지"만 나타낸다.
 */
enum class Group(val color: Int, val mark: String) {
    FOUND(Color.parseColor("#1A56C4"), "✓"),
    DIFF(Color.parseColor("#B0520A"), "≠"),
    NONE(Color.parseColor("#56617A"), "–"),
}

object Status {
    fun label(code: String): String = when (code) {
        "CONFIRMED" -> "공식 자료 확인"
        "CONFLICT" -> "공식 자료와 상충"
        "PARTIAL" -> "일부 사실 확인"
        "OMITTED_CONTEXT" -> "원문 조건 일부 미포함"
        "UNCONFIRMED" -> "공식 확인 없음"
        "INSUFFICIENT" -> "확인 근거 부족"
        "DISPUTED" -> "전문가·자료 간 이견"
        "OPINION" -> "의견·미래 전망"
        "OUTDATED" -> "시점 경과로 사실 변경"
        "SYSTEM_CORRECTED" -> "검증 오류 정정 완료"
        else -> "확인 근거 부족"
    }

    fun group(code: String): Group = when (code) {
        "CONFIRMED" -> Group.FOUND
        "CONFLICT", "PARTIAL", "OMITTED_CONTEXT", "DISPUTED", "OUTDATED" -> Group.DIFF
        else -> Group.NONE
    }
}

/** 대조가 끝난 결과 */
data class Card(
    val claimCode: String,
    val statusCode: String,
    /** 한 줄 결론 */
    val summary: String,
)

/** 영상 속 주장 하나. card 가 null 이면 아직 대조 중이다. */
class Claim(
    val position: Int,
    val text: String,
    /** 영상에서 이 주장이 나오는 시점(초). 화면 문구에서 나온 주장은 null */
    val tsSec: Int?,
    var card: Card? = null,
)
