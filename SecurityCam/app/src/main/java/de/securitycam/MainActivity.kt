package de.securitycam

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SystemSettings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("UseSwitchCompatOrMaterialCode")
class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var previewView: PreviewView
    private lateinit var previewHint: TextView
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var audio: Switch
    private lateinit var front: Switch
    private lateinit var storage: Spinner
    private lateinit var battery: Button
    private lateinit var listHeader: TextView
    private lateinit var list: ListView

    private var preview: Preview? = null
    /** Start angefordert, Dienst meldet sich aber evtl. noch nicht als laufend. */
    private var startPending = false
    private var files: List<File> = emptyList()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (granted(Manifest.permission.CAMERA)) {
                if (settings.recordAudio && !granted(Manifest.permission.RECORD_AUDIO)) {
                    toast("Ohne Mikrofon-Berechtigung wird ohne Ton aufgenommen.")
                }
                refresh()
                startRecording()
            } else {
                toast("Ohne Kamera-Berechtigung geht es leider nicht.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        previewView = findViewById(R.id.preview)
        previewHint = findViewById(R.id.previewHint)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        audio = findViewById(R.id.audio)
        front = findViewById(R.id.front)
        storage = findViewById(R.id.storage)
        battery = findViewById(R.id.battery)
        listHeader = findViewById(R.id.listHeader)
        list = findViewById(R.id.list)

        audio.isChecked = settings.recordAudio
        audio.setOnCheckedChangeListener { _, checked -> settings.recordAudio = checked }

        front.isChecked = settings.frontCamera
        front.setOnCheckedChangeListener { _, checked ->
            settings.frontCamera = checked
            bindPreview()
        }

        val options = Settings.STORAGE_OPTIONS_GB
        storage.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, options.map { "$it GB" }
        )
        storage.setSelection(options.indexOf(settings.maxStorageGb).coerceAtLeast(0))
        storage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                settings.maxStorageGb = options[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        toggle.setOnClickListener {
            if (RecordingService.isRunning) RecordingService.stop(this) else requestAndStart()
        }
        battery.setOnClickListener { requestIgnoreBatteryOptimizations() }

        list.setOnItemClickListener { _, _, pos, _ -> play(files[pos]) }
        list.setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(files[pos]); true }
    }

    override fun onResume() {
        super.onResume()
        RecordingService.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        RecordingService.listener = null
        super.onPause()
    }

    private fun refresh() {
        val running = RecordingService.isRunning
        if (running) startPending = false
        status.text = if (running) "● Aufnahme läuft" else "Bereit"
        status.setTextColor(if (running) Color.parseColor("#FF5252") else Color.WHITE)
        toggle.text = if (running) "Aufnahme stoppen" else "Aufnahme starten"
        audio.isEnabled = !running
        front.isEnabled = !running
        storage.isEnabled = !running

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        battery.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE

        if (running) {
            unbindPreview()
            previewHint.visibility = View.VISIBLE
        } else {
            previewHint.visibility = View.GONE
            if (granted(Manifest.permission.CAMERA)) bindPreview()
        }

        files = Storage.list(this)
        val totalMb = files.sumOf { it.length() } / (1024 * 1024)
        listHeader.text = "Aufnahmen: ${files.size} ($totalMb MB) – antippen = abspielen, lange drücken = löschen"
        val input = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY)
        val output = SimpleDateFormat("dd.MM.yyyy  HH:mm:ss", Locale.GERMANY)
        list.adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1,
            files.map { f ->
                val date = runCatching {
                    output.format(input.parse(f.nameWithoutExtension.removePrefix("cam_"))!!)
                }.getOrDefault(f.name)
                "$date   ·   ${f.length() / (1024 * 1024)} MB"
            }
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also {
                    (it as TextView).setTextColor(Color.WHITE)
                }
        }
    }

    private fun bindPreview() {
        if (RecordingService.isRunning || startPending || !granted(Manifest.permission.CAMERA)) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (RecordingService.isRunning || startPending || isFinishing) return@addListener
            val provider = future.get()
            preview?.let { provider.unbind(it) }
            val newPreview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            var selector = if (settings.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
            else CameraSelector.DEFAULT_BACK_CAMERA
            if (!provider.hasCamera(selector)) {
                selector = if (selector == CameraSelector.DEFAULT_BACK_CAMERA)
                    CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            }
            try {
                provider.bindToLifecycle(this, selector, newPreview)
                preview = newPreview
            } catch (e: Exception) {
                preview = null
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Gibt nur die eigene Vorschau frei, damit der Aufnahmedienst die Kamera bekommt. */
    private fun unbindPreview() {
        val p = preview ?: return
        preview = null
        ProcessCameraProvider.getInstance(this).get().unbind(p)
    }

    private fun requestAndStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (settings.recordAudio) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filterNot { granted(it) }
        if (missing.isEmpty()) startRecording() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startRecording() {
        startPending = true
        unbindPreview()
        RecordingService.start(this)
        toast("Aufnahme gestartet – Bildschirm kann jetzt aus.")
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            @SuppressLint("BatteryLife")
            val intent = Intent(SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(SystemSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun play(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Kein Videoplayer gefunden.")
        }
    }

    private fun confirmDelete(file: File) {
        if (RecordingService.isRunning && file == files.firstOrNull()) {
            toast("Diese Aufnahme wird gerade geschrieben.")
            return
        }
        AlertDialog.Builder(this)
            .setMessage("Aufnahme ${file.name} löschen?")
            .setPositiveButton("Löschen") { _, _ -> file.delete(); refresh() }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
