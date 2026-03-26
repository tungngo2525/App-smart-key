package com.example.smartkey.ui.vehicles

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.smartkey.data.Vehicle
 
@Composable
fun ShareVehicleDialog(
    vehicle: Vehicle,
    onDismiss: () -> Unit,
    onSubmit: (email: String, durationHours: Int, unlimited: Boolean) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("24") }
    var unlimited by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chia sẻ \"${vehicle.name.ifBlank { vehicle.plate }}\"") },
        text = {
            Column {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email người nhận") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Next,
                        keyboardType = KeyboardType.Email
                    )
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    Text("Không giới hạn thời gian", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Switch(checked = unlimited, onCheckedChange = { unlimited = it })
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = duration,
                    onValueChange = { if (!unlimited) duration = it.filter { ch -> ch.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Thời hạn (giờ)") },
                    singleLine = true,
                    enabled = !unlimited,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text(
                    text = if (unlimited) {
                        "Người nhận giữ quyền cho đến khi bạn thu hồi."
                    } else {
                        "Người nhận chỉ sử dụng được đến khi hết hạn."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = email.isNotBlank(),
                onClick = {
                    val hours = duration.toIntOrNull() ?: 24
                    onSubmit(email.trim(), hours, unlimited)
                }
            ) {
                Text("Chia sẻ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
        }
    )
}
