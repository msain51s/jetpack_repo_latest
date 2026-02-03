package com.ey.model.api

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.squareup.moshi.JsonClass

/**
 * This is a sealed class used for handling API responses in a clean and concise way.
 * It has three subclasses representing the three states of a network request: Loading, Success, and Error.
 *
 * @property data The data returned from the API. It's nullable because it might not exist in case of an error.
 * @property message The error message. It's nullable because it might not exist in case of success.
 */
sealed class Resource<T>(val data: T? = null, val message: String? = null, val errorCode: Int = 0) {
    class Loading<T>(data: T? = null) : Resource<T>(data)
    class Success<T>(data: T?) : Resource<T>(data)
    class Error<T>(message: String? = "", errorCode: Int = 0, data: T? = null, val apiError: ApiError? = null) : Resource<T>(data, message, errorCode)
    class Reset<T>(data: T? = null) : Resource<T>(data)
}

@Keep
@JsonClass(generateAdapter = true)
data class ApiError(
    @SerializedName("type")
    val type: String?,
    @SerializedName("code")
    val code: String?,
    @SerializedName("source")
    val source: String?,
    @SerializedName("message")
    val message: String?
)
