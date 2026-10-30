package ru.voiceassistant.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.voiceassistant.service.AssistantForegroundService
import ru.voiceassistant.service.SpeechRecognizerManager
import ru.voiceassistant.ui.components.ConfirmDialog
import ru.voiceassistant.ui.permissions.PermissionCards
import ru.voiceassistant.ui.permissions.Permissions
import ru.voiceassistant.wakeword.WakeWordKeyword
import java.time.LocalDate

private val LOCALES = listOf("ru-RU" to "Русский", "en-US" to "English")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val ctx = LocalContext.current
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val wakeActive by viewModel.wakeWordActive.collectAsStateWithLifecycle()
    val wakeError by viewModel.wakeWordError.collectAsStateWithLifecycle()

    var url by rememberSaveable(s.backendUrl) { mutableStateOf(s.backendUrl) }
    var token by rememberSaveable(s.appToken) { mutableStateOf(s.appToken) }
    var picoKey by rememberSaveable(s.picovoiceAccessKey) { mutableStateOf(s.picovoiceAccessKey) }
    var confirmWipe by remember { mutableStateOf(false) }
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::export)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        confirmImport = uri
    }
    val micForWake = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.update { it.copy(wakeWordEnabled = true) }
            AssistantForegroundService.start(ctx)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- Backend ----------
        Section("Сервер ассистента") {
            OutlinedTextField(
                url, { url = it }, label = { Text("Адрес backend API") },
                placeholder = { Text("https://assistant.example.ru") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                token, { token = it }, label = { Text("Токен приложения (APP_TOKEN)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.saveBackend(url, token) }) { Text("Сохранить") }
                OutlinedButton(enabled = url.isNotBlank() && !checking, onClick = { viewModel.testBackend(url) }) {
                    Text(if (checking) "Проверяю…" else "Проверить связь")
                }
            }
        }

        // ---------- Wake word ----------
        Section("Ключевая фраза") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Слушать ключевую фразу")
                    Text(
                        if (wakeActive) "Включено — в шторке висит уведомление" else "Выключено — ассистент работает по кнопке",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = s.wakeWordEnabled && wakeActive,
                    onCheckedChange = { on ->
                        viewModel.clearWakeWordError()
                        if (on) {
                            if (Permissions.mic(ctx)) {
                                viewModel.update { it.copy(wakeWordEnabled = true) }
                                AssistantForegroundService.start(ctx)
                            } else {
                                micForWake.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            viewModel.update { it.copy(wakeWordEnabled = false) }
                            AssistantForegroundService.stop(ctx)
                        }
                    },
                )
            }
            wakeError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Text("Фраза", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WakeWordKeyword.all.forEach { k ->
                    FilterChip(
                        selected = s.wakeWordKeywordId == k.id,
                        onClick = { viewModel.update { it.copy(wakeWordKeywordId = k.id) } },
                        label = { Text(k.displayName) },
                    )
                }
            }
            OutlinedTextField(
                picoKey, { picoKey = it }, label = { Text("AccessKey Picovoice") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { viewModel.update { it.copy(picovoiceAccessKey = picoKey) } }) { Text("Сохранить ключ") }
            var sensitivity by remember(s.wakeWordSensitivity) { mutableStateOf(s.wakeWordSensitivity) }
            Text("Чувствительность: ${"%.2f".format(sensitivity)}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                onValueChangeFinished = { viewModel.update { it.copy(wakeWordSensitivity = sensitivity) } },
                valueRange = 0.3f..0.9f,
            )
            Text(
                "Изменения фразы и ключа применяются после повторного включения.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------- Recognition & TTS ----------
        Section("Распознавание и голос") {
            if (viewModel.recognizerAvailability == SpeechRecognizerManager.Availability.NONE) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "На телефоне не найден сервис распознавания речи. На Huawei без сервисов Google установите " +
                            "приложение с RecognitionService и выберите его в системных настройках голосового ввода. " +
                            "Пока можно писать команды текстом на главном экране.",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Text("Язык", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LOCALES.forEach { (tag, name) ->
                    FilterChip(selected = s.speechLocale == tag, onClick = { viewModel.setLocale(tag) }, label = { Text(name) })
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Озвучивать ответы", Modifier.weight(1f))
                Switch(checked = s.ttsEnabled, onCheckedChange = { on -> viewModel.update { it.copy(ttsEnabled = on) } })
            }
            Text("Голос", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = s.ttsVoiceName.isBlank(),
                    onClick = { viewModel.update { it.copy(ttsVoiceName = "") } },
                    label = { Text("По умолчанию") },
                )
                voices.forEach { v ->
                    FilterChip(
                        selected = s.ttsVoiceName == v.name,
                        onClick = { viewModel.update { it.copy(ttsVoiceName = v.name) } },
                        label = { Text(v.label) },
                    )
                }
            }
            var rate by remember(s.ttsRate) { mutableStateOf(s.ttsRate) }
            Text("Скорость речи: ${"%.1f".format(rate)}×", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = rate,
                onValueChange = { rate = it },
                onValueChangeFinished = { viewModel.update { it.copy(ttsRate = rate) } },
                valueRange = 0.5f..2.0f,
                steps = 14,
            )
            OutlinedButton(onClick = viewModel::previewVoice) { Text("Прослушать") }
        }

        // ---------- Permissions ----------
        Section("Разрешения") { PermissionCards() }

        // ---------- Data ----------
        Section("Данные") {
            Text(
                "Все задачи, заметки, расходы и история хранятся только на этом телефоне.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("assistant-backup-${LocalDate.now()}.json") }) {
                    Text("Экспорт в JSON")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text("Импорт")
                }
            }
            Button(
                onClick = { confirmWipe = true },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Удалить все данные") }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmWipe) {
        ConfirmDialog(
            title = "Удалить все данные?",
            text = "Задачи, заметки, расходы, напоминания и история будут удалены с телефона безвозвратно. " +
                "Сначала сделайте экспорт, если данные могут понадобиться.",
            confirmLabel = "Удалить всё",
            destructive = true,
            onConfirm = viewModel::wipe,
            onDismiss = { confirmWipe = false },
        )
    }
    confirmImport?.let { uri ->
        ConfirmDialog(
            title = "Импортировать данные?",
            text = "Текущие задачи, заметки, расходы, напоминания и история будут заменены содержимым файла.",
            confirmLabel = "Заменить",
            destructive = true,
            onConfirm = { viewModel.import(uri) },
            onDismiss = { confirmImport = null },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            text = { Text(it) },
            confirmButton = { TextButton(onClick = viewModel::consumeMessage) { Text("OK") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
