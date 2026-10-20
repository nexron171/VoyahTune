package com.bms.monitor

import android.provider.Settings
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.util.Properties

/**
 * Простой SSH-клиент (JSch). Соединение переиспользуется; команды сериализуются
 * мьютексом, т.к. утилиты VNS нельзя запускать параллельно.
 *
 *  Подключение: root@172.16.104.20. Если в Settings.Global задан
 *  ssh_tbox_private_key (путь к приватному ключу ed25519) — авторизация по ключу,
 *  иначе по паролю из Settings.Global (ssh_tbox_password), если не определено — 12345.
 */
object SshManager {

    private const val HOST = "172.16.104.20"
    private const val PORT = 22
    private const val USER = "root"
    private const val DEFAULT_PASS = "12345"
    private const val PASS_SETTING_KEY = "ssh_tbox_password"
    private const val KEY_SETTING_KEY = "ssh_tbox_private_key"

    /** Непустое значение Settings.Global или null. */
    private fun getGlobal(name: String): String? {
        val ctx = BmsApp.probeCtx() ?: return null
        return try {
            Settings.Global.getString(ctx.contentResolver, name)?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Пароль: Settings.Global ssh_tbox_password, если не определено — 12345. */
    private fun getPass(): String = getGlobal(PASS_SETTING_KEY) ?: DEFAULT_PASS

    /** Путь к приватному ключу ed25519: Settings.Global ssh_tbox_private_key или null. */
    private fun getKeyPath(): String? = getGlobal(KEY_SETTING_KEY)

    private val mutex = Mutex()
    @Volatile private var session: Session? = null

    // --- Команды из файла commands (perl убран — разбор на стороне приложения) ---
    // CMD_TEMP_MAX / CMD_TEMP_MIN в опросе не используются (заменены CMD_BMS_CAN),
    // оставлены для ручной диагностики vnsTestApp; разбор — Parsers.parseTemp.
    const val CMD_TEMP_MAX = """export LD_LIBRARY_PATH="/mnt/hirain_app/usr/lib"
{ echo 1; sleep 1; echo bms_packTempMax; sleep 3; echo 99; } | /mnt/hirain_app/usr/bin/vnsTestApp test"""

    const val CMD_TEMP_MIN = """export LD_LIBRARY_PATH="/mnt/hirain_app/usr/lib"
{ echo 1; sleep 1; echo bms_packTempMin; sleep 3; echo 99; } | /mnt/hirain_app/usr/bin/vnsTestApp test"""

    /** Сырые CAN-кадры 0x304 BMS_temperature (~10/с), 0x514 BMS_metric (~1/с,
     *  SOH), 0x584 BMS_moduleTemperature (кадры 0 и 1, ~5/с каждый) и 0x574
     *  BMS_tboxRequest (~1/с, изоляция и индексы Max/Min): за 3 с приходит ~2 кадра 0x514 и 0x574,
     *  берётся последний каждого.
     *  vnsTestApp отдаёт Max/Avg как -nan — vnsd не заполняет их в общей
     *  памяти, хотя в кадре они есть. */
    const val CMD_BMS_CAN = """export LD_LIBRARY_PATH="/mnt/hirain_app/usr/lib"
timeout -s INT 3 stdbuf -o0 -e0 /mnt/hirain_app/usr/bin/vnsMonitorCan -s:0x514,0x304,0x584,0x574 2>&1 | grep -a -A1 -E "CAN\[(304|514|584|574)\]""""

    /**
     * Ячейки: сырые кадры 0x594 BMS_moduleVoltage — мультиплексный кадр,
     * 4 ячейки на кадр, для 100 ячеек нужны кадры 0..24 (~10/с, цикл ≈2.6 с).
     * vnsMonitorCan буферизует вывод (stdbuf не помогает), поэтому окно
     * фиксированное: подписка занимает 1..3.7 с, 6 с обычно хватает на полный
     * цикл; если нет — MainViewModel.readCells повторяет запрос.
     */
    const val CMD_CELLS = """export LD_LIBRARY_PATH="/mnt/hirain_app/usr/lib"
timeout -s INT 6 stdbuf -o0 -e0 /mnt/hirain_app/usr/bin/vnsMonitorCan -s:0x594 2>&1 | grep -a -A1 -F "CAN[594]""""

    /** Запись лога: 0x304, 0x514, 0x584, 0x574 и ячейки 0x594 одним запуском vnsMonitorCan,
     *  окно 6 с как у CMD_CELLS (за ним — полный цикл 0x594 и 0x514). */
    const val CMD_BMS_CAN_CELLS = """export LD_LIBRARY_PATH="/mnt/hirain_app/usr/lib"
timeout -s INT 6 stdbuf -o0 -e0 /mnt/hirain_app/usr/bin/vnsMonitorCan -s:0x514,0x304,0x584,0x574,0x594 2>&1 | grep -a -A1 -E "CAN\[(304|514|584|574|594)\]""""

    private fun ensureSession(): Session {
        var s = session
        if (s != null && s.isConnected) return s
        val jsch = JSch()
        val keyPath = getKeyPath()
        if (keyPath != null) jsch.addIdentity(keyPath)
        s = jsch.getSession(USER, HOST, PORT)
        val cfg = Properties()
        cfg["StrictHostKeyChecking"] = "no"
        if (keyPath != null) {
            cfg["PreferredAuthentications"] = "publickey"
        } else {
            s.setPassword(getPass())
            cfg["PreferredAuthentications"] = "password"
        }
        s.setConfig(cfg)
        s.serverAliveInterval = 15000
        s.connect(15000)
        session = s
        return s
    }

    /** Выполнить команду и вернуть СЫРЫЕ байты stdout (нужно для бинарного блока ячеек). */
    suspend fun exec(command: String): ByteArray = mutex.withLock {
        var channel: ChannelExec? = null
        try {
            val s = ensureSession()
            channel = s.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.setErrStream(null)
            val input = channel.inputStream
            channel.connect(15000)

            val buf = ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            while (true) {
                while (input.available() > 0) {
                    val n = input.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                }
                if (channel.isClosed) {
                    if (input.available() > 0) continue
                    break
                }
                Thread.sleep(50)
            }
            buf.toByteArray()
        } catch (e: Exception) {
            // Сбрасываем сессию, чтобы следующий вызов переподключился
            try { session?.disconnect() } catch (_: Exception) {}
            session = null
            throw e
        } finally {
            try { channel?.disconnect() } catch (_: Exception) {}
        }
    }

    fun close() {
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
    }
}
