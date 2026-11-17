package app.factfilter.probe

import android.content.ComponentName
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 1단계 시험: 유튜브 앱이 다른 앱에 무엇을 알려주는지 확인한다.
 *
 * 알림 접근 권한이 켜지면 시스템이 이 서비스를 띄운다. 여기서
 *   - 활성 미디어 세션 중 유튜브의 것을 찾아 제목·채널·길이·재생 위치를 읽고
 *   - 유튜브가 재생 중일 때 화면 아래에 반투명 창을 띄워 그 값을 보여준다.
 * 알림의 내용은 읽지도 저장하지도 않는다.
 */
class MediaWatchService : NotificationListenerService() {

    companion object {
        const val YOUTUBE = "com.google.android.youtube"
        private const val TICK_MS = 500L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var sessionManager: MediaSessionManager? = null
    private var youtube: MediaController? = null
    private var overlay: LinearLayout? = null
    private var overlayTitle: TextView? = null
    private var overlayDetail: TextView? = null
    private var lastLoggedTitle: String? = null
    private var lastLoggedState: String? = null

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessions(controllers ?: emptyList())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() {
            ProbeState.addLog("유튜브 세션 종료")
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
        ProbeState.listenerConnected = false
        ProbeState.addLog("알림 접근 연결 끊김")
        handler.removeCallbacks(tick)
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        attach(null)
        hideOverlay()
    }

    private fun onSessions(controllers: List<MediaController>) {
        val packages = controllers.map { it.packageName }
        if (packages != ProbeState.sessionPackages) {
            ProbeState.sessionPackages = packages
            ProbeState.addLog("활성 세션: ${if (packages.isEmpty()) "없음" else packages.joinToString()}")
        }
        attach(controllers.firstOrNull { it.packageName == YOUTUBE })
    }

    private fun attach(controller: MediaController?) {
        if (youtube?.sessionToken == controller?.sessionToken) return
        youtube?.unregisterCallback(controllerCallback)
        youtube = controller
        controller?.registerCallback(controllerCallback, handler)
        if (controller != null) ProbeState.addLog("유튜브 세션 발견")
        refresh()
    }

    /** 유튜브 세션의 현재 값을 읽어 저장하고 창을 갱신한다. */
    private fun refresh() {
        val controller = youtube
        if (controller == null) {
            ProbeState.current = null
            hideOverlay()
            return
        }

        val metadata = controller.metadata
        val state = controller.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val stateName = stateName(state?.state)

        val snapshot = ProbeState.Snapshot(
            packageName = controller.packageName,
            title = title,
            artist = artist,
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: -1,
            positionMs = currentPosition(state),
            state = stateName,
            speed = state?.playbackSpeed ?: 0f,
            metadataKeys = metadata?.keySet()?.sorted() ?: emptyList(),
            mediaId = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
            mediaUri = metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI),
        )
        ProbeState.current = snapshot

        // 제목이나 상태가 바뀔 때만 기록한다.
        if (title != lastLoggedTitle) {
            lastLoggedTitle = title
            ProbeState.addLog(
                "제목: ${title ?: "(없음)"} | 채널: ${artist ?: "(없음)"} | 길이: ${ProbeState.formatMs(snapshot.durationMs)}" +
                    " | mediaId: ${snapshot.mediaId ?: "(없음)"} | 항목: ${snapshot.metadataKeys.joinToString { it.removePrefix("android.media.metadata.") }}",
            )
        }
        if (stateName != lastLoggedState) {
            lastLoggedState = stateName
            ProbeState.addLog("상태: $stateName | 위치: ${ProbeState.formatMs(snapshot.positionMs)} | 속도: ${snapshot.speed}")
        }

        if (state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING) {
            showOverlay(snapshot)
        } else {
            hideOverlay()
        }
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

    // ── 반투명 창 ──────────────────────────────────────────────

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun showOverlay(snapshot: ProbeState.Snapshot) {
        if (!Settings.canDrawOverlays(this)) return

        if (overlay == null) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0C1322"))
                    cornerRadius = dp(10f).toFloat()
                }
            }
            val title = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
            }
            val detail = TextView(this).apply {
                setTextColor(Color.parseColor("#C9D3E6"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 2
            }
            box.addView(title)
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
                x = 0
                y = dp(96f)
                horizontalMargin = 0.03f
                // Android 12 이상은 터치를 통과시키는 창의 불투명도를 0.8 이하로 제한한다.
                alpha = 0.8f
            }
            try {
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(box, params)
                overlay = box
                overlayTitle = title
                overlayDetail = detail
                ProbeState.addLog("투명 창 표시")
            } catch (e: Exception) {
                ProbeState.addLog("투명 창을 띄우지 못함: ${e.javaClass.simpleName} ${e.message}")
                return
            }
        }

        overlayTitle?.text = snapshot.title ?: "(유튜브가 제목을 알려주지 않음)"
        overlayDetail?.text =
            "${snapshot.artist ?: "채널 정보 없음"}  ·  ${ProbeState.formatMs(snapshot.positionMs)} / ${ProbeState.formatMs(snapshot.durationMs)}"
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (_: Exception) {
        }
        overlay = null
        overlayTitle = null
        overlayDetail = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        hideOverlay()
        super.onDestroy()
    }
}
