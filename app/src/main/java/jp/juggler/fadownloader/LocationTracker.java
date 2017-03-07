package jp.juggler.fadownloader;

import android.location.Location;
import android.support.annotation.NonNull;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.text.SimpleDateFormat;
import java.util.Locale;

public class LocationTracker implements LocationListener{

	// 動作モード。互換性のためにAPIの数字そのままはではない
	public static final int NO_LOCATION_UPDATE = 0;
	public static final int LOCATION_NO_POWER = 1;
	public static final int LOCATION_LOW_POWER = 2;
	public static final int LOCATION_BALANCED = 3;
	public static final int LOCATION_HIGH_ACCURACY = 4;

	// Setting のデフォルト値
	public static final int DEFAULT_MODE = NO_LOCATION_UPDATE;
	public static final long DEFAULT_INTERVAL_DESIRED = 1000L * 3600;
	public static final long DEFAULT_INTERVAL_MIN = 1000L * 300;

	public static class Setting{

		int mode;

		// Sets the desired interval for active location updates. This interval is
		// inexact. You may not receive updates at all if no location sources are available, or
		// you may receive them slower than requested. You may also receive updates faster than
		// requested if other applications are requesting location at a faster interval.
		public long interval_desired;

		// Sets the fastest rate for active location updates.
		// This interval is exact, and your application will never receive updates faster than this value.
		public long interval_min;

		boolean isUpdateRequired(){
			return mode != NO_LOCATION_UPDATE;
		}

		int getUpdatePriority(){
			switch( mode ){
			default:
			case LOCATION_NO_POWER:
				return LocationRequest.PRIORITY_NO_POWER;
			case LOCATION_LOW_POWER:
				return LocationRequest.PRIORITY_LOW_POWER;
			case LOCATION_BALANCED:
				return LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
			case LOCATION_HIGH_ACCURACY:
				return LocationRequest.PRIORITY_HIGH_ACCURACY;
			}
		}
	}

	interface Callback {
		void onLocationChanged(Location location);
	}

	final Callback callback;
	final LogWriter log;
	final GoogleApiClient mGoogleApiClient;
	Location mCurrentLocation;
	Setting location_setting;
	boolean is_disposed = false;

	public LocationTracker( LogWriter log, GoogleApiClient client ,Callback callback){
		this.log = log;
		this.mGoogleApiClient = client;
		this.callback = callback;
	}

	public void dispose(){
		is_disposed = true;
		tracking_end();
	}

	public void onGoogleAPIConnected(){
		tracking_start();
	}

	public void onGoogleAPIDisconnected(){
		tracking_end();
	}

	public synchronized void updateSetting( Setting setting ){
		tracking_end();
		this.location_setting = setting;
		tracking_start();
	}

	//////////////////////////////////////////////////

	private boolean isTracked = false;

	private void tracking_end(){
		if( ! isTracked ) return;

		try{
			if( ! mGoogleApiClient.isConnected() ){
				log.w( "tracking_end: api not connected." );
			}else{
				LocationServices.FusedLocationApi.removeLocationUpdates(
					mGoogleApiClient,
					this
				).setResultCallback( new ResultCallback<Status>(){
					@Override public void onResult( @NonNull Status status ){
						if( status.isSuccess() ){
							// 正常終了
							log.h( R.string.location_update_end );
						}else{
							log.e( "tracking_end: result %s %s", status.getStatusCode(), status.getStatusMessage() );
						}
					}
				} );
			}
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( "tracking_end: exception: %s %s", ex.getClass().getSimpleName(), ex.getMessage() );
		}finally{
			isTracked = false;
		}
	}

	private void tracking_start(){
		if( is_disposed ){
			log.d( "tracking_start: tracker is already disposed." );
			return;
		}
		if( ! mGoogleApiClient.isConnected() ){
			log.d( "tracking_start: api not connected." );
			return;
		}

		// last known location で初期化
		try{
			mCurrentLocation = LocationServices.FusedLocationApi.getLastLocation( mGoogleApiClient );
			if( mCurrentLocation != null ){
				log.v( R.string.location_last_known, date_fmt.format( mCurrentLocation.getTime() ) );
				callback.onLocationChanged( mCurrentLocation );
			}
		}catch( SecurityException ex ){
			log.e( ex, "getLastLocation() failed." );
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "getLastLocation() failed." );
		}

		if( location_setting != null && location_setting.isUpdateRequired() ){
			LocationRequest mLocationRequest = new LocationRequest();
			mLocationRequest.setInterval( location_setting.interval_desired );
			mLocationRequest.setFastestInterval( location_setting.interval_min );
			mLocationRequest.setPriority( location_setting.getUpdatePriority() );
			try{
				LocationServices.FusedLocationApi.requestLocationUpdates(
					mGoogleApiClient,
					mLocationRequest,
					this
				).setResultCallback( new ResultCallback<Status>(){
					@Override public void onResult( @NonNull Status status ){
						if( status.isSuccess() ){
							if( ! isTracked ) log.h( R.string.location_update_start );
							isTracked = true;
						}else{
							log.e( R.string.location_update_request_result, status.getStatusCode(), status.getStatusMessage() );
						}
					}
				} );
			}catch( SecurityException ex ){
				log.e( ex,"requestLocationUpdates() failed." );
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( ex,"requestLocationUpdates() failed." );
			}
		}
	}

	static final SimpleDateFormat date_fmt = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss.SSS z", Locale.getDefault() );

	@Override public synchronized void onLocationChanged( Location location ){
		log.v( R.string.location_changed, date_fmt.format( location.getTime() ) );
		mCurrentLocation = location;
		callback.onLocationChanged( mCurrentLocation );
	}

	public synchronized Location getLocation(){
		if( location_setting == null || ! location_setting.isUpdateRequired() ) return null;
		return mCurrentLocation;
	}

}
