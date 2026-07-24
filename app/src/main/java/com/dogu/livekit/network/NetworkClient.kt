package com.dogu.livekit.network

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object NetworkClient {
    // Gerçek cihazlar için bilgisayarının yerel IP'si
    private const val PHYSICAL_HOST_IP = "10.0.2.120"
    
    // Emülatör tespiti ve uygun URL seçimi
    val TOKEN_SERVER_URL: String by lazy {
        val model = android.os.Build.MODEL
        val hardware = android.os.Build.HARDWARE
        val fingerprint = android.os.Build.FINGERPRINT
        val product = android.os.Build.PRODUCT
        val manufacturer = android.os.Build.MANUFACTURER
        val brand = android.os.Build.BRAND
        val device = android.os.Build.DEVICE
        
        // Çok daha geniş kapsamlı emülatör kontrolü
        val isEmulator = fingerprint.contains("generic")
                || fingerprint.contains("unknown")
                || model.contains("google_sdk")
                || model.contains("Emulator")
                || model.contains("Android SDK built for x86")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || manufacturer.contains("Genymotion")
                || product.contains("sdk_google")
                || product.contains("google_sdk")
                || product.contains("sdk")
                || product.contains("sdk_x86")
                || product.contains("vbox86p")
                || product.contains("emulator")
                || product.contains("simulator")
                || (brand.startsWith("generic") && device.startsWith("generic"))

        val url = if (isEmulator) {
            "http://10.0.2.2:3005"
        } else {
            "http://$PHYSICAL_HOST_IP:3005"
        }
        
        android.util.Log.e("NetworkClient", "Model: $model, HW: $hardware, Fingerprint: $fingerprint, URL: $url")
        url
    }

    val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun createPostRequest(endpoint: String, json: JSONObject): Request {
        return Request.Builder()
            .url(TOKEN_SERVER_URL + endpoint)
            .post(json.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
    }

    fun createGetRequest(url: String): Request {
        return Request.Builder().url(url).build()
    }
}
