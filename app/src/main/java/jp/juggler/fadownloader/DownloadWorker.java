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
import android.os.Build;
import android.os.SystemClock;
import android.support.annotation.NonNull;

import it.sephiroth.android.library.exif2.ExifInterface;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
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

		final String air_path;
		final LocalFile local_file;

		Item( String air_path, LocalFile local_file ){
			this.air_path = air_path;
			this.local_file = local_file;
		}
	}

	static final Pattern reLine = Pattern.compile( "([^\\x0d\\x0a]+)" );
	static final Pattern reAttr = Pattern.compile( ",(\\d+),(\\d+),(\\d+),(\\d+)$" );

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
			if( ! tmp_path.prepareFile( log ) ){
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
				em = new ErrorAndMessage( true, log.formatError( ex, "exif mangling failed." ) );
			}

			if( em != null ){
				tmp_path.delete();
				return em;
			}

			// 更新後の方がファイルが小さいことがあるのか？
			if( tmp_path.length() < file.length() ){
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
			return new ErrorAndMessage( true, log.formatError( ex, "exif mangling failed." ) );
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

	final ContentValues cv = new ContentValues();

	@SuppressWarnings( "ConstantConditions" ) @Override public void run(){

		status.set( service.getString( R.string.thread_start ) );

		boolean allow_stop_service = false;

		callback.onThreadStart();

		LinkedList<Item> job_queue = null;
		long flashair_update_status = 0L;
		boolean file_error = false;

		while( ! isCancelled() ){
			status.set( service.getString( R.string.initializing ) );

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
						status.set( service.getString( R.string.wait_short, Utils.formatTimeDuration( remain ) ) );
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

			// 通信状態の確認
			status.set( service.getString( R.string.wifi_check ) );
			long network_check_start = SystemClock.elapsedRealtime();
			Object network = null;
			while( ! isCancelled() ){
				network = getWiFiNetwork( service );
				if( network != null ) break;

				// 一定時間待機してもダメならスレッドを停止する
				long er_now = SystemClock.elapsedRealtime();
				if( er_now - network_check_start >= 60 * 1000L ){
					Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();
					job_queue = null;
					cancel( service.getString( R.string.wifi_not_good ) );
					break;
				}

				// 少し待って再確認
				waitEx( 10000L );
			}
			if( isCancelled() ) break;

			// ファイルスキャンの開始
			if( job_queue == null ){
				Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();

				// FlashAir アップデートステータスを確認
				status.set( service.getString( R.string.flashair_update_status_check ) );
				flashair_update_status = getFlashAirUpdateStatus( network );
				if( flashair_update_status == ERROR_CONTINUE ){
					continue;
				}else if( flashair_update_status != - 1L
					&& flashair_update_status == Pref.pref( service ).getLong( Pref.FLASHAIR_UPDATE_STATUS_OLD, - 1L )
					){
					// 前回スキャン開始時と同じ数字なので変更されていない
					log.d( R.string.flashair_not_updated );
					continue;
				}

				// フォルダスキャン開始
				job_queue = new LinkedList<>();
				job_queue.add( new Item( "/", new LocalFile( service, folder_uri ) ) );
				file_error = false;
			}

			// ファイルスキャンの終了
			if( job_queue.isEmpty() ){
				job_queue = null;
				status.set( service.getString( R.string.file_scan_completed ) );
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

			final Item item = job_queue.removeFirst();

			status.set( service.getString( R.string.progress_folder, item.air_path ) );

			// フォルダを読む
			String cgi_url = flashair_url + "command.cgi?op=100&DIR=" + Uri.encode( item.air_path );
			byte[] data = client.getHTTP( log, network, cgi_url );
			if( isCancelled() ) break;

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
				continue;
			}

			String text = Utils.decodeUTF8( data );
			Matcher mLine = reLine.matcher( text );

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
						// サブフォルダはキューに追加する
						job_queue.add( new Item( child_air_path, local_child ) );
						continue;
					}

					long time_start = SystemClock.elapsedRealtime();

					try{
						status.set( service.getString( R.string.progress_file, child_air_path ) );

						// file type matching
						boolean bMatch = false;
						for( Pattern re : file_type_list ){
							if( ! re.matcher( file_name ).find() ) continue;
							bMatch = true;
							break;
						}
						if( ! bMatch ){
//							DownloadRecord.insert(
//								service.getContentResolver()
//								, cv
//								, file_name
//								, child_air_path
//								, "" // local file uri
//								, DownloadRecord.STATE_FILE_TYPE_NOT_MATCH
//								, "file type not match. skipped."
//								, SystemClock.elapsedRealtime() - time_start
//							);
							continue;
						}

						if( ! local_child.prepareFile( log ) ){
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
							);
							continue;
						}else if( local_child.length() >= size ){
							// log.f( "%s//%s :skip. same file size.",item.air_path, file_name );
							continue;
						}

						status.set( service.getString( R.string.download_file, child_air_path ) );
						final String get_url = flashair_url + Uri.encode( child_air_path );
						data = client.getHTTP( log, network, get_url, new HTTPClientReceiver(){
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
								, local_child.getFileUri( log )
								, DownloadRecord.STATE_CANCELLED
								, "download cancelled."
								, SystemClock.elapsedRealtime() - time_start
							);
							break;
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
								, local_child.getFileUri( log )
								, DownloadRecord.STATE_DOWNLOAD_ERROR
								, client.last_error
								, SystemClock.elapsedRealtime() - time_start
							);
							file_error = true;
						}else{
							log.i( "FILE %s :download complete. %dms", file_name, SystemClock.elapsedRealtime() - time_start );

							// 位置情報を取得する時にファイルの日時が使えないかと思ったけど
							// タイムゾーンがわからん…

							Location location = callback.getLocation();
							if( location != null && reJPEG.matcher( file_name ).find() ){
								status.set( service.getString( R.string.exif_update, child_air_path ) );
								ErrorAndMessage em = updateFileLocation( location, local_child );
								DownloadRecord.insert(
									service.getContentResolver()
									, cv
									, file_name
									, child_air_path
									, local_child.getFileUri( log )
									, em.bError ? DownloadRecord.STATE_EXIF_MANGLING_ERROR : DownloadRecord.STATE_COMPLETED
									, "location data: " + em.message
									, SystemClock.elapsedRealtime() - time_start
								);
							}else{
								DownloadRecord.insert(
									service.getContentResolver()
									, cv
									, file_name
									, child_air_path
									, local_child.getFileUri( log )
									, DownloadRecord.STATE_COMPLETED
									, "OK"
									, SystemClock.elapsedRealtime() - time_start
								);
							}
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
							, local_child.getFileUri( log )
							, DownloadRecord.STATE_DOWNLOAD_ERROR
							, log.formatError( ex, "?" )
							, SystemClock.elapsedRealtime() - time_start
						);
					}
				}catch( Throwable ex ){
					ex.printStackTrace();
					log.e( ex, "parse error: %s", line );
				}
			}
		}
		callback.releaseWakeLock();
		status.set( service.getString( R.string.thread_end ) );
		callback.onThreadEnd( allow_stop_service );
	}

}
