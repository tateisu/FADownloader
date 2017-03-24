package jp.juggler.fadownloader;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;

import java.io.File;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MediaScannerTracker implements MediaScannerConnection.MediaScannerConnectionClient{

	@Override public void onMediaScannerConnected(){
		handler.post( queue_reader );
	}

	@Override public void onScanCompleted( String path, Uri uri ){
	}

	final Context context;
	final LogWriter log;
	final MediaScannerConnection conn;
	final Handler handler;
	long last_connect_start;

	volatile boolean is_dispose = false;

	void dispose(){
		is_dispose = true;
	}

	public MediaScannerTracker( Context context, LogWriter log ){
		this.context = context;
		this.log = log;
		this.conn = new MediaScannerConnection( context, this );
		this.handler = new Handler();
		prepareConnection();
	}

	static class Item{

		String path;
		String mime_type;
	}

	final ConcurrentLinkedQueue<Item> queue = new ConcurrentLinkedQueue<>();

	public void addFile( File file, String mime_type ){
		if( file == null || ! file.isFile() ) return;
		if( TextUtils.isEmpty( mime_type ) ) return;
		Item item = new Item();
		item.path = file.getAbsolutePath();
		item.mime_type = mime_type;
		queue.add( item );
		handler.post( queue_reader );
	}

	private boolean prepareConnection(){
		if( conn.isConnected() ) return true;

		long now = SystemClock.elapsedRealtime();
		if( now - last_connect_start >= 5000L ){
			last_connect_start = now;
			conn.connect();
		}
		return false;
	}

	final Runnable queue_reader = new Runnable(){
		@Override public void run(){
			handler.removeCallbacks( queue_reader );
			for( ; ; ){

				Item item = queue.peek();

				if( item == null ){
					if( is_dispose ){
						conn.disconnect();
					}
					break;
				}

				if( ! prepareConnection() ){
					handler.postDelayed( queue_reader, 1000L );
					break;
				}

				conn.scanFile( item.path, item.mime_type );

				queue.poll();
			}
		}
	};

}
