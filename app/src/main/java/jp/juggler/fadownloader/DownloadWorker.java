package jp.juggler.fadownloader;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import it.sephiroth.android.library.exif2.ExifInterface;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadWorker extends WorkerBase {

	static final boolean RECORD_QUEUED_STATE = false;


	public static final int TARGET_TYPE_FLASHAIR_AP = 0;
	public static final int TARGET_TYPE_FLASHAIR_STA = 1;

	public interface Callback{

		void releaseWakeLock();

		void acquireWakeLock();

		void onThreadStart();

		void onThreadEnd( boolean allow_stop_service );

		Location getLocation();
	}

	final DownloadService service;
	final Callback callback;

	final boolean repeat;
	String flashair_url;
	final String folder_uri;
	final int interval;
	final String file_type;
	final LogWriter log;
	final ArrayList<Pattern> file_type_list;
	final boolean force_wifi;
	final String ssid;
	final int target_type;

	public DownloadWorker( DownloadService service, Intent intent, Callback callback ){
		this.service = service;
		this.callback = callback;
		this.log = new LogWriter( service );

		log.i( R.string.thread_ctor_params );
		this.repeat = intent.getBooleanExtra( DownloadService.EXTRA_REPEAT, false );
		this.flashair_url = intent.getStringExtra( DownloadService.EXTRA_URI );
		this.folder_uri = intent.getStringExtra( DownloadService.EXTRA_FOLDER_URI );
		this.interval = intent.getIntExtra( DownloadService.EXTRA_INTERVAL, 86400 );
		this.file_type = intent.getStringExtra( DownloadService.EXTRA_FILE_TYPE );
		this.force_wifi = intent.getBooleanExtra( DownloadService.EXTRA_FORCE_WIFI, false );
		this.ssid = intent.getStringExtra( DownloadService.EXTRA_SSID );
		this.target_type =intent.getIntExtra( DownloadService.EXTRA_TARGET_TYPE, 0 );

		LocationTracker.Setting location_setting = new LocationTracker.Setting();
		location_setting.interval_desired = intent.getLongExtra( DownloadService.EXTRA_LOCATION_INTERVAL_DESIRED, LocationTracker.DEFAULT_INTERVAL_DESIRED );
		location_setting.interval_min = intent.getLongExtra( DownloadService.EXTRA_LOCATION_INTERVAL_MIN, LocationTracker.DEFAULT_INTERVAL_MIN );
		location_setting.mode = intent.getIntExtra( DownloadService.EXTRA_LOCATION_MODE, LocationTracker.DEFAULT_MODE );

		Pref.pref( service ).edit()
			.putBoolean( Pref.WORKER_REPEAT, repeat )
			.putInt( Pref.WORKER_TARGET_TYPE, target_type )
			.putString( Pref.WORKER_FLASHAIR_URL, flashair_url )
			.putString( Pref.WORKER_FOLDER_URI, folder_uri )
			.putInt( Pref.WORKER_INTERVAL, interval )
			.putString( Pref.WORKER_FILE_TYPE, file_type )
			.putLong( Pref.WORKER_LOCATION_INTERVAL_DESIRED, location_setting.interval_desired )
			.putLong( Pref.WORKER_LOCATION_INTERVAL_MIN, location_setting.interval_min )
			.putInt( Pref.WORKER_LOCATION_MODE, location_setting.mode )
			.putBoolean( Pref.WORKER_FORCE_WIFI, force_wifi )
			.putString( Pref.WORKER_SSID, ssid )
			.apply();

		file_type_list = file_type_parse();

		service.wifi_tracker.updateSetting( force_wifi, ssid ,target_type,flashair_url);

		service.location_tracker.updateSetting( location_setting );
	}

	public DownloadWorker( DownloadService service, String cause, Callback callback ){
		this.service = service;
		this.callback = callback;
		this.log = new LogWriter( service );

		log.i( R.string.thread_ctor_restart, cause );
		SharedPreferences pref = Pref.pref( service );
		this.repeat = pref.getBoolean( Pref.WORKER_REPEAT, false );
		this.flashair_url = pref.getString( Pref.WORKER_FLASHAIR_URL, null );
		this.folder_uri = pref.getString( Pref.WORKER_FOLDER_URI, null );
		this.interval = pref.getInt( Pref.WORKER_INTERVAL, 86400 );
		this.file_type = pref.getString( Pref.WORKER_FILE_TYPE, null );

		this.force_wifi = pref.getBoolean( Pref.WORKER_FORCE_WIFI, false );
		this.ssid = pref.getString( Pref.WORKER_SSID, null );
		this.target_type = pref.getInt( Pref.WORKER_TARGET_TYPE, 0);


		LocationTracker.Setting location_setting = new LocationTracker.Setting();
		location_setting.interval_desired = pref.getLong( Pref.WORKER_LOCATION_INTERVAL_DESIRED, LocationTracker.DEFAULT_INTERVAL_DESIRED );
		location_setting.interval_min = pref.getLong( Pref.WORKER_LOCATION_INTERVAL_MIN, LocationTracker.DEFAULT_INTERVAL_MIN );
		location_setting.mode = pref.getInt( Pref.WORKER_LOCATION_MODE, LocationTracker.DEFAULT_MODE );

		file_type_list = file_type_parse();

		service.wifi_tracker.updateSetting( force_wifi, ssid  ,target_type ,flashair_url);

		service.location_tracker.updateSetting( location_setting );
	}

	final HTTPClient client = new HTTPClient( 30000, 4, "HTTP Client", this );

	public boolean cancel( String reason ){
		boolean rv = super.cancel( reason );
		if( rv ) log.i( R.string.thread_cancelled, reason );
		try{
			client.cancel( log );
		}catch( Throwable ex ){
			ex.printStackTrace();
		}
		return rv;
	}

	static final Pattern reJPEG = Pattern.compile( "\\.jp(g|eg?)\\z", Pattern.CASE_INSENSITIVE );
	static final Pattern reFileType = Pattern.compile( "(\\S+)" );

	private ArrayList<Pattern> file_type_parse(){
		ArrayList<Pattern> list = new ArrayList<>();
		Matcher m = reFileType.matcher( file_type );
		while( m.find() ){
			try{
				String spec = m.group( 1 ).replaceAll( "(\\W)", "\\\\$1" ).replaceAll( "\\\\\\*", ".*?" );
				list.add( Pattern.compile( spec + "\\z", Pattern.CASE_INSENSITIVE ) );
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( R.string.file_type_parse_error, m.group( 1 ), ex.getClass().getSimpleName(), ex.getMessage() );
			}
		}
		return list;
	}

	// static final long ERROR_BREAK = -1L;
	static final long ERROR_CONTINUE = - 2L;

	long getFlashAirUpdateStatus( Object network ){
		String cgi_url = flashair_url + "command.cgi?op=121";
		byte[] data = client.getHTTP( log, network, cgi_url );
		if( isCancelled() ) return ERROR_CONTINUE;

		if( data == null ){
			if( client.last_error.contains( "UnknownHostException" ) ){
				client.last_error = service.getString( R.string.flashair_host_error );
				cancel( service.getString( R.string.flashair_host_error_short ) );
			}else if( client.last_error.contains( "ENETUNREACH" ) ){
				client.last_error = service.getString( R.string.network_unreachable );
				cancel( service.getString( R.string.network_unreachable ) );
			}
			log.e( R.string.flashair_update_check_failed, cgi_url, client.last_error );
			return ERROR_CONTINUE;
		}
		try{
			//noinspection ConstantConditions
			return Long.parseLong( Utils.decodeUTF8( data ).trim() );
		}catch( Throwable ex ){
			log.e( R.string.flashair_update_status_error );
			cancel( service.getString( R.string.flashair_update_status_error ) );
			return ERROR_CONTINUE;
		}
	}

	static class ErrorAndMessage{

		boolean bError;
		String message;

		ErrorAndMessage( boolean b, String s ){
			bError = b;
			message = s;
		}
	}

	private @NonNull ErrorAndMessage updateFileLocation( final Location location, LocalFile file ){
		ErrorAndMessage em = null;
		try{

			LocalFile tmp_path = new LocalFile( file.getParent(), "tmp-" + currentThread().getId() + "-" + android.os.Process.myPid() + "-" + file.getName() );
			if( ! tmp_path.prepareFile( log, true ) ){
				return new ErrorAndMessage( true, "file creation failed." );
			}

			try{
				OutputStream os = tmp_path.openOutputStream( service );
				try{
					InputStream is = file.openInputStream( service );
					try{
						ExifInterface.modifyExifTag( is, ExifInterface.Options.OPTION_ALL
							, os, new ExifInterface.ModifyExifTagCallback(){
								@Override public boolean modify( ExifInterface ei ){
									String s = ei.getLatitude();
									if( s != null ) return false;

									double latitude = location.getLatitude();
									double longitude = location.getLongitude();

									ei.addGpsTags( latitude, longitude );

									return true;
								}
							} );
					}finally{
						try{
							if( is != null ) is.close();
						}catch( Throwable ignored ){
						}

					}
				}finally{
					try{
						if( os != null ) os.close();
					}catch( Throwable ignored ){
					}
				}
			}catch( ExifInterface.ModifyExifTagFailedException ignored ){
				// 既にEXIF情報があった
				em = new ErrorAndMessage( false, "既に位置情報が含まれています" );
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( ex, "exif mangling failed." );

				// 変更失敗
				em = new ErrorAndMessage( true, LogWriter.formatError( ex, "exif mangling failed." ) );
			}

			if( em != null ){
				tmp_path.delete();
				return em;
			}

			// 更新後の方がファイルが小さいことがあるのか？
			if( tmp_path.length( log, false ) < file.length( log, false ) ){
				log.e( "EXIF付与したファイルの方が小さい!付与前後のファイルを残しておく" );
				// この場合両方のファイルを残しておく
				return new ErrorAndMessage( true, "EXIF付与したファイルの方が小さい" );
			}

			if( ! file.delete() || ! tmp_path.renameTo( file.getName() ) ){
				log.e( "EXIF追加後のファイル操作に失敗" );
				return new ErrorAndMessage( true, "EXIF追加後のファイル操作に失敗" );
			}

			log.i( "%s に位置情報を付与しました", file.getName() );
			return new ErrorAndMessage( false, "embedded" );

		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "exif mangling failed." );
			return new ErrorAndMessage( true, LogWriter.formatError( ex, "exif mangling failed." ) );
		}
	}

	boolean isForcedSSID(){
		if( ! force_wifi ) return true;
		WifiManager wm = (WifiManager) service.getApplicationContext().getSystemService( Context.WIFI_SERVICE );
		WifiInfo wi = wm.getConnectionInfo();
		String current_ssid = wi.getSSID().replace( "\"", "" );
		return ! TextUtils.isEmpty( current_ssid ) && current_ssid.equals( this.ssid );
	}

	@SuppressWarnings( "deprecation" )
	private Object getWiFiNetwork(){
		ConnectivityManager cm = (ConnectivityManager) service.getApplicationContext().getSystemService( Context.CONNECTIVITY_SERVICE );
		if( Build.VERSION.SDK_INT >= 21 ){
			for( Network n : cm.getAllNetworks() ){
				NetworkInfo info = cm.getNetworkInfo( n );
				if( info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI ){
					if( isForcedSSID() ) return n;
				}
			}
		}else{
			for( NetworkInfo info : cm.getAllNetworkInfo() ){
				if( info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI ){
					if( isForcedSSID() ) return info;
					return info;
				}
			}
		}
		return null;
	}

	final ContentValues cv = new ContentValues();

	static class Item{

		final String air_path;
		final LocalFile local_file;
		final boolean is_file;
		final long size;

		Item( String air_path, LocalFile local_file, boolean is_file, long size ){
			this.air_path = air_path;
			this.local_file = local_file;
			this.is_file = is_file;
			this.size = size;
		}
	}

	static final Pattern reLine = Pattern.compile( "([^\\x0d\\x0a]+)" );
	static final Pattern reAttr = Pattern.compile( ",(\\d+),(\\d+),(\\d+),(\\d+)$" );

	LinkedList<Item> job_queue = null;
	long flashair_update_status = 0L;
	boolean file_error = false;

	void loadFolder( Object network, Item item ){

		// フォルダを読む
		String cgi_url = flashair_url + "command.cgi?op=100&DIR=" + Uri.encode( item.air_path );
		byte[] data = client.getHTTP( log, network, cgi_url );
		if( isCancelled() ) return;

		if( data == null ){
			if( client.last_error.contains( "UnknownHostException" ) ){
				client.last_error = service.getString( R.string.flashair_host_error );
				cancel( service.getString( R.string.flashair_host_error_short ) );
			}else if( client.last_error.contains( "ENETUNREACH" ) ){
				client.last_error = service.getString( R.string.network_unreachable );
				cancel( service.getString( R.string.network_unreachable ) );
			}
			log.e( R.string.folder_list_failed, item.air_path, cgi_url, client.last_error );
			file_error = true;
			return;
		}

		Matcher mLine;
		try{
			//noinspection ConstantConditions
			mLine = reLine.matcher( Utils.decodeUTF8( data ) );
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "folder list parse error." );
			return;
		}

		while( ! isCancelled() && mLine.find() ){
			String line = mLine.group( 1 );
			Matcher mAttr = reAttr.matcher( line );
			if( ! mAttr.find() ) continue;

			try{
				long size = Long.parseLong( mAttr.group( 1 ), 10 );
				int attr = Integer.parseInt( mAttr.group( 2 ), 10 );
//							int date = Integer.parseInt( mAttr.group( 3 ), 10 );
//							int time = Integer.parseInt( mAttr.group( 4 ), 10 );

				// https://flashair-developers.com/ja/support/forum/#/discussion/3/%E3%82%AB%E3%83%B3%E3%83%9E%E5%8C%BA%E5%88%87%E3%82%8A
				String dir = ( item.air_path.equals( "/" ) ? "" : item.air_path );
				String file_name = line.substring( dir.length() + 1, mAttr.start() );

				if( ( attr & 2 ) != 0 ){
					// skip hidden file
					continue;
				}else if( ( attr & 4 ) != 0 ){
					// skip system file
					continue;
				}

				String child_air_path = dir + "/" + file_name;
				final LocalFile local_child = new LocalFile( item.local_file, file_name );

				if( ( attr & 0x10 ) != 0 ){
					// フォルダはキューの頭に追加
					job_queue.addFirst( new Item( child_air_path, local_child, false, 0L ) );
				}else{
					for( Pattern re : file_type_list ){
						if( ! re.matcher( file_name ).find() ) continue;
						// マッチした

						// ローカルのファイルサイズを調べて既読スキップ
						if( local_child.length( log, false ) >= size ) continue;

						// ファイルはキューの末尾に追加
						job_queue.addLast( new Item( child_air_path, local_child, true, size ) );

						if( RECORD_QUEUED_STATE ){
							// 未取得のデータを履歴に表示する
							DownloadRecord.insert(
								service.getContentResolver()
								, cv
								, file_name
								, child_air_path
								, "" // local file uri
								, DownloadRecord.STATE_QUEUED
								, "queued."
								, 0L
								, size
							);
						}
						break;
					}
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( ex, "folder list parse error: %s", line );
			}
		}
	}

	private void loadFile( Object network, Item item ){
		long time_start = SystemClock.elapsedRealtime();
		final String child_air_path = item.air_path;
		final LocalFile local_child = item.local_file;
		final String file_name = new File( item.air_path ).getName();

		try{

			if( ! local_child.prepareFile( log, true ) ){
				log.e( "%s//%s :skip. can not prepare local file.", item.air_path, file_name );
				DownloadRecord.insert(
					service.getContentResolver()
					, cv
					, file_name
					, child_air_path
					, "" // local file uri
					, DownloadRecord.STATE_LOCAL_FILE_PREPARE_ERROR
					, "can not prepare local file."
					, SystemClock.elapsedRealtime() - time_start
					, item.size
				);
				return;
			}

			final String get_url = flashair_url + Uri.encode( child_air_path );
			byte[] data = client.getHTTP( log, network, get_url, new HTTPClientReceiver(){
				final byte[] buf = new byte[ 2048 ];

				public byte[] onHTTPClientStream( LogWriter log, CancelChecker cancel_checker, InputStream in, int content_length ){
					try{
						OutputStream os = local_child.openOutputStream( service );
						if( os == null ){
							log.e( "cannot open local output file." );
						}else{
							try{
								for( ; ; ){
									if( cancel_checker.isCancelled() ){
										return null;
									}
									int delta = in.read( buf );
									if( delta <= 0 ) break;
									os.write( buf, 0, delta );
								}
								return buf;
							}finally{
								try{
									os.close();
								}catch( Throwable ignored ){
								}
							}
						}
					}catch( Throwable ex ){
						log.e( "HTTP read error. %s:%s"
							, ex.getClass().getSimpleName()
							, ex.getMessage()
						);
					}
					return null;
				}
			} );

			if( isCancelled() ){
				DownloadRecord.insert(
					service.getContentResolver()
					, cv
					, file_name
					, child_air_path
					, local_child.getFileUri( log, false )
					, DownloadRecord.STATE_CANCELLED
					, "download cancelled."
					, SystemClock.elapsedRealtime() - time_start
					, item.size
				);
				return;
			}

			if( data == null ){
				log.e( "FILE %s :HTTP error %s", file_name, client.last_error );

				if( client.last_error.contains( "UnknownHostException" ) ){
					client.last_error = service.getString( R.string.flashair_host_error );
					cancel( service.getString( R.string.flashair_host_error_short ) );
				}else if( client.last_error.contains( "ENETUNREACH" ) ){
					client.last_error = service.getString( R.string.network_unreachable );
					cancel( service.getString( R.string.network_unreachable ) );
				}
				DownloadRecord.insert(
					service.getContentResolver()
					, cv
					, file_name
					, child_air_path
					, local_child.getFileUri( log, false )
					, DownloadRecord.STATE_DOWNLOAD_ERROR
					, client.last_error
					, SystemClock.elapsedRealtime() - time_start
					, item.size
				);
				file_error = true;
				return;
			}

			log.i( "FILE %s :download complete. %dms", file_name, SystemClock.elapsedRealtime() - time_start );

			// 位置情報を取得する時にファイルの日時が使えないかと思ったけど
			// タイムゾーンがわからん…

			Location location = callback.getLocation();
			if( location != null && reJPEG.matcher( file_name ).find() ){
				ErrorAndMessage em = updateFileLocation( location, local_child );
				DownloadRecord.insert(
					service.getContentResolver()
					, cv
					, file_name
					, child_air_path
					, local_child.getFileUri( log, false )
					, em.bError ? DownloadRecord.STATE_EXIF_MANGLING_ERROR : DownloadRecord.STATE_COMPLETED
					, "location data: " + em.message
					, SystemClock.elapsedRealtime() - time_start
					, item.size
				);
			}else{
				DownloadRecord.insert(
					service.getContentResolver()
					, cv
					, file_name
					, child_air_path
					, local_child.getFileUri( log, false )
					, DownloadRecord.STATE_COMPLETED
					, "OK"
					, SystemClock.elapsedRealtime() - time_start
					, item.size
				);
			}
		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( ex, "error." );

			file_error = true;
			DownloadRecord.insert(
				service.getContentResolver()
				, cv
				, file_name
				, child_air_path
				, local_child.getFileUri( log, false )
				, DownloadRecord.STATE_DOWNLOAD_ERROR
				, LogWriter.formatError( ex, "?" )
				, SystemClock.elapsedRealtime() - time_start
				, item.size
			);
		}

	}

	AtomicLong queued_file_count = new AtomicLong();
	AtomicLong queued_byte_count = new AtomicLong();
	AtomicLong queued_byte_count_max = new AtomicLong();

	final AtomicReference<String> _status = new AtomicReference<>( "?" );

	public void setStatus( boolean bShowQueueCount, String s ){
		if( ! bShowQueueCount ){
			queued_file_count.set( 0L );
			queued_byte_count.set( 0L );
		}else{
			long fc = 0L;
			long bc = 0L;
			if( job_queue != null ){
				for( Item item : job_queue ){
					if( item.is_file ){
						++ fc;
						bc += item.size;
					}
				}
			}
			queued_file_count.set( fc );
			queued_byte_count.set( bc );
			if( bc > 0 && queued_byte_count_max.get() == Long.MAX_VALUE ){
				queued_byte_count_max.set( bc );
			}
		}
		_status.set( s );
	}

	public String getStatus(){
		long fc = queued_file_count.get();
		String s = _status.get();
		if( fc > 0L ){
			long bc = queued_byte_count.get();
			long bcm = queued_byte_count_max.get();
			return s + String.format( "\n%s %d%%, %s %dfile %sbyte"
				, service.getString( R.string.progress )
				, bcm <= 0 ? 0 : 100L * ( bcm - bc ) / bcm
				, service.getString( R.string.remain )
				, fc
				, Utils.formatBytes( bc )
			);
		}else{
			return s;
		}
	}

	@SuppressWarnings( "ConstantConditions" ) @Override public void run(){

		setStatus( false, service.getString( R.string.thread_start ) );
		boolean allow_stop_service = false;
		callback.onThreadStart();

		while( ! isCancelled() ){

			// 古いアラームがあれば除去
			try{
				PendingIntent pi = Utils.createAlarmPendingIntent( service );
				AlarmManager am = (AlarmManager) service.getSystemService( Context.ALARM_SERVICE );
				am.cancel( pi );
			}catch( Throwable ex ){
				ex.printStackTrace();
			}

			if( job_queue == null ){
				// 指定時刻まで待機する
				while( ! isCancelled() ){
					long now = System.currentTimeMillis();
					long last_file_listing = Pref.pref( service ).getLong( Pref.LAST_IDLE_START, 0L );
					long remain = last_file_listing + interval * 1000L - now;
					if( remain <= 0 ) break;

					if( remain < ( 15 * 1000L ) ){
						setStatus( false, service.getString( R.string.wait_short, Utils.formatTimeDuration( remain ) ) );
						waitEx( remain > 1000L ? 1000L : remain );
					}else{
						try{
							PendingIntent pi = Utils.createAlarmPendingIntent( service );

							AlarmManager am = (AlarmManager) service.getSystemService( Context.ALARM_SERVICE ); // AlarmManager取得
							/*
							if( Build.VERSION.SDK_INT >= 23 ){
								am.setExactAndAllowWhileIdle( AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime(), pi );
								// レシーバーは受け取れるが端末のIDLE状態は解除されない。アプリが動けるのは10秒。IDLEからの復帰は15分に1度だけ許される
							}else
							*/
							if( Build.VERSION.SDK_INT >= 21 ){
								am.setAlarmClock( new AlarmManager.AlarmClockInfo( now + remain, pi ), pi );
							}else if( Build.VERSION.SDK_INT >= 19 ){
								am.setExact( AlarmManager.RTC_WAKEUP, now + remain, pi );
							}else{
								am.set( AlarmManager.RTC_WAKEUP, now + remain, pi );
							}
						}catch( Throwable ex ){
							ex.printStackTrace();
							log.e( "待機の設定に失敗 %s %s", ex.getClass().getSimpleName(), ex.getMessage() );
						}
						cancel( service.getString( R.string.wait_alarm, Utils.formatTimeDuration( remain ) ) );
						break;
					}
				}
				if( isCancelled() ) break;
				// 待機が終わった
			}

			callback.acquireWakeLock();
			Object network = null;

			// 通信状態の確認
			setStatus( false, service.getString( R.string.network_check ) );
			long network_check_start = SystemClock.elapsedRealtime();

			if( target_type == TARGET_TYPE_FLASHAIR_STA ){
				while( ! isCancelled() ){
					boolean tracker_last_result = service.wifi_tracker.last_result.get();
					String air_url = service.wifi_tracker.last_flash_air_url.get();
					if( tracker_last_result && air_url != null ){
						this.flashair_url = air_url;
						break;
					}

					// 一定時間待機してもダメならスレッドを停止する
					// 通信状態変化でまた起こされる
					long er_now = SystemClock.elapsedRealtime();
					if( er_now - network_check_start >= 60 * 1000L ){
						// Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();
						job_queue = null;
						cancel( service.getString( R.string.network_not_good ) );
						break;
					}

					// 少し待って再確認
					waitEx( 10000L );
				}
			}else{

				while( ! isCancelled() ){
					network = getWiFiNetwork();
					if( network != null ) break;

					// 一定時間待機してもダメならスレッドを停止する
					// 通信状態変化でまた起こされる
					long er_now = SystemClock.elapsedRealtime();
					if( er_now - network_check_start >= 60 * 1000L ){
						// Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();
						job_queue = null;
						cancel( service.getString( R.string.network_not_good ) );
						break;
					}

					// 少し待って再確認
					waitEx( 10000L );
				}
			}
			if( isCancelled() ) break;




			// ファイルスキャンの開始
			if( job_queue == null ){
				Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();

				// FlashAir アップデートステータスを確認
				setStatus( false, service.getString( R.string.flashair_update_status_check ) );
				flashair_update_status = getFlashAirUpdateStatus( network );
				if( flashair_update_status == ERROR_CONTINUE ){
					continue;
				}else{
					long old = Pref.pref( service ).getLong( Pref.FLASHAIR_UPDATE_STATUS_OLD, - 1L );
					if( flashair_update_status == old && old != - 1L ){
						// 前回スキャン開始時と同じ数字なので変更されていない
						log.d( R.string.flashair_not_updated );
						continue;
					}else{
						log.d( "flashair updated %d %d"
							, old
							, flashair_update_status
						);
					}
				}

				// 未取得状態のファイルを履歴から消す
				if( RECORD_QUEUED_STATE ){
					service.getContentResolver().delete(
						DownloadRecord.meta.content_uri
						, DownloadRecord.COL_STATE_CODE + "=?"
						, new String[]{
							Integer.toString( DownloadRecord.STATE_QUEUED )
						}
					);
				}

				// フォルダスキャン開始
				job_queue = new LinkedList<>();
				job_queue.add( new Item( "/", new LocalFile( service, folder_uri ), false, 0L ) );
				file_error = false;
				queued_byte_count_max.set( Long.MAX_VALUE );
			}

			// ファイルスキャンの終了
			if( job_queue.isEmpty() ){
				job_queue = null;
				setStatus( false, service.getString( R.string.file_scan_completed ) );
				if( ! file_error ){
					log.i( "ファイルスキャン完了" );
					Pref.pref( service ).edit()
						.putLong( Pref.FLASHAIR_UPDATE_STATUS_OLD, flashair_update_status )
						.apply();

					if( ! repeat ){
						Pref.pref( service ).edit().putInt( Pref.LAST_MODE, Pref.LAST_MODE_STOP ).apply();
						cancel( service.getString( R.string.repeat_off ) );
						allow_stop_service = true;
					}
				}
				continue;
			}

			try{
				final Item head = job_queue.getFirst();
				if( head.is_file ){
					// キューから除去するまえに残りサイズを計算したい
					setStatus( true, service.getString( R.string.download_file, head.air_path ) );
					job_queue.removeFirst();
					loadFile( network, head );
				}else{
					setStatus( false, service.getString( R.string.progress_folder, head.air_path ) );
					job_queue.removeFirst();
					loadFolder( network, head );
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( ex, "error." );
			}
		}

		// 未取得状態のファイルを履歴から消す
		if( RECORD_QUEUED_STATE ){
			service.getContentResolver().delete(
				DownloadRecord.meta.content_uri
				, DownloadRecord.COL_STATE_CODE + "=?"
				, new String[]{
					Integer.toString( DownloadRecord.STATE_QUEUED )
				}
			);
		}

		setStatus( false, service.getString( R.string.thread_end ) );
		callback.releaseWakeLock();
		callback.onThreadEnd( allow_stop_service );
	}
}
