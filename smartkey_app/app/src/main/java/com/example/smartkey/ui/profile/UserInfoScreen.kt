package com.example.smartkey.ui.profile

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smartkey.security.BiometricUtils
import com.example.smartkey.security.findFragmentActivity
import com.google.firebase.auth.FirebaseAuth

@Composable
fun UserInfoScreen() {
    val user = FirebaseAuth.getInstance().currentUser
    val presets = remember { ProfileBackground.presets }
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE) }
    var selectedIndex by rememberSaveable {
        mutableIntStateOf(prefs.getInt("profile_bg_index", 0).coerceIn(presets.indices))
    }
    var biometricEnabledFlag by rememberSaveable {
        mutableIntStateOf(if (prefs.getBoolean("biometric_enabled", false)) 1 else 0)
    }
    val selectedBackground = presets[selectedIndex]
    val greetingName = user?.displayName?.takeIf { it.isNotBlank() }
        ?: user?.email?.substringBefore("@")
        ?: "Người dùng SmartKey"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProfileHeader(background = selectedBackground, title = greetingName, email = user?.email)

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Thông tin người dùng", style = MaterialTheme.typography.titleLarge)
                ProfileField(label = "Email", value = user?.email ?: "Không xác định")
                ProfileField(label = "UID", value = user?.uid ?: "—")
            }
        }

        BiometricCard(
            enabledInApp = biometricEnabledFlag == 1,
            capability = BiometricUtils.capability(context),
            onUpdateFingerprint = {
                val activity = context.findFragmentActivity()
                if (activity == null) {
                    Toast.makeText(context, "Không thể mở xác thực lúc này.", Toast.LENGTH_SHORT).show()
                    return@BiometricCard
                }
                BiometricUtils.authenticate(
                    activity = activity,
                    title = "Cập nhật vân tay",
                    subtitle = "Xác nhận vân tay để bật bảo vệ SmartKey."
                ) { success, error ->
                    if (success) {
                        biometricEnabledFlag = 1
                        prefs.edit().putBoolean("biometric_enabled", true).apply()
                        Toast.makeText(context, "Đã bật bảo vệ vân tay cho SmartKey.", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, error ?: "Xác thực thất bại.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onOpenSettings = { BiometricUtils.launchEnrollSettings(context) }
        )

        Text(
            text = "Tuỳ chỉnh hình nền",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(presets) { index, background ->
                BackgroundPreview(
                    background = background,
                    selected = index == selectedIndex,
                    onClick = {
                        selectedIndex = index
                        prefs.edit().putInt("profile_bg_index", index).apply()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileHeader(background: ProfileBackground, title: String, email: String?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(background.colors))
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomStart),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.3f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(18.dp)
                )
            }
            Text("Xin chào,", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                email ?: "—",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BackgroundPreview(
    background: ProfileBackground,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier
            .width(140.dp)
            .height(70.dp)
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        tonalElevation = if (selected) 8.dp else 2.dp,
        shadowElevation = if (selected) 8.dp else 0.dp,
        border = if (selected) BorderStroke(2.dp, borderColor) else null
    ) {
        Box(
            modifier = Modifier
                .background(Brush.linearGradient(background.colors))
                .fillMaxSize()
        )
    }
}

@Composable
private fun BiometricCard(
    enabledInApp: Boolean,
    capability: BiometricUtils.Capability,
    onUpdateFingerprint: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                    Icon(
                        imageVector = Icons.Outlined.Fingerprint,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Column {
                    Text("Cập nhật vân tay", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            capability == BiometricUtils.Capability.NO_HARDWARE -> "Thiết bị không hỗ trợ cảm biến vân tay."
                            capability == BiometricUtils.Capability.NO_ENROLLED -> "Chưa đăng ký vân tay trên thiết bị."
                            enabledInApp -> "Đã kích hoạt xác thực vân tay cho SmartKey."
                            else -> "Nhấn để xác nhận vân tay và tăng bảo mật."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = capability == BiometricUtils.Capability.AVAILABLE,
                    onClick = onUpdateFingerprint
                ) {
                    Text(if (enabledInApp) "Xác thực lại" else "Kích hoạt")
                }
                if (capability == BiometricUtils.Capability.NO_ENROLLED) {
                    OutlinedButton(onClick = onOpenSettings) {
                        Text("Mở cài đặt")
                    }
                }
            }
        }
    }
}

private data class ProfileBackground(val colors: List<Color>) {
    companion object {
        val presets = listOf(
            ProfileBackground(
                colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899))
            ),
            ProfileBackground(
                colors = listOf(Color(0xFF0EA5E9), Color(0xFF22D3EE), Color(0xFF38BDF8))
            ),
            ProfileBackground(
                colors = listOf(Color(0xFF34D399), Color(0xFF10B981), Color(0xFF059669))
            ),
            ProfileBackground(
                colors = listOf(Color(0xFFFFA94D), Color(0xFFFF7E67), Color(0xFFF43F5E))
            )
        )
    }
}
