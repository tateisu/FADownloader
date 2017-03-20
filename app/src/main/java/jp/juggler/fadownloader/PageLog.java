package jp.juggler.fadownloader;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Process;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.ListView;

import java.io.File;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Locale;

import config.BuildVariant;

public class PageLog extends PagerAdapterBase.PageViewHolder implements View.OnClickListener{

	ListView lvLog;
	LogViewer log_viewer;

	@Override public void onClick( View v ){
		switch( v.getId() ){
		case R.id.btnLogSend:
			log_send();

			break;

		case R.id.btnLogClear:
			new AlertDialog.Builder( activity )
				.setTitle( R.string.log_clear )
				.setMessage( R.string.log_clear_confirm )
				.setCancelable( true )
				.setNegativeButton( R.string.cancel, null )
				.setPositiveButton( R.string.ok, new DialogInterface.OnClickListener(){
					@Override public void onClick( DialogInterface dialog, int which ){
						activity.getContentResolver().delete( LogData.meta.content_uri, null, null );
					}
				} )
				.show();
			break;
		}

	}

	public PageLog( Activity activity, View ignored ){
		super( activity, ignored );
	}

	@Override protected void onPageCreate( int page_idx, View root ) throws Throwable{
		lvLog = (ListView) root.findViewById( R.id.lvLog );
		log_viewer = new LogViewer();

		root.findViewById( R.id.btnLogSend ).setOnClickListener( this );
		root.findViewById( R.id.btnLogClear ).setOnClickListener( this );

		if( ( (ActMain) activity ).is_start ){
			onStart();
		}
	}

	@Override protected void onPageDestroy( int page_idx, View root ) throws Throwable{
		onStop();
	}

	void onStart(){
		log_viewer.onStart( (ActMain) activity, lvLog, ActMain.LOADER_ID_LOG );
	}

	void onStop(){
		log_viewer.onStop();
	}


	static final SimpleDateFormat filename_date_fmt = new SimpleDateFormat( "yyyyMMddHHmmss", Locale.getDefault() );

	void log_send(){
		final ProgressDialog progress = new ProgressDialog( activity );
		final AsyncTask<Void, Integer, File> task = new AsyncTask<Void, Integer, File>(){
			@Override protected File doInBackground( Void... params ){
				try{
					File cache_dir = activity.getExternalCacheDir();

					File log_file = new File( cache_dir, String.format( "%s-FADownloader-%s-%s.txt"
						, filename_date_fmt.format( System.currentTimeMillis() )
						, Process.myPid()
						, Thread.currentThread().getId()
					) );
					PrintStream fos = new PrintStream( log_file, "UTF-8" );
					try{
						Cursor cursor = activity.getContentResolver().query( LogData.meta.content_uri, null, null, null, LogData.COL_TIME + " asc" );
						if( cursor != null ){
							try{
								int i=0;
								int count = cursor.getCount();
								if( count <= 0 ) count = 1;
								int colidx_time = cursor.getColumnIndex( LogData.COL_TIME );
								int colidx_message = cursor.getColumnIndex( LogData.COL_MESSAGE );
								int colidx_level = cursor.getColumnIndex( LogData.COL_LEVEL );
								while( cursor.moveToNext() ){
									if( isCancelled() ) return null;
									publishProgress( i++ , count );
									fos.printf( "%s %s/%s\n"
										, LogViewer.date_fmt.format( cursor.getLong( colidx_time ) )
										, LogData.getLogLevelString( cursor.getInt( colidx_level ) )
										, cursor.getString( colidx_message )
									);
								}
							}finally{
								cursor.close();
							}
						}

					}finally{
						try{
							fos.flush();
							fos.close();
						}catch( Throwable ignored ){

						}
					}
					return log_file;

				}catch( Throwable ex ){
					ex.printStackTrace();
					Utils.showToast( activity, ex, "log data collection failed." );
				}
				return null;
			}

			@Override protected void onProgressUpdate( Integer... values ){
				if( progress.isShowing() ){
					progress.setMax( values[1] );
					progress.setProgress(  values[0] );
				}
			}

			@Override protected void onCancelled(){
				progress.dismiss();
			}

			@Override protected void onPostExecute( File file ){
				progress.dismiss();
				if( file == null ) return;
				try{
					Uri uri = FileProvider.getUriForFile( activity, BuildVariant.FILE_PROVIDER_AUTHORITY, file );
					// LGV32(Android 6.0)でSDカードを使うと例外発生
					// IllegalArgumentException: Failed to find configured root that contains /storage/3136-6334/image1.jpg
					// ワークアラウンド： FileProviderに指定するpath xml に <root-path  name="pathRoot" path="." /> を追加
					if( uri == null ){
						Utils.showToast( activity, true, "can't get FileProvider URI from %s", file.getAbsolutePath() );
						return;
					}
					String mime_type = "application/octet-stream";// Utils.getMimeType( file.getAbsolutePath() );
					Intent intent = new Intent( Intent.ACTION_SEND );
					intent.setType( mime_type );
					intent.addFlags(
						Intent.FLAG_GRANT_WRITE_URI_PERMISSION
							| Intent.FLAG_GRANT_READ_URI_PERMISSION
					);
					intent.putExtra( Intent.EXTRA_STREAM, uri );
					activity.startActivityForResult( intent, ActMain.REQUEST_CODE_SEND );
				}catch( Throwable ex ){
					ex.printStackTrace();
					Utils.showToast( activity, ex, "send failed." );
				}
			}

		};
		progress.setMessage( activity.getString(R.string.log_collection_progress ));

		progress.setIndeterminate( false );
		progress.setProgressStyle( ProgressDialog.STYLE_HORIZONTAL );
		progress.setCancelable( true );
		progress.setOnCancelListener( new DialogInterface.OnCancelListener(){
			@Override public void onCancel( DialogInterface dialog ){
				task.cancel( false );
			}
		} );
		progress.show();
		task.execute();

	}
}
