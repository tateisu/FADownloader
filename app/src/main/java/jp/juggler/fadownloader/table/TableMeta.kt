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

abstract class TableMeta(
	private val authority : String,
	val table : String
) {
	
	companion object {
		
		val sUriMatcher = UriMatcher(UriMatcher.NO_MATCH)
		val sUriHandlerList = ArrayList<MatchResult>()
		
		// URIマッチングを行う
		internal fun matchUri(uri : Uri) : MatchResult? {
			val n = sUriMatcher.match(uri)
			return if(n >= 0 && n < sUriHandlerList.size) sUriHandlerList[n] else null
		}
		
		// IDが指定されているかどうかでwhere節の条件を少し変える
		fun selection_with_id(uri : Uri, selection : String?) : String {
			val id = java.lang.Long.parseLong(uri.pathSegments[1])
			return BaseColumns._ID + "=" + id.toString() + if(selection == null) "" else "AND ($selection)"
		}
	}
	
	class MatchResult(val meta : TableMeta, val is_item : Boolean)
	
	////////////////////////////////////////////////////////
	
	val content_uri : Uri = Uri.parse("content://$authority/$table")
	private val mime_type_dir : String = "vnd.android.cursor.dir/$authority.$table"
	private val mime_type_item : String = "vnd.android.cursor.item/$authority.$table"
	
	private var matcher_idx : Int = 0

	fun registerUri() {
		this.matcher_idx = sUriHandlerList.size
		
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
	
	//	fun getItemURI(id : Long) : Uri {
	//		return Uri.withAppendedPath(content_uri, java.lang.Long.toString(id))
	//	}
	//
	//	fun getIDFromUri(uri : Uri) : Long {
	//		return try {
	//			val segments = uri.pathSegments
	//			java.lang.Long.parseLong(segments[segments.size - 1], 10)
	//		} catch(ex : Throwable) {
	//			- 1
	//		}
	//	}
	
	fun getType(match : MatchResult) : String {
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
		val row_count = if(match.is_item) {
			db.delete(table, selection_with_id(uri, selection), selectionArgs)
		} else {
			db.delete(table, selection, selectionArgs)
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
		val row_count = if(match.is_item) {
			db.update(table, values, selection_with_id(uri, selection), selectionArgs)
		} else {
			db.update(table, values, selection, selectionArgs)
		}
		cr.notifyChange(uri, null)
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
		val cursor = db.query(
			table,
			projection,
			if(match.is_item) {
				selection_with_id(uri, selection)
			} else {
				selection
			},
			selectionArgs,
			null,
			null,
			sortOrder
		)
		cursor.setNotificationUri(cr, uri)
		return cursor
	}
	
}
