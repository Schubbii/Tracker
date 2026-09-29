package de.securitycam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Vordergrund-Dienst, der die Kamera hält und fortlaufend in Abschnitten
 * aufnimmt. Läuft weiter, wenn der Bildschirm aus ist oder die App aus der
 * Übersicht geschlossen wird.
 */
class RecordingService : LifecycleService() {

    companion object {
        private const val TAG = "RecordingService"
        private const val ACTION_START = "de.securitycam.START"
        private const val ACTION_STOP = "de.securitycam.STOP"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val RETRY_DELAY_MS = 3000L

        /** Wird true, sobald der Dienst läuft (für die Oberfläche). */
        @Volatile
        var isRunning = false
            private set

        /** Wird im Hauptthread aufgerufen, wenn sich der Zustand ändert. */
        var listener: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var withAudio = false
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (isRunning) return START_STICKY

        if (!hasPermission(Manifest.permission.CAMERA)) {
            stopSelf()
            return START_NOT_STICKY
        }
        withAudio = settings.recordAudio && hasPermission(Manifest.permission.RECORD_AUDIO)

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), serviceType())
        } catch (e: Exception) {
            // z. B. wenn das System den Dienst im Hintergrund neu startet und
            // der Kamerazugriff dort nicht erlaubt ist.
            Log.e(TAG, "startForeground fehlgeschlagen", e)
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        stopping = false
        acquireWakeLock()
        bindCamera()
        notifyListener()
        return START_STICKY
    }

    private fun serviceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (withAudio) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return type
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (stopping) return@addListener
            try {
                val provider = future.get()
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.HD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                        )
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)

                var selector = if (settings.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    selector = if (selector == CameraSelector.DEFAULT_BACK_CAMERA)
                        CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.bindToLifecycle(this, selector, capture)
                cameraProvider = provider
                videoCapture = capture
                startSegment()
            } catch (e: Exception) {
                Log.e(TAG, "Kamera konnte nicht gestartet werden", e)
                requestStop()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO wird in onStartCommand geprüft
    private fun startSegment() {
        val capture = videoCapture ?: return
        if (stopping || recording != null) return

        Storage.enforceLimit(this, settings.maxStorageBytes)
        val options = FileOutputOptions.Builder(Storage.newFile(this))
            .setDurationLimitMillis(TimeUnit.MINUTES.toMillis(Settings.SEGMENT_MINUTES))
            .build()

        var pending = capture.output.prepareRecording(this, options)
        if (withAudio) pending = pending.withAudioEnabled()

        try {
            recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) onSegmentFinished(event)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Aufnahme konnte nicht starten", e)
            recording = null
            handler.postDelayed({ startSegment() }, RETRY_DELAY_MS)
        }
    }

    private fun onSegmentFinished(event: VideoRecordEvent.Finalize) {
        recording = null
        val error = event.error
        val ok = error == VideoRecordEvent.Finalize.ERROR_NONE ||
            error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
        if (!ok) {
            Log.w(TAG, "Abschnitt beendet mit Fehler $error", event.cause)
            if (error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                event.outputResults.outputUri.path?.let { java.io.File(it).delete() }
            }
        }
        notifyListener()

        if (stopping) {
            finishStop()
        } else if (ok) {
            startSegment()
        } else {
            // Kamera kurz nicht verfügbar o. Ä. – nach kurzer Pause erneut versuchen.
            handler.postDelayed({ startSegment() }, RETRY_DELAY_MS)
        }
    }

    private fun requestStop() {
        if (stopping) return
        stopping = true
        handler.removeCallbacksAndMessages(null)
        val active = recording
        if (active != null) {
            active.stop() // finishStop() folgt im Finalize-Event
        } else {
            finishStop()
        }
    }

    private fun finishStop() {
        videoCapture?.let { cameraProvider?.unbind(it) }
        videoCapture = null
        releaseWakeLock()
        isRunning = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        notifyListener()
    }

    override fun onDestroy() {
        stopping = true
        handler.removeCallbacksAndMessages(null)
        recording?.stop()
        recording = null
        releaseWakeLock()
        isRunning = false
        notifyListener()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SecurityCam:recording").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun notifyListener() {
        handler.post { listener?.invoke() }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Aufnahme", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), flags
        )
        val since = SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date())
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Überwachung aktiv")
            .setContentText("Aufnahme läuft seit $since")
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, "Stoppen", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
