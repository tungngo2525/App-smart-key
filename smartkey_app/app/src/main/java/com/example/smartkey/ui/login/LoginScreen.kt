package com.example.smartkey.ui.login

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smartkey.security.BiometricUtils
import com.example.smartkey.security.findFragmentActivity
import com.example.smartkey.ui.common.SmartScreenContainer
import com.example.smartkey.viewmodel.AuthViewModel
import com.google.firebase.auth.FirebaseAuth

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel = viewModel(),
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE) }
    val biometricEnabled = remember { prefs.getBoolean("biometric_enabled", false) }
    val currentUser = FirebaseAuth.getInstance().currentUser
    val activity = context.findFragmentActivity()
    val canLoginWithBiometric = biometricEnabled && currentUser != null && BiometricUtils.isAvailable(context)
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val loginState by authViewModel.loginState.collectAsState()

    SmartScreenContainer(verticalArrangement = Arrangement.Center) {
        AnimatedVisibility(visible = true, enter = fadeIn()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(12.dp),
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(32.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(28.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "SmartKey",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Quản lý chìa khóa thông minh",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        )
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Mật khẩu") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { authViewModel.loginWithEmail(email.trim(), password) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Đăng nhập", style = MaterialTheme.typography.titleMedium)
                    }

                    if (biometricEnabled && currentUser != null) {
                        Button(
                            onClick = {
                                val hostActivity = activity
                                if (hostActivity == null) {
                                    Toast.makeText(context, "Không thể mở xác thực vân tay.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (!canLoginWithBiometric) {
                                    Toast.makeText(context, "Thiết bị chưa sẵn sàng cho đăng nhập vân tay.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                BiometricUtils.authenticate(
                                    activity = hostActivity,
                                    title = "Đăng nhập SmartKey",
                                    subtitle = "Xác nhận vân tay để vào ứng dụng."
                                ) { success, error ->
                                    if (success) {
                                        onLoginSuccess()
                                    } else {
                                        Toast.makeText(context, error ?: "Xác thực thất bại.", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            enabled = canLoginWithBiometric,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("Vào bằng vân tay", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    TextButton(onClick = onNavigateToRegister) {
                        Text("Chưa có tài khoản? Đăng ký ngay", color = MaterialTheme.colorScheme.primary)
                    }

                    when (loginState) {
                        AuthViewModel.LoginState.Loading -> Text("Đang đăng nhập...", color = MaterialTheme.colorScheme.primary)
                        AuthViewModel.LoginState.Error -> Text("Sai email hoặc mật khẩu", color = MaterialTheme.colorScheme.error)
                        AuthViewModel.LoginState.Success -> LaunchedEffect(Unit) { onLoginSuccess() }
                        else -> {}
                    }
                }
            }
        }
    }
}
