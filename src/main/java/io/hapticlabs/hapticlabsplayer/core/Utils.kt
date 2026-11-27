package io.hapticlabs.hapticlabsplayer.core

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import java.io.InputStream
import java.security.MessageDigest

fun getVibrator(context: Context): Vibrator {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
}

/**
 * Hashes everything [inputStream] provides with SHA-256.
 *
 * @return The hash as lowercase hex digits
 */
fun sha256Hex(inputStream: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    var bytesRead: Int
    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        digest.update(buffer, 0, bytesRead)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
