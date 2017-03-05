package jp.juggler.fadownloader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import config.BuildVariant;

public class DataProvider extends ContentProvider{

	static final String AUTHORITY = BuildVariant.DATA_PROVIDER_AUTHORITY;

	static final String DB_NAME = "data";
	static final int DB_SCHEMA_VERSION = 1;

	static class DBHelper1 extends SQLiteOpenHelper{

		DBHelper1( Context context ){
			super( context, DB_NAME, null, DB_SCHEMA_VERSION );
		}

		@Override public void onCreate( SQLiteDatabase db ){
			LogData.meta.onDBCreate( db );
		}

		@Override public void onUpgrade( SQLiteDatabase db, int v_old, int v_new ){
			LogData.meta.onDBUpgrade( db, v_old, v_new );
		}
	}

	static{
		LogData.meta.registerUri( AUTHORITY, "log" );
	}

	private DBHelper1 mDBHelper;

	@Override public boolean onCreate(){
		mDBHelper = new DBHelper1( getContext() );
		return true;
	}

	// URIを照合して、mime type 文字列を返す
	@Nullable @Override public String getType( @NonNull Uri uri ){
		TableMeta.MatchResult match = TableMeta.matchUri( uri );
		return match == null ? null : match.meta.getType( uri, match );

	}

	@Override
	public Uri insert(
		@NonNull Uri uri
		, ContentValues values
	){
		TableMeta.MatchResult match = TableMeta.matchUri( uri );
		//noinspection ConstantConditions
		return match == null ? null : match.meta.insert(
			getContext().getContentResolver()
			, mDBHelper.getWritableDatabase()
			, match
			, uri
			, values
		);
	}

	@Override
	public int delete(
		@NonNull Uri uri
		, String selection
		, String[] selectionArgs
	){
		TableMeta.MatchResult match = TableMeta.matchUri( uri );
		//noinspection ConstantConditions
		return match == null ? 0 : match.meta.delete(
			getContext().getContentResolver()
			, mDBHelper.getWritableDatabase()
			, match
			, uri
			, selection
			, selectionArgs
		);

	}

	@Override
	public int update(
		@NonNull Uri uri
		, ContentValues values
		, String selection
		, String[] selectionArgs
	){
		TableMeta.MatchResult match = TableMeta.matchUri( uri );
		//noinspection ConstantConditions
		return match == null ? 0 : match.meta.update(
			getContext().getContentResolver()
			, mDBHelper.getWritableDatabase()
			, match
			, uri
			, values
			, selection
			, selectionArgs
		);
	}

	public Cursor query(
		@NonNull Uri uri
		, String[] projection
		, String selection
		, String[] selectionArgs
		, String sortOrder
	){
		TableMeta.MatchResult match = TableMeta.matchUri( uri );
		//noinspection ConstantConditions
		return match == null ? null : match.meta.query(
			getContext().getContentResolver()
			, mDBHelper.getWritableDatabase()
			, match
			, uri
			, projection
			, selection
			, selectionArgs
			, sortOrder
		);
	}

}
