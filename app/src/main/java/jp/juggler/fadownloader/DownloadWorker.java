package jp.juggler.fadownloader;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.support.v4.provider.DocumentFile;

import it.sephiroth.android.library.exif2.ExifInterface;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadWorker extends Thread implements CancelChecker{

	public interface Callback{

		void releaseWakeLock();

		void acquireWakeLock();

		void onThreadStart(LocationTracker.Setting location_setting);

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
	final LocationTracker.Setting location_setting;

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

		location_setting = new LocationTracker.Setting();
		location_setting.interval_desired = intent.getLongExtra( DownloadService.EXTRA_LOCATION_INTERVAL_DESIRED ,LocationTracker.DEFAULT_INTERVAL_DESIRED);
		location_setting.interval_min = intent.getLongExtra( DownloadService.EXTRA_LOCATION_INTERVAL_MIN ,LocationTracker.DEFAULT_INTERVAL_MIN);
		location_setting.mode = intent.getIntExtra( DownloadService.EXTRA_LOCATION_MODE ,LocationTracker.DEFAULT_MODE);

		Pref.pref( service ).edit()
			.putBoolean( Pref.WORKER_REPEAT, repeat )
			.putString( Pref.WORKER_FLASHAIR_URL, flashair_url )
			.putString( Pref.WORKER_FOLDER_URI, folder_uri )
			.putInt( Pref.WORKER_INTERVAL, interval )
			.putString( Pref.WORKER_FILE_TYPE, file_type )
			.putLong( Pref.WORKER_LOCATION_INTERVAL_DESIRED, location_setting.interval_desired )
			.putLong( Pref.WORKER_LOCATION_INTERVAL_MIN, location_setting.interval_min  )
			.putInt( Pref.WORKER_LOCATION_MODE, location_setting.mode )
			.apply();

		file_type_list = file_type_parse();

		service.location_tracker.updateSetting(location_setting);
	}

	public DownloadWorker( DownloadService service, Callback callback ){
		this.service = service;
		this.callback = callback;
		this.log = new LogWriter( service );

		log.i( R.string.thread_ctor_restart );
		SharedPreferences pref = Pref.pref( service );
		this.repeat = pref.getBoolean( Pref.WORKER_REPEAT, false );
		this.flashair_url = pref.getString( Pref.WORKER_FLASHAIR_URL, null );
		this.folder_uri = pref.getString( Pref.WORKER_FOLDER_URI, null );
		this.interval = pref.getInt( Pref.WORKER_INTERVAL, 86400 );
		this.file_type = pref.getString( Pref.WORKER_FILE_TYPE, null );

		location_setting = new LocationTracker.Setting();
		location_setting.interval_desired = pref.getLong(Pref.WORKER_LOCATION_INTERVAL_DESIRED ,LocationTracker.DEFAULT_INTERVAL_DESIRED);
		location_setting.interval_min = pref.getLong(Pref.WORKER_LOCATION_INTERVAL_MIN,LocationTracker.DEFAULT_INTERVAL_MIN);
		location_setting.mode = pref.getInt(Pref.WORKER_LOCATION_MODE ,LocationTracker.DEFAULT_MODE);

		file_type_list = file_type_parse();

		service.location_tracker.updateSetting(location_setting);

	}

	final AtomicReference<String> status = new AtomicReference<>( "?" );

	public String getStatus(){
		return status.get();
	}

	Pattern reJPEG = Pattern.compile( "\\.jp(g|eg?)\\z", Pattern.CASE_INSENSITIVE );

	Pattern reFileType = Pattern.compile( "(\\S+)" );

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

	final HTTPClient client = new HTTPClient( 30000, 4, "DownloadWorker", this );
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
				client.cancel( log );
			}
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

	static class FilePathX{

		DocumentFile document_file;
		String name;
		FilePathX parent;
		ArrayList<DocumentFile> file_list;

		public ArrayList<DocumentFile> getFileList(){
			if( document_file != null ){
				if( file_list != null ) return file_list;
				ArrayList<DocumentFile> result = new ArrayList<>();
				Collections.addAll( result, document_file.listFiles() );
				Collections.sort( result, new Comparator<DocumentFile>(){
					@Override public int compare( DocumentFile a, DocumentFile b ){
						return a.getName().compareTo( b.getName() );
					}
				} );
				return file_list = result;
			}else if( parent != null ){
				ArrayList<DocumentFile> parent_childs = parent.getFileList();
				if( parent_childs != null ){
					DocumentFile file = bsearch( parent_childs, name );
					if( file != null ){
						this.document_file = file;
						return getFileList();
					}
				}
			}
			return null;
		}

		private DocumentFile prepareDirectory( LogWriter log ){
			try{
				if( document_file != null ) return document_file;
				if( parent != null ){
					DocumentFile parent_dir = parent.prepareDirectory( log );
					if( parent_dir == null ) return null;

					ArrayList<DocumentFile> parent_list = parent.getFileList();
					DocumentFile file = bsearch( parent_list, name );
					if( file == null ){
						log.i( R.string.folder_create, name );
						file = parent_dir.createDirectory( name );
					}
					return document_file = file;
				}
			}catch( Throwable ex ){
				log.e( R.string.folder_create_failed, ex.getClass().getSimpleName(), ex.getMessage() );
			}
			return null;
		}

		public DocumentFile prepareFile( LogWriter log ){
			try{
				if( document_file != null ) return document_file;
				if( parent != null ){
					DocumentFile parent_dir = parent.prepareDirectory( log );
					if( parent_dir == null ) return null;

					DocumentFile file = bsearch( parent.getFileList(), name );
					if( file == null ){
						file = parent_dir.createFile( "application/octet-stream", name );
					}
					return document_file = file;
				}
			}catch( Throwable ex ){
				log.e( R.string.file_create_failed, ex.getClass().getSimpleName(), ex.getMessage() );
			}
			return null;
		}
	}

	static class Item{

		String air_path;
		FilePathX local_path;
		boolean is_file;
		long size;

		Item( String air_path, FilePathX local_path, boolean is_file, long size ){
			this.air_path = air_path;
			this.local_path = local_path;
			this.is_file = is_file;
			this.size = size;
		}
	}

	Pattern reLine = Pattern.compile( "([^\\x0d\\x0a]+)" );
	Pattern reAttr = Pattern.compile( ",(\\d+),(\\d+),(\\d+),(\\d+)$" );

	private static DocumentFile bsearch( ArrayList<DocumentFile> local_files, String fname ){
		int start = 0;
		int end = local_files.size();
		while( ( end - start ) > 0 ){
			int mid = ( ( start + end ) >> 1 );
			DocumentFile x = local_files.get( mid );
			int i = fname.compareTo( x.getName() );
			if( i < 0 ){
				end = mid;
			}else if( i > 0 ){
				start = mid + 1;
			}else{
				return x;
			}
		}
		return null;
	}

	@Override public void run(){

		status.set( service.getString( R.string.thread_start ) );

		boolean allow_stop_service = false;


		callback.onThreadStart(location_setting);

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

			// 指定時刻まで待機する
			while( ! isCancelled() ){
				long now = System.currentTimeMillis();
				long last_start = Pref.pref( service ).getLong( Pref.LAST_START, 0L );
				long remain = last_start + interval * 1000L - now;
				if( remain <= 0 ) break;

				if( remain < ( 15 * 1000L ) ){
					status.set( service.getString( R.string.wait_short, Utils.formatTimeDuration( remain ) ) );
					waitEx( remain > 1000L ? 1000L : remain );
				}else{

					try{
						PendingIntent pi = Utils.createAlarmPendingIntent( service );

						AlarmManager am = (AlarmManager) service.getSystemService( Context.ALARM_SERVICE ); // AlramManager取得
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
					callback.releaseWakeLock();
					cancel( service.getString( R.string.wait_alarm, Utils.formatTimeDuration( remain ) ) );
					break;
				}
			}
			if( isCancelled() ) break;
			// 待機が終わった

			// WakeLockやWiFiLockを確保
			callback.acquireWakeLock();

			status.set( service.getString( R.string.wifi_check ) );

			// 通信の安定を確認
			Network network = null;
			long network_check_start = SystemClock.elapsedRealtime();
			while( ! isCancelled() ){
				network = Utils.getWiFiNetwork( service );
				if( network != null ) break;
				//
				long er_now = SystemClock.elapsedRealtime();
				if( network_check_start == 0L ) network_check_start = er_now;
				if( er_now - network_check_start >= 60 * 1000L ) break;
				//
				waitEx( 333L );
			}
			if( isCancelled() ) break;

			if( network == null ){
				cancel( service.getString( R.string.wifi_not_connected ) );
				break;
			}

			// 成功しても失敗しても、次回待機の計算はここから
			Pref.pref( service ).edit().putLong( Pref.LAST_START, System.currentTimeMillis() ).apply();

			// アップデートステータスの取得
			long flashair_update_status;
			{
				status.set( service.getString( R.string.flashair_update_status_check ) );
				String cgi_url = flashair_url + "command.cgi?op=121";
				byte[] data = client.getHTTP( log, network, cgi_url );
				if( data == null ){
					if( client.last_error.contains( "UnknownHostException" ) ){
						client.last_error = service.getString( R.string.flashair_host_error );
						cancel( service.getString( R.string.flashair_host_error_short ) );
						callback.releaseWakeLock();
					}
					log.e( R.string.flashair_update_check_failed, cgi_url, client.last_error );
					continue;
				}
				try{
					flashair_update_status = Long.parseLong( Utils.decodeUTF8( data ).trim() );
				}catch( Throwable ex ){
					log.e( R.string.flashair_update_status_error );
					cancel( service.getString( R.string.flashair_update_status_error ) );
					callback.releaseWakeLock();
					continue;
				}
				long flashair_update_status_old = Pref.pref( service ).getLong( Pref.FLASHAIR_UPDATE_STATUS_OLD, - 1L );
				if( flashair_update_status_old != - 1L && flashair_update_status_old == flashair_update_status ){
					// 前回スキャン開始時と同じ数字なので変更されていない
					log.d( R.string.flashair_not_updated );
					continue;
				}
			}

			// フォルダを探索する
			final LinkedList<Item> job_queue = new LinkedList<>();
			{
				FilePathX local_path = new FilePathX();
				local_path.document_file = DocumentFile.fromTreeUri( service, Uri.parse( folder_uri ) );
				job_queue.add( new Item( "/", local_path, false, 0L ) );
			}
			boolean has_error = false;
			while( ! isCancelled() ){

				if( job_queue.isEmpty() ){
					status.set( service.getString( R.string.file_scan_completed ) );
					if( ! has_error ){

						Pref.pref( service ).edit()
							.putLong( Pref.LAST_SCAN_COMPLETE, System.currentTimeMillis() )
							.putLong( Pref.FLASHAIR_UPDATE_STATUS_OLD, flashair_update_status )
							.apply();

						if( ! repeat ){
							Pref.pref( service ).edit().putInt( Pref.LAST_MODE, Pref.LAST_MODE_STOP ).apply();
							cancel( service.getString( R.string.repeat_off ) );
							callback.releaseWakeLock();
							allow_stop_service = true;
						}
					}
					break;
				}

				// WakeLockやWiFiLockを確保
				callback.acquireWakeLock();

				Item item = job_queue.removeFirst();
				if( item.is_file ){
					// TODO ファイル転送
				}else{
					status.set( service.getString( R.string.progress_folder, item.air_path ) );
					// フォルダを読む
					String cgi_url = flashair_url + "command.cgi?op=100&DIR=" + Uri.encode( item.air_path );
					byte[] data = client.getHTTP( log, network, cgi_url );
					if( data == null ){
						if( client.last_error.contains( "UnknownHostException" ) ){
							client.last_error = service.getString( R.string.flashair_host_error );
							cancel( service.getString( R.string.flashair_host_error_short ) );
							callback.releaseWakeLock();
						}
						log.e( R.string.folder_list_failed, item.air_path, cgi_url, client.last_error );
						has_error = true;
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
							int date = Integer.parseInt( mAttr.group( 3 ), 10 );
							int time = Integer.parseInt( mAttr.group( 4 ), 10 );
							int delm = line.indexOf( ',' );
							String dir = line.substring( 0, delm );
							String fname = line.substring( delm + 1, mAttr.start() );

							if( ( attr & 2 ) != 0 ){
								// skip hidden file
								continue;
							}else if( ( attr & 4 ) != 0 ){
								// skip system file
								continue;
							}

							String child_air_path = dir + "/" + fname;

							FilePathX local_child = new FilePathX();
							local_child.parent = item.local_path;
							local_child.name = fname;

							if( ( attr & 0x10 ) != 0 ){
								// サブフォルダはキューに追加する
								job_queue.add( new Item( child_air_path, local_child, false, 0L ) );
								continue;
							}
							status.set( service.getString( R.string.progress_file, child_air_path ) );

							long time_start = SystemClock.elapsedRealtime();

							// file type matching
							boolean bMatch = false;
							for( Pattern re : file_type_list ){
								if( ! re.matcher( fname ).find() ) continue;
								bMatch = true;
								break;
							}
							if( ! bMatch ){
								continue;
							}

							DocumentFile file = local_child.prepareFile( log );
							if( file == null ){
								log.e( "%s//%s :skip. can not prepare local file.", item.air_path, fname );
								continue;
							}else if( file.length() >= size ){
								// log.f( "%s//%s :skip. same file size.",item.air_path, fname );
								continue;
							}

							status.set( service.getString( R.string.download_file, child_air_path ) );

							final Uri file_uri = file.getUri();
							final String get_url = flashair_url + Uri.encode( child_air_path );
							data = client.getHTTP( log, network, get_url, new HTTPClientReceiver(){
								byte[] buf = new byte[ 2048 ];

								public byte[] onHTTPClientStream( LogWriter log, CancelChecker cancel_checker, InputStream in, int content_length ){
									try{
										OutputStream os = service.getContentResolver().openOutputStream( file_uri );
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
								// no log.
							}else if( data == null ){
								log.e( "FILE %s :HTTP error %s", fname, client.last_error );
								has_error = true;
							}else{
								log.i( "FILE %s :download complete. %dms", fname, SystemClock.elapsedRealtime() - time_start );

								// 位置情報を取得する時にファイルの日時が使えないかと思ったけど
								// タイムゾーンがわからん…

								Location location = callback.getLocation();
								if( location != null && reJPEG.matcher( fname ).find() ){
									status.set( service.getString( R.string.exif_update, child_air_path ) );
									updateFileLocation( location, local_child );
								}

							}

						}catch( Throwable ex ){
							log.e( "parse error: %s", line );
							has_error = true;
						}

					}
				}
			}
		}
		status.set( service.getString( R.string.thread_end ) );
		callback.onThreadEnd( allow_stop_service );
	}

	private void updateFileLocation( final Location location, FilePathX file ){

		try{
			FilePathX tmp_path = new FilePathX();
			tmp_path.parent = file.parent;
			tmp_path.name =  "tmp-" + currentThread().getId() + "-" + android.os.Process.myPid()  + "-" +file.name;

			DocumentFile tmp_file = tmp_path.prepareFile( log );
			if( tmp_file != null ){
				boolean bModifyFailed = false;
				OutputStream os = service.getContentResolver().openOutputStream( tmp_file.getUri() );
				try{
					InputStream is = service.getContentResolver().openInputStream( file.document_file.getUri() );
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
					log.e( "exif mangling failed. %s %s", ex.getClass().getSimpleName(), ex.getMessage() );
				}finally{
					try{
						if( os != null ) os.close();
					}catch( Throwable ignored ){
					}
				}



				if( bModifyFailed ){
					try{
						tmp_file.delete();
					}catch( Throwable ignored ){

					}
				}else{
					try{
						// 更新後の方がファイルが小さいことがあるのか？
						if(tmp_file.length() < file.document_file.length() ){
							log.e("EXIF付与したファイルの方が小さい!付与前後のファイルを残しておく");
							// この場合両方のファイルを残しておく
						}else{
							if( ! file.document_file.delete() ){
								log.e( "EXIF追加後のファイル操作に失敗" );
							}else if( ! tmp_file.renameTo( file.name ) ){
								log.e( "EXIF追加後のファイル操作に失敗" );
							}else{
								log.i("%s に位置情報を付与しました",file.name );
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

}
