package big.town.runyn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** Значения по умолчанию — те же, что были зашиты в командах раньше. */
const val DEFAULT_PACKAGE = "ru.yandex.yandexmaps"
const val DEFAULT_COMPONENT = "ru.yandex.yandexmaps/.launch.LaunchActivity"

private const val PREFS_NAME = "runyn"
private const val KEY_PACKAGE = "selected_package"
private const val KEY_COMPONENT = "selected_component"
private const val KEY_LABEL = "selected_label"

/** Приложение, которое можно выбрать в списке. */
data class AppInfo(
    val label: String,
    val packageName: String,
    /** Компонент запуска в коротком виде: `пакет/.Активити` — как в команде START_YN. */
    val component: String,
    val icon: ImageBitmap
)

/** Запомненный выбор пользователя. */
data class Selection(
    val label: String,
    val packageName: String,
    val component: String
)

/**
 * Список приложений, у которых есть значок в лаунчере (своё приложение исключаем).
 * Системные не отбрасываем: на голове навигатор нередко предустановлен.
 */
fun loadLaunchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .asSequence()
        .filter { it.activityInfo.packageName != context.packageName }
        .filter { it.activityInfo.enabled && it.activityInfo.applicationInfo.enabled }
        .map { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo
            AppInfo(
                label = resolveInfo.loadLabel(pm).toString(),
                packageName = activityInfo.packageName,
                component = ComponentName(activityInfo.packageName, activityInfo.name)
                    .flattenToShortString(),
                icon = resolveInfo.loadIcon(pm).toBitmap(width = 96, height = 96).asImageBitmap()
            )
        }
        .distinctBy { it.component }
        .sortedWith(compareBy({ it.isSystem(context) }, { it.label.lowercase() }))
        .toList()
}

/** Пользовательские приложения — в начало списка, предустановленные — после них. */
private fun AppInfo.isSystem(context: Context): Boolean = try {
    val flags = context.packageManager.getApplicationInfo(packageName, 0).flags
    flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
} catch (e: Exception) {
    false
}

fun loadSelection(context: Context): Selection {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return Selection(
        label = prefs.getString(KEY_LABEL, DEFAULT_PACKAGE) ?: DEFAULT_PACKAGE,
        packageName = prefs.getString(KEY_PACKAGE, DEFAULT_PACKAGE) ?: DEFAULT_PACKAGE,
        component = prefs.getString(KEY_COMPONENT, DEFAULT_COMPONENT) ?: DEFAULT_COMPONENT
    )
}

fun saveSelection(context: Context, app: AppInfo) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_LABEL, app.label)
        .putString(KEY_PACKAGE, app.packageName)
        .putString(KEY_COMPONENT, app.component)
        .apply()
}

/** Иконка выбранного приложения для кнопки-переключателя; null, если пакет не найден. */
fun loadAppIcon(context: Context, packageName: String): ImageBitmap? = try {
    context.packageManager.getApplicationIcon(packageName)
        .toBitmap(width = 192, height = 192)
        .asImageBitmap()
} catch (e: Exception) {
    null
}
