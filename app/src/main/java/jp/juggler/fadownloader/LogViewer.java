package jp.juggler.fadownloader;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AppCompatActivity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.GregorianCalendar;

public class LogViewer implements LoaderManager.LoaderCallbacks<Cursor>{

	AppCompatActivity activity;
	ListView listview;
	int last_view_start;
	Loader<Cursor> loader;

	public LogViewer(){
	}

	void onStart( AppCompatActivity activity, ListView listview, int loader_id ){
		this.activity = activity;
		this.listview = listview;
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
		return activity == null ? null : new CursorLoader( activity, LogData.meta.content_uri, null, null, null, LogData.COL_TIME + " desc" );
	}

	@Override public void onLoadFinished( Loader<Cursor> loader, Cursor cursor ){
		if( listview == null ) return;
		LogAdapter adapter = (LogAdapter) listview.getAdapter();
		if( adapter == null ){
			listview.setAdapter( new LogAdapter( activity, cursor ) );
		}else{
			adapter.swapCursor( cursor );
		}
	}

	@Override public void onLoaderReset( Loader<Cursor> loader ){
		if( listview == null ) return;
		LogAdapter adapter = (LogAdapter) listview.getAdapter();
		if( adapter != null ){
			adapter.swapCursor( null );
		}
	}

	static class ViewHolder{

		TextView tvTime;
		TextView tvMessage;
	}

	class LogAdapter extends CursorAdapter{

		LayoutInflater inflater = activity.getLayoutInflater();
		int colidx_time;
		int colidx_message;
		int colidx_level;

		SimpleDateFormat date_fmt = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss.SSS z", Locale.getDefault(  ) );

		public LogAdapter( Context context, Cursor c ){
			super( context, c, false );
			//
			colidx_time = c.getColumnIndex( LogData.COL_TIME );
			colidx_message = c.getColumnIndex( LogData.COL_MESSAGE );
			colidx_level = c.getColumnIndex( LogData.COL_LEVEL );

		}

		@Override public View newView( Context context, Cursor cursor, ViewGroup viewGroup ){
			View root = inflater.inflate( R.layout.lv_log, viewGroup, false );
			ViewHolder holder = new ViewHolder();
			holder.tvTime = (TextView) root.findViewById( R.id.tvTime );
			holder.tvMessage = (TextView) root.findViewById( R.id.tvMessage );
			root.setTag( holder );
			return root;
		}

		@Override public void bindView( View view, Context context, Cursor cursor ){
			ViewHolder holder = (ViewHolder) view.getTag();
			long time = cursor.getLong( colidx_time );
			String message = cursor.getString( colidx_message );
			int level = cursor.getInt( colidx_level );
			holder.tvTime.setText( date_fmt.format(time ) );
			holder.tvMessage.setText( message );

			int fg;
			if( level >= LogData.LEVEL_FLOOD ){
				fg = 0xff777777;
			}else if( level >= LogData.LEVEL_HEARTBEAT ){
				fg = 0xff555555;
			}else if( level >= LogData.LEVEL_DEBUG ){
				fg = 0xff333333;
			}else if( level >= LogData.LEVEL_VERBOSE ){
				fg = 0xff111111;
			}else if( level >= LogData.LEVEL_INFO ){
				fg = 0xff000000;
			}else if( level >= LogData.LEVEL_WARNING ){
				fg = 0xffff8000;
			}else{
				fg = 0xffff0000;
			}
			holder.tvTime.setTextColor( fg );
			holder.tvMessage.setTextColor( fg );
		}
	}

}
