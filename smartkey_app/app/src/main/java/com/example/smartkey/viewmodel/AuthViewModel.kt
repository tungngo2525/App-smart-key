package com.example.smartkey.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartkey.data.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val userRepository = UserRepository()

    sealed class LoginState {
        object Idle : LoginState()
        object Loading : LoginState()
        object Success : LoginState()
        object Error : LoginState()
    }

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Idle)
    val loginState = _loginState.asStateFlow()

    fun loginWithEmail(email: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            auth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val user = auth.currentUser
                        if (user != null) {
                            viewModelScope.launch {
                                runCatching {
                                    userRepository.createOrUpdateProfile(
                                        user.uid,
                                        user.displayName.orEmpty(),
                                        user.email ?: email
                                    )
                                }
                            }
                        }
                        syncMessagingToken()
                        _loginState.value = LoginState.Success
                    } else {
                        _loginState.value = LoginState.Error
                    }
                }
        }
    }

    fun registerWithEmail(username: String, email: String, password: String) {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val user = task.result?.user
                        if (user != null) {
                            viewModelScope.launch {
                                try {
                                    userRepository.createOrUpdateProfile(
                                        user.uid,
                                        username,
                                        email
                                    )
                                    _loginState.value = LoginState.Success
                                    syncMessagingToken()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    _loginState.value = LoginState.Error
                                }
                            }
                        } else {
                            _loginState.value = LoginState.Error
                        }
                    } else {
                        _loginState.value = LoginState.Error
                    }
                }
        }
    }

    fun signOut() {
        auth.signOut()
        _loginState.value = LoginState.Idle
    }

    private fun syncMessagingToken() {
        val uid = auth.currentUser?.uid ?: return
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                viewModelScope.launch {
                    runCatching { userRepository.saveMessagingToken(uid, token) }
                }
            }
    }
}
