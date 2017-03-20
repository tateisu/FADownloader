package jp.juggler.fadownloader;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.WakefulBroadcastReceiver;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;

public class DownloadService extends Service{

	static final String ACTION_BROADCAST_RECEIVED = "broadcast_received";
	static final String EXTRA_BROADCAST_INTENT = "broadcast_intent";

	static final String ACTION_START = "start";

	static final String EXTRA_REPEAT = "repeat";
	static final String EXTRA_TARGET_URL = "uri";
	static final String EXTRA_LOCAL_FOLDER = "folder_uri";
	static final String EXTRA_INTERVAL = "interval";
	static final String EXTRA_FILE_TYPE = "file_type";
	static final String EXTRA_LOCATION_INTERVAL_DESIRED = "location_interval_desired";
	static final String EXTRA_LOCATION_INTERVAL_MIN = "location_interval_min";
	static final String EXTRA_LOCATION_MODE = "location_mode";
	static final String EXTRA_FORCE_WIFI = "force_wifi";
	static final String EXTRA_SSID = "ssid";
	static final String EXTRA_TARGET_TYPE = "target_type" ;

	static final int NOTIFICATION_ID_SERVICE = 1;
	static final int NOTIFICATION_ID_DOWNLOAD_COMPLETE = 2;

	public LogWriter log;

	boolean is_alive;
	boolean cancel_alarm_on_destroy;
	PowerManager.WakeLock wake_lock;

	WifiManager.WifiLock wifi_lock;
	Handler handler;

	LocationTracker location_tracker;
	public NetworkTracker wifi_tracker;

	NotificationManager mNotificationManager;
	WorkerTracker worker_tracker;

	@Override public void onCreate(){
		super.onCreate();

		service_instance = this;
		handler = new Handler();

		is_alive = true;
		cancel_alarm_on_destroy = false;

		log = new LogWriter( this );
		log.d( getString( R.string.service_start ) );

		PowerManager pm = (PowerManager) getApplicationContext().getSystemService( Context.POWER_SERVICE );
		wake_lock = pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, getPackageName() );
		wake_lock.setReferenceCounted( false );

		WifiManager wm = (WifiManager) getApplicationContext().getSystemService( Context.WIFI_SERVICE );
		wifi_lock = wm.createWifiLock( WifiManager.WIFI_MODE_FULL, getPackageName() );
		wifi_lock.setReferenceCounted( false );

		mNotificationManager = (NotificationManager)  getApplicationContext().getSystemService( Context.NOTIFICATION_SERVICE );

		setServiceNotification( getString( R.string.service_idle ) );

		mGoogleApiClient = new GoogleApiClient.Builder( this )
			.addConnectionCallbacks( connection_callback )
			.addOnConnectionFailedListener( connection_fail_callback )
			.addApi( LocationServices.API )
			.build();
		mGoogleApiClient.connect();

		location_tracker = new LocationTracker( this,log, mGoogleApiClient, new LocationTracker.Callback(){
			@Override public void onLocationChanged( Location location ){
				DownloadService.location = location;
			}
		} );

		wifi_tracker = new NetworkTracker( this, log, new NetworkTracker.Callback(){
			@Override public void onConnectionEvent( boolean is_connected ,String cause){
				if( is_connected ){
					int last_mode = Pref.pref( DownloadService.this ).getInt( Pref.LAST_MODE, Pref.LAST_MODE_STOP );
					if( last_mode != Pref.LAST_MODE_STOP ){
						worker_tracker.wakeup( cause );
					}
				}
			}
		} );

		worker_tracker = new WorkerTracker( this, log);
	}

	@Override public void onDestroy(){

		is_alive = false;

		worker_tracker.dispose();

		location_tracker.dispose();
		wifi_tracker.dispose();

		if( mGoogleApiClient.isConnected() ){
			mGoogleApiClient.disconnect();
		}


		if( cancel_alarm_on_destroy ){
			try{
				PendingIntent pi = Utils.createAlarmPendingIntent( this );
				AlarmManager am = (AlarmManager) getSystemService( Context.ALARM_SERVICE );
				am.cancel( pi );
			}catch( Throwable ex ){
				ex.printStackTrace();
			}
		}

		wake_lock.release();
		wake_lock = null;

		wifi_lock.release();
		wifi_lock = null;

		stopForeground( true );

		log.d( getString( R.string.service_end ) );
		log.dispose();
		log = null;

		service_instance = null;

		super.onDestroy();
	}

	@Override public int onStartCommand( Intent intent, int flags, int startId ){
		if( intent != null ){
			String action = intent.getAction();
			if( ACTION_BROADCAST_RECEIVED.equals( action ) ){
				try{
					Intent broadcast_intent = intent.getParcelableExtra( EXTRA_BROADCAST_INTENT );
					if( broadcast_intent != null ){
						action = broadcast_intent.getAction();

						if( Receiver1.ACTION_ALARM.equals( action ) ){
							worker_tracker.wakeup( "Alarm" );
						}else if( Intent.ACTION_BOOT_COMPLETED.equals( action ) ){
							worker_tracker.wakeup( "Boot completed" );
						}else{
							log.d( getString( R.string.broadcast_received, action ) );
						}
					}
				}finally{
					WakefulBroadcastReceiver.completeWakefulIntent( intent );
				}
			}else if( ACTION_START.equals( action ) ){
				worker_tracker.start(intent);
			}else{
				log.d( getString( R.string.unsupported_intent_received, action ) );
			}
		}

		return super.onStartCommand( intent, flags, startId );
	}

	@Nullable @Override public IBinder onBind( Intent intent ){
		return null;
	}

	//////////////////////////////////////////////////////////////////////////////////

	void releaseWakeLock(){
		if( ! is_alive ) return;
		try{
			wake_lock.release();
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "WakeLock release failed." );
		}
		try{
			wifi_lock.release();
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "WifiLock release failed." );
		}
	}
	void acquireWakeLock(){
		if( ! is_alive ) return;
		try{
			wake_lock.acquire();
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "WakeLock acquire failed." );
		}
		try{
			wifi_lock.acquire();
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "WifiLock acquire failed." );
		}
	}
	public void onThreadStart(){
		if( ! is_alive ) return;
		setServiceNotification( getString( R.string.thread_running ) );
	}

	void onThreadEnd(boolean complete_and_no_repeat ){
		if(!is_alive) return;

		if( complete_and_no_repeat ){
			DownloadService.this.cancel_alarm_on_destroy = true;
			stopSelf();
		}else{
			setServiceNotification( getString( R.string.service_idle ) );
		}
	}

	void clearDownloadCompleteNotification(long count){
		if( count > 0 ){
			mNotificationManager.cancel( NOTIFICATION_ID_DOWNLOAD_COMPLETE );
		}
	}
	void setDownloadCompleteNotification(long count){
		NotificationCompat.Builder builder = new NotificationCompat.Builder( DownloadService.this );
		builder.setSmallIcon( R.drawable.ic_service );
		builder.setContentTitle( getString( R.string.app_name ) );
		builder.setContentText( getString(R.string.download_complete_notification,count) );
		builder.setTicker( getString(R.string.download_complete_notification,count) );
		builder.setWhen( System.currentTimeMillis() );
		builder.setDefaults(  NotificationCompat.DEFAULT_ALL );
		builder.setAutoCancel( true );

		Intent intent = new Intent( DownloadService.this, ActMain.class );
		intent.putExtra(ActMain.EXTRA_TAB,ActMain.TAB_RECORD);
		intent.setFlags( Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY );
		PendingIntent pi = PendingIntent.getActivity( getApplicationContext(), 567, intent, 0 );
		builder.setContentIntent( pi );

		mNotificationManager.notify(  NOTIFICATION_ID_DOWNLOAD_COMPLETE, builder.build() );
	}

	static DownloadService service_instance;

	public static String getStatusForActivity( Context context ){
		if( service_instance == null ){
			return context.getString( R.string.service_not_running );
		}

		StringBuilder sb = new StringBuilder();
		sb.append( context.getString( R.string.service_running ));
		sb.append( "WakeLock=" )
			.append( service_instance.wake_lock.isHeld() ? "ON" : "OFF" )
			.append( ", " )
		.append( "WiFiLock=" )
			.append( service_instance.wifi_lock.isHeld() ? "ON" : "OFF" )
			.append( ", " )
		.append( "Location=" )
			.append( service_instance.location_tracker.getStatus() )
			.append( ", " )
		.append( "Network=" );
		service_instance.wifi_tracker.getStatus(sb);
		sb.append('\n');

		DownloadWorker worker = service_instance.worker_tracker.worker;
		if( worker == null || ! worker.isAlive() ){
			sb.append( context.getString( R.string.thread_not_running_status ) );
		}else{
			sb.append( worker.getStatus() );
		}

		return sb.toString();
	}

	void setServiceNotification( String status ){
		if( ! is_alive ) return;

		NotificationCompat.Builder builder = new NotificationCompat.Builder( this );
		builder.setSmallIcon( R.drawable.ic_service );
		builder.setContentTitle( getString( R.string.app_name ) );
		builder.setContentText( status );
		builder.setOngoing( true );

		Intent intent = new Intent( this, ActMain.class );
		intent.setFlags( Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY );
		PendingIntent pi = PendingIntent.getActivity( getApplicationContext(), 567, intent, 0 );
		builder.setContentIntent( pi );

		startForeground( NOTIFICATION_ID_SERVICE, builder.build() );
	}



	GoogleApiClient mGoogleApiClient;

	final GoogleApiClient.OnConnectionFailedListener connection_fail_callback = new GoogleApiClient.OnConnectionFailedListener(){
		@Override public void onConnectionFailed( @NonNull ConnectionResult connectionResult ){
			int code = connectionResult.getErrorCode();
			if( code == ConnectionResult.SERVICE_INVALID ){
				// Kindle端末で発生
				return;
			}

			String msg = Utils.getConnectionResultErrorMessage( connectionResult );
			log.w( R.string.play_service_connection_failed, code, msg );
			location_tracker.onGoogleAPIDisconnected();
		}
	};

	final GoogleApiClient.ConnectionCallbacks connection_callback = new GoogleApiClient.ConnectionCallbacks(){
		@Override public void onConnected( @Nullable Bundle bundle ){
			if( ! is_alive ) return;

			// 位置情報の追跡状態を更新する
			location_tracker.onGoogleAPIConnected();
		}

		// Playサービスとの接続が失われた
		@Override public void onConnectionSuspended( int i ){
			if( ! is_alive ) return;

			String msg = Utils.getConnectionSuspendedMessage( i );
			log.w( R.string.play_service_connection_suspended, i, msg );

			// 再接続は自動で行われるらしい

			// 位置情報の追跡状態を更新する
			location_tracker.onGoogleAPIDisconnected();
		}
	};

	static Location location;

	public static Location getLocation(){
		return location;
	}

}
