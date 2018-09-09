package jp.juggler.fadownloader.tracker

import android.content.Context
import android.location.Location
import com.google.android.gms.location.*
import jp.juggler.fadownloader.R
import jp.juggler.fadownloader.util.LogWriter
import java.text.SimpleDateFormat
import java.util.*

class LocationTracker(
	internal val context : Context,
	internal val log : LogWriter,
	internal val callback : (location : Location) -> Unit // called when location updated.
) : LocationListener, LocationCallback() {
	
	companion object {
		
		// 動作モード。互換性のためにAPIの数字そのままはではない
		const val NO_LOCATION_UPDATE = 0
		const val LOCATION_NO_POWER = 1
		const val LOCATION_LOW_POWER = 2
		const val LOCATION_BALANCED = 3
		const val LOCATION_HIGH_ACCURACY = 4
		
		// Setting のデフォルト値
		const val DEFAULT_MODE =
			NO_LOCATION_UPDATE
		const val DEFAULT_INTERVAL_DESIRED = 1000L * 3600
		const val DEFAULT_INTERVAL_MIN = 1000L * 300
		
		internal val date_fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
	}
	
	class Setting(
		internal var mode : Int = 0,
		
		// Sets the desired intervalSeconds for active location updates. This intervalSeconds is
		// inexact. You may not receive updates at all if no location sources are available, or
		// you may receive them slower than requested. You may also receive updates faster than
		// requested if other applications are requesting location at a faster intervalSeconds.
		var interval_desired : Long = 0,
		
		// Sets the fastest rate for active location updates.
		// This intervalSeconds is exact, and your application will never receive updates faster than this value.
		var interval_min : Long = 0
	) {
		
		internal val isUpdateRequired : Boolean
			get() = mode != NO_LOCATION_UPDATE
		
		internal val updatePriority : Int
			get() = when(mode) {
				LOCATION_NO_POWER -> LocationRequest.PRIORITY_NO_POWER
				LOCATION_LOW_POWER -> LocationRequest.PRIORITY_LOW_POWER
				LOCATION_BALANCED -> LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
				LOCATION_HIGH_ACCURACY -> LocationRequest.PRIORITY_HIGH_ACCURACY
				else -> LocationRequest.PRIORITY_NO_POWER
			}
		
		internal fun getModeString(context : Context) =
			when(mode) {
				NO_LOCATION_UPDATE -> context.getString(
					R.string.location_mode_0
				)
				LOCATION_NO_POWER -> context.getString(
					R.string.location_mode_1
				)
				LOCATION_LOW_POWER -> context.getString(
					R.string.location_mode_2
				)
				LOCATION_BALANCED -> context.getString(
					R.string.location_mode_3
				)
				LOCATION_HIGH_ACCURACY -> context.getString(
					R.string.location_mode_4
				)
				else -> "?"
			}
	}
	
	private var mCurrentLocation : Location? = null
	private var location_setting : Setting? = null
	private var is_disposed = false
	
	//////////////////////////////////////////////////
	
	private var isTracked = false
	
	val status : String
		get() {
			if(! isTracked) return "OFF"
			return location_setting?.getModeString(context) ?: "(no location setting)"
		}
	
	val location : Location?
		@Synchronized get() =
			if(location_setting?.isUpdateRequired != true) {
				null
			} else {
				mCurrentLocation
			}
	
	private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context) !!
	
	fun dispose() {
		is_disposed = true
		tracking_end()
	}
	
	@Synchronized
	fun updateSetting(setting : Setting) {
		tracking_end()
		this.location_setting = setting
		tracking_start()
	}
	
	private fun tracking_end() {
		if(! isTracked) return
		
		try {
			fusedLocationClient.removeLocationUpdates(this)
			log.h(R.string.location_update_end)
		} catch(ex : Throwable) {
			ex.printStackTrace()
			log.e(ex, "removeLocationUpdates() failed.")
		} finally {
			isTracked = false
		}
	}
	
	private fun tracking_start() {
		val location_setting = this.location_setting
		if(location_setting?.isUpdateRequired != true) {
			return
		}
		
		if(is_disposed) {
			log.d("tracking_start: tracker is already disposed.")
			return
		}
		
		// last known location で初期化
		try {
			fusedLocationClient.lastLocation
				.addOnSuccessListener { loc : Location? ->
					// Got last known location. In some rare situations this can be null.
					if(loc != null) {
						mCurrentLocation = loc
						log.v(R.string.location_last_known, date_fmt.format(loc.time))
						callback(loc)
					}
				}
		} catch(ex : SecurityException) {
			log.e(ex, "getLastLocation() failed.")
		} catch(ex : Throwable) {
			ex.printStackTrace()
			log.e(ex, "getLastLocation() failed.")
		}
		
		try {
			val mLocationRequest = LocationRequest()
			mLocationRequest.interval = location_setting.interval_desired
			mLocationRequest.fastestInterval = location_setting.interval_min
			mLocationRequest.priority = location_setting.updatePriority

			fusedLocationClient.requestLocationUpdates(
				mLocationRequest,
				this,
				context.mainLooper
			)
				.addOnSuccessListener {
				
				}
				.addOnFailureListener { ex ->
					log.e(ex, R.string.location_update_request_result)
				}
			
			if(! isTracked) log.h(R.string.location_update_start)
			isTracked = true

		} catch(ex : SecurityException) {
			log.e(ex, "requestLocationUpdates() failed.")

		} catch(ex : Throwable) {
			ex.printStackTrace()
			log.e(ex, "requestLocationUpdates() failed.")
		}
		
	}
	
	@Synchronized
	override fun onLocationChanged(location : Location) {
		log.v(R.string.location_changed, date_fmt.format(location.time))
		mCurrentLocation = location
		callback(location)
	}
	
	@Synchronized
	override fun onLocationResult(locationResult : LocationResult?) {
		locationResult ?: return
		var lastLocation : Location? = null
		for(location in locationResult.locations) {
			lastLocation = location
			mCurrentLocation = location
		}
		if(lastLocation != null) {
			log.v(R.string.location_changed, date_fmt.format(lastLocation.time))
			mCurrentLocation = lastLocation
			callback(lastLocation)
		}
	}
}
