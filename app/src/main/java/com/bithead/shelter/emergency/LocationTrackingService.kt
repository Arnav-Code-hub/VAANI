package com.bithead.shelter.emergency

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bithead.shelter.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow

data class CachedLocation(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val accuracyMeters: Float?
)

object LocationCache {
    private const val PREFS = "vaani_location_protection"
    val latest = MutableStateFlow<CachedLocation?>(null)
    val trackingEnabled = MutableStateFlow(false)

    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        trackingEnabled.value = prefs.getBoolean("enabled", false)
        if (prefs.contains("latitude") && prefs.contains("longitude")) {
            latest.value = CachedLocation(
                Double.fromBits(prefs.getLong("latitude", 0L)),
                Double.fromBits(prefs.getLong("longitude", 0L)),
                prefs.getLong("timestamp", 0L),
                prefs.getFloat("accuracy", 0f).takeIf { it > 0f }
            )
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        trackingEnabled.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
    }

    fun save(context: Context, location: Location) {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
        val fix = CachedLocation(
            location.latitude, location.longitude,
            location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
            location.accuracy.takeIf { location.hasAccuracy() && it > 0f }
        )
        save(context, fix)
    }

    fun save(context: Context, fix: CachedLocation) {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) return
        if ((latest.value?.timestampMillis ?: 0L) > fix.timestampMillis) return
        latest.value = fix
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("latitude", fix.latitude.toBits())
            .putLong("longitude", fix.longitude.toBits())
            .putLong("timestamp", fix.timestampMillis)
            .putFloat("accuracy", fix.accuracyMeters ?: 0f)
            .apply()
    }
}

class LocationTrackingService : Service(), LocationListener {
    companion object {
        const val ACTION_START = "com.bithead.shelter.LOCATION_START"
        const val ACTION_STOP = "com.bithead.shelter.LOCATION_STOP"
        private const val CHANNEL = "vaani_location_protection"
        private const val NOTIFICATION_ID = 701
    }

    private lateinit var manager: LocationManager

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LocationCache.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!LocationCache.trackingEnabled.value || !hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, "Location protection", NotificationManager.IMPORTANCE_LOW
        ))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, LocationTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Location protection active")
            .setContentText("Last known location ready for emergency alerts")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .build()
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            subscribe()
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun subscribe() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val providers = buildList {
            add(LocationManager.NETWORK_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
        }
        providers.forEach { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { LocationCache.save(this, it) }
            if (runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) {
                runCatching { manager.requestLocationUpdates(provider, 30_000L, 25f, this, mainLooper) }
            }
        }
    }

    override fun onLocationChanged(location: Location) = LocationCache.save(this, location)

    override fun onDestroy() {
        runCatching { manager.removeUpdates(this) }
        super.onDestroy()
    }
}
