package com.example.smartkey.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.smartkey.security.BiometricUtils
import com.example.smartkey.security.findFragmentActivity

@Composable
fun SecurityVerificationDialog(
    title: String,
    description: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    requirePassword: Boolean = true,
    requireBiometric: Boolean = true,
    autoConfirmOnBiometric: Boolean = false,
    onVerified: (password: String) -> Unit
) {
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    val canUseBiometric = remember { BiometricUtils.isAvailable(context) }
    var password by remember { mutableStateOf("") }
    var biometricVerified by remember { mutableStateOf(false) }
    var biometricMessage by remember { mutableStateOf<String?>(null) }
    val usingPasswordFallback = requireBiometric && !canUseBiometric
    val showManualConfirm = requirePassword || !requireBiometric || !autoConfirmOnBiometric || usingPasswordFallback

    fun resetState() {
        password = ""
        biometricVerified = false
        biometricMessage = null
    }

    fun launchBiometric() {
        if (!canUseBiometric) {
            biometricMessage = "Thiết bị chưa hỗ trợ hoặc chưa cài đặt vân tay."
            return
        }
        val act = activity ?: run {
            biometricMessage = "Không thể mở trình xác thực."
            return
        }
        BiometricUtils.authenticate(
            activity = act,
            title = title,
            subtitle = "Xác nhận vân tay trước khi tiếp tục."
        ) { success, error ->
            biometricVerified = success
            biometricMessage = if (success) {
                if (autoConfirmOnBiometric && !requirePassword) {
                    onVerified("")
                    resetState()
                    return@authenticate
                }
                "Đã xác thực vân tay."
            } else {
                error ?: "Xác thực thất bại."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            if (showManualConfirm) {
                TextButton(
                    enabled = if (usingPasswordFallback) {
                        password.isNotBlank()
                    } else {
                        (!requirePassword || password.isNotBlank()) && (!requireBiometric || biometricVerified)
                    },
                    onClick = {
                        onVerified(password)
                        resetState()
                    }
                ) { Text(confirmLabel) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                if (requirePassword || usingPasswordFallback) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (usingPasswordFallback) "Mật khẩu đăng nhập (thay thế vân tay)" else "Mật khẩu đăng nhập") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                }
                if (requireBiometric && !usingPasswordFallback) {
                    Button(onClick = { launchBiometric() }, enabled = canUseBiometric) {
                        Text(if (biometricVerified) "Vân tay đã xác thực" else "Xác thực vân tay")
                    }
                }
                biometricMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = if (biometricVerified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                if (usingPasswordFallback) {
                    Text(
                        text = "Vân tay không khả dụng. Vui lòng nhập mật khẩu để xác thực.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )
}
