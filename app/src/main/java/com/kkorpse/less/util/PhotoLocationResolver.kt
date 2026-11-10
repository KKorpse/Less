package com.kkorpse.less.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.kkorpse.less.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class PhotoLocationResolver {

    private val cache = mutableMapOf<String, String>()

    suspend fun resolve(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val key = uri.toString()
        cache[key]?.let { return@withContext it }

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext context.getString(R.string.location_permission_missing)
        }

        val latLong = FloatArray(2)
        val hasLocation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getLatLong(latLong)
            } ?: false
        }.getOrDefault(false)

        if (!hasLocation || (latLong[0] == 0f && latLong[1] == 0f)) {
            return@withContext context.getString(R.string.location_unknown)
        }

        val geocoded = runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(
                latLong[0].toDouble(),
                latLong[1].toDouble(),
                1
            )
            addresses?.firstOrNull()?.let { buildAddress(it) }
        }.getOrNull()

        val result = geocoded
            ?: String.format(Locale.US, "%.4f, %.4f", latLong[0], latLong[1])
        cache[key] = result
        result
    }

    private fun buildAddress(address: Address): String? {
        val parts = listOfNotNull(
            address.adminArea,
            address.locality,
            address.subLocality,
            address.thoroughfare,
            address.subThoroughfare
        ).map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (parts.isNotEmpty()) return parts.joinToString("")
        return address.getAddressLine(0)?.takeIf { it.isNotBlank() }
    }
}
