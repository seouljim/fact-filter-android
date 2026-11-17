package app.factfilter.probe

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 서비스가 읽은 재생 정보를 화면(MainActivity)과 나누는 저장소. 앱이 떠 있는 동안만 유지된다. */
object ProbeState {
    /** 지금 유튜브가 알려주는 재생 정보 한 장 */
    data class Snapshot(
        val packageName: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val positionMs: Long,
        val state: String,
        val speed: Float,
        /** 유튜브가 내보낸 메타데이터 항목 이름 전체 (어떤 정보가 더 있는지 확인용) */
        val metadataKeys: List<String>,
        val mediaId: String?,
        val mediaUri: String?,
    )

    @Volatile var listenerConnected: Boolean = false
    @Volatile var current: Snapshot? = null
    @Volatile var sessionPackages: List<String> = emptyList()

    private val log = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    @Synchronized
    fun addLog(line: String) {
        log.addLast("${clock.format(Date())} $line")
        while (log.size > 200) log.removeFirst()
    }

    @Synchronized
    fun logText(): String = log.joinToString("\n")

    fun formatMs(ms: Long): String {
        if (ms < 0) return "알 수 없음"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }
}
