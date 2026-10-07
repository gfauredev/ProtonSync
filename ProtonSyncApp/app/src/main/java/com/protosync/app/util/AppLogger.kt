package com.protosync.app.util

import android.util.Log
import me.proton.core.util.kotlin.CoreLogger
import me.proton.core.util.kotlin.Logger
import javax.inject.Inject

class AppLogger @Inject constructor() : Logger {

    private fun sanitize(message: String?): String {
        if (message == null) return ""
        var sanitized = message
        // Mask emails
        sanitized = sanitized.replace(Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"), "[EMAIL]")
        // Mask potential phone numbers (basic heuristic)
        sanitized = sanitized.replace(Regex("\\+?\\b\\d[\\d\\s\\-]{7,14}\\d\\b"), "[PHONE]")
        return sanitized
    }

    override fun e(tag: String, message: String) {
        Log.e(tag, sanitize(message))
    }

    override fun e(tag: String, throwable: Throwable) {
        Log.e(tag, sanitize(throwable.message ?: ""), throwable)
    }

    override fun e(tag: String, throwable: Throwable, message: String) {
        Log.e(tag, sanitize(message), throwable)
    }

    override fun w(tag: String, message: String) {
        Log.w(tag, sanitize(message))
    }

    override fun w(tag: String, throwable: Throwable) {
        Log.w(tag, sanitize(throwable.message ?: ""), throwable)
    }

    override fun w(tag: String, throwable: Throwable, message: String) {
        Log.w(tag, sanitize(message), throwable)
    }

    override fun i(tag: String, message: String) {
        Log.i(tag, sanitize(message))
    }

    override fun i(tag: String, throwable: Throwable, message: String) {
        Log.i(tag, sanitize(message), throwable)
    }

    override fun d(tag: String, message: String) {
        Log.d(tag, sanitize(message))
    }

    override fun d(tag: String, throwable: Throwable, message: String) {
        Log.d(tag, sanitize(message), throwable)
    }

    override fun v(tag: String, message: String) {
        Log.v(tag, sanitize(message))
    }

    override fun v(tag: String, throwable: Throwable, message: String) {
        Log.v(tag, sanitize(message), throwable)
    }

    companion object {
        fun install() {
            CoreLogger.set(AppLogger())
        }
    }
}