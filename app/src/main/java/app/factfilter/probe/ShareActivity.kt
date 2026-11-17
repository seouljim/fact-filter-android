package app.factfilter.probe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

/**
 * 유튜브의 "공유" 목록에 나타나는 진입점.
 * 쇼츠처럼 유튜브가 재생 정보를 알려주지 않는 영상은, 사용자가 공유 버튼으로 주소를 보내면 분석한다.
 * 화면 없이 바로 닫히므로 사용자는 유튜브에 그대로 머문다. 결과는 유튜브 위 반투명 창에 나타난다.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val url = Regex("https?://[^\\s<>\"']+").find(shared)?.value?.trimEnd(')', '.', ',', '!', '?')

        val service = MediaWatchService.instance
        when {
            url == null -> toast("공유된 내용에서 영상 주소를 찾지 못했습니다.")
            Config.server(this).isBlank() -> toast("FACT FILTER 앱에서 서버 주소를 먼저 입력해 주세요.")
            service == null -> toast("FACT FILTER 앱을 열어 알림 접근 권한을 켜 주세요.")
            !Settings.canDrawOverlays(this) -> toast("FACT FILTER 앱에서 '다른 앱 위에 표시' 권한을 켜 주세요.")
            else -> {
                service.startShared(url)
                toast("공식 자료와 대조를 시작합니다.")
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
}
