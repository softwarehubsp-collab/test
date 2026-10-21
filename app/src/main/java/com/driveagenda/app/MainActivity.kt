package com.driveagenda.app

import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var files: LinearLayout
    private lateinit var account: TextView
    private val authOptions by lazy {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestScopes(Scope(DriveScopes.DRIVE_FILE)).build()
    }
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            try {
                val user = GoogleSignIn.getSignedInAccountFromIntent(res.data).result
                getSharedPreferences("auth", 0).edit().putString("email", user.email).apply()
                Toast.makeText(this, "Cuenta conectada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { showError("No se pudo conectar: ${e.message}") }
        }
        redraw()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        lifecycleScope.launch {
            var count = 0
            for (uri in uris) {
                try {
                    withContext(Dispatchers.IO) { cacheFile(uri) }
                    count++
                } catch (e: Exception) { showError("No se pudo importar un archivo: ${e.message}") }
            }
            Toast.makeText(this@MainActivity, "$count archivos guardados", Toast.LENGTH_SHORT).show()
            redraw()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 28, 24, 16) }
        val scroller = ScrollView(this).apply { addView(root) }
        setContentView(scroller)
        root.addView(TextView(this).apply { text = "Drive Agenda"; textSize = 26f; setTypeface(null, 1) })
        root.addView(TextView(this).apply { text = "Selecciona, guarda y programa archivos para Google Drive."; textSize = 15f; setPadding(0, 6, 0, 20) })
        account = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, 12) }
        root.addView(account)
        action(root, "1. Iniciar sesión con Google") { login.launch(GoogleSignIn.getClient(this, authOptions).signInIntent) }
        action(root, "2. Autorizar acceso a archivos de Android") { requestFileAccess() }
        action(root, "3. Seleccionar y guardar archivos") { picker.launch(arrayOf("*/*")) }
        action(root, "Actualizar lista") { redraw() }
        root.addView(TextView(this).apply { text = "Archivos guardados"; textSize = 21f; setTypeface(null, 1); setPadding(0, 20, 0, 6) })
        files = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(files)
        root.addView(TextView(this).apply {
            text = "Para subir automáticamente, mantén la sesión de Google activa y una conexión a Internet. La hora exacta está sujeta a los permisos y restricciones de batería del teléfono."
            textSize = 12f; setPadding(0, 18, 0, 12)
        })
        redraw()
    }
    private fun action(parent: LinearLayout, title: String, onTap: () -> Unit) {
        parent.addView(Button(this).apply { text = title; isAllCaps = false; setOnClickListener { onTap() } })
    }
    private fun redraw() {
        account.text = "Cuenta: " + (getSharedPreferences("auth", 0).getString("email", null) ?: "sin conectar")
        files.removeAllViews()
        val items = Store.list(this)
        if (items.isEmpty()) files.addView(TextView(this).apply { text = "Todavía no has agregado archivos." })
        items.forEach { f ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 14, 10, 14) }
            box.addView(TextView(this).apply { text = f.filename; textSize = 16f; setTypeface(null, 1) })
            val date = if (f.scheduledAt == 0L) "Sin programar" else DateFormat.getDateTimeInstance().format(java.util.Date(f.scheduledAt))
            box.addView(TextView(this).apply { text = "$date · ${f.status}" })
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            controls.addView(Button(this).apply {
                text = "Programar"; isAllCaps = false
                setOnClickListener { chooseTime(f) }
            })
            controls.addView(Button(this).apply {
                text = "Subir ahora"; isAllCaps = false
                setOnClickListener {
                    Store.update(this@MainActivity, f.id) { it.copy(status = "Esperando subida") }
                    Schedule.enqueue(this@MainActivity, f.id)
                    redraw()
                }
            })
            controls.addView(Button(this).apply {
                text = "Quitar"; isAllCaps = false
                setOnClickListener {
                    Store.save(this@MainActivity, Store.list(this@MainActivity).filterNot { it.id == f.id })
                    File(f.path).delete()
                    redraw()
                }
            })
            val horizontal = HorizontalScrollView(this).apply { addView(controls) }
            box.addView(horizontal)
            files.addView(box)
            files.addView(View(this).apply { setBackgroundColor(0xffdddddd.toInt()); layoutParams = LinearLayout.LayoutParams(-1, 1) })
        }
    }
    private fun chooseTime(f: QueuedFile) {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            TimePickerDialog(this, { _, h, min ->
                val whenAt = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
                if (whenAt <= System.currentTimeMillis()) { showError("Selecciona una fecha futura"); return@TimePickerDialog }
                val changed = f.copy(scheduledAt = whenAt, status = "Programado")
                Store.update(this, f.id) { changed }
                Schedule.schedule(this, changed)
                redraw()
                if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
                    Toast.makeText(this, "Hora aproximada: puedes habilitar alarmas exactas en ajustes", Toast.LENGTH_LONG).show()
                }
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    private suspend fun cacheFile(uri: Uri) = withContext(Dispatchers.IO) {
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val i = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && i >= 0) cursor.getString(i) else null
        } ?: "archivo"
        val id = UUID.randomUUID().toString()
        val folder = File(filesDir, "queued").apply { mkdirs() }
        val out = File(folder, id)
        try {
            contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: error("No se pudo abrir")
            val entry = QueuedFile(id, name, out.absolutePath, contentResolver.getType(uri) ?: "application/octet-stream")
            Store.save(this@MainActivity, Store.list(this@MainActivity) + entry)
        } catch (e: Exception) { out.delete(); throw e }
    }
    private fun requestFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "El permiso de archivos ya está autorizado", Toast.LENGTH_SHORT).show()
            } else {
                try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            Toast.makeText(this, "El selector de Android permite seleccionar archivos sin permisos adicionales", Toast.LENGTH_LONG).show()
        }
    }
    private fun showError(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
}
