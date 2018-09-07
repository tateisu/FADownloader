package jp.juggler.fadownloader.targets;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

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

public class PqiAirCard{

	final DownloadWorker thread;
	final DownloadService service;
	final LogWriter log;

	public PqiAirCard( DownloadService service, DownloadWorker thread ){
		this.service = service;
		this.thread = thread;
		this.log = service.log;
	}

	static final Pattern reDate = Pattern.compile( "(\\w+)\\s+(\\d+)\\s+(\\d+):(\\d+):(\\d+)\\s+(\\d+)" );

	final GregorianCalendar calendar = new GregorianCalendar( TimeZone.getDefault() );

	static final String[] month_array = new String[]{
		"January".toLowerCase(),
		"February".toLowerCase(),
		"March".toLowerCase(),
		"April".toLowerCase(),
		"May".toLowerCase(),
		"June".toLowerCase(),
		"July".toLowerCase(),
		"August".toLowerCase(),
		"September".toLowerCase(),
		"October".toLowerCase(),
		"November".toLowerCase(),
		"December".toLowerCase(),
	};

	// 月の省略形から月の数字(1-12)を返す
	static int parseMonth( String target ){
		target = target.toLowerCase();
		for( int i = 0, ie = month_array.length ; i < ie ; ++ i ){
			if( month_array[ i ].startsWith( target ) ) return i + 1;
		}
		throw new RuntimeException( "invalid month name :" + target );
	}

	void loadFolder( final Object network, final ScanItem item ){

		// フォルダを読む
		String cgi_url = thread.target_url + "cgi-bin/wifi_filelist?fn=/mnt/sd" + Uri.encode( item.remote_path, "/_-" ) + ( item.remote_path.length() > 1 ? "/" : "" );
		byte[] data = thread.client.getHTTP( log, network, cgi_url );
		if( thread.isCancelled() ) return;

		if( data == null ){
			thread.checkHostError();
			log.e( R.string.folder_list_failed, item.remote_path, cgi_url, thread.client.last_error );
			thread.file_error = true;
			return;
		}

		//	Element root = Utils.parseXml( "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"+ Utils.decodeUTF8( data ) );
		Element root = Utils.parseXml( data );
		if( root != null ){
			if( "filelist".equals( root.getTagName() ) ){
				NodeList child_list = root.getChildNodes();
				if( child_list != null ){
					for( int i = 0, ie = child_list.getLength() ; i < ie ; ++ i ){
						if( thread.isCancelled() ) break;
						Node node = child_list.item( i );
						if( ! "file".equals( node.getNodeName() ) ) continue;
						NamedNodeMap attr = node.getAttributes();
						if( attr == null ) continue;
						String file_name = Utils.getAttribute( attr, "name", null );
						String size_str = Utils.getAttribute( attr, "size", null );
						String date_str = Utils.getAttribute( attr, "date", null );
						String type_str = Utils.getAttribute( attr, "type", null );
						if( TextUtils.isEmpty( file_name ) ) continue;
						if( TextUtils.isEmpty( size_str ) ) continue;
						if( TextUtils.isEmpty( date_str ) ) continue;
						if( TextUtils.isEmpty( type_str ) ) continue;
						long size;
						try{
							size = Long.parseLong( size_str, 10 );
						}catch( NumberFormatException ex ){
							ex.printStackTrace();
							log.e( "incorrect size: %s", size_str );
							continue;
						}
						long time;
						try{
							Matcher matcher = reDate.matcher( date_str );
							if( ! matcher.find() ){
								log.e( "incorrect date: %s", date_str );
								continue;
							}
							int y = Integer.parseInt( matcher.group( 6 ), 10 );
							int m = parseMonth( matcher.group( 1 ) );
							int d = Integer.parseInt( matcher.group( 2 ), 10 );
							int h = Integer.parseInt( matcher.group( 3 ), 10 );
							int j = Integer.parseInt( matcher.group( 4 ), 10 );
							int s = Integer.parseInt( matcher.group( 5 ), 10 );
							//	log.f( "time=%s,%s,%s,%s,%s,%s", y, m, d, h, j, s );
							calendar.set( y, m - 1, d, h, j, s );
							calendar.set( Calendar.MILLISECOND, 500 );
							time = calendar.getTimeInMillis();
						}catch( NumberFormatException ex ){
							ex.printStackTrace();
							continue;
						}
						String dir = ( item.remote_path.equals( "/" ) ? "" : item.remote_path );

						String remote_path = dir + "/" + file_name;
						final LocalFile local_file = new LocalFile( item.local_file, file_name );

						if( ! type_str.equals( "0" ) ){
							// フォルダはキューの頭に追加
							thread.job_queue.addFolder( new ScanItem( file_name, remote_path, local_file ) );
						}else{
							// ファイル
							for( Pattern re : thread.file_type_list ){
								if( ! re.matcher( file_name ).find() ) continue;
								// マッチした

								// ローカルのファイルサイズを調べて既読スキップ
								if( thread.checkSkip( local_file,log,size )) continue;

								String mime_type = Utils.getMimeType( log, file_name );

								// ファイルはキューの末尾に追加
								ScanItem sub_item = new ScanItem( file_name, remote_path, local_file, size, time, mime_type );
								thread.job_queue.addFile( sub_item );
								thread.record( sub_item, 0L, DownloadRecord.STATE_QUEUED, "queued." );

								break;
							}
						}

					}

				}
				return;
			}
		}
		log.e( R.string.folder_list_failed, item.remote_path, cgi_url, "(xml parse error)" );
		thread.file_error = true;
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

			final String get_url = thread.target_url + "/sd" + Uri.encode( remote_path, "/_-" );
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

			if( thread.target_type == Pref.TARGET_TYPE_PQI_AIR_CARD_TETHER ){
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
					final ScanItem head = thread.job_queue.queue_folder.getFirst();
					thread.setStatus( false, service.getString( R.string.progress_folder, head.remote_path ) );
					thread.job_queue.queue_folder.removeFirst();
					loadFolder( network, head );
				}else if( ! thread.job_queue.queue_file.isEmpty() ){
					// キューから除去するまえに残りサイズを計算したい
					final ScanItem head = thread.job_queue.queue_file.getFirst();
					thread.setStatus( true, service.getString( R.string.download_file, head.remote_path ) );
					thread.job_queue.queue_file.removeFirst();
					loadFile( network, head );
				}else{
					// ファイルスキャンの終了
					long file_count = thread.job_queue.file_count;
					thread.job_queue = null;
					thread.setStatus( false, service.getString( R.string.file_scan_completed ) );
					if( ! thread.file_error ){
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
