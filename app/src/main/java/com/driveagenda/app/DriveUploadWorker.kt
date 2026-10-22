package com.driveagenda.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.InputStreamContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DriveUploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val item = Store.list(applicationContext).find { it.id == id } ?: return@withContext Result.failure()
        if (item.status == "Subido") return@withContext Result.success()
        val name = applicationContext.getSharedPreferences("auth", 0).getString("email", null)
        if (name.isNullOrEmpty()) {
            Store.update(applicationContext, id) { it.copy(status = "Inicia sesión en Google") }
            return@withContext Result.failure()
        }
        try {
            val source = File(item.path)
            if (!source.exists()) throw IllegalStateException("Archivo local no disponible")
            val credential = GoogleAccountCredential.usingOAuth2(applicationContext, listOf(DriveScopes.DRIVE_FILE)).apply { selectedAccountName = name }
            val drive = Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
                .setApplicationName("Drive Agenda").build()
            source.inputStream().use { stream ->
                val media = InputStreamContent(item.mime, stream).apply { length = source.length() }
                drive.files().create(DriveFile().setName(item.filename), media).setFields("id,name").execute()
            }
            Store.update(applicationContext, id) { it.copy(status = "Subido") }
            return@withContext Result.success()
        } catch (e: Exception) {
            val description = e.javaClass.simpleName
            Store.update(applicationContext, id) { it.copy(status = "Error: $description") }
            return@withContext if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }
}
