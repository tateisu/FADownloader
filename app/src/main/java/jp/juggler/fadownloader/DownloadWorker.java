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

import com.neovisionaries.ws.client.OpeningHandshakeException;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import com.neovisionaries.ws.client.WebSocketState;

import org.json.JSONArray;
import org.json.JSONObject;

import it.sephiroth.android.library.exif2.ExifInterface;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadWorker extends Thread implements CancelChecker{

	static final boolean RECORD_QUEUED_STATE = false;

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
	final String flashair_url;
	final String folder_uri;
	final int interval;
	final String file_type;
	final LogWriter log;
	final ArrayList<Pattern> file_type_list;
	final boolean force_wifi;
	final String ssid;

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

		LocationTracker.Setting location_setting = new LocationTracker.Setting();
		location_setting.interval_desired = intent.getLongExtra( DownloadService.EXTRA_LOCATION_INTERVAL_DESIRED, LocationTracker.DEFAULT_INTERVAL_DESIRED );
		location_setting.interval_min = intent.getLongExtra( DownloadService.EXTRA_LOCATION_INTERVAL_MIN, LocationTracker.DEFAULT_INTERVAL_MIN );
		location_setting.mode = intent.getIntExtra( DownloadService.EXTRA_LOCATION_MODE, LocationTracker.DEFAULT_MODE );

		Pref.pref( service ).edit()
			.putBoolean( Pref.WORKER_REPEAT, repeat )
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

		service.wifi_tracker.updateSetting( force_wifi, ssid );

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

		LocationTracker.Setting location_setting = new LocationTracker.Setting();
		location_setting.interval_desired = pref.getLong( Pref.WORKER_LOCATION_INTERVAL_DESIRED, LocationTracker.DEFAULT_INTERVAL_DESIRED );
		location_setting.interval_min = pref.getLong( Pref.WORKER_LOCATION_INTERVAL_MIN, LocationTracker.DEFAULT_INTERVAL_MIN );
		location_setting.mode = pref.getInt( Pref.WORKER_LOCATION_MODE, LocationTracker.DEFAULT_MODE );

		file_type_list = file_type_parse();

		service.wifi_tracker.updateSetting( force_wifi, ssid );

		service.location_tracker.updateSetting( location_setting );
	}

	final HTTPClient client = new HTTPClient( 30000, 4, "HTTP Client", this );
	final AtomicReference<String> cancel_reason = new AtomicReference<>( null );

	@Override public boolean isCancelled(){
		return cancel_reason.get() != null;
	}

	public void cancel( String reason ){
		try{
			if( cancel_reason.compareAndSet( null, reason ) ){
				log.i( R.string.thread_cancelled, reason );
			}
			synchronized( this ){
				notify();
			}
			client.cancel( log );
		}catch( Throwable ex ){
			ex.printStackTrace();
		}
	}

	void waitEx( long ms ){
		try{
			synchronized( this ){
				wait( ms );
			}
		}catch( Throwable ex ){
			ex.printStackTrace();
		}
	}

	public synchronized void notifyEx(){
		notify();
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

	/////////////////////////////////////////

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

	LinkedList<Item> job_queue = null;
	boolean file_error = false;

	boolean loadFolder( Object network ){
		String cgi_url = flashair_url + "v1/photos";
		byte[] data = client.getHTTP( log, network, cgi_url );
		if( isCancelled() ) return false;
		if( data == null ){
			if( client.last_error.contains( "UnknownHostException" ) ){
				client.last_error = service.getString( R.string.flashair_host_error );
			}else if( client.last_error.contains( "ENETUNREACH" ) ){
				client.last_error = service.getString( R.string.network_unreachable );
			}
			log.e( R.string.folder_list_failed, "/", cgi_url, client.last_error );
			return false;
		}
		// job_queue.add( new Item( "/", new LocalFile( service, folder_uri ), false, 0L ) );

		try{
			JSONObject info = new JSONObject( Utils.decodeUTF8( data ) );
			if( info.optInt( "errCode", 0 ) != 200 ){
				throw new RuntimeException( "server's errMsg:" + info.optString( "errMsg" ) );
			}
			JSONArray root_dir = info.optJSONArray( "dirs" );
			if( root_dir == null ){
				log.e( "missing root folder." );
				return false;
			}
			LocalFile local_root = new LocalFile( service, folder_uri );
			job_queue = new LinkedList<>();
			for( int i = 0, ie = root_dir.length() ; i < ie ; ++ i ){

				Object o = root_dir.opt( i );
				if( ! ( o instanceof JSONObject ) ) continue;
				JSONObject subdir = (JSONObject) o;

				String sub_dir_name = subdir.optString( "name", null );
				if( TextUtils.isEmpty( sub_dir_name ) ) continue;

				LocalFile sub_dir_local = new LocalFile( local_root, sub_dir_name );
				JSONArray files = subdir.optJSONArray( "files" );
				if( files == null ) continue;

				for( int j = 0, je = files.length() ; j < je ; ++ j ){
					String fname = files.optString( j );
					if( TextUtils.isEmpty( fname ) ) continue;
					// file type matching
					for( Pattern re : file_type_list ){
						if( ! re.matcher( fname ).find() ) continue;
						// マッチした

						String child_air_path = "/" + sub_dir_name + "/" + fname;
						LocalFile local_file = new LocalFile( sub_dir_local, fname );
						long size = 1L; // PentaxのAPIだとこの時点ではサイズ不明

						// ローカルにあるファイルのサイズが1以上ならスキップする
						final long local_size = local_file.length( log, false );
						if( local_size < size ){

							// ファイルはキューの末尾に追加
							job_queue.addLast( new Item( child_air_path, local_file, true, size ) );

							if( RECORD_QUEUED_STATE ){
								// 未取得のデータを履歴に表示する
								DownloadRecord.insert(
									service.getContentResolver()
									, cv
									, fname
									, child_air_path
									, "" // local file uri
									, DownloadRecord.STATE_QUEUED
									, "queued."
									, 0L
									, size
								);
							}
						}

						break;
					}
				}
			}
			log.i( "%s files queued.", job_queue.size() );
			return true;
		}catch( Throwable ex ){
			log.e( ex, R.string.camera_file_list_parse_error );
		}
		job_queue = null;
		return false;
	}

	void loadFile( Object network, Item item ){
		long time_start = SystemClock.elapsedRealtime();
		final String remote_path = item.air_path;
		final LocalFile local_file = item.local_file;
		final String file_name = local_file.getName();

		try{

			if( ! local_file.prepareFile( log, true ) ){
				log.e( "%s//%s :skip. can not prepare local file.", item.air_path, file_name );
				DownloadRecord.insert(
					service.getContentResolver()
					, cv
					, file_name
					, remote_path
					, "" // local file uri
					, DownloadRecord.STATE_LOCAL_FILE_PREPARE_ERROR
					, "can not prepare local file."
					, SystemClock.elapsedRealtime() - time_start
					, item.size
				);
				return;
			}

			final String get_url = flashair_url + "v1/photos" + Uri.encode( remote_path, "/_" ) + "?size=full";
			byte[] data = client.getHTTP( log, network, get_url, new HTTPClientReceiver(){

				final byte[] buf = new byte[ 2048 ];

				public byte[] onHTTPClientStream( LogWriter log, CancelChecker cancel_checker, InputStream in, int content_length ){
					try{

						OutputStream os = local_file.openOutputStream( service );
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
					, remote_path
					, local_file.getFileUri( log, false )
					, DownloadRecord.STATE_CANCELLED
					, "download cancelled."
					, SystemClock.elapsedRealtime() - time_start
					, item.size
				);
				return;
			}

			if( data == null ){
				local_file.delete();
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
					, remote_path
					, local_file.getFileUri( log, false )
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
				ErrorAndMessage em = updateFileLocation( location, local_file );
				DownloadRecord.insert(
					service.getContentResolver()
					, cv
					, file_name
					, remote_path
					, local_file.getFileUri( log, false )
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
					, remote_path
					, local_file.getFileUri( log, false )
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
				, remote_path
				, local_file.getFileUri( log, false )
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

			// WakeLockやWiFiLockを確保
			callback.acquireWakeLock();

			// 通信状態の確認
			setStatus( false, service.getString( R.string.wifi_check ) );
			long network_check_start = SystemClock.elapsedRealtime();
			Object network = null;
			while( ! isCancelled() ){
				network = getWiFiNetwork();
				if( network != null ) break;

				// 一定時間待機してもダメならスレッドを停止する
				// 通信状態変化でまた起こされる
				long er_now = SystemClock.elapsedRealtime();
				if( er_now - network_check_start >= 60 * 1000L ){
					// Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();
					job_queue = null;
					cancel( service.getString( R.string.wifi_not_good ) );
					break;
				}

				// 少し待って再確認
				waitEx( 10000L );
			}
			if( isCancelled() ) break;

			// WebSocketが閉じられていたら後処理をする
			if( ws_client != null && ws_client.getState() == WebSocketState.CLOSED ){
				setStatus( false, "WebSocket closing" );
				ws_client.removeListener( ws_listener );
				ws_client.disconnect();
				ws_client = null;
			}
			if( isCancelled() ) break;

			// WebSocketがなければ開く
			if( ws_client == null ){
				setStatus( false, "WebSocket creating" );
				try{
					WebSocketFactory factory = new WebSocketFactory();
					ws_client = factory.createSocket( flashair_url + "v1/changes", 5000 );
					ws_client.addListener( ws_listener );
					ws_client.connect();
					waitEx( 2000L );
				}catch( OpeningHandshakeException ex ){
					ex.printStackTrace();
					log.e( ex, "WebSocket connection failed(1)." );
					waitEx( 5000L );
					continue;
				}catch( WebSocketException ex ){
					ex.printStackTrace();
					log.e( ex, "WebSocket connection failed(2)." );
					waitEx( 5000L );
					continue;
				}catch( IOException ex ){
					ex.printStackTrace();
					log.e( ex, "WebSocket connection failed(3)." );
					waitEx( 5000L );
					continue;
				}
			}
			if( isCancelled() ) break;

			long now = System.currentTimeMillis();
			long remain;
			if( mIsCameraBusy.get() ){
				// ビジー状態なら待機を続ける
				setStatus( false, "camera is busy." );
				remain = 2000L;
			}else if( now - mLastBusyTime.get() < 2000L ){
				// ビジーが終わっても数秒は待機を続ける
				setStatus( false, "camera was busy." );
				remain = mLastBusyTime.get() + 2000L - now;
			}else if( now - mCameraUpdateTime.get() < 2000L ){
				// カメラが更新された後数秒は待機する
				setStatus( false, "waiting camera storage." );
				remain = mCameraUpdateTime.get() + 2000L - now;
			}else if( job_queue != null ){
				// キューにある項目を処理する
				remain = 0L;
			}else{
				// キューがカラなら、最後にファイル一覧を取得した時刻から一定は待つ
				remain = mLastFileListed.get() + interval * 1000L - now;
				setStatus( false, service.getString( R.string.wait_short, Utils.formatTimeDuration( remain ) ) );
			}
			if( remain > 0 ){
				waitEx( remain < 1000L ? remain : 1000L );
				if( isCancelled() ) break;
				continue;
			}

			// ファイルスキャンの開始
			if( job_queue == null ){
				Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();

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
				setStatus( false, service.getString( R.string.camera_file_listing ) );
				job_queue = new LinkedList<>();
				if( ! loadFolder( network ) ){
					job_queue = null;
					continue;
				}
				mLastFileListed.set( System.currentTimeMillis() );
				file_error = false;
				queued_byte_count_max.set( Long.MAX_VALUE );
			}

			// ファイルスキャンの終了
			if( job_queue.isEmpty() ){
				job_queue = null;
				setStatus( false, service.getString( R.string.file_scan_completed ) );
				if( ! file_error ){
					log.i( "ファイルスキャン完了" );
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

				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( ex, "error." );
			}
		}

		// WebSocketの解除
		if( ws_client != null ){
			ws_client.removeListener( ws_listener );
			ws_client.disconnect();
			ws_client = null;
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

	AtomicLong mCameraUpdateTime = new AtomicLong( 0L );
	AtomicLong mLastBusyTime = new AtomicLong( 0L );
	AtomicBoolean mIsCameraBusy = new AtomicBoolean( false );
	AtomicLong mLastFileListed = new AtomicLong( 0L );

	WebSocket ws_client;
	final WebSocketAdapter ws_listener = new WebSocketAdapter(){

		@Override public void onUnexpectedError( WebSocket websocket, WebSocketException ex ) throws Exception{
			super.onUnexpectedError( websocket, ex );
			log.e( ex, "WebSocket onUnexpectedError" );
		}

		@Override public void onError( WebSocket websocket, WebSocketException ex ) throws Exception{
			super.onError( websocket, ex );
			log.e( ex, "WebSocket onError" );
		}

		@Override public void onConnectError( WebSocket websocket, WebSocketException ex ) throws Exception{
			super.onConnectError( websocket, ex );
			log.e( ex, "WebSocket onConnectError();" );
		}

		@Override public void onTextMessageError( WebSocket websocket, WebSocketException ex, byte[] data ) throws Exception{
			super.onTextMessageError( websocket, ex, data );
			log.e( ex, "WebSocket onTextMessageError" );
		}

		@Override public void onDisconnected( WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer ) throws Exception{
			super.onDisconnected( websocket, serverCloseFrame, clientCloseFrame, closedByServer );
			log.d( "WebSocket onDisconnects" );
		}

		@Override public void onConnected( WebSocket websocket, Map<String, List<String>> headers ) throws Exception{
			super.onConnected( websocket, headers );
			log.d( "WebSocket onConnect();" );
		}

		boolean bBusy = false;

		@SuppressWarnings( "StatementWithEmptyBody" )
		@Override public void onTextMessage( WebSocket websocket, String text ) throws Exception{
			super.onTextMessage( websocket, text );
			try{
				JSONObject info = new JSONObject( text );
				if( 200 == info.optInt( "errCode", 0 ) ){
					String changed = info.optString( "changed" );
					if( "camera".equals( changed ) ){
						// 何もしていない状態でも定期的に発生する
					}else if( "cameraDirect".equals( changed ) ){
						bBusy = info.optBoolean( "capturing", false )
							|| ! "idle".equals( info.optString( "stateStill" ) )
							|| ! "idle".equals( info.optString( "stateMovie" ) )
						;
						if( bBusy && mIsCameraBusy.compareAndSet( false, true ) ){
							// busy になった
							mLastBusyTime.set( System.currentTimeMillis() );
						}else if( ! bBusy && mIsCameraBusy.compareAndSet( true, false ) ){
							// busyではなくなった
							mLastBusyTime.set( System.currentTimeMillis() );
						}
					}else if( "storage".equals( changed ) ){
						mLastFileListed.set( 0L );
						mCameraUpdateTime.set( System.currentTimeMillis() );
						notifyEx();
					}else{
						log.d( "WebSocket onTextMessage %s", text );
					}
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( ex, "WebSocket message handling error." );
			}
		}
	};
}
