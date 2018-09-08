package jp.juggler.fadownloader

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

import config.BuildVariant

class DataProvider : ContentProvider() {
	
	
	companion object {
		
		internal val AUTHORITY = BuildVariant.DATA_PROVIDER_AUTHORITY
		
		internal val DB_NAME = "data"
		internal val DB_SCHEMA_VERSION = 4
		
		init {
			LogData.meta.registerUri(AUTHORITY, "log")
			DownloadRecord.meta.registerUri(AUTHORITY, "dr")
		}
	}
	
	
	private var mDBHelper : DBHelper1? = null
	
	internal class DBHelper1(context : Context) :
		SQLiteOpenHelper(context, DB_NAME, null, DB_SCHEMA_VERSION) {
		
		override fun onCreate(db : SQLiteDatabase) {
			LogData.meta.onDBCreate(db)
			DownloadRecord.meta.onDBCreate(db)
		}
		
		override fun onUpgrade(db : SQLiteDatabase, v_old : Int, v_new : Int) {
			LogData.meta.onDBUpgrade(db, v_old, v_new)
			DownloadRecord.meta.onDBUpgrade(db, v_old, v_new)
		}
	}
	
	override fun onCreate() : Boolean {
		mDBHelper = DBHelper1(context)
		return true
	}
	
	// URIを照合して、mime type 文字列を返す
	override fun getType(uri : Uri) : String? {
		val match = TableMeta.matchUri(uri)
		return match?.meta?.getType(uri, match)
		
	}
	
	override fun insert(
		uri : Uri, values : ContentValues
	) : Uri? {
		val match = TableMeta.matchUri(uri)
		
		return match?.meta?.insert(
			context !!.contentResolver, mDBHelper !!.writableDatabase, match, uri, values
		)
	}
	
	override fun delete(
		uri : Uri, selection : String?, selectionArgs : Array<String>?
	) : Int {
		val match = TableMeta.matchUri(uri)
		
		return match?.meta?.delete(
			context !!.contentResolver,
			mDBHelper !!.writableDatabase,
			match,
			uri,
			selection,
			selectionArgs
		) ?: 0
		
	}
	
	override fun update(
		uri : Uri, values : ContentValues, selection : String?, selectionArgs : Array<String>?
	) : Int {
		val match = TableMeta.matchUri(uri)
		
		return match?.meta?.update(
			context !!.contentResolver,
			mDBHelper !!.writableDatabase,
			match,
			uri,
			values,
			selection,
			selectionArgs
		) ?: 0
	}
	
	override fun query(
		uri : Uri,
		projection : Array<String>?,
		selection : String?,
		selectionArgs : Array<String>?,
		sortOrder : String?
	) : Cursor? {
		val match = TableMeta.matchUri(uri)
		
		return match?.meta?.query(
			context !!.contentResolver,
			mDBHelper !!.writableDatabase,
			match,
			uri,
			projection,
			selection,
			selectionArgs,
			sortOrder
		)
	}

}
