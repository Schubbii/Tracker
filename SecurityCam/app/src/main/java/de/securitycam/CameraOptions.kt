package de.securitycam

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import java.util.Locale
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.math.tan

/** Eine wählbare Kamera: Kamera-ID plus Zoomfaktor (< 1 = Weitwinkel über die Hauptkamera). */
data class CameraOption(val cameraId: String, val zoom: Float, val label: String)

@OptIn(ExperimentalCamera2Interop::class)
object CameraOptions {

    private fun idOf(info: CameraInfo) = Camera2CameraInfo.from(info).cameraId

    /** Horizontaler Bildwinkel in Grad, falls ermittelbar. */
    private fun fovDegrees(info: CameraInfo): Double? {
        val c = Camera2CameraInfo.from(info)
        val focal = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull() ?: return null
        val sensor = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return null
        if (focal <= 0f) return null
        return Math.toDegrees(2 * atan(sensor.width / (2.0 * focal)))
    }

    /** Kleinster Zoomfaktor (Android 11+). Werte < 1 bedeuten: Ultraweitwinkel verfügbar. */
    private fun minZoom(info: CameraInfo): Float {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 1f
        return Camera2CameraInfo.from(info)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            ?.lower ?: 1f
    }

    fun list(provider: ProcessCameraProvider): List<CameraOption> {
        val infos = provider.availableCameraInfos
        val back = infos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
        val front = infos.filter { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
        val result = mutableListOf<CameraOption>()

        val main = back.firstOrNull()
        val mainFov = main?.let { fovDegrees(it) }
        if (main != null) {
            result += CameraOption(idOf(main), 1f, "Hauptkamera" + deg(mainFov))
            val zoom = minZoom(main)
            if (zoom < 0.95f) {
                val wideFov = mainFov?.let {
                    Math.toDegrees(2 * atan(tan(Math.toRadians(it / 2)) / zoom))
                }
                val z = String.format(Locale.GERMANY, "%.1f", zoom)
                val about = wideFov?.let { ", ca. ${it.roundToInt()}°" } ?: ""
                result += CameraOption(idOf(main), zoom, "Weitwinkel (${z}x$about)")
            }
        }
        // Manche Handys geben Weitwinkel/Tele als eigene Kamera frei.
        for (info in back.drop(1)) {
            val fov = fovDegrees(info)
            val name = when {
                fov != null && mainFov != null && fov > mainFov + 5 -> "Weitwinkel"
                fov != null && mainFov != null && fov < mainFov - 5 -> "Tele"
                else -> "Rückkamera ${idOf(info)}"
            }
            result += CameraOption(idOf(info), 1f, name + deg(fov))
        }
        front.forEachIndexed { i, info ->
            result += CameraOption(idOf(info), 1f, if (i == 0) "Frontkamera" else "Frontkamera ${i + 1}")
        }
        return result
    }

    private fun deg(fov: Double?) = if (fov == null) "" else " (${fov.roundToInt()}°)"

    /**
     * Bindet die in den Einstellungen gewählte Kamera. Fehlt sie, wird die
     * Hauptkamera (bzw. Frontkamera) verwendet.
     */
    fun bind(
        provider: ProcessCameraProvider,
        owner: LifecycleOwner,
        settings: Settings,
        vararg useCases: androidx.camera.core.UseCase
    ): Camera {
        val id = settings.cameraId
        val available = id != null && provider.availableCameraInfos.any { idOf(it) == id }
        val selector: CameraSelector
        val zoom: Float
        if (available) {
            selector = CameraSelector.Builder()
                .addCameraFilter { list -> list.filter { idOf(it) == id } }
                .build()
            zoom = settings.zoomRatio
        } else {
            selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            zoom = 1f
        }
        val camera = provider.bindToLifecycle(owner, selector, *useCases)
        // Zoom erst setzen, wenn die Kamera geöffnet ist (sonst wird er verworfen).
        // Alte Beobachter entfernen, damit kein früher gewählter Zoom nachwirkt.
        val state = camera.cameraInfo.cameraState
        state.removeObservers(owner)
        state.observe(owner) {
            if (it.type == CameraState.Type.OPEN) camera.cameraControl.setZoomRatio(zoom)
        }
        return camera
    }
}
