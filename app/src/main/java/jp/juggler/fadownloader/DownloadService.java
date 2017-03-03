package jp.juggler.fadownloader;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.WakefulBroadcastReceiver;

public class DownloadService extends Service{

	static final String ACTION_BROADCAST_RECEIVED = "broadcast_received";
	static final String EXTRA_BROADCAST_INTENT = "broadcast_intent";

	static final String ACTION_START = "start";

	static final String EXTRA_REPEAT = "repeat";
	static final String EXTRA_URI = "uri";
	static final String EXTRA_FOLDER_URI = "folder_uri";
	static final String EXTRA_INTERVAL = "interval";
	static final String EXTRA_FILE_TYPE = "file_type";

	static final int NOTIFICATION_ID_SERVICE = 1;

	LogWriter log;

	final BroadcastReceiver receiver = new BroadcastReceiver(){
		@Override public void onReceive( Context context, Intent intent ){
			try{
				String action = intent.getAction();
				if( ConnectivityManager.CONNECTIVITY_ACTION.equals( action ) ){
					Network n = Utils.getWiFiNetwork( context );
					if( n != null ){
						log.v( "接続状況の変化：Wi-Fi 通信可能" );
						int last_mode = Pref.pref( context ).getInt( Pref.LAST_MODE, Pref.LAST_MODE_STOP );
						if( last_mode == Pref.LAST_MODE_STOP ){
							// 起こさない
						}else{
							worker_wakeup();
						}
					}else{
						log.v( "接続状況の変化：Wi-Fi 通信不可" );
					}
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
			}
		}
	};

	boolean is_alive;
	boolean allow_cancel_alarm;
	PowerManager.WakeLock wake_lock;

	WifiManager.WifiLock wifi_lock;
	Handler handler;

	@Override public void onCreate(){
		super.onCreate();

		service_instance = this;
		handler = new Handler();

		is_alive = true;
		allow_cancel_alarm = false;

		log = new LogWriter( getContentResolver() );
		log.d( "サービス開始" );

		PowerManager pm = (PowerManager) getSystemService( Context.POWER_SERVICE );
		wake_lock = pm.newWakeLock( PowerManager.PARTIAL_WAKE_LOCK, getPackageName() );
		wake_lock.setReferenceCounted( false );

		WifiManager wm = (WifiManager) getSystemService( Context.WIFI_SERVICE );
		wifi_lock = wm.createWifiLock( WifiManager.WIFI_MODE_FULL, getPackageName() );
		wifi_lock.setReferenceCounted( false );

		setServiceNotification("待機中");

		registerReceiver( receiver, new IntentFilter( ConnectivityManager.CONNECTIVITY_ACTION ) );

	}

	@Override public void onDestroy(){

		is_alive = false;

		if( worker != null && worker.isAlive() ){
			worker.dispose( "サービス終了" );
		}

		if( allow_cancel_alarm ){
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

		unregisterReceiver( receiver );

		stopForeground( true );

		log.d( "サービス終了" );
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
						log.d( "broadcast受信: %s", action );

						if( Receiver1.ACTION_ALARM.equals( action ) ){
							worker_wakeup();
						}else if( Intent.ACTION_BOOT_COMPLETED.equals( action ) ){
							worker_wakeup();
						}
					}
				}finally{
					WakefulBroadcastReceiver.completeWakefulIntent( intent );
				}
			}else if( ACTION_START.equals( action ) ){

				try{
					will_restart = true;
					if( worker != null ){
						worker.dispose( "手動リスタート" );
						worker = null;
					}
				}catch( Throwable ex ){
					ex.printStackTrace();
					log.e( "スレッドのキャンセルに失敗 %s %s", ex.getClass().getSimpleName(), ex.getMessage() );
				}finally{
					will_restart = false;
				}

				try{
					Pref.pref( this ).edit()
						.remove( Pref.LAST_START )
						.remove( Pref.LAST_SCAN_COMPLETE )
						.apply();
					worker = new DownloadWorker( this, intent, worker_callback );
					worker.start();
				}catch( Throwable ex ){
					ex.printStackTrace();
					log.e( "スレッド開始に失敗 %s %s", ex.getClass().getSimpleName(), ex.getMessage() );
				}
			}else{
				log.d( "インテント受信: %s", action );
			}
		}

		return super.onStartCommand( intent, flags, startId );
	}

	@Nullable @Override public IBinder onBind( Intent intent ){
		return null;
	}

	/////////////////////////////////////////

	DownloadWorker worker;
	boolean will_restart;

	void worker_wakeup(){
		if( worker != null && worker.isAlive() ) return;

		try{
			worker = new DownloadWorker( this, worker_callback );
			worker.start();
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( "スレッド開始に失敗 %s %s", ex.getClass().getSimpleName(), ex.getMessage() );
		}
	}

	final DownloadWorker.Callback worker_callback = new DownloadWorker.Callback(){
		@Override public void releaseWakeLock(){
			if( ! is_alive ) return;
			if( will_restart ) return;
			try{
				wake_lock.release();
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( "WakeLockの解放に失敗:%s %s", ex.getClass().getSimpleName(), ex.getMessage() );
			}
			try{
				wifi_lock.release();
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( "WifiLockの解放に失敗:%s %s", ex.getClass().getSimpleName(), ex.getMessage() );
			}
		}

		@Override public void acquireWakeLock(){
			if( ! is_alive ) return;
			try{
				wake_lock.acquire();
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( "WakeLockの取得に失敗:%s %s", ex.getClass().getSimpleName(), ex.getMessage() );
			}
			try{
				wifi_lock.acquire();
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( "WifiLockの取得に失敗:%s %s", ex.getClass().getSimpleName(), ex.getMessage() );
			}
		}

		@Override public void onThreadStart(){
			setServiceNotification("スレッド実行中");

		}

		@Override public void onThreadEnd( boolean allow_stop_service ){
			if(!will_restart){
				if( allow_stop_service ){
					allow_cancel_alarm = true;
					stopSelf();
				}else{
					setServiceNotification( "待機中" );
				}
			}
		}

	};

	static DownloadService service_instance;

	public static String getStatus(){
		if( service_instance == null ){
			return "サービス停止中。REPEATまたはONCEボタンを押すと開始します";
		}

		StringBuilder sb = new StringBuilder();
		sb.append( String.format( "サービス起動中。WakeLock=%s,WiFiLock=%s\n"
			, service_instance.wake_lock.isHeld() ? "ON" : "OFF"
			, service_instance.wifi_lock.isHeld() ? "ON" : "OFF"
		) );

		if( service_instance.worker == null || ! service_instance.worker.isAlive() ){
			sb.append( "スレッド停止中。WiFi通信状態の変化や時間経過で開始するかも" );
		}else{
			sb.append( "スレッド実行中\n" );
			sb.append( service_instance.worker.getStatus() );
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

}
