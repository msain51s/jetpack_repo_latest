package com.ey.resources.utils

import androidx.annotation.Keep
import com.ey.common.constants.EyDateFormatConstants
import com.ey.common.constants.MYBConstants.CANCEL_CHECK_IN
import com.ey.common.constants.MYBConstants.CANCEL_FLIGHT
import com.ey.common.constants.MYBConstants.CHANGE_FLIGHT
import com.ey.common.constants.MYBConstants.CHECK_IN
import com.ey.common.constants.MYBConstants.EXCHANGE_FLIGHT
import com.ey.common.constants.MYBConstants.INCLUDED
import com.ey.common.constants.MYBConstants.NOT_INCLUDED
import com.ey.common.constants.MYBConstants.SEAT_SELECTION
import com.ey.common.constants.MYBConstants.SELF_REACCOMMODATION
import com.ey.model.api.ApiError
import com.ey.model.api.Resource
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.runCatching
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import retrofit2.Response
import timber.log.Timber

/**
 * Converts a suspend API call into a Flow of Resource<T>.
 *
 * This function emits:
 * 1. Resource.Loading() before starting the network call.
 * 2. Resource.Success() if the call is successful and returns a body.
 * 3. Resource.Error() if the call fails, including parsed API error details if available.
 *
 * Usage example:
 * toResultFlow { apiService.getTrips() }
 *
 * @param call A suspend function making the network request.
 * @return Flow<Resource<T>> that can be collected to observe API state.
 */
fun <T> toResultFlow(call: suspend () -> Response<T>): Flow<Resource<T>> = flow {
    // Emit loading state to indicate API call has started.
    emit(Resource.Loading())
    // Safely execute the suspend API call and capture the result.
    val response = runCatching { call.invoke() }
    emit(
        if (response.isSuccess && response.getOrNull()?.isSuccessful == true) {
            Resource.Success(response.getOrNull()?.body())
        } else {
            // If the response is not successful, parse and emit the error details.
            val errorString = response.getOrNull()?.errorBody()?.string()
            Timber.d("Error: $errorString")
            val apiError = parseApiError(errorString)
            Timber.d("Parsed Error: $apiError")
            Resource.Error(
                message = apiError?.message ?: response.getOrNull()?.message(),
                errorCode = response.getOrNull()?.code() ?: 0,
                apiError = apiError
            )
        }
    )
}

/**
 * Executes a suspend API call and emits a [Resource] wrapped in a [Flow],
 * with retry logic based on HTTP status codes or exceptions.
 *
 * Retries the call up to [retryCount] times unless the response is HTTP 200 or 201.
 * Optionally adds a delay of [delayMillis] ms between attempts.
 *
 * @param retryCount Max number of retry attempts on failure.
 * @param delayMillis Delay (ms) between retries.
 * @param call The suspend API call to execute.
 * @return A [Flow] emitting [Resource.Loading], [Resource.Success], or [Resource.Error].
 */
fun <T> toResultFlowWithRetry(
    retryCount: Int = 3,
    delayMillis: Long = 0L,
    call: suspend () -> Response<T>
): Flow<Resource<T>> = flow {
    emit(Resource.Loading())

    var attempt = 0
    var lastError: Resource.Error<T>? = null

    while (attempt <= retryCount) {
        val result = runCatching { call() }

        val response = result.getOrNull()
        if (response?.isSuccessful == true ||
            response?.code() == 200 ||
            response?.code() == 201
        ) {
            emit(Resource.Success(response?.body()))
            return@flow
        }

        val errorString = response?.errorBody()?.string()
        Timber.d("Error: $errorString")
        val apiError = parseApiError(errorString)
        Timber.d("Parsed Error: $apiError")
        lastError = Resource.Error(
            message = apiError?.message ?: response?.message(),
            errorCode = response?.code() ?: 0,
            apiError = apiError
        )

        if (++attempt <= retryCount) delay(delayMillis)
    }

    emit(lastError ?: Resource.Error("Unknown error", errorCode = 0))
}

fun parseApiError(errorString: String?): ApiError? {
    return try {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        // Determine which JSON structure is used
        if (errorString?.contains("apiErrors") == true) {
            val wrapperAdapter = moshi.adapter(ApiErrorWrapper::class.java)
            val wrappedError = wrapperAdapter.fromJson(errorString)
            wrappedError?.apiErrors?.firstOrNull()
        } else {
            val directAdapter = moshi.adapter(ApiError::class.java)
            directAdapter.fromJson(errorString)
        }
    } catch (e: Exception) {
        Timber.e(e, "Failed to parse API error")
        null
    }
}

@Keep
private data class ApiErrorWrapper(
    val apiErrors: List<ApiError>?
)

/**
 * Extension function for MutableStateFlow<Resource<T>> to update data only when in Success state
 */
inline fun <T> MutableStateFlow<Resource<T>>.updateIfSuccess(crossinline transform: (T) -> T) {
    this.update { resource ->
        when (resource) {
            is Resource.Success -> resource.data?.let { data ->
                Resource.Success(transform(data))
            } ?: resource

            else -> resource
        }
    }
}
}
