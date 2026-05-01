package com.example.smartkey

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smartkey.card.ProvisioningManager
import com.example.smartkey.ui.dashboard.DashboardScreen
import com.example.smartkey.ui.login.LoginScreen
import com.example.smartkey.ui.register.RegisterScreen
import com.example.smartkey.ui.theme.SmartkeyTheme
import com.example.smartkey.ui.vehicles.VehicleDetailScreen
import com.example.smartkey.viewmodel.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private var navControllerRef: NavController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Pre-warm Android KeyStore so first NFC/HCE tap has no cold-start delay
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pm = ProvisioningManager(applicationContext)
                pm.initialize()
                pm.getPublicKey65() // force-load key into keystore cache
            } catch (_: Exception) {}
        }

        setContent {
            SmartkeyTheme {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = viewModel()

                LaunchedEffect(navController) {
                    navControllerRef = navController
                }

                // Handle notification deep link on cold start
                LaunchedEffect(Unit) {
                    handleNotificationIntent(intent)?.let {
                        navController.navigate("dashboard")
                    }
                }

                NavHost(navController, startDestination = "login") {
                    composable("login") {
                        LoginScreen(
                            onLoginSuccess = {
                                navController.navigate("dashboard") {
                                    popUpTo("login") { inclusive = true }
                                }
                            },
                            onNavigateToRegister = { navController.navigate("register") }
                        )
                    }
                    composable("register") {
                        RegisterScreen(
                            onRegisterSuccess = {
                                navController.navigate("dashboard") {
                                    popUpTo("login") { inclusive = true }
                                }
                            },
                            onNavigateToLogin = { navController.popBackStack() }
                        )
                    }
                    composable("dashboard") {
                        DashboardScreen(
                            onNavigateToHce = { vehicle ->
                                navController.navigate("vehicle_detail/${vehicle.id}/${vehicle.tbDeviceId}")
                            },
                            onLogout = {
                                authViewModel.signOut()
                                navController.navigate("login") {
                                    popUpTo("dashboard") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("vehicle_detail/{vehicleId}/{token}") { backStackEntry ->
                        val vehicleId = backStackEntry.arguments?.getString("vehicleId") ?: ""
                        val token = backStackEntry.arguments?.getString("token") ?: ""
                        VehicleDetailScreen(
                            vehicleId = vehicleId,
                            token = token,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Navigate to dashboard when notification is tapped while app is already open
        if (handleNotificationIntent(intent) != null) {
            navControllerRef?.navigate("dashboard")
        }
    }
    
    private fun handleNotificationIntent(intent: Intent?): String? {
        if (intent?.getStringExtra("action") == "config_vehicle") {
            return intent.getStringExtra("vehicle_id")
        }
        return null
    }
}
