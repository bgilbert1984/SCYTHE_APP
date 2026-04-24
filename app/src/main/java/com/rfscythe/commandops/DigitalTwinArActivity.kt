package com.rfscythe.commandops

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.getDescription
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import io.github.sceneview.node.SphereNode
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.cos
import kotlin.math.ln1p
import kotlin.math.sin

class DigitalTwinArActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sceneView: ARSceneView
    private lateinit var tvStatus: TextView
    private lateinit var tvMeta: TextView
    private lateinit var tvEntities: TextView

    private val client = OkHttpClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val markerNodes = mutableListOf<Node>()

    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var currentHeadingDeg: Float = 0.0f
    private var currentLocation: Location? = null
    private var trackingMessage: String? = null

    private val locationListener = LocationListener { location ->
        currentLocation = location
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchProjection()
            mainHandler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_digital_twin_ar)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        sceneView = findViewById(R.id.sceneView)
        tvStatus = findViewById(R.id.tvArStatus)
        tvMeta = findViewById(R.id.tvArMeta)
        tvEntities = findViewById(R.id.tvArEntities)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        sceneView.lifecycle = lifecycle
        sceneView.planeRenderer.isEnabled = false
        sceneView.configureSession { session, config ->
            config.depthMode = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                Config.DepthMode.AUTOMATIC
            } else {
                Config.DepthMode.DISABLED
            }
            config.instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        }
        sceneView.onTrackingFailureChanged = { reason ->
            trackingMessage = reason?.getDescription(this)
            updateStatus()
        }
        sceneView.onSessionUpdated = { _, _ ->
            if (trackingMessage == null) {
                updateStatus()
            }
        }

        ensurePermissions()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.also {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        if (hasLocationPermission()) {
            requestLocationUpdates()
        }
        currentLocation = bestLastKnownLocation()
        refreshRunnable.run()
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(locationListener)
        mainHandler.removeCallbacks(refreshRunnable)
        clearMarkers()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && hasLocationPermission()) {
            requestLocationUpdates()
            currentLocation = bestLastKnownLocation()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) {
            return
        }
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotationMatrix, orientation)
        currentHeadingDeg = ((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun fetchProjection() {
        val location = currentLocation ?: bestLastKnownLocation()
        if (location == null) {
            tvStatus.text = "Waiting for location fix"
            tvMeta.text = "Camera-space AR is ready, but projection needs device location."
            return
        }

        val observerId = observerId()
        val baseUrl = ScytheConfig.normaliseServerUrl(ScytheConfig.getServerUrl(this))
        val url = StringBuilder(baseUrl)
            .append("/api/ar/projection?observer_id=")
            .append(URLEncoder.encode(observerId, StandardCharsets.UTF_8.name()))
            .append("&lat=").append(location.latitude)
            .append("&lon=").append(location.longitude)
            .append("&alt_m=").append(location.altitude)
            .append("&heading_deg=").append(currentHeadingDeg)
            .append("&limit=12")
            .toString()

        client.newCall(
            Request.Builder()
                .url(url)
                .get()
                .build()
        ).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvStatus.text = "Projection request failed"
                    tvMeta.text = e.message ?: "Unable to reach SCYTHE server."
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    runOnUiThread {
                        tvStatus.text = "Projection request failed"
                        tvMeta.text = body.ifBlank { "Server returned HTTP ${response.code}" }
                    }
                    return
                }
                runCatching {
                    val payload = JSONObject(body)
                    val entities = payload.optJSONArray("entities")
                    val tracks = buildList {
                        if (entities != null) {
                            for (index in 0 until entities.length()) {
                                val item = entities.optJSONObject(index) ?: continue
                                add(
                                    ProjectionTrack(
                                        entityId = item.optString("entity_id"),
                                        label = item.optString("label", item.optString("entity_id")),
                                        type = item.optString("type", "RECON_ENTITY"),
                                        confidence = item.optDouble("confidence", 0.0),
                                        distanceM = item.optDouble("distance_m", 0.0),
                                        relativeBearingDeg = item.optDouble("relative_bearing_deg", 0.0),
                                        elevationDeg = item.optDouble("elevation_deg", 0.0),
                                    )
                                )
                            }
                        }
                    }
                    runOnUiThread {
                        renderTracks(tracks)
                        tvStatus.text = "AR tracks: ${tracks.size}"
                        tvMeta.text = buildMetaLine(location, payload.optJSONObject("counts"))
                        tvEntities.text = buildEntitySummary(tracks)
                    }
                }.onFailure { error ->
                    runOnUiThread {
                        tvStatus.text = "Projection parse failed"
                        tvMeta.text = error.message ?: "Unexpected AR payload."
                    }
                }
            }
        })
    }

    private fun renderTracks(tracks: List<ProjectionTrack>) {
        clearMarkers()
        for (track in tracks) {
            val displayDistanceM = projectedDepth(track.distanceM)
            val azimuthRad = Math.toRadians(track.relativeBearingDeg)
            val elevationRad = Math.toRadians(track.elevationDeg)
            val x = (sin(azimuthRad) * cos(elevationRad) * displayDistanceM).toFloat()
            val y = (sin(elevationRad) * displayDistanceM).toFloat()
            val z = (-cos(azimuthRad) * cos(elevationRad) * displayDistanceM).toFloat()
            val radius = (0.05 + (track.confidence.coerceIn(0.1, 1.0) * 0.07)).toFloat()

            val marker = SphereNode(
                engine = sceneView.engine,
                radius = radius,
                materialInstance = sceneView.materialLoader.createColorInstance(trackColor(track))
            ).apply {
                name = track.label
                position = Position(x, y, z)
            }
            sceneView.cameraNode.addChildNode(marker)
            markerNodes += marker
        }
    }

    private fun clearMarkers() {
        val parent = sceneView.cameraNode
        for (node in markerNodes) {
            parent.removeChildNode(node)
            node.destroy()
        }
        markerNodes.clear()
    }

    private fun buildMetaLine(location: Location, counts: JSONObject?): String {
        val bindings = counts?.optInt("bindings", 0) ?: 0
        val forecasts = counts?.optInt("forecast_paths", 0) ?: 0
        val tracking = trackingMessage?.let { " | $it" } ?: ""
        return String.format(
            "lat %.5f lon %.5f | hdg %.0f deg | %d bindings | %d forecasts%s",
            location.latitude,
            location.longitude,
            currentHeadingDeg,
            bindings,
            forecasts,
            tracking
        )
    }

    private fun buildEntitySummary(tracks: List<ProjectionTrack>): String {
        if (tracks.isEmpty()) {
            return "No nearby recon actors or bindings in the current projection window."
        }
        return tracks.take(5).joinToString(separator = "\n") { track ->
            String.format(
                "%s | %.0fm | %.0f deg | %s",
                track.label,
                track.distanceM,
                track.relativeBearingDeg,
                track.type
            )
        }
    }

    private fun projectedDepth(distanceM: Double): Double {
        return (2.0 + ln1p(distanceM.coerceAtLeast(0.0)) * 1.6).coerceIn(2.0, 16.0)
    }

    private fun trackColor(track: ProjectionTrack): Int {
        return when {
            track.type == "RF_IP_BOUND" -> Color.argb(220, 0, 212, 255)
            track.type.contains("WIFI", ignoreCase = true) -> Color.argb(200, 0, 255, 136)
            track.type.contains("PREDICT", ignoreCase = true) -> Color.argb(180, 255, 153, 0)
            else -> Color.argb(200, 74, 158, 255)
        }
    }

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CAMERA
        }
        if (!hasLocationPermission()) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationUpdates() {
        runCatching {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        }
    }

    private fun bestLastKnownLocation(): Location? {
        if (!hasLocationPermission()) {
            return null
        }
        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }
        return candidates.maxByOrNull { it.time }
    }

    private fun observerId(): String {
        val raw = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        return if (raw.isNullOrBlank()) "android-unknown-device" else "android-$raw"
    }

    private fun updateStatus() {
        val tracking = trackingMessage
        tvStatus.text = if (tracking.isNullOrBlank()) {
            "AR ready - scanning for projected recon actors"
        } else {
            "AR tracking: $tracking"
        }
    }

    private data class ProjectionTrack(
        val entityId: String,
        val label: String,
        val type: String,
        val confidence: Double,
        val distanceM: Double,
        val relativeBearingDeg: Double,
        val elevationDeg: Double,
    )

    companion object {
        private const val REQUEST_PERMISSIONS = 401
        private const val REFRESH_MS = 2000L
    }
}
