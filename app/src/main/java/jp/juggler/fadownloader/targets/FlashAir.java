package jp.juggler.fadownloader.targets;

import android.net.Uri;
import android.os.SystemClock;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.juggler.fadownloader.CancelChecker;
import jp.juggler.fadownloader.DownloadRecord;
import jp.juggler.fadownloader.DownloadService;
import jp.juggler.fadownloader.DownloadWorker;
import jp.juggler.fadownloader.HTTPClientReceiver;
import jp.juggler.fadownloader.LocalFile;
import jp.juggler.fadownloader.LogWriter;
import jp.juggler.fadownloader.Pref;
import jp.juggler.fadownloader.ScanItem;
import jp.juggler.fadownloader.R;
import jp.juggler.fadownloader.Utils;

public class FlashAir{

	static final Pattern reLine = Pattern.compile( "([^\\x0d\\x0a]+)" );
	static final Pattern reAttr = Pattern.compile( ",(\\d+),(\\d+),(\\d+),(\\d+)$" );

	// static final long ERROR_BREAK = -1L;
	static final long ERROR_CONTINUE = - 2L;

	final DownloadWorker thread;
	final DownloadService service;
	final LogWriter log;

	long flashair_update_status = 0L;

	public FlashAir( DownloadService service, DownloadWorker thread ){
		this.service = service;
		this.thread = thread;
		this.log = service.log;
	}

	long getFlashAirUpdateStatus( Object network ){
		String cgi_url = thread.target_url + "command.cgi?op=121";
		byte[] data = thread.client.getHTTP( log, network, cgi_url );
		if( thread.isCancelled() ) return ERROR_CONTINUE;

		if( data == null ){
			thread.checkHostError();
			log.e( R.string.flashair_update_check_failed, cgi_url, thread.client.last_error );
			return ERROR_CONTINUE;
		}
		try{
			//noinspection ConstantConditions
			return Long.parseLong( Utils.decodeUTF8( data ).trim() );
		}catch( Throwable ex ){
			log.e( R.string.flashair_update_status_error );
			thread.cancel( service.getString( R.string.flashair_update_status_error ) );
			return ERROR_CONTINUE;
		}
	}

	void loadFolder( final Object network, final ScanItem item ){

		// フォルダを読む
		String cgi_url = thread.target_url + "command.cgi?op=100&DIR=" + Uri.encode( item.remote_path );
		byte[] data = thread.client.getHTTP( log, network, cgi_url );
		if( thread.isCancelled() ) return;

		if( data == null ){
			thread.checkHostError();
			log.e( R.string.folder_list_failed, item.remote_path, cgi_url, thread.client.last_error );
			thread.file_error = true;
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
		GregorianCalendar calendar = new GregorianCalendar( TimeZone.getDefault() );

		while( ! thread.isCancelled() && mLine.find() ){
			String line = mLine.group( 1 );
			Matcher mAttr = reAttr.matcher( line );
			if( ! mAttr.find() ) continue;

			try{
				long size = Long.parseLong( mAttr.group( 1 ), 10 );
				int attr = Integer.parseInt( mAttr.group( 2 ), 10 );
				//
				long time;
				{
					int bits_date = Integer.parseInt( mAttr.group( 3 ), 10 );
					int bits_time = Integer.parseInt( mAttr.group( 4 ), 10 );
					int y = ( ( bits_date >> 9 ) & 0x7f ) + 1980;
					int m = ( ( bits_date >> 5 ) & 0xf );
					int d = ( ( bits_date ) & 0x1f );
					int h = ( ( bits_time >> 11 ) & 0x1f );
					int j = ( ( bits_time >> 5 ) & 0x3f );
					int s = ( ( bits_time ) & 0x1f ) * 2;
					//	log.f( "time=%s,%s,%s,%s,%s,%s", y, m, d, h, j, s );
					calendar.set( y, m - 1, d, h, j, s );
					calendar.set( Calendar.MILLISECOND, 500 );
					time = calendar.getTimeInMillis();
				}

				// https://flashair-developers.com/ja/support/forum/#/discussion/3/%E3%82%AB%E3%83%B3%E3%83%9E%E5%8C%BA%E5%88%87%E3%82%8A
				String dir = ( item.remote_path.equals( "/" ) ? "" : item.remote_path );
				String file_name = line.substring( dir.length() + 1, mAttr.start() );

				if( ( attr & 2 ) != 0 ){
					// skip hidden file
					continue;
				}else if( ( attr & 4 ) != 0 ){
					// skip system file
					continue;
				}

				String remote_path = dir + "/" + file_name;
				final LocalFile local_file = new LocalFile( item.local_file, file_name );

				if( ( attr & 0x10 ) != 0 ){
					// フォルダはキューの頭に追加
					thread.job_queue.addFolder( new ScanItem( file_name, remote_path, local_file ) );
				}else{

					if( thread.protected_only ){
						if( ( attr & 1 ) == 0 ){
							// リードオンリー属性がオフ
							continue;
						}
					}

					for( Pattern re : thread.file_type_list ){
						if( ! re.matcher( file_name ).find() ) continue;
						// マッチした

						// ローカルのファイルサイズを調べて既読スキップ
						if( local_file.length( log ) >= size ) continue;

						String mime_type = Utils.getMimeType( log, file_name );

						// ファイルはキューの末尾に追加
						ScanItem sub_item = new ScanItem( file_name, remote_path, local_file, size, time, mime_type );
						thread.job_queue.addFile( sub_item );
						thread.record( sub_item, 0L, DownloadRecord.STATE_QUEUED, "queued." );

						break;
					}
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( ex, "folder list parse error: %s", line );
			}
		}
	}

	private void loadFile( Object network, ScanItem item ){
		final long time_start = SystemClock.elapsedRealtime();
		final String file_name = new File( item.remote_path ).getName();
		final String remote_path = item.remote_path;
		final LocalFile local_file = item.local_file;

		try{

			if( ! local_file.prepareFile( log, true, item.mime_type ) ){
				log.e( "%s//%s :skip. can not prepare local file.", item.remote_path, file_name );
				thread.record( item
					, SystemClock.elapsedRealtime() - time_start
					, DownloadRecord.STATE_LOCAL_FILE_PREPARE_ERROR
					, "can not prepare local file."
				);
				return;
			}

			final String get_url = thread.target_url + Uri.encode( remote_path );
			byte[] data = thread.client.getHTTP( log, network, get_url, new HTTPClientReceiver(){
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

			thread.afterDownload( time_start, data, item );

		}catch( Throwable ex ){
			thread.afterDownload( time_start, ex, item );

		}

	}

	public void run(){

		while( ! thread.isCancelled() ){

			if( thread.job_queue == null && ! thread.callback.hasHiddenDownloadCount() ){
				// 指定時刻まで待機する
				while( ! thread.isCancelled() ){
					long now = System.currentTimeMillis();
					long last_file_listing = Pref.pref( service ).getLong( Pref.LAST_IDLE_START, 0L );
					long remain = last_file_listing + thread.interval * 1000L - now;
					if( remain <= 0 ) break;

					if( thread.isTetheringType() || remain < ( 15 * 1000L ) ){
						thread.setShortWait( remain );

					}else{
						thread.setAlarm( now, remain );
						break;
					}
				}
				if( thread.isCancelled() ) break;
				// 待機が終わった
			}

			thread.callback.acquireWakeLock();
			Object network = null;

			// 通信状態の確認
			thread.setStatus( false, service.getString( R.string.network_check ) );
			long network_check_start = SystemClock.elapsedRealtime();

			if( thread.target_type == Pref.TARGET_TYPE_FLASHAIR_STA ){
				while( ! thread.isCancelled() ){
					boolean tracker_last_result = service.wifi_tracker.last_result.get();
					String air_url = service.wifi_tracker.last_flash_air_url.get();
					if( tracker_last_result && air_url != null ){
						thread.target_url = air_url;
						break;
					}

					// 一定時間待機してもダメならスレッドを停止する
					// 通信状態変化でまた起こされる
					long er_now = SystemClock.elapsedRealtime();
					if( er_now - network_check_start >= 60 * 1000L ){
						// Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();
						thread.job_queue = null;
						thread.cancel( service.getString( R.string.network_not_good ) );
						break;
					}

					// 少し待って再確認
					thread.waitEx( 10000L );
				}
			}else{

				while( ! thread.isCancelled() ){
					network = thread.getWiFiNetwork();
					if( network != null ) break;

					// 一定時間待機してもダメならスレッドを停止する
					// 通信状態変化でまた起こされる
					long er_now = SystemClock.elapsedRealtime();
					if( er_now - network_check_start >= 60 * 1000L ){
						// Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();
						thread.job_queue = null;
						thread.cancel( service.getString( R.string.network_not_good ) );
						break;
					}

					// 少し待って再確認
					thread.waitEx( 10000L );
				}
			}
			if( thread.isCancelled() ) break;

			// ファイルスキャンの開始
			if( thread.job_queue == null ){
				Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();

				// FlashAir アップデートステータスを確認
				thread.setStatus( false, service.getString( R.string.flashair_update_status_check ) );
				flashair_update_status = getFlashAirUpdateStatus( network );
				if( flashair_update_status == ERROR_CONTINUE ){
					continue;
				}else{
					long old = Pref.pref( service ).getLong( Pref.FLASHAIR_UPDATE_STATUS_OLD, - 1L );
					if( flashair_update_status == old && old != - 1L ){
						// 前回スキャン開始時と同じ数字なので変更されていない
						log.d( R.string.flashair_not_updated );
						thread.onFileScanComplete( 0 );
						continue;
					}else{
						log.d( "flashair updated %d %d"
							, old
							, flashair_update_status
						);
					}
				}

				// 未取得状態のファイルを履歴から消す
				if( DownloadWorker.RECORD_QUEUED_STATE ){
					service.getContentResolver().delete(
						DownloadRecord.meta.content_uri
						, DownloadRecord.COL_STATE_CODE + "=?"
						, new String[]{
							Integer.toString( DownloadRecord.STATE_QUEUED )
						}
					);
				}

				// フォルダスキャン開始
				thread.onFileScanStart();
				thread.job_queue.addFolder( new ScanItem( "", "/", new LocalFile( service, thread.folder_uri ) ) );
			}

			try{
				if( ! thread.job_queue.queue_folder.isEmpty() ){
					ScanItem item = thread.job_queue.queue_folder.removeFirst();
					thread.setStatus( false, service.getString( R.string.progress_folder, item.remote_path ) );
					loadFolder( network, item );
				}else if( ! thread.job_queue.queue_file.isEmpty() ){
					// キューから除去するまえに残りサイズを計算したい
					final ScanItem item = thread.job_queue.queue_file.getFirst();
					thread.setStatus( true, service.getString( R.string.download_file, item.remote_path ) );
					thread.job_queue.queue_file.removeFirst();
					loadFile( network, item );
				}else{
					// ファイルスキャンの終了
					long file_count = thread.job_queue.file_count;
					thread.job_queue = null;
					thread.setStatus( false, service.getString( R.string.file_scan_completed ) );
					if( ! thread.file_error ){
						Pref.pref( service ).edit()
							.putLong( Pref.FLASHAIR_UPDATE_STATUS_OLD, flashair_update_status )
							.apply();
						thread.onFileScanComplete( file_count );
					}
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				log.e( ex, "error." );
			}
		}
	}
}
