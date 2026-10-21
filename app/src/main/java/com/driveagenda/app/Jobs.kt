package com.driveagenda.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class QueuedFile(val id: String, val filename: String, val path: String, val mime: String, val scheduledAt: Long = 0L, val status: String = "Pendiente")

object Store {
    fun list(ctx: Context): List<QueuedFile> = synchronized(this) {
        val a = JSONArray(ctx.getSharedPreferences("queue", 0).getString("items", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o -> QueuedFile(o.getString("id"), o.getString("filename"), o.getString("path"), o.optString("mime", "application/octet-stream"), o.optLong("scheduledAt"), o.optString("status", "Pendiente")) }
    }
    fun save(ctx: Context, entries: List<QueuedFile>) = synchronized(this) {
        val arr = JSONArray()
        entries.forEach { f -> arr.put(JSONObject().put("id", f.id).put("filename", f.filename).put("path", f.path).put("mime", f.mime).put("scheduledAt", f.scheduledAt).put("status", f.status)) }
        ctx.getSharedPreferences("queue", 0).edit().putString("items", arr.toString()).commit()
    }
    fun update(ctx: Context, id: String, modifier: (QueuedFile) -> QueuedFile) = synchronized(this) {
        save(ctx, list(ctx).map { if (it.id == id) modifier(it) else it })
    }
}

object Schedule {
    fun schedule(ctx: Context, entry: QueuedFile) {
        val alarm = ctx.getSystemService(AlarmManager::class.java)
        val intent = Intent(ctx, UploadAlarmReceiver::class.java).putExtra("id", entry.id)
        val code = entry.id.hashCode()
        val pi = PendingIntent.getBroadcast(ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
            enqueue(ctx, entry.id, entry.scheduledAt)
        } else {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.scheduledAt, pi)
        }
    }
    fun enqueue(ctx: Context, id: String, whenMillis: Long = System.currentTimeMillis()) {
        val delay = (whenMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val task = OneTimeWorkRequestBuilder<DriveUploadWorker>()
            .setInputData(workDataOf("id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("upload_$id", androidx.work.ExistingWorkPolicy.REPLACE, task)
    }
}
class UploadAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { intent.getStringExtra("id")?.let { Schedule.enqueue(context, it) } }
}
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Store.list(context).filter { it.scheduledAt > 0 && it.status != "Subido" }.forEach { Schedule.schedule(context, it) }
    }
}
