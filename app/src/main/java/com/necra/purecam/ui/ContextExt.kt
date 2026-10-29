package com.necra.purecam.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.necra.purecam.R

internal fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

internal fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

internal fun openMedia(context: Context, uri: Uri) {
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "image/jpeg"
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    // No chooser: Android then remembers the user's "Always" choice for this file type.
    runCatching { context.startActivity(view) }.onFailure {
        Toast.makeText(context, R.string.open_photo_failed, Toast.LENGTH_SHORT).show()
    }
}
