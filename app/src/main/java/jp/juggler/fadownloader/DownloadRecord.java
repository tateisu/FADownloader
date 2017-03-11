package jp.juggler.fadownloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;

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
					+ ",mt text"
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
		}

		@Override public void onDBUpgrade( SQLiteDatabase db, int v_old, int v_new ){
			if( v_old < 2 && v_new >= 2 ){
				onDBCreate( db );
			}
		}
	};

	public long time;
	public String air_path;
	public String local_file; // may null
	public int state_code;
	public String state_message;
	public long lap_time;
	public String mime_type;

	public static final String COL_ID = BaseColumns._ID;
	public static final String COL_TIME = "t";
	public static final String COL_NAME = "n";
	public static final String COL_AIR_PATH = "ap";
	public static final String COL_LOCAL_FILE = "lf";
	public static final String COL_STATE_CODE = "sc";
	public static final String COL_STATE_MESSAGE = "sm";
	public static final String COL_LAP_TIME = "lt";
	public static final String COL_MIME_TYPE = "mt";

	public static class ColIdx{

		int idx_time = -1;
		int idx_name;
		int idx_air_path;
		int idx_local_file;
		int idx_state_code;
		int idx_state_message;
		int idx_lap_time;
		int idx_mime_type;

		void setup( Cursor c ){
			idx_time = c.getColumnIndex( DownloadRecord.COL_TIME );
			idx_name = c.getColumnIndex( DownloadRecord.COL_NAME );
			idx_air_path = c.getColumnIndex( DownloadRecord.COL_AIR_PATH );
			idx_local_file = c.getColumnIndex( DownloadRecord.COL_LOCAL_FILE );
			idx_state_code = c.getColumnIndex( DownloadRecord.COL_STATE_CODE );
			idx_state_message = c.getColumnIndex( DownloadRecord.COL_STATE_MESSAGE );
			idx_lap_time = c.getColumnIndex( DownloadRecord.COL_LAP_TIME );
			idx_mime_type = c.getColumnIndex( DownloadRecord.COL_MIME_TYPE );
		}
	}


	public void loadFrom( Cursor cursor, ColIdx colIdx ){
		if( colIdx==null) colIdx = new ColIdx();
		if( colIdx.idx_time ==-1) colIdx.setup(cursor);
		//
		time = cursor.getLong( colIdx.idx_time );
		air_path = cursor.getString( colIdx.idx_air_path );
		local_file = cursor.isNull( colIdx.idx_local_file ) ? null : cursor.getString( colIdx.idx_local_file );
		state_code = cursor.getInt( colIdx.idx_state_code );
		state_message = cursor.isNull( colIdx.idx_state_message ) ? null : cursor.getString( colIdx.idx_state_message );
		lap_time = cursor.getLong( colIdx.idx_lap_time );
		mime_type = cursor.isNull( colIdx.idx_mime_type ) ? null : cursor.getString( colIdx.idx_mime_type );
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
	){
		try{
			FileInfo fi = new FileInfo( cr,local_file );

			cv.clear();
			cv.put( COL_TIME, System.currentTimeMillis() );
			cv.put( COL_NAME, name );
			cv.put( COL_AIR_PATH, air_path );
			cv.put( COL_LOCAL_FILE, fi.uri.toString() );
			cv.put( COL_STATE_CODE, state_code );
			cv.put( COL_STATE_MESSAGE, state_message );
			cv.put( COL_LAP_TIME, lap_time );
			cv.put( COL_MIME_TYPE, fi.mime_type );

			return cr.insert( meta.content_uri, cv );

		}catch( Throwable ex ){
			ex.printStackTrace();
			return null;
		}
	}



	public static final int STATE_COMPLETED = 0;
	public static final int STATE_FILE_TYPE_NOT_MATCH = 1;
	public static final int STATE_LOCAL_FILE_PREPARE_ERROR = 2;
	public static final int STATE_DOWNLOAD_ERROR = 3;
	public static final int STATE_EXIF_MANGLING_ERROR = 4;
	public static final int STATE_CANCELLED = 5;

	public static String getStateCodeString( int code ){
		switch( code ){
		case DownloadRecord.STATE_COMPLETED:
			return "COMPLETED";
		case DownloadRecord.STATE_FILE_TYPE_NOT_MATCH:
			return "FILE_TYPE_NOT_MATCH";
		case DownloadRecord.STATE_LOCAL_FILE_PREPARE_ERROR:
			return "LOCAL_FILE_PREPARE_ERROR";
		case DownloadRecord.STATE_DOWNLOAD_ERROR:
			return "DOWNLOAD_ERROR";
		case DownloadRecord.STATE_EXIF_MANGLING_ERROR:
			return "EXIF_MANGLING_ERROR";
		case DownloadRecord.STATE_CANCELLED:
			return "CANCELLED";
		default:
			return "?";
		}

	}


	static class FileInfo{
		Uri uri;
		String mime_type;

		FileInfo( ContentResolver cr, String any_uri){
			if( any_uri.startsWith( "/" ) ){
				uri = Uri.fromFile( new File( any_uri ) );
			}else{
				uri = Uri.parse( any_uri );
				if( Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION ){
					{
						Cursor cursor = cr.query( uri, null, null, null, null );
						if( cursor != null ){
							try{
								if( cursor.moveToFirst() ){
									int col_count = cursor.getColumnCount();
									for( int i = 0 ; i < col_count ; ++ i ){
										int type = cursor.getType( i );
										if( type != Cursor.FIELD_TYPE_STRING ) continue;
										String name = cursor.getColumnName( i );
										String value = cursor.isNull( i ) ? null : cursor.getString( i );
										Log.d( "DownloadRecordViewer", String.format( "%s %s", name, value ) );
										if( ! TextUtils.isEmpty( value ) ){
											if( "filePath".equals( name ) ){
												uri = Uri.fromFile( new File( value ) );
											}else if( "drmMimeType".equals( name ) ){
												mime_type = value;
											}
										}
									}
								}
							}catch( Throwable ex ){
								ex.printStackTrace();
							}finally{
								cursor.close();
							}
						}
					}
				}
			}
		}
	}

}
