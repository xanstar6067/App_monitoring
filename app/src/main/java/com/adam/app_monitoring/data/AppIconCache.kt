package com.adam.app_monitoring.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

class AppIconCache(private val context: Context) {
    private val memory = LruCache<String, Bitmap>(32)

    fun load(packageName: String, cachePath: String?): Bitmap? {
        memory.get(packageName)?.let { return it }
        val file = cachePath?.let(::File)
        if (file?.isFile == true) {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.let {
                memory.put(packageName, it)
                return it
            }
        }
        val drawable = runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull() ?: return null
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            Bitmap.createScaledBitmap(drawable.bitmap, ICON_SIZE, ICON_SIZE, true)
        } else {
            Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888).also { target ->
                val canvas = Canvas(target)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
            }
        }
        memory.put(packageName, bitmap)
        if (file != null) {
            runCatching {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, output)
                }
            }
        }
        return bitmap
    }

    private companion object {
        const val ICON_SIZE = 96
    }
}
