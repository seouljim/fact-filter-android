package app.factfilter.probe

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** AI FACT FILTER 서버 호출. 모든 함수는 백그라운드 스레드에서 불러야 한다. */
class Api(private val base: String) {

    class ApiException(message: String) : Exception(message)

    private fun open(path: String, readTimeoutMs: Int): HttpURLConnection {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = readTimeoutMs
        connection.doOutput = true
        connection.setRequestProperty("content-type", "application/json; charset=utf-8")
        return connection
    }

    private fun errorMessage(connection: HttpURLConnection): String {
        val text = try {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        } catch (_: Exception) {
            null
        }
        val fromServer = try {
            text?.let { JSONObject(it).optString("error") }?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        return fromServer ?: "서버 응답 오류 (${connection.responseCode})"
    }

    /**
     * 제목·채널·길이로 영상 주소를 찾는다.
     * @return 영상 주소. 확실히 일치하는 영상이 없으면 null
     */
    fun resolve(title: String, channel: String?, durationSec: Long): String? {
        val connection = open("/api/resolve", 20_000)
        try {
            val body = JSONObject().put("title", title).put("duration_sec", durationSec)
            if (!channel.isNullOrBlank()) body.put("channel", channel)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            return when (connection.responseCode) {
                200 -> {
                    val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    JSONObject(text).optString("url").takeIf { it.isNotBlank() }
                }
                404 -> null
                else -> throw ApiException(errorMessage(connection))
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 영상 주소를 분석 요청하고, 서버가 보내는 진행 상황을 이벤트 단위로 넘겨준다.
     * 이벤트: progress · claims · card · result · error
     *
     * @param onOpen 연결 객체를 넘겨준다. 도중에 끊으려면 disconnect() 를 부른다.
     */
    fun streamVerify(url: String, onOpen: (HttpURLConnection) -> Unit, onEvent: (event: String, data: String) -> Unit) {
        // 긴 영상은 주장이 많아 오래 걸릴 수 있다.
        val connection = open("/api/verify", 180_000)
        onOpen(connection)
        try {
            connection.setRequestProperty("accept", "text/event-stream")
            connection.outputStream.use { it.write(JSONObject().put("url", url).toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) throw ApiException(errorMessage(connection))

            BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                var event: String? = null
                val data = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> data.append(line.substring(5).trim())
                        line.isEmpty() -> {
                            val name = event
                            if (name != null && data.isNotEmpty()) onEvent(name, data.toString())
                            event = null
                            data.setLength(0)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
