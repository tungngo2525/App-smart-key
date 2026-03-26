package com.example.smartkey

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.smartkey.ui.dashboard.DashboardScreen
import com.example.smartkey.ui.login.LoginScreen
import com.example.smartkey.ui.register.RegisterScreen
import com.example.smartkey.ui.theme.SmartkeyTheme
import com.example.smartkey.ui.vehicles.VehicleDetailScreen
import com.example.smartkey.viewmodel.AuthViewModel

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartkeyTheme {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = viewModel()
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
}
