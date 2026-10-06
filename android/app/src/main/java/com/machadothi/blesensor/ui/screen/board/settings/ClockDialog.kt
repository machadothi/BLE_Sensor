package com.machadothi.blesensor.ui.screen.board.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Sets the board's clock by hand, for a board without internet. Starts at the
 * phone's time; the time is in the phone's time zone. "Use phone time" is the
 * same as what the app sends on every connect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetClockDialog(onSet: (unixMs: Long) -> Unit, onPhoneTime: () -> Unit, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val now = remember { java.time.ZonedDateTime.now(zone) }
    val time = rememberTimePickerState(now.hour, now.minute, is24Hour = true)
    // The date picker works in UTC midnights.
    val date = rememberDatePickerState(now.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    var pickingDate by remember { mutableStateOf(false) }
    val day = date.selectedDateMillis?.let { Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate() } ?: now.toLocalDate()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set the board's clock") },
        text = {
            Column {
                TimeInput(time)
                FilledTonalButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(day.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")))
                }
                Text(
                    "With internet the board corrects it by itself. The board forgets the time when it loses power.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onPhoneTime) { Text("Use the phone's time instead") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val local = LocalDate.from(day).atTime(LocalTime.of(time.hour, time.minute))
                onSet(local.atZone(zone).toInstant().toEpochMilli())
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (pickingDate) {
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = { TextButton(onClick = { pickingDate = false }) { Text("OK") } },
        ) { DatePicker(date) }
    }
}
