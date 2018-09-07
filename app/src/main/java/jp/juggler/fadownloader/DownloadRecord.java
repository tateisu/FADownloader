package jp.juggler.fadownloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.net.Uri;
import android.provider.BaseColumns;

public class DownloadRecord{

	public static final TableMeta meta = new TableMeta(){
		@Override public Uri insert( ContentResolver cr, SQLiteDatabase db, MatchResult match, Uri uri, ContentValues values ){
			return super.insert( cr, db, match, uri, values );
		}

		@Override public void onDBCreate( SQLiteDatabase db ){
			db.execSQL(
				"create table if not exists " + table
					+ "(_id INTEGER PRIMARY KEY"
					+ ",t integer not null"
					+ ",n text not null"
					+ ",ap text not null"
					+ ",lf text " // may null
					+ ",sc integer not null"
					+ ",sm text not null"
					+ ",lt integer not null"
					+ ",sz integer not null default 0"
					+ ")"
			);
			db.execSQL(
				"create index if not exists " + table + "_time on " + table + "(t,ap)"
			);
			db.execSQL(
				"create index if not exists " + table + "_state on " + table + "(sc,t,ap)"
			);
			db.execSQL(
				"create unique index if not exists " + table + "_air_path on " + table + "(ap)"
			);
			db.execSQL(
				"create unique index if not exists " + table + "_name on " + table + "(n)"
			);
		}

		@Override public void onDBUpgrade( SQLiteDatabase db, int v_old, int v_new ){
			if( v_old < 2 && v_new >= 2 ){
				onDBCreate( db );
			}
			if( v_old < 3 && v_new >= 3){
				try{
					db.execSQL( "alter table "+table+" add column sz integer not null default 0" );
				}catch(Throwable ex){
					// 既にカラムが存在する場合、ここを通る
					ex.printStackTrace(  );
				}
			}
			if( v_old < 4 && v_new >= 4){
				try{
					db.execSQL(
						"create unique index if not exists " + table + "_name on " + table + "(n)"
					);
				}catch(Throwable ex){
					ex.printStackTrace(  );
				}
			}
		}
	};

	public long time;
	public String air_path;
	public String local_file; // may null
	public int state_code;
	public String state_message;
	public long lap_time;
	public long size;

	public static final String COL_ID = BaseColumns._ID;
	public static final String COL_TIME = "t";
	public static final String COL_NAME = "n";
	public static final String COL_AIR_PATH = "ap";
	public static final String COL_LOCAL_FILE = "lf";
	public static final String COL_STATE_CODE = "sc";
	public static final String COL_STATE_MESSAGE = "sm";
	public static final String COL_LAP_TIME = "lt";
	public static final String COL_SIZE = "sz";


	public static class ColIdx{

		int idx_time = - 1;
		int idx_name;
		int idx_air_path;
		int idx_local_file;
		int idx_state_code;
		int idx_state_message;
		int idx_lap_time;
		int idx_size;

		void setup( Cursor c ){
			idx_time = c.getColumnIndex( DownloadRecord.COL_TIME );
			idx_name = c.getColumnIndex( DownloadRecord.COL_NAME );
			idx_air_path = c.getColumnIndex( DownloadRecord.COL_AIR_PATH );
			idx_local_file = c.getColumnIndex( DownloadRecord.COL_LOCAL_FILE );
			idx_state_code = c.getColumnIndex( DownloadRecord.COL_STATE_CODE );
			idx_state_message = c.getColumnIndex( DownloadRecord.COL_STATE_MESSAGE );
			idx_lap_time = c.getColumnIndex( DownloadRecord.COL_LAP_TIME );
			idx_size = c.getColumnIndex( DownloadRecord.COL_SIZE );
		}
	}

	public void loadFrom( Cursor cursor, ColIdx colIdx ){
		if( colIdx == null ) colIdx = new ColIdx();
		if( colIdx.idx_time == - 1 ) colIdx.setup( cursor );
		//
		time = cursor.getLong( colIdx.idx_time );
		air_path = cursor.getString( colIdx.idx_air_path );
		local_file = cursor.isNull( colIdx.idx_local_file ) ? null : cursor.getString( colIdx.idx_local_file );
		state_code = cursor.getInt( colIdx.idx_state_code );
		state_message = cursor.isNull( colIdx.idx_state_message ) ? null : cursor.getString( colIdx.idx_state_message );
		lap_time = cursor.getLong( colIdx.idx_lap_time );
		size = cursor.getLong( colIdx.idx_size );
	}
	
	

	public static Uri insert(
		ContentResolver cr
		, ContentValues cv
		, String name
		, String air_path
		, String local_file
		, int state_code
		, String state_message
		, long lap_time
	    ,long size
	){
		try{
			cv.clear();
			cv.put( COL_TIME, System.currentTimeMillis() );
			cv.put( COL_NAME, name );
			cv.put( COL_AIR_PATH, air_path );
			cv.put( COL_LOCAL_FILE, local_file );
			cv.put( COL_STATE_CODE, state_code );
			cv.put( COL_STATE_MESSAGE, state_message );
			cv.put( COL_LAP_TIME, lap_time );
			cv.put( COL_SIZE, size );

			return cr.insert( meta.content_uri, cv );

		}catch( Throwable ex ){
			ex.printStackTrace();
			return null;
		}
	}

	public static final int STATE_COMPLETED = 0;
	public static final int STATE_QUEUED = 1;
	public static final int STATE_LOCAL_FILE_PREPARE_ERROR = 2;
	public static final int STATE_DOWNLOAD_ERROR = 3;
	public static final int STATE_EXIF_MANGLING_ERROR = 4;
	public static final int STATE_CANCELLED = 5;

	public static String formatStateText( Context context, int state_code, String state_message ){
		switch(state_code){
		default:
			return String.format("(%d)%s",state_code,state_message);
		case STATE_COMPLETED:
			return context.getString(R.string.download_completed);
		case STATE_CANCELLED:
			return context.getString(R.string.download_cancelled);
		case STATE_QUEUED:
			return context.getString(R.string.queued);

		case STATE_LOCAL_FILE_PREPARE_ERROR:
		case STATE_DOWNLOAD_ERROR:
		case STATE_EXIF_MANGLING_ERROR:
			return String.format("error: %s",state_message);
		}
	}

	public static int getStateCodeColor( int code ){
		switch( code ){
		default:
		case DownloadRecord.STATE_CANCELLED:
			return Color.BLACK;

		case DownloadRecord.STATE_COMPLETED:
			return 0xff008000;

		case STATE_QUEUED:
			return 0xff8000cc;

		case DownloadRecord.STATE_EXIF_MANGLING_ERROR:
			return 0xff800080;

		case DownloadRecord.STATE_LOCAL_FILE_PREPARE_ERROR:
		case DownloadRecord.STATE_DOWNLOAD_ERROR:
			return 0xffC00000;

		}
	}

}
