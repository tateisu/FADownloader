package jp.juggler.fadownloader;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

public abstract class TableMeta{

	public static class MatchResult{

		public final TableMeta meta;
		public final boolean is_item;

		public MatchResult( TableMeta meta, boolean is_item ){
			this.meta = meta;
			this.is_item = is_item;
		}
	}

	public static final UriMatcher sUriMatcher = new UriMatcher( UriMatcher.NO_MATCH );
	public static final ArrayList<MatchResult> sUriHandlerList = new ArrayList<>();

	// URIマッチングを行う
	static MatchResult matchUri( Uri uri ){
		int n = sUriMatcher.match( uri );
		if( n >= 0 && n < sUriHandlerList.size() ) return sUriHandlerList.get( n );
		return null;
	}

	////////////////////////////////////////////////////////

	// IDが指定されているかどうかでwhere節の条件を少し変える
	public static String selection_with_id( Uri uri, String selection ){
		long id = Long.parseLong( uri.getPathSegments().get( 1 ) );
		return android.provider.BaseColumns._ID + "=" + Long.toString( id ) + ( selection == null ? "" : "AND (" + selection + ")" );
	}

	////////////////////////////////////////////////////////

	public int matcher_idx;
	public String table;
	public String authority;
	public Uri content_uri;
	public String mime_type_dir;
	public String mime_type_item;

	public TableMeta(){
	}

	@SuppressWarnings( "unused" )
	public Uri getItemURI( long id ){
		return Uri.withAppendedPath( content_uri, Long.toString( id ) );
	}

	@SuppressWarnings( "unused" )
	public long getIDFromUri( Uri uri ){
		try{
			List<String> segments = uri.getPathSegments();
			return Long.parseLong( segments.get( segments.size() - 1 ), 10 );
		}catch( Throwable ex ){
			return - 1;
		}
	}

	public void registerUri( String authority, String table ){
		this.authority = authority;
		this.matcher_idx = sUriHandlerList.size();
		this.table = table;

		this.authority = authority;
		this.content_uri = Uri.parse( "content://" + authority + "/" + table );
		this.mime_type_dir = "vnd.android.cursor.dir/" + authority + "." + table;
		this.mime_type_item = "vnd.android.cursor.item/" + authority + "." + table;

		// uri for group
		sUriHandlerList.add( new MatchResult( this, false ) );
		sUriMatcher.addURI( authority, table, matcher_idx );

		// uri for element
		sUriHandlerList.add( new MatchResult( this, true ) );
		sUriMatcher.addURI( authority, table + "/#", matcher_idx + 1 );
	}

	abstract public void onDBCreate( SQLiteDatabase db );

	abstract public void onDBUpgrade( SQLiteDatabase db, int v_old, int v_new );

	@SuppressWarnings( "UnusedParameters" )
	public String getType( Uri uri, MatchResult match ){
		return match.is_item ? match.meta.mime_type_item : match.meta.mime_type_dir;
	}

	@SuppressWarnings( "UnusedParameters" )
	public Uri insert( ContentResolver cr, SQLiteDatabase db, MatchResult match, Uri uri, ContentValues values ){

		if( match.is_item ) return null;
		final long id = db.insertOrThrow( table, null, values );

		// 変更を通知する
		final Uri newUri = ContentUris.withAppendedId( content_uri, id );
		cr.notifyChange( newUri, null );

		return newUri;
	}

	public int delete( ContentResolver cr, SQLiteDatabase db, MatchResult match, Uri uri, String selection, String[] selectionArgs ){
		int row_count;
		if( match.is_item ){
			row_count = db.delete( table, selection_with_id( uri, selection ), selectionArgs );
		}else{
			row_count = db.delete( table, selection, selectionArgs );
		}
		cr.notifyChange( uri, null );
		return row_count;
	}

	public int update( ContentResolver cr, SQLiteDatabase db, MatchResult match, Uri uri, ContentValues values, String selection, String[] selectionArgs ){
		int row_count;
		if( match.is_item ){
			row_count = db.update( table, values, selection_with_id( uri, selection ), selectionArgs );
			cr.notifyChange( uri, null );
		}else{
			row_count = db.update( table, values, selection, selectionArgs );
			cr.notifyChange( uri, null );
		}
		return row_count;
	}

	public Cursor query( ContentResolver cr, SQLiteDatabase db, MatchResult match, Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder ){
		if( match.is_item ) selection = selection_with_id( uri, selection );
		Cursor cursor = db.query( table, projection, selection, selectionArgs, null, null, sortOrder );
		cursor.setNotificationUri( cr, uri );
		return cursor;
	}

}
