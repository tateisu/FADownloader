package jp.juggler.fadownloader

import android.content.Context
import android.location.Location
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.LocationListener
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import java.text.SimpleDateFormat
import java.util.*

class LocationTracker(
	internal val context : Context,
	internal val log : LogWriter,
	internal val mGoogleApiClient : GoogleApiClient,
	internal val callback : (location : Location)->Unit // called when location updated.
) : LocationListener {
	
	
	companion object {
		
		// 動作モード。互換性のためにAPIの数字そのままはではない
		const val NO_LOCATION_UPDATE = 0
		const val LOCATION_NO_POWER = 1
		const val LOCATION_LOW_POWER = 2
		const val LOCATION_BALANCED = 3
		const val LOCATION_HIGH_ACCURACY = 4
		
		// Setting のデフォルト値
		const val DEFAULT_MODE = NO_LOCATION_UPDATE
		const val DEFAULT_INTERVAL_DESIRED = 1000L * 3600
		const val DEFAULT_INTERVAL_MIN = 1000L * 300
		
		internal val date_fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
	}
	
	
	internal var mCurrentLocation : Location? = null
	internal var location_setting : Setting? = null
	internal var is_disposed = false
	
	//////////////////////////////////////////////////
	
	private var isTracked = false
	
	val status : String
		get() {
			if(! isTracked) return "OFF"
			when(location_setting !!.mode) {
				NO_LOCATION_UPDATE -> return context.getString(R.string.location_mode_0)
				LOCATION_NO_POWER -> return context.getString(R.string.location_mode_1)
				LOCATION_LOW_POWER -> return context.getString(R.string.location_mode_2)
				LOCATION_BALANCED -> return context.getString(R.string.location_mode_3)
				LOCATION_HIGH_ACCURACY -> return context.getString(R.string.location_mode_4)
				else -> return "?"
			}
		}
	
	val location : Location?
		@Synchronized get() = if(location_setting == null || ! location_setting !!.isUpdateRequired) null else mCurrentLocation
	
	class Setting {
		
		internal var mode : Int = 0
		
		// Sets the desired interval for active location updates. This interval is
		// inexact. You may not receive updates at all if no location sources are available, or
		// you may receive them slower than requested. You may also receive updates faster than
		// requested if other applications are requesting location at a faster interval.
		var interval_desired : Long = 0
		
		// Sets the fastest rate for active location updates.
		// This interval is exact, and your application will never receive updates faster than this value.
		var interval_min : Long = 0
		
		internal val isUpdateRequired : Boolean
			get() = mode != NO_LOCATION_UPDATE
		
		internal val updatePriority : Int
			get() {
				when(mode) {
					LOCATION_NO_POWER -> return LocationRequest.PRIORITY_NO_POWER
					LOCATION_LOW_POWER -> return LocationRequest.PRIORITY_LOW_POWER
					LOCATION_BALANCED -> return LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY
					LOCATION_HIGH_ACCURACY -> return LocationRequest.PRIORITY_HIGH_ACCURACY
					else -> return LocationRequest.PRIORITY_NO_POWER
				}
			}
	}
	
	fun dispose() {
		is_disposed = true
		tracking_end()
	}
	
	fun onGoogleAPIConnected() {
		tracking_start()
	}
	
	fun onGoogleAPIDisconnected() {
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
			if(! mGoogleApiClient.isConnected) {
				log.d("tracking_end: api not connected.")
			} else {
				LocationServices.FusedLocationApi.removeLocationUpdates(
					mGoogleApiClient,
					this
				).setResultCallback { status ->
					if(status.isSuccess) {
						// 正常終了
						log.h(R.string.location_update_end)
					} else {
						log.e(
							"removeLocationUpdates() failed. (%d)%s",
							status.statusCode,
							status.statusMessage
						)
					}
				}
			}
		} catch(ex : Throwable) {
			ex.printStackTrace()
			log.e(ex, "removeLocationUpdates() failed.")
		} finally {
			isTracked = false
		}
	}
	
	private fun tracking_start() {
		
		if(location_setting == null || ! location_setting !!.isUpdateRequired) {
			return
		}
		
		if(is_disposed) {
			log.d("tracking_start: tracker is already disposed.")
			return
		}
		
		if(! mGoogleApiClient.isConnected) {
			log.d("tracking_start: api not connected.")
			return
		}
		
		// last known location で初期化
		try {
			val loc = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient)
			mCurrentLocation = loc
			if(loc != null) {
				log.v(R.string.location_last_known, date_fmt.format(loc.time))
				callback(loc)
			}
		} catch(ex : SecurityException) {
			log.e(ex, "getLastLocation() failed.")
		} catch(ex : Throwable) {
			ex.printStackTrace()
			log.e(ex, "getLastLocation() failed.")
		}
		
		if(location_setting != null && location_setting !!.isUpdateRequired) {
			val mLocationRequest = LocationRequest()
			mLocationRequest.interval = location_setting !!.interval_desired
			mLocationRequest.fastestInterval = location_setting !!.interval_min
			mLocationRequest.priority = location_setting !!.updatePriority
			try {
				LocationServices.FusedLocationApi.requestLocationUpdates(
					mGoogleApiClient,
					mLocationRequest,
					this
				).setResultCallback { status ->
					if(status.isSuccess) {
						if(! isTracked) log.h(R.string.location_update_start)
						isTracked = true
					} else {
						log.e(
							R.string.location_update_request_result,
							status.statusCode,
							status.statusMessage
						)
					}
				}
			} catch(ex : SecurityException) {
				log.e(ex, "requestLocationUpdates() failed.")
			} catch(ex : Throwable) {
				ex.printStackTrace()
				log.e(ex, "requestLocationUpdates() failed.")
			}
			
		}
	}
	
	@Synchronized
	override fun onLocationChanged(location : Location) {
		log.v(R.string.location_changed, date_fmt.format(location.time))
		mCurrentLocation = location
		callback(location)
	}
	
}
