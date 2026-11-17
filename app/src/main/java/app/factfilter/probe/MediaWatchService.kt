package app.factfilter.probe

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.Executors

/**
 * 유튜브를 지켜보다가 영상 위에 반투명 창으로 대조 결과를 보여준다.
 *
 * 자동 모드 (긴 영상)
 *   알림 접근 권한으로 유튜브의 미디어 세션에서 제목·채널·길이·재생 위치를 읽는다.
 *   → 서버에서 제목·길이로 영상을 찾고 → 분석을 요청하고 → 재생 위치에 맞춰 주장과 결과를 띄운다.
 *
 * 공유 모드 (쇼츠)
 *   유튜브는 쇼츠의 재생 정보를 비워서 내보낸다. 사용자가 공유 버튼으로 주소를 보내면
 *   그 주소를 분석해 결과를 차례로 띄운다.
 *
 * 알림의 내용은 읽지도 저장하지도 않는다.
 */
class MediaWatchService : NotificationListenerService() {

    companion object {
        const val YOUTUBE = "com.google.android.youtube"
        private const val TICK_MS = 500L

        /** 채널명은 제목보다 늦게 도착하므로 잠깐 기다렸다가 영상을 찾는다. */
        private const val RESOLVE_DELAY_MS = 2_500L

        /** 주장이 나온 뒤 결과를 크게 보여주는 시간 */
        private const val ACTIVE_SHOW_SEC = 25

        /** 공유 모드에서 주장 하나를 보여주는 시간 */
        private const val SHARED_ROTATE_MS = 8_000L

        /** 공유 모드에서 분석이 끝난 뒤 창을 유지하는 시간 */
        private const val SHARED_KEEP_MS = 45_000L

        @Volatile
        var instance: MediaWatchService? = null
    }

    private enum class Mode { AUTO, SHARED }
    private enum class Phase { IDLE, RESOLVING, NOT_FOUND, ANALYZING, DONE, ERROR }

    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newCachedThreadPool()
    private var sessionManager: MediaSessionManager? = null
    private var youtube: MediaController? = null

    // ── 분석 상태 (메인 스레드에서만 읽고 쓴다) ────────────────
    /** 분석을 새로 시작할 때마다 올린다. 이전 분석의 뒤늦은 응답을 버리는 데 쓴다. */
    private var generation = 0
    private var mode = Mode.AUTO
    private var phase = Phase.IDLE
    private var message = ""
    private var claims: List<Claim> = emptyList()
    private var trackKey: String? = null
    private var phaseSince = 0L
    private var connection: HttpURLConnection? = null

    // ── 재생 상태 ─────────────────────────────────────────────
    private var playing = false
    private var positionSec = 0L
    private var lastLoggedTitle: String? = null
    private var lastLoggedState: String? = null

    // ── 창 ───────────────────────────────────────────────────
    private var overlay: LinearLayout? = null
    private var headerView: TextView? = null
    private var bodyView: TextView? = null
    private var chipView: TextView? = null
    private var detailView: TextView? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessions(controllers ?: emptyList())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() {
            attach(null)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onListenerConnected() {
        instance = this
        ProbeState.listenerConnected = true
        ProbeState.addLog("알림 접근 연결됨")
        val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        sessionManager = manager
        val self = ComponentName(this, MediaWatchService::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, self, handler)
            onSessions(manager.getActiveSessions(self))
        } catch (e: SecurityException) {
            ProbeState.addLog("세션 목록을 읽을 권한이 없음: ${e.message}")
        }
        handler.post(tick)
    }

    override fun onListenerDisconnected() {
        instance = null
        ProbeState.listenerConnected = false
        ProbeState.addLog("알림 접근 연결 끊김")
        handler.removeCallbacksAndMessages(null)
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        attach(null)
        reset()
        hideOverlay()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        cancelNetwork()
        hideOverlay()
        network.shutdownNow()
        super.onDestroy()
    }

    // ── 유튜브 세션 읽기 ──────────────────────────────────────

    private fun onSessions(controllers: List<MediaController>) {
        val packages = controllers.map { it.packageName }
        if (packages != ProbeState.sessionPackages) {
            ProbeState.sessionPackages = packages
        }
        attach(controllers.firstOrNull { it.packageName == YOUTUBE })
    }

    private fun attach(controller: MediaController?) {
        if (youtube?.sessionToken == controller?.sessionToken) return
        youtube?.unregisterCallback(controllerCallback)
        youtube = controller
        controller?.registerCallback(controllerCallback, handler)
        refresh()
    }

    /** 유튜브 세션의 현재 값을 읽고, 필요하면 분석을 시작하고, 창을 갱신한다. */
    private fun refresh() {
        val controller = youtube
        val metadata = controller?.metadata
        val state = controller?.playbackState

        val title = (
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            )?.trim().orEmpty()
        val artist = (
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            )?.trim().orEmpty()
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: -1L
        val positionMs = currentPosition(state)
        val stateLabel = stateName(state?.state)

        playing = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING
        positionSec = if (positionMs >= 0) positionMs / 1000 else 0

        ProbeState.current = if (controller == null) null else ProbeState.Snapshot(
            packageName = controller.packageName,
            title = title.ifEmpty { null },
            artist = artist.ifEmpty { null },
            album = null,
            durationMs = durationMs,
            positionMs = positionMs,
            state = stateLabel,
            speed = state?.playbackSpeed ?: 0f,
            metadataKeys = emptyList(),
            mediaId = null,
            mediaUri = null,
        )
        if (title != lastLoggedTitle) {
            lastLoggedTitle = title
            if (title.isNotEmpty()) ProbeState.addLog("재생: $title (${ProbeState.formatMs(durationMs)})")
        }
        if (stateLabel != lastLoggedState) {
            lastLoggedState = stateLabel
        }

        // 제목과 길이를 알려주는 영상(긴 영상)이면 자동으로 분석한다.
        if (title.isNotEmpty() && durationMs > 0) {
            val key = "$title|${durationMs / 1000}"
            if (key != trackKey) startAuto(key, title, durationMs / 1000)
        } else if (mode == Mode.AUTO && trackKey != null) {
            // 영상이 끝났거나 쇼츠로 넘어갔다.
            ProbeState.addLog("영상 정보 없음 → 대기")
            reset()
        }

        render()
    }

    /** 마지막으로 보고된 위치에 그 뒤로 흐른 시간을 더해 지금 위치를 계산한다. */
    private fun currentPosition(state: PlaybackState?): Long {
        if (state == null || state.position < 0) return -1
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
        return state.position + (elapsed * state.playbackSpeed).toLong()
    }

    private fun stateName(state: Int?): String = when (state) {
        PlaybackState.STATE_PLAYING -> "재생 중"
        PlaybackState.STATE_PAUSED -> "일시정지"
        PlaybackState.STATE_BUFFERING -> "버퍼링"
        PlaybackState.STATE_STOPPED -> "정지"
        PlaybackState.STATE_NONE, null -> "없음"
        else -> "기타($state)"
    }

    // ── 분석 흐름 ─────────────────────────────────────────────

    private fun setPhase(next: Phase, text: String = "") {
        phase = next
        message = text
        phaseSince = SystemClock.elapsedRealtime()
    }

    private fun cancelNetwork() {
        val open = connection
        connection = null
        if (open != null) network.execute { try { open.disconnect() } catch (_: Exception) {} }
    }

    private fun reset() {
        generation++
        cancelNetwork()
        mode = Mode.AUTO
        trackKey = null
        claims = emptyList()
        setPhase(Phase.IDLE)
    }

    /** 자동 모드: 새 영상이 재생되기 시작했다. */
    private fun startAuto(key: String, title: String, durationSec: Long) {
        reset()
        trackKey = key
        val run = generation
        val server = Config.server(this)
        if (server.isBlank()) {
            setPhase(Phase.ERROR, "FACT FILTER 앱에서 서버 주소를 입력해 주세요.")
            return
        }
        setPhase(Phase.RESOLVING, "영상을 확인하고 있습니다")

        val cached = Config.cachedUrl(this, key)
        if (cached != null) {
            ProbeState.addLog("기억해 둔 영상 주소 사용")
            startAnalysis(run, cached)
            return
        }

        // 채널명이 늦게 도착하므로 잠깐 기다렸다가 그 시점의 값을 읽는다.
        handler.postDelayed({
            if (run != generation) return@postDelayed
            val channel = youtube?.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
            network.execute {
                try {
                    val url = Api(server).resolve(title, channel.ifEmpty { null }, durationSec)
                    handler.post {
                        if (run != generation) return@post
                        if (url == null) {
                            ProbeState.addLog("영상을 찾지 못함: $title")
                            setPhase(Phase.NOT_FOUND, "이 영상을 찾지 못했습니다. 유튜브의 공유 → FACT FILTER 로 보내 주세요.")
                            render()
                        } else {
                            ProbeState.addLog("영상 찾음: $url")
                            Config.cacheUrl(this, key, url)
                            startAnalysis(run, url)
                        }
                    }
                } catch (e: Exception) {
                    handler.post {
                        if (run != generation) return@post
                        ProbeState.addLog("영상 찾기 실패: ${e.javaClass.simpleName} ${e.message}")
                        setPhase(Phase.ERROR, e.message ?: "서버에 연결하지 못했습니다.")
                        render()
                    }
                }
            }
        }, RESOLVE_DELAY_MS)
    }

    /** 공유 모드: 사용자가 유튜브의 공유 버튼으로 주소를 보냈다. */
    fun startShared(url: String) {
        handler.post {
            reset()
            mode = Mode.SHARED
            ProbeState.addLog("공유로 받은 주소: $url")
            startAnalysis(generation, url)
            render()
        }
    }

    private fun startAnalysis(run: Int, url: String) {
        val server = Config.server(this)
        if (server.isBlank()) {
            setPhase(Phase.ERROR, "FACT FILTER 앱에서 서버 주소를 입력해 주세요.")
            return
        }
        setPhase(Phase.ANALYZING, "분석을 시작합니다")
        network.execute {
            try {
                Api(server).streamVerify(
                    url,
                    onOpen = { opened -> handler.post { if (run == generation) connection = opened } },
                    onEvent = { event, data -> handler.post { if (run == generation) onServerEvent(event, data) } },
                )
                handler.post {
                    if (run == generation && phase == Phase.ANALYZING) {
                        setPhase(Phase.ERROR, "응답이 중간에 끊겼습니다.")
                        render()
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    if (run != generation) return@post
                    ProbeState.addLog("분석 요청 실패: ${e.javaClass.simpleName} ${e.message}")
                    setPhase(Phase.ERROR, e.message ?: "서버에 연결하지 못했습니다.")
                    render()
                }
            }
        }
    }

    private fun onServerEvent(event: String, data: String) {
        try {
            when (event) {
                "progress" -> message = JSONObject(data).optString("message", message)
                "claims" -> {
                    val array = JSONArray(data)
                    claims = sorted((0 until array.length()).map { i ->
                        val item = array.getJSONObject(i)
                        Claim(
                            position = item.getInt("position"),
                            text = item.getString("anonymized_proposition"),
                            tsSec = if (item.isNull("timestamp_sec")) null else item.getInt("timestamp_sec"),
                        )
                    })
                    ProbeState.addLog("주장 ${claims.size}건 발견")
                }
                "card" -> putCard(JSONObject(data))
                "result" -> {
                    val result = JSONObject(data)
                    val cards = result.optJSONArray("cards") ?: JSONArray()
                    for (i in 0 until cards.length()) putCard(cards.getJSONObject(i))
                    // 대조에 실패한 주장은 결과에 없다. 계속 "분석 중"으로 남지 않게 뺀다.
                    claims = claims.filter { it.card != null }
                    val notice = if (result.isNull("notice")) "" else result.optString("notice")
                    setPhase(Phase.DONE, if (claims.isEmpty()) notice.ifBlank { "대조할 주장을 찾지 못했습니다." } else "")
                    ProbeState.addLog("분석 완료: ${claims.size}건")
                }
                "error" -> {
                    val text = JSONObject(data).optString("message", "확인 중 문제가 발생했습니다.")
                    ProbeState.addLog("서버 오류: $text")
                    setPhase(Phase.ERROR, text)
                }
            }
        } catch (e: Exception) {
            ProbeState.addLog("응답 해석 실패($event): ${e.message}")
        }
        render()
    }

    private fun putCard(json: JSONObject) {
        val position = json.optInt("position", 0)
        val extracted = json.getJSONObject("extracted_claim")
        val verification = json.getJSONObject("verification")
        val card = Card(
            claimCode = json.optString("claim_code"),
            statusCode = verification.optString("status_code"),
            summary = verification.optString("delta_summary"),
        )
        val existing = claims.firstOrNull { it.position == position }
        if (existing != null) {
            existing.card = card
        } else {
            // 이미 확인한 적 있는 영상은 주장 목록 없이 카드가 바로 온다.
            claims = sorted(
                claims + Claim(
                    position = position,
                    text = extracted.getString("anonymized_proposition"),
                    tsSec = if (extracted.isNull("timestamp_sec")) null else extracted.getInt("timestamp_sec"),
                    card = card,
                ),
            )
        }
    }

    private fun sorted(list: List<Claim>): List<Claim> = list.sortedWith(compareBy({ it.tsSec ?: 0 }, { it.position }))

    // ── 창에 무엇을 보여줄지 ──────────────────────────────────

    private class Panel(
        val header: String,
        val body: String,
        val chip: String? = null,
        val chipColor: Int = 0,
        val detail: String? = null,
    )

    private fun clock(sec: Int): String = ProbeState.formatMs(sec * 1000L)

    private fun panelFor(claim: Claim, header: String): Panel {
        val card = claim.card
        return if (card == null) {
            Panel(header, claim.text, detail = "공식 자료와 대조하는 중…")
        } else {
            val group = Status.group(card.statusCode)
            Panel(header, claim.text, chip = "${group.mark} ${Status.label(card.statusCode)}", chipColor = group.color, detail = card.summary)
        }
    }

    /** 지금 창에 보여줄 내용. null 이면 창을 숨긴다. */
    private fun currentPanel(): Panel? {
        val sincePhase = SystemClock.elapsedRealtime() - phaseSince

        if (mode == Mode.SHARED) {
            return when (phase) {
                Phase.ERROR -> if (sincePhase < 12_000) Panel("FACT FILTER", message) else null
                Phase.ANALYZING, Phase.DONE -> {
                    if (phase == Phase.DONE && sincePhase > SHARED_KEEP_MS) return null
                    if (claims.isEmpty()) {
                        return if (phase == Phase.DONE) Panel("FACT FILTER", message) else Panel("FACT FILTER", "$message…")
                    }
                    // 주장을 차례로 돌려 가며 보여준다.
                    val index = ((SystemClock.elapsedRealtime() / SHARED_ROTATE_MS) % claims.size).toInt()
                    panelFor(claims[index], "주장 ${index + 1} / ${claims.size}")
                }
                else -> null
            }
        }

        // 자동 모드는 영상이 재생 중일 때만 보여준다.
        if (!playing) return null
        return when (phase) {
            Phase.IDLE -> null
            Phase.RESOLVING -> Panel("FACT FILTER", "$message…")
            Phase.NOT_FOUND -> if (sincePhase < 12_000) Panel("FACT FILTER", message) else null
            Phase.ERROR -> if (sincePhase < 12_000) Panel("FACT FILTER", message) else null
            Phase.ANALYZING, Phase.DONE -> {
                if (claims.isEmpty()) {
                    return when {
                        phase == Phase.ANALYZING -> Panel("FACT FILTER", "$message…")
                        sincePhase < 8_000 -> Panel("FACT FILTER", message)
                        else -> null
                    }
                }
                val now = positionSec.toInt()
                val active = claims.lastOrNull { (it.tsSec ?: 0) <= now }
                val next = claims.firstOrNull { (it.tsSec ?: 0) > now }
                val nextHint = next?.tsSec?.let { "다음 주장 ${clock(it)}" }

                if (active != null && now - (active.tsSec ?: 0) <= ACTIVE_SHOW_SEC) {
                    val at = active.tsSec?.let { "${clock(it)}에 나온 주장" } ?: "화면 문구에 나온 주장"
                    panelFor(active, if (nextHint != null) "$at · $nextHint" else at)
                } else {
                    // 주장이 없는 구간에서는 한 줄만 작게 보여준다.
                    val pending = claims.count { it.card == null }
                    val status = if (pending > 0) "주장 ${claims.size}건 중 ${claims.size - pending}건 대조 완료" else "주장 ${claims.size}건 대조 완료"
                    Panel(if (nextHint != null) "$status · $nextHint" else status, "")
                }
            }
        }
    }

    // ── 반투명 창 ─────────────────────────────────────────────

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun render() {
        val panel = currentPanel()
        if (panel == null || !Settings.canDrawOverlays(this)) {
            hideOverlay()
            return
        }
        if (overlay == null && !createOverlay()) return

        headerView?.text = panel.header
        bodyView?.apply {
            text = panel.body
            visibility = if (panel.body.isEmpty()) View.GONE else View.VISIBLE
        }
        chipView?.apply {
            if (panel.chip == null) {
                visibility = View.GONE
            } else {
                text = panel.chip
                (background as? GradientDrawable)?.setColor(panel.chipColor)
                visibility = View.VISIBLE
            }
        }
        detailView?.apply {
            text = panel.detail.orEmpty()
            visibility = if (panel.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun createOverlay(): Boolean {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(12f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0C1322"))
                cornerRadius = dp(12f).toFloat()
            }
        }
        val header = TextView(this).apply {
            setTextColor(Color.parseColor("#AEB9D0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
        }
        val body = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 3
            setPadding(0, dp(3f), 0, 0)
        }
        val chip = TextView(this).apply {
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
        val detail = TextView(this).apply {
            setTextColor(Color.parseColor("#E3E9F5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 4
            setPadding(0, dp(6f), 0, 0)
        }
        box.addView(header)
        box.addView(body)
        box.addView(chip)
        box.addView(detail)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 창이 터치를 가로채지 않아 유튜브를 그대로 조작할 수 있다.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96f)
            horizontalMargin = 0.03f
            // Android 12 이상은 터치를 통과시키는 창의 불투명도를 0.8 이하로 제한한다.
            alpha = 0.8f
        }
        return try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, params)
            overlay = box
            headerView = header
            bodyView = body
            chipView = chip
            detailView = detail
            true
        } catch (e: Exception) {
            ProbeState.addLog("투명 창을 띄우지 못함: ${e.javaClass.simpleName} ${e.message}")
            false
        }
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
        }
        overlay = null
        headerView = null
        bodyView = null
        chipView = null
        detailView = null
    }
}
