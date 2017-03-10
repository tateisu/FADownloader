package jp.juggler.fadownloader;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
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
		boolean force_wifi = intent.getBooleanExtra( DownloadService.EXTRA_FORCE_WIFI, false );
		String ssid = intent.getStringExtra( DownloadService.EXTRA_SSID );

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

		boolean force_wifi = pref.getBoolean( Pref.WORKER_FORCE_WIFI, false );
		String ssid = pref.getString( Pref.WORKER_SSID, null );

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

	final AtomicReference<String> status = new AtomicReference<>( "?" );

	public String getStatus(){
		return status.get();
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

	static class Item{

		final String remote_path;
		final LocalFile local_file;

		Item( String air_path, LocalFile local_file ){
			this.remote_path = air_path;
			this.local_file = local_file;
		}
	}

	private void queueDirectory( LinkedList<Item> queue, JSONArray dir, LocalFile local_root ){
		if( dir == null || dir.length() == 0 ) return;
		for( int i = 0, ie = dir.length() ; i < ie ; ++ i ){
			Object o = dir.opt( i );
			if( o instanceof JSONObject ){
				JSONObject subdir = (JSONObject) o;
				String sub_dir_name = subdir.optString( "name", null );
				if( ! TextUtils.isEmpty( sub_dir_name ) ){
					LocalFile sub_dir_local = new LocalFile( local_root, sub_dir_name );
					//
					JSONArray files = subdir.optJSONArray( "files" );
					if( files != null ){
						for( int j = 0, je = files.length() ; j < je ; ++ j ){
							String fname = files.optString( j );
							if( ! TextUtils.isEmpty( sub_dir_name ) ){
								// file type matching
								boolean bMatch = false;
								for( Pattern re : file_type_list ){
									if( ! re.matcher( fname ).find() ) continue;
									bMatch = true;
									break;
								}
								if( bMatch ){
									LocalFile local_file = new LocalFile( sub_dir_local, fname );
									queue.add( new Item( sub_dir_name + "/" + fname, local_file ) );
								}
							}
						}
					}
				}
			}
		}
	}

	private void updateFileLocation( final Location location, LocalFile file ){

		try{
			LocalFile tmp_path = new LocalFile( file.getParent(), "tmp-" + currentThread().getId() + "-" + android.os.Process.myPid() + "-" + file.getName() );

			if( ! tmp_path.prepareFile( log ) ){
				throw new RuntimeException( "create file failed." );
			}else{
				boolean bModifyFailed = false;
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
				}catch( ExifInterface.ModifyExifTagFailedException ignored ){
					// 既にEXIF情報があった
					bModifyFailed = true;
				}catch( Throwable ex ){
					// 変更失敗
					bModifyFailed = true;
					ex.printStackTrace();
					log.e( ex, "exif mangling failed." );
				}finally{
					try{
						if( os != null ) os.close();
					}catch( Throwable ignored ){
					}
				}

				if( bModifyFailed ){
					tmp_path.delete();

				}else{
					try{
						// 更新後の方がファイルが小さいことがあるのか？
						if( tmp_path.length() < file.length() ){
							log.e( "EXIF付与したファイルの方が小さい!付与前後のファイルを残しておく" );
							// この場合両方のファイルを残しておく
						}else{
							if( ! file.delete() ){
								log.e( "EXIF追加後のファイル操作に失敗" );
							}else if( ! tmp_path.renameTo( file.getName() ) ){
								log.e( "EXIF追加後のファイル操作に失敗" );
							}else{
								log.i( "%s に位置情報を付与しました", file.getName() );
							}
						}

					}catch( Throwable ex ){
						log.e( "EXIF追加後のファイル操作に失敗. %s %s", ex.getClass().getSimpleName(), ex.getMessage() );
					}
				}
			}

		}catch( Throwable ex ){
			ex.printStackTrace();
			log.e( "exif mangling failed. %s %s", ex.getClass().getSimpleName(), ex.getMessage() );
		}

	}

	@SuppressWarnings( "deprecation" )
	public static Object getWiFiNetwork( Context context ){
		ConnectivityManager cm = (ConnectivityManager) context.getSystemService( Context.CONNECTIVITY_SERVICE );
		if( Build.VERSION.SDK_INT >= 21 ){
			for( Network n : cm.getAllNetworks() ){
				NetworkInfo info = cm.getNetworkInfo( n );
				if( info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI ) return n;
			}
		}else{
			for( NetworkInfo info : cm.getAllNetworkInfo() ){
				if( info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI ) return info;
			}
		}
		return null;
	}

	AtomicLong mCameraUpdateTime = new AtomicLong( 0L );
	AtomicLong mLastBusyTime = new AtomicLong( 0L );
	AtomicBoolean mIsCameraBusy = new AtomicBoolean( false );
	AtomicLong mLastFileListing = new AtomicLong( 0L );

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

		@Override public void onTextMessage( WebSocket websocket, String text ) throws Exception{
			super.onTextMessage( websocket, text );
			try{
				JSONObject info = new JSONObject( text );
				if( 200 == info.optInt( "errCode", 0 ) ){
					String changed = info.optString( "changed" );
					if( "camera".equals( changed ) ){
						// 何もしていない状態でも定期的に発生する
						return;
					}
					log.d( "WebSocket onTextMessage %s", text );

					if( "cameraDirect".equals( changed ) ){
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
						mLastFileListing.set( 0L );
						mCameraUpdateTime.set( System.currentTimeMillis() );
						notifyEx();
					}
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( ex, "WebSocket message handling error." );
			}
		}
	};

	@SuppressWarnings( "ConstantConditions" ) @Override public void run(){

		status.set( service.getString( R.string.thread_start ) );

		callback.onThreadStart();

		boolean allow_stop_service = false;

		// ファイル一覧の取得
		LinkedList<Item> job_queue = null;

		boolean file_error = false;

		while( ! isCancelled() ){
			status.set( service.getString( R.string.initializing ) );

			// WakeLockやWiFiLockを確保
			callback.acquireWakeLock();

			// 通信の安定を確認
			status.set( service.getString( R.string.wifi_check ) );

			Object network = null;
			while( ! isCancelled() ){
				network = getWiFiNetwork( service );
				if( network != null ) break;
				//
				waitEx( 1000L );
			}
			if( isCancelled() ) break;

			// WebSocketが閉じられていたら後処理をする
			if( ws_client != null && ws_client.getState() == WebSocketState.CLOSED ){
				status.set( "WebSocket closing" );
				ws_client.removeListener( ws_listener );
				ws_client.disconnect();
				ws_client = null;
			}
			if( isCancelled() ) break;

			// WebSocketがなければ開く
			if( ws_client == null ){
				status.set( "WebSocket creating" );
				try{
					WebSocketFactory factory = new WebSocketFactory();
					ws_client = factory.createSocket( flashair_url + "v1/changes", 5000 );
					ws_client.addListener( ws_listener );
					ws_client.connect();
					waitEx( 2000L );
				}catch( OpeningHandshakeException ex ){
					ex.printStackTrace();
					log.e( ex, "WebSocket connection failed(1)." );
				}catch( WebSocketException ex ){
					ex.printStackTrace();
					log.e( ex, "WebSocket connection failed(2)." );
				}catch( IOException ex ){
					ex.printStackTrace();
					log.e( ex, "WebSocket connection failed(3)." );
				}
			}
			if( isCancelled() ) break;

			long now = System.currentTimeMillis();
			long remain;
			if( mIsCameraBusy.get() ){
				// ビジー状態なら待機を続ける
				status.set( "camera is busy." );
				remain = 2000L;
			}else if( now - mLastBusyTime.get() < 2000L ){
				// ビジーが終わっても数秒は待機を続ける
				status.set( "camera was busy." );
				remain = mLastBusyTime.get() + 2000L - now;
			}else if( now - mCameraUpdateTime.get() < 2000L ){
				// カメラが更新された後数秒は待機する
				status.set( "waiting camera storage." );
				remain = mCameraUpdateTime.get() + 2000L - now;
			}else if( job_queue != null ){
				// キューにある項目を処理する
				remain = 0L;
			}else{
				// キューがカラなら、最後にファイル一覧を取得した時刻から一定は待つ
				remain = mLastFileListing.get() + interval * 1000L - now;
				status.set( service.getString( R.string.wait_short, Utils.formatTimeDuration( remain ) ) );
			}
			if( remain > 0 ){
				waitEx( remain < 1000L ? remain : 1000L );
				if( isCancelled() ) break;
				continue;
			}

			// ファイル一覧を取得
			if( job_queue == null ){
				mLastFileListing.set( System.currentTimeMillis() );
				file_error = false;
				status.set( service.getString( R.string.camera_file_listing ) );
				String cgi_url = flashair_url + "v1/photos";
				byte[] data = client.getHTTP( log, network, cgi_url );
				if( isCancelled() ) break;
				try{
					if( data == null ){
						if( client.last_error.contains( "UnknownHostException" ) ){
							client.last_error = service.getString( R.string.flashair_host_error );
						}else if( client.last_error.contains( "ENETUNREACH" ) ){
							client.last_error = service.getString( R.string.network_unreachable );
						}
						log.e( R.string.flashair_update_check_failed, cgi_url, client.last_error );
					}else{
						JSONObject info = new JSONObject( Utils.decodeUTF8( data ) );
						if( info.optInt( "errCode", 0 ) != 200 ){
							throw new RuntimeException( "server's errMsg:" + info.optString( "errMsg" ) );
						}
						JSONArray root_dir = info.optJSONArray( "dirs" );
						job_queue = new LinkedList<>();
						queueDirectory( job_queue, root_dir, new LocalFile( service, folder_uri ) );
						log.i( "%s files queued.", job_queue.size() );
					}
					continue;
				}catch( Throwable ex ){
					log.e( ex, R.string.camera_file_list_parse_error );
					job_queue = null;
					continue;
				}
			}

			// キューを全て処理した
			if( job_queue.isEmpty() ){
				job_queue = null;
				log.i( "ファイルスキャン完了" );
				status.set( service.getString( R.string.file_scan_completed ) );

				if( ! file_error ){
					if( ! repeat ){
						Pref.pref( service ).edit().putInt( Pref.LAST_MODE, Pref.LAST_MODE_STOP ).apply();
						cancel( service.getString( R.string.repeat_off ) );
						allow_stop_service = true;
						break;
					}
				}
				continue;
			}

			try{
				final Item item = job_queue.removeFirst();
				long time_start = SystemClock.elapsedRealtime();

				final String remote_path = item.remote_path;
				final LocalFile local_file = item.local_file;
				final String file_name = local_file.getName();

				status.set( service.getString( R.string.progress_file, remote_path ) );
				if( ! local_file.prepareFile( log ) ){
					log.e( "%s :skip. can not prepare local file.", item.remote_path );
					continue;
				}
				final long local_size = local_file.length();
				if( local_size > 0L ) continue;

				final String get_url = flashair_url + "v1/photos/" + Uri.encode( remote_path, "/_" ) + "?size=full";
				final byte[] no_update = new byte[ 1 ];
				byte[] data = client.getHTTP( log, network, get_url, new HTTPClientReceiver(){

					final byte[] buf = new byte[ 2048 ];

					public byte[] onHTTPClientStream( LogWriter log, CancelChecker cancel_checker, InputStream in, int content_length ){
						try{
							if( local_size > content_length ){
								// 既にダウンロード済みらしいし何もしない
								return no_update;
							}
							status.set( service.getString( R.string.download_file, remote_path ) );

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

				if( isCancelled() ) break;

				if( data == no_update ){
					// download skipped.
					continue;
				}

				if( data == null ){
					local_file.delete();
					log.e( "FILE %s :HTTP error %s", remote_path, client.last_error );

					if( client.last_error.contains( "UnknownHostException" ) ){
						client.last_error = service.getString( R.string.flashair_host_error );
						cancel( service.getString( R.string.flashair_host_error_short ) );
					}else if( client.last_error.contains( "ENETUNREACH" ) ){
						client.last_error = service.getString( R.string.network_unreachable );
						cancel( service.getString( R.string.network_unreachable ) );
					}

					file_error = true;
				}else{
					log.i( "FILE %s :download complete. %dms", file_name, SystemClock.elapsedRealtime() - time_start );

					// 位置情報を取得する時にファイルの日時が使えないかと思ったけど
					// タイムゾーンがわからん…

					Location location = callback.getLocation();
					if( location != null && reJPEG.matcher( file_name ).find() ){
						status.set( service.getString( R.string.exif_update, remote_path ) );
						updateFileLocation( location, local_file );
					}
				}

			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( ex, "error." );
				file_error = true;
			}
		}

		// WebSocketの解除
		if( ws_client != null ){
			ws_client.removeListener( ws_listener );
			ws_client.disconnect();
			ws_client = null;
		}
		callback.releaseWakeLock();
		status.set( service.getString( R.string.thread_end ) );
		callback.onThreadEnd( allow_stop_service );
	}
}


