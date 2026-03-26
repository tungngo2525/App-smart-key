package com.example.smartkey.ui.hce

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smartkey.card.ProvisioningManager

/**
 * HCE Provisioning Screen
 * Hiển thị trạng thái provisioning và cho phép quản lý
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HceProvisioningScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val provisioningManager = remember { ProvisioningManager(context) }
    
    var isProvisioned by remember { mutableStateOf(false) }
    var keyId by remember { mutableStateOf<String?>(null) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    
    // Load status
    LaunchedEffect(Unit) {
        provisioningManager.initialize()
        isProvisioned = provisioningManager.isProvisioned()
        keyId = provisioningManager.getKeyId()
        publicKey = provisioningManager.getPublicKey65()?.take(16)?.joinToString("") { "%02X".format(it) }
    }
    
    fun refreshStatus() {
        isProvisioned = provisioningManager.isProvisioned()
        keyId = provisioningManager.getKeyId()
        publicKey = provisioningManager.getPublicKey65()?.take(16)?.joinToString("") { "%02X".format(it) }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HCE Provisioning") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.Clear, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshStatus() }) {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isProvisioned) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Provisioning Status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (isProvisioned) "Provisioned" else "Not Provisioned",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Icon(
                        if (isProvisioned) Icons.Default.Check else Icons.Default.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = if (isProvisioned) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            MaterialTheme.colorScheme.error
                    )
                }
            }
            
            // Key Info Card
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Key Information",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Divider()
                    
                    // Key ID
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Key ID:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            keyId ?: "Not set",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                    
                    Divider()
                    
                    // Public Key Preview
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Public Key (first 16 bytes):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            publicKey ?: "Not available",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            }
            
            // Instructions Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "How to Provision",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "1. Make sure NFC is enabled on your phone\n" +
                        "2. Tap your phone to the IoT NFC reader\n" +
                        "3. The reader will automatically provision your phone\n" +
                        "4. Check status above to confirm provisioning",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Actions
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { showClearDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    enabled = isProvisioned
                ) {
                    Text("Clear Provisioning Data")
                }
                
                OutlinedButton(
                    onClick = {
                        provisioningManager.clearAll()
                        refreshStatus()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Reset All (including keys)")
                }
            }
        }
    }
    
    // Clear Confirmation Dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Provisioning Data?") },
            text = { Text("This will clear the phone provisioning data but keep the device keypair. You'll need to provision again.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        provisioningManager.clearProvisionedOnly()
                        refreshStatus()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
