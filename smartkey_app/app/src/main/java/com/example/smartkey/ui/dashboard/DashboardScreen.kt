package com.example.smartkey.ui.dashboard

import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CarRental
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smartkey.card.ProvisioningManager
import com.example.smartkey.data.UserRepository
import com.example.smartkey.data.Vehicle
import com.example.smartkey.ui.location.LocationScreen
import com.example.smartkey.ui.profile.UserInfoScreen
import com.example.smartkey.ui.vehicles.VehicleListViewModel
import com.example.smartkey.ui.vehicles.VehiclesScreen
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

enum class DrawerDestination(val label: String) {
    PROFILE("Thông tin người dùng"),
    OWNED("Danh sách xe của bạn"),
    SHARED("Xe được chia sẻ"),
    LOCATION("Tọa độ của bạn"),
    LOGOUT("Đăng xuất")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToHce: (Vehicle) -> Unit,
    onLogout: () -> Unit,
    vm: VehicleListViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed)
    var selected by remember { mutableStateOf(DrawerDestination.OWNED) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@LaunchedEffect
        val publicKeyHex = ProvisioningManager(context.applicationContext)
            .getPublicKey65()
            ?.joinToString("") { "%02X".format(it) }
            .orEmpty()

        if (publicKeyHex.isNotEmpty()) {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val deviceId = "android_${androidId ?: "unknown"}"
            runCatching { UserRepository().updateDigitalKeyMaterial(uid, publicKeyHex, deviceId) }
        }
    }

    val drawerItems = listOf(
        DrawerDestination.PROFILE,
        DrawerDestination.OWNED,
        DrawerDestination.SHARED,
        DrawerDestination.LOCATION,
        DrawerDestination.LOGOUT
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "SmartKey",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp)
                )
                drawerItems.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.label) },
                        selected = selected == item,
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (item == DrawerDestination.LOGOUT) {
                                onLogout()
                            } else {
                                selected = item
                            }
                        },
                        icon = {
                            val icon = when (item) {
                                DrawerDestination.PROFILE -> Icons.Default.Person
                                DrawerDestination.OWNED -> Icons.Default.CarRental
                                DrawerDestination.SHARED -> Icons.Default.Share
                                DrawerDestination.LOCATION -> Icons.Default.LocationOn
                                DrawerDestination.LOGOUT -> Icons.Default.Logout
                            }
                            Icon(imageVector = icon, contentDescription = null)
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(selected.label) },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                if (drawerState.isClosed) drawerState.open() else drawerState.close()
                            }
                        }) {
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            DashboardContent(
                selection = selected,
                contentPadding = padding,
                vm = vm,
                onNavigateToHce = onNavigateToHce
            )
        }
    }
}

@Composable
private fun DashboardContent(
    selection: DrawerDestination,
    contentPadding: PaddingValues,
    vm: VehicleListViewModel,
    onNavigateToHce: (Vehicle) -> Unit
) {
    when (selection) {
        DrawerDestination.PROFILE -> Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
        ) {
            UserInfoScreen()
        }

        DrawerDestination.OWNED -> VehiclesScreen(
            vm = vm,
            onVehicleClick = onNavigateToHce,
            title = "Xe của bạn",
            showOwned = true,
            showShared = false,
            allowAdd = true,
            enableShareAction = true,
            showTopBar = false,
            contentPadding = contentPadding
        )

        DrawerDestination.SHARED -> VehiclesScreen(
            vm = vm,
            onVehicleClick = onNavigateToHce,
            title = "Xe được chia sẻ",
            showOwned = false,
            showShared = true,
            allowAdd = false,
            enableShareAction = false,
            showTopBar = false,
            contentPadding = contentPadding
        )

        DrawerDestination.LOCATION -> Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
        ) {
            LocationScreen(vm = vm)
        }

        DrawerDestination.LOGOUT -> Unit
    }
}
