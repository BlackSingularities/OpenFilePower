package pl.openfilepower

import androidx.exifinterface.media.ExifInterface
import java.util.Locale

/** Najważniejsze parametry zdjęcia (także RAW) w czytelnej postaci – do panelu i nakładki w przeglądarce. */
data class PhotoExif(
    val camera: String?, val lens: String?, val aperture: String?, val shutter: String?, val iso: String?, val focal: String?,
    val taken: String?, val width: Int, val height: Int, val program: String?, val flash: String?, val whiteBalance: String?,
    val bias: String?, val gps: Pair<Double, Double>?, val software: String?,
) {
    /** Krótkie plakietki: f/2.8 · 1/250 s · ISO 400 · 50 mm. */
    val badges get() = listOfNotNull(aperture, shutter, iso?.let { "ISO $it" }, focal, bias)
    val megapixels get() = if (width > 0 && height > 0) "%.1f MP".format(Locale.ROOT, width * height / 1_000_000.0) else null

    companion object {
        fun from(exif: ExifInterface): PhotoExif {
            fun a(tag: String) = exif.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
            val make = a(ExifInterface.TAG_MAKE); val model = a(ExifInterface.TAG_MODEL)
            val camera = when { model == null -> make; make == null || model.startsWith(make, true) -> model; else -> "$make $model" }
            val f = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "f/" + trim(it) }
            val t = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { shutter(it) }
            val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { mm ->
                val eq = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
                trim(mm) + " mm" + if (eq > 0 && eq != mm.toInt()) " ($eq mm)" else ""
            }
            val bias = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, 0.0).takeIf { it != 0.0 }?.let { (if (it > 0) "+" else "") + "%.1f EV".format(Locale.ROOT, it) }
            val program = when (exif.getAttributeInt(ExifInterface.TAG_EXPOSURE_PROGRAM, -1)) {
                1 -> "Manualny"; 2 -> "Program"; 3 -> "Priorytet przysłony"; 4 -> "Priorytet migawki"; 5 -> "Kreatywny"; 6 -> "Sport"; 7 -> "Portret"; 8 -> "Krajobraz"; else -> null
            }
            val flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1).takeIf { it >= 0 }?.let { if (it and 1 == 1) "Błysk" else "Bez błysku" }
            val wb = when (exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)) { 0 -> "Automatyczny"; 1 -> "Ręczny"; else -> null }
            val width = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val height = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 } ?: exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            val taken = a(ExifInterface.TAG_DATETIME_ORIGINAL) ?: a(ExifInterface.TAG_DATETIME)
            return PhotoExif(camera, a(ExifInterface.TAG_LENS_MODEL), f, t, a(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY), focal,
                taken?.let { it.replaceFirst(':', '-').replaceFirst(':', '-') }, width, height, program, flash, wb, bias,
                exif.latLong?.let { it[0] to it[1] }, a(ExifInterface.TAG_SOFTWARE))
        }
        private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(Locale.ROOT, v)
        fun shutter(seconds: Double): String = if (seconds >= 0.5) trim(seconds) + " s" else "1/" + Math.round(1 / seconds) + " s"
    }
}
