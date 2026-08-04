package ox.fzer0x.snakeloader.ui.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

abstract class BaseViewModel : ViewModel() {
    var isLoading by mutableStateOf(false)
        protected set

    var errorMessage by mutableStateOf<String?>(null)
        protected set

    fun launchWithLoading(block: suspend CoroutineScope.() -> Unit) {
        viewModelScope.launch {
            try {
                isLoading = true
                errorMessage = null
                block()
            } catch (e: Exception) {
                errorMessage = e.message ?: "An unexpected error occurred"
            } finally {
                isLoading = false
            }
        }
    }

    fun clearError() {
        errorMessage = null
    }
}
