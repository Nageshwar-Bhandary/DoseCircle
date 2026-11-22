package com.nagi.dosecircle.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageStorageHelper {

    private const val DIRECTORY_NAME = "medicine_photos"

    fun copyUriToInternalStorage(context: Context, sourceUri: Uri): String? {
        return try {
            val photosDir = File(context.filesDir, DIRECTORY_NAME)
            if (!photosDir.exists()) {
                photosDir.mkdirs()
            }
            val fileName = "photo_${UUID.randomUUID()}.jpg"
            val destinationFile = File(photosDir, fileName)

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output)
                }
            }
            destinationFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteFile(filePath: String?) {
        if (!filePath.isNullOrBlank()) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
