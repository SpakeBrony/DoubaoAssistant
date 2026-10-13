package com.spake.brony.doubaoassistant.information

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.spake.brony.doubaoassistant.R
import com.spake.brony.doubaoassistant.conversation.AiOperationFailure
import com.spake.brony.doubaoassistant.ui.PhonePermissionActivity
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 仅天气请求临时读取定位，不后台持续跟踪；近似位置可用。 */
internal class WeatherLocation(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)

    suspend fun current(): Location {
        if (!granted(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !PhonePermissionActivity.request(app, listOf(Manifest.permission.ACCESS_COARSE_LOCATION))) {
            throw AiOperationFailure(R.string.weather_location_permission_denied)
        }
        return withContext(Dispatchers.Main.immediate) {
            if (!manager.isLocationEnabled) throw AiOperationFailure(R.string.weather_location_disabled)
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
                (it != LocationManager.GPS_PROVIDER || granted(Manifest.permission.ACCESS_FINE_LOCATION)) &&
                    manager.allProviders.contains(it) && manager.isProviderEnabled(it)
            }
            if (providers.isEmpty()) throw AiOperationFailure(R.string.weather_location_unavailable)
            try {
                val cached = providers.mapNotNull { manager.getLastKnownLocation(it) }.filter(::fresh)
                    .maxByOrNull { it.elapsedRealtimeNanos }
                if (cached != null) return@withContext cached
                var listener: LocationListener? = null
                try {
                    withTimeoutOrNull(15_000) {
                        suspendCancellableCoroutine<Location> { continuation ->
                            listener = object : LocationListener {
                                override fun onLocationChanged(location: Location) {
                                    if (continuation.isActive && fresh(location)) continuation.resume(location)
                                }
                                override fun onProviderEnabled(provider: String) = Unit
                                override fun onProviderDisabled(provider: String) = Unit
                                @Deprecated("Legacy callback")
                                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                            }
                            providers.forEach { manager.requestLocationUpdates(it, 0L, 0f, checkNotNull(listener), Looper.getMainLooper()) }
                        }
                    } ?: throw AiOperationFailure(R.string.weather_location_unavailable)
                } finally {
                    listener?.let(manager::removeUpdates)
                }
            } catch (_: SecurityException) {
                throw AiOperationFailure(R.string.weather_location_permission_denied)
            }
        }
    }

    // 用系统反向地理编码确认国家，不能把经纬度矩形内的邻国当成中国。
    suspend fun address(location: Location): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(app, Locale.SIMPLIFIED_CHINESE)
        return withTimeoutOrNull(8_000) {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
                }
            } else withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }
        }
    }

    private fun granted(permission: String) = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    private fun fresh(location: Location): Boolean = location.latitude.isFinite() && location.longitude.isFinite() &&
        location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0 && location.hasAccuracy() &&
        location.accuracy in 0f..20_000f &&
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) in 0..300_000_000_000L
}
