package jp.juggler.fadownloader.table

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.BaseColumns

import java.util.ArrayList

abstract class TableMeta {
	
	companion object {
		
		val sUriMatcher = UriMatcher(UriMatcher.NO_MATCH)
		val sUriHandlerList = ArrayList<MatchResult>()
		
		// URIマッチングを行う
		internal fun matchUri(uri : Uri) : MatchResult? {
			val n = sUriMatcher.match(uri)
			return if(n >= 0 && n < sUriHandlerList.size) sUriHandlerList[n] else null
		}
		
		////////////////////////////////////////////////////////
		
		// IDが指定されているかどうかでwhere節の条件を少し変える
		fun selection_with_id(uri : Uri, selection : String?) : String {
			val id = java.lang.Long.parseLong(uri.pathSegments[1])
			return BaseColumns._ID + "=" + id.toString() + if(selection == null) "" else "AND ($selection)"
		}
	}
	
	
	////////////////////////////////////////////////////////
	
	var matcher_idx : Int = 0
	lateinit var table : String
	lateinit var authority : String
	lateinit var content_uri : Uri
	lateinit var mime_type_dir : String
	lateinit var mime_type_item : String
	
	class MatchResult(val meta : TableMeta, val is_item : Boolean)
	
	fun getItemURI(id : Long) : Uri {
		return Uri.withAppendedPath(content_uri, java.lang.Long.toString(id))
	}
	
	fun getIDFromUri(uri : Uri) : Long {
		try {
			val segments = uri.pathSegments
			return java.lang.Long.parseLong(segments[segments.size - 1], 10)
		} catch(ex : Throwable) {
			return - 1
		}
		
	}
	
	fun registerUri(authority : String, table : String) {
		this.authority = authority
		this.matcher_idx = sUriHandlerList.size
		this.table = table
		
		this.authority = authority
		this.content_uri = Uri.parse("content://$authority/$table")
		this.mime_type_dir = "vnd.android.cursor.dir/$authority.$table"
		this.mime_type_item = "vnd.android.cursor.item/$authority.$table"
		
		// uri for group
		sUriHandlerList.add(
			MatchResult(
				this,
				false
			)
		)
		sUriMatcher.addURI(authority, table, matcher_idx)
		
		// uri for element
		sUriHandlerList.add(
			MatchResult(
				this,
				true
			)
		)
		sUriMatcher.addURI(authority, "$table/#", matcher_idx + 1)
	}
	
	abstract fun onDBCreate(db : SQLiteDatabase)
	
	abstract fun onDBUpgrade(db : SQLiteDatabase, v_old : Int, v_new : Int)
	
	fun getType(uri : Uri, match : MatchResult) : String {
		return if(match.is_item) match.meta.mime_type_item else match.meta.mime_type_dir
	}
	
	open fun insert(
		cr : ContentResolver,
		db : SQLiteDatabase,
		match : MatchResult,
		uri : Uri,
		values : ContentValues
	) : Uri? {
		
		if(match.is_item) return null
		val id = db.replaceOrThrow(table, null, values)
		
		// 変更を通知する
		val newUri = ContentUris.withAppendedId(content_uri, id)
		cr.notifyChange(newUri, null)
		
		return newUri
	}
	
	fun delete(
		cr : ContentResolver,
		db : SQLiteDatabase,
		match : MatchResult,
		uri : Uri,
		selection : String?,
		selectionArgs : Array<String>?
	) : Int {
		val row_count : Int
		if(match.is_item) {
			row_count = db.delete(table,
				selection_with_id(uri, selection), selectionArgs)
		} else {
			row_count = db.delete(table, selection, selectionArgs)
		}
		cr.notifyChange(uri, null)
		return row_count
	}
	
	fun update(
		cr : ContentResolver,
		db : SQLiteDatabase,
		match : MatchResult,
		uri : Uri,
		values : ContentValues,
		selection : String?,
		selectionArgs : Array<String>?
	) : Int {
		val row_count : Int
		if(match.is_item) {
			row_count = db.update(table, values,
				selection_with_id(uri, selection), selectionArgs)
			cr.notifyChange(uri, null)
		} else {
			row_count = db.update(table, values, selection, selectionArgs)
			cr.notifyChange(uri, null)
		}
		return row_count
	}
	
	fun query(
		cr : ContentResolver,
		db : SQLiteDatabase,
		match : MatchResult,
		uri : Uri,
		projection : Array<String>?,
		selection : String?,
		selectionArgs : Array<String>?,
		sortOrder : String?
	) : Cursor {
		var selection = selection
		if(match.is_item) selection =
			selection_with_id(uri, selection)
		val cursor = db.query(table, projection, selection, selectionArgs, null, null, sortOrder)
		cursor.setNotificationUri(cr, uri)
		return cursor
	}
	
	
}
