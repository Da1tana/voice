package ru.voiceassistant.ui.permissions

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

object Permissions {
    fun mic(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun exactAlarms(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun exactAlarmSettingsIntent(ctx: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
        } else appSettingsIntent(ctx)

    fun appSettingsIntent(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
}

/** Пересчитывает состояние разрешений при возврате на экран (например, из системных настроек). */
@Composable
fun rememberPermissionTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

/** Карточки с объяснением разрешений. Используется при первом запуске и в настройках. */
@Composable
fun PermissionCards(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tick = rememberPermissionTick()
    var localTick by remember { mutableIntStateOf(0) }
    val key = tick + localTick

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { localTick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { localTick++ }

    val micOk = remember(key) { Permissions.mic(ctx) }
    val notifOk = remember(key) { Permissions.notifications(ctx) }
    val alarmsOk = remember(key) { Permissions.exactAlarms(ctx) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PermissionCard(
            icon = Icons.Outlined.Mic,
            title = "Микрофон",
            text = "Чтобы слышать ваши команды. Запись идёт только когда вы нажали кнопку или произнесли " +
                "ключевую фразу. Ключевая фраза распознаётся на телефоне, на сервер уходит только распознанный текст.",
            granted = micOk,
            onRequest = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionCard(
                icon = Icons.Outlined.Notifications,
                title = "Уведомления",
                text = "Для напоминаний, таймеров и значка «Ассистент слушает», пока включена ключевая фраза.",
                granted = notifOk,
                onRequest = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionCard(
                icon = Icons.Outlined.Alarm,
                title = "Точные будильники",
                text = "Чтобы напоминания приходили минута в минуту. Без этого система может задержать их на несколько минут.",
                granted = alarmsOk,
                onRequest = { ctx.startActivity(Permissions.exactAlarmSettingsIntent(ctx)) },
            )
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.BatterySaver, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Huawei: энергосбережение", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "EMUI закрывает фоновые приложения. Чтобы ключевая фраза и напоминания работали, откройте " +
                        "Настройки → Аккумулятор → Запуск приложений → «Ассистент» и включите ручное управление " +
                        "со всеми тремя переключателями.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { ctx.startActivity(Permissions.appSettingsIntent(ctx)) }) {
                    Text("Открыть настройки приложения")
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(icon: ImageVector, title: String, text: String, granted: Boolean, onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (granted) Icon(Icons.Filled.CheckCircle, "Разрешено", tint = MaterialTheme.colorScheme.tertiary)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (!granted) Button(onClick = onRequest) { Text("Разрешить") }
        }
    }
}

/** Экран первого запуска. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Голосовой ассистент", style = MaterialTheme.typography.displaySmall)
        Text(
            "Задачи, заметки, напоминания и расходы — голосом. Данные хранятся на телефоне, " +
                "на ваш сервер отправляется только текст команды.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("Разрешения", style = MaterialTheme.typography.headlineSmall)
        PermissionCards()
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Продолжить") }
        Text(
            "Разрешения можно выдать позже в настройках приложения.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
