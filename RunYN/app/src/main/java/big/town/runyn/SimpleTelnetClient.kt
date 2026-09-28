package big.town.runyn
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import java.nio.charset.StandardCharsets

class AsyncTelnetClient(
    private val host: String,
    private val port: Int = 23,
    private val timeout: Long = 10_000L
) {
    private var job: Job? = null
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            socket = Socket(host, port).apply { soTimeout = timeout.toInt() }
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            println("Асинхронно подключено к $host:$port")
            true
        } catch (e: Exception) {
            println("Ошибка асинхронного подключения: ${e.message}")
            false
        }
    }

    suspend fun sendCommand(command: String): String = withContext(Dispatchers.IO) {
        try {
            // Приветствие/согласование telnet и приглашение shell — читаем то,
            // что уже пришло, и не блокируемся, если данных больше нет.
            val banner = drain(1_000)
            println("#### Приветствие: $banner")

            outputStream?.write("${command}\r\n".toByteArray(StandardCharsets.UTF_8))
            outputStream?.flush()

            val response = drain(2_000)
            println("#### Ответ shell: $response")
            response
        } catch (e: Exception) {
            "Ошибка: ${e.message}"
        }
    }

    /** Читает всё, что доступно, пока в течение readTimeoutMs не перестанут приходить данные. */
    private fun drain(readTimeoutMs: Int): String {
        val sock = socket ?: return ""
        val stream = inputStream ?: return ""
        val previousTimeout = sock.soTimeout
        val buffer = ByteArray(4096)
        val text = StringBuilder()
        try {
            sock.soTimeout = readTimeoutMs
            while (true) {
                val bytesRead = stream.read(buffer)
                if (bytesRead <= 0) break
                text.append(String(buffer, 0, bytesRead, StandardCharsets.UTF_8))
            }
        } catch (e: SocketTimeoutException) {
            // данных больше нет — это нормальное завершение чтения
        } catch (e: IOException) {
            text.append("\n[ошибка чтения: ${e.message}]")
        } finally {
            try { sock.soTimeout = previousTimeout } catch (e: Exception) { }
        }
        return text.toString()
    }

    fun disconnect() {
        job?.cancel()
        try { inputStream?.close() } catch (e: Exception) { }
        try { outputStream?.close() } catch (e: Exception) { }
        try { socket?.close() } catch (e: Exception) { }
    }
}
