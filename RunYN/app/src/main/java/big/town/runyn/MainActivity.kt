package big.town.runyn

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

const val STOP_YN="am force-stop  ru.yandex.yandexnavi ; am broadcast -a ru.big.town.anative.CLUSTER_NAVI --ei enable 0"
const val START_YN="am force-stop  ru.yandex.yandexnavi ; am broadcast -a ru.big.town.anative.CLUSTER_NAVI --ei enable 1"

/** Значок с зелёной рамкой — навигатор остановлен (состояние по умолчанию). */
private const val ALIAS_STOPPED = "big.town.runyn.LauncherStopped"

/** Значок с красной рамкой — навигатор запущен. */
private const val ALIAS_RUNNING = "big.town.runyn.LauncherRunning"

/**
 * Окна не показывает: по нажатию на значок в лаунчере отправляет команду
 * по telnet и переключает значок (зелёная рамка <-> красная рамка).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val running = isNaviRunning()
        val command = if (running) STOP_YN else START_YN

        // Значок переключаем сразу, не дожидаясь ответа telnet.
        setNaviRunning(!running)

        lifecycleScope.launch {
            sendYandexNaviCommand(command)
            finish()
        }
    }

    /** Текущее состояние хранится в том, какой из alias'ов сейчас включён. */
    private fun isNaviRunning(): Boolean =
        packageManager.getComponentEnabledSetting(ComponentName(packageName, ALIAS_RUNNING)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setNaviRunning(running: Boolean) {
        enableAlias(ALIAS_RUNNING, running)
        enableAlias(ALIAS_STOPPED, !running)
    }

    private fun enableAlias(alias: String, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(
            ComponentName(packageName, alias),
            state,
            PackageManager.DONT_KILL_APP
        )
    }
}

private suspend fun sendYandexNaviCommand(command: String) {
    val client = AsyncTelnetClient("127.0.0.1")
    if (client.connect()) {
        val response = client.sendCommand(command)
        println("Ответ: $response")
        client.disconnect()
    } else {
        println("Ответ: нет соединения с 127.0.0.1:23")
    }
}
