package ru.voiceassistant.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.voiceassistant.domain.model.AssistantState
import ru.voiceassistant.util.DateTimeUtils
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Большая круглая кнопка микрофона с пульсацией во время записи. */
@Composable
fun MicButton(state: AssistantState, onClick: () -> Unit, modifier: Modifier = Modifier, size: Int = 128) {
    val recording = state is AssistantState.Recording
    val busy = state is AssistantState.Thinking || state is AssistantState.Answering
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring",
    )
    val rmsScale by animateFloatAsState(
        targetValue = if (state is AssistantState.Recording) 1f + (state.rms.coerceIn(0f, 10f) / 40f) else 1f,
        label = "rms",
    )
    val color = when {
        recording -> MaterialTheme.colorScheme.error
        busy -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val description = when {
        recording -> "Закончить запись"
        busy -> "Остановить ответ"
        else -> "Говорить с ассистентом"
    }
    Box(modifier.size((size * 1.3).dp), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier.size(size.dp).scale(ring).clip(CircleShape)
                    .background(color.copy(alpha = 0.18f))
            )
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = color,
            shadowElevation = 6.dp,
            modifier = Modifier.size(size.dp).scale(rmsScale).semantics { contentDescription = description },
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    state is AssistantState.Thinking -> CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onSecondary, modifier = Modifier.size((size / 3).dp),
                    )
                    busy -> Icon(Icons.Filled.Stop, null, tint = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.size((size / 2.5).dp))
                    else -> Icon(Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size((size / 2.5).dp))
                }
            }
        }
    }
}

/** Индикатор состояния: выключен / слушает wake word / записывает / думает / отвечает. */
@Composable
fun AssistantStatus(state: AssistantState, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        AssistantState.Off -> "Ассистент выключен — нажмите на микрофон" to MaterialTheme.colorScheme.outline
        AssistantState.WaitingWakeWord -> "Слушаю ключевую фразу" to MaterialTheme.colorScheme.tertiary
        is AssistantState.Recording -> "Записываю…" to MaterialTheme.colorScheme.error
        is AssistantState.Thinking -> "Думаю…" to MaterialTheme.colorScheme.secondary
        is AssistantState.Answering -> "Отвечаю" to MaterialTheme.colorScheme.primary
        is AssistantState.Error -> "Ошибка" to MaterialTheme.colorScheme.error
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        action?.invoke()
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun CenteredEmpty(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Поле выбора даты и времени: чип с текущим значением, по нажатию — DatePicker, затем TimePicker.
 * Значение — epoch millis UTC или null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeField(
    value: Long?,
    onChange: (Long?) -> Unit,
    zone: ZoneId,
    nowMillis: Long,
    emptyLabel: String = "Без срока",
    allowClear: Boolean = true,
) {
    var showDate by remember { mutableStateOf(false) }
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        AssistChip(
            onClick = { showDate = true },
            label = { Text(value?.let { DateTimeUtils.formatHuman(it, zone, nowMillis) } ?: emptyLabel) },
            leadingIcon = { Icon(Icons.Outlined.Event, null) },
        )
        if (allowClear && value != null) {
            IconButton(onClick = { onChange(null) }) { Icon(Icons.Filled.Close, "Убрать срок") }
        }
    }

    if (showDate) {
        // DatePicker работает в UTC-полуночи — переводим в локальную дату явно.
        val initial = (value ?: nowMillis).let {
            Instant.ofEpochMilli(it).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    pickedDate = state.selectedDateMillis?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDate = false
                }) { Text("Далее") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Отмена") } },
        ) { DatePicker(state = state) }
    }

    pickedDate?.let { date ->
        val current = value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: LocalTime.of(18, 0)
        val timeState = rememberTimePickerState(initialHour = current.hour, initialMinute = current.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickedDate = null },
            title = { Text("Время") },
            text = { Column { TimePicker(state = timeState) } },
            confirmButton = {
                TextButton(onClick = {
                    onChange(date.atTime(timeState.hour, timeState.minute).atZone(zone).toInstant().toEpochMilli())
                    pickedDate = null
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { pickedDate = null }) { Text("Отмена") } },
        )
    }
}
