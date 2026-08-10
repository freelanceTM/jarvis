package com.jarvis.assistant.core.result

/**
 * Generic sealed class representing Data & Domain operations with strongly typed errors.
 */
sealed interface Resource<out T> {
    data class Success<out T>(val data: T) : Resource<T>
    data class Error(val exception: Throwable, val message: String? = null) : Resource<Nothing>
    data object Loading : Resource<Nothing>
}

inline fun <T> Resource<T>.onSuccess(action: (value: T) -> Unit): Resource<T> {
    if (this is Resource.Success) action(data)
    return this
}

inline fun <T> Resource<T>.onError(action: (exception: Throwable, message: String?) -> Unit): Resource<T> {
    if (this is Resource.Error) action(exception, message)
    return this
}

inline fun <T> Resource<T>.onLoading(action: () -> Unit): Resource<T> {
    if (this is Resource.Loading) action()
    return this
}
