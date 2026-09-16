package dev.therealashik.jules

actual fun getApiKey(): String = KeyValueStore().getString("api_key")
