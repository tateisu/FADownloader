package jp.juggler.fadownloader;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.provider.DocumentFile;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class DownloadRecordViewer implements LoaderManager.LoaderCallbacks<Cursor>, AdapterView.OnItemClickListener{

	AppCompatActivity activity;
	ListView listview;
	int last_view_start;
	Loader<Cursor> loader;

	public DownloadRecordViewer(){
	}

	void onStart( AppCompatActivity activity, ListView listview, int loader_id ){
		this.activity = activity;
		this.listview = listview;
		listview.setOnItemClickListener( this );
		loader = activity.getSupportLoaderManager().initLoader( loader_id, null, this );
	}

	void onStop(){
		if( loader != null ){
			activity.getSupportLoaderManager().destroyLoader( loader.getId() );
			loader = null;
		}
		this.last_view_start = listview.getFirstVisiblePosition();
		listview.setAdapter( null );
		this.listview = null;
		this.activity = null;
	}

	@Override public Loader<Cursor> onCreateLoader( int id, Bundle args ){
		return activity == null ? null : new CursorLoader( activity, DownloadRecord.meta.content_uri, null, null, null, LogData.COL_TIME + " desc" );
	}

	@Override public void onLoadFinished( Loader<Cursor> loader, Cursor cursor ){
		if( listview == null ) return;
		RecordAdapter adapter = (RecordAdapter) listview.getAdapter();
		if( adapter == null ){
			listview.setAdapter( new RecordAdapter( activity, cursor ) );
		}else{
			adapter.swapCursor( cursor );
		}
	}

	@Override public void onLoaderReset( Loader<Cursor> loader ){
		if( listview == null ) return;
		RecordAdapter adapter = (RecordAdapter) listview.getAdapter();
		if( adapter != null ){
			adapter.swapCursor( null );
		}
	}

	@Override public void onItemClick( AdapterView<?> parent, View view, int position, long id ){
		try{
			RecordAdapter adapter = (RecordAdapter) parent.getAdapter();
			final DownloadRecord data = adapter.loadAt( position );
			if( data == null ){
				( (ActMain) activity ).showToast( false, "missing record data at clicked position." );
				return;
			}
			if( data.local_file == null ){
				( (ActMain) activity ).showToast( false, "missing local file uri." );
				return;
			}

			Intent intent = new Intent( Intent.ACTION_VIEW );
			if( data.mime_type != null ){
				intent.setDataAndType( Uri.parse(data.local_file), data.mime_type );
			}else{
				intent.setData( Uri.parse(data.local_file) );
			}
			activity.startActivity( intent );
		}catch( Throwable ex ){
			ex.printStackTrace();
		}
	}

	class RecordAdapter extends CursorAdapter{

		final DownloadRecord.ColIdx colIdx = new DownloadRecord.ColIdx();
		final DownloadRecord data = new DownloadRecord();
		final LayoutInflater inflater = activity.getLayoutInflater();

		DownloadRecord loadAt( int position ){
			Cursor cursor = getCursor();
			if( cursor.moveToPosition( position ) ){
				DownloadRecord result = new DownloadRecord();
				result.loadFrom( cursor, colIdx );
				return result;
			}
			return null;
		}

		public RecordAdapter( Context context, Cursor c ){
			super( context, c, false );
		}

		@Override public View newView( Context context, Cursor cursor, ViewGroup viewGroup ){
			View root = inflater.inflate( R.layout.lv_download_record, viewGroup, false );
			ViewHolder holder = new ViewHolder( root );
			return root;
		}

		@Override public void bindView( View view, Context context, Cursor cursor ){
			ViewHolder holder = (ViewHolder) view.getTag();
			data.loadFrom( cursor, colIdx );

			holder.bind( view, data );

		}
	}

	static final SimpleDateFormat date_fmt = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss.SSS z", Locale.getDefault() );

	static class ViewHolder{

		final TextView tvTime;
		final TextView tvAirPath;
		final TextView tvLocalFile;
		final TextView tvStateCode;

		ViewHolder( View root ){
			root.setTag( this );
			tvTime = (TextView) root.findViewById( R.id.tvTime );
			tvAirPath = (TextView) root.findViewById( R.id.tvAirPath );
			tvLocalFile = (TextView) root.findViewById( R.id.tvLocalFile );
			tvStateCode = (TextView) root.findViewById( R.id.tvStateCode );
		}

		public void bind( View root, DownloadRecord data ){

			tvTime.setText( date_fmt.format( data.time ) );
			tvAirPath.setText( "air_path:" + data.air_path );
			tvLocalFile.setText( "local:" + data.local_file );
			tvStateCode.setText( String.format( "%s (%d)%s %s"
				, Utils.formatTimeDuration( data.lap_time )
				, data.state_code
				, DownloadRecord.getStateCodeString( data.state_code )
				,data.state_message
			) );

//			int fg;
//			if( level >= LogData.LEVEL_FLOOD ){
//				fg = 0xffbbbbbb;
//			}else if( level >= LogData.LEVEL_HEARTBEAT ){
//				fg = 0xff999999;
//			}else if( level >= LogData.LEVEL_DEBUG ){
//				fg = 0xff777777;
//			}else if( level >= LogData.LEVEL_VERBOSE ){
//				fg = 0xff555555;
//			}else if( level >= LogData.LEVEL_INFO ){
//				fg = 0xff000000;
//			}else if( level >= LogData.LEVEL_WARNING ){
//				fg = 0xffff8000;
//			}else{
//				fg = 0xffff0000;
//			}
//			holder.tvTime.setTextColor( fg );
//			holder.tvMessage.setTextColor( fg );
		}
	}
}
