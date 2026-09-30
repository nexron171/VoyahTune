package big.town.runyn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import big.town.runyn.ui.theme.RunYNTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** `am force-stop <пакет выбранного приложения>` + выключение кластера. */
fun stopCommand(packageName: String) =
    "am force-stop  $packageName ; am broadcast -a ru.big.town.anative.CLUSTER_NAVI --ei enable 0"

/** `am force-stop <пакет>` + запуск активити выбранного приложения на кластере. */
fun startCommand(packageName: String, component: String) =
    "am force-stop  $packageName ; am broadcast -a ru.big.town.anative.CLUSTER_NAVI --es component $component"

// На тёмном фоне нужны более светлые оттенки, иначе рамка почти не видна
private val BorderStopped = Color(0xFF66BB6A) // зелёная — навигатор не запущен
private val BorderRunning = Color(0xFFEF5350) // красная — навигатор запущен

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RunYNTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LaunchToggle(scope = lifecycleScope)
                }
            }
        }
    }
}

@Composable
fun LaunchToggle(scope: CoroutineScope) {
    // false — остановлено (зелёная рамка), true — запущено (красная рамка)
    var running by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Выбранное приложение переживает перезапуск — лежит в SharedPreferences.
    var selection by remember { mutableStateOf(loadSelection(context)) }
    var apps by remember { mutableStateOf(emptyList<AppInfo>()) }
    LaunchedEffect(context) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
    }

    // Значок кнопки — иконка выбранного приложения. Пока список не прочитан
    // (или пакет удалён) показываем собственную иконку RunYN.
    // На API 26+ это adaptive icon (XML), painterResource его не умеет,
    // поэтому берём Drawable и превращаем в bitmap.
    val fallbackIcon = remember(context) {
        context.packageManager.getApplicationIcon(context.packageName)
            .toBitmap(width = 192, height = 192)
            .asImageBitmap()
    }
    var icon by remember { mutableStateOf(fallbackIcon) }
    LaunchedEffect(selection.packageName) {
        icon = withContext(Dispatchers.IO) { loadAppIcon(context, selection.packageName) }
            ?: fallbackIcon
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .padding(top = 16.dp)
                .size(140.dp)
                .border(
                    width = 6.dp,
                    color = if (running) BorderRunning else BorderStopped,
                    shape = RoundedCornerShape(24.dp)
                )
                .clickable(enabled = !busy) {
                    val command = if (running) {
                        stopCommand(selection.packageName)
                    } else {
                        startCommand(selection.packageName, selection.component)
                    }
                    // Рамку переключаем сразу, не дожидаясь ответа telnet.
                    running = !running
                    busy = true
                    scope.launch {
                        sendYandexNaviCommand(command)
                        busy = false
                    }
                }
                .padding(12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = icon,
                contentDescription = if (running) "Остановить" else "Запустить",
                modifier = Modifier.fillMaxSize()
            )
        }

        Text(
            text = if (running) "Запущено: ${selection.label}" else "Остановлено: ${selection.label}",
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
            color = if (running) BorderRunning else BorderStopped,
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "Выберите приложение",
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(apps, key = { it.component }) { app ->
                AppRow(
                    app = app,
                    selected = app.component == selection.component,
                    onClick = {
                        saveSelection(context, app)
                        selection = Selection(app.label, app.packageName, app.component)
                    }
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: AppInfo, selected: Boolean, onClick: () -> Unit) {
    val background =
        if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val labelColor =
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(background)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = app.label,
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
