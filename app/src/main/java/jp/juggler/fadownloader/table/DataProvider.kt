package jp.juggler.fadownloader.table

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

import config.BuildVariant

@SuppressLint("Registered")
class DataProvider : ContentProvider() {
	
	companion object {
		
		internal const val AUTHORITY = BuildVariant.DATA_PROVIDER_AUTHORITY
		internal const val DB_NAME = "data"

		internal const val DB_SCHEMA_VERSION = 5
		
		private val metaList = arrayOf(LogData.meta, DownloadRecord.meta)
		
		init {
			for(meta in metaList) {
				meta.registerUri()
			}
		}
	}
	
	private class DBHelper1(context : Context) :
		SQLiteOpenHelper(context, DB_NAME, null, DB_SCHEMA_VERSION) {
		
		override fun onCreate(db : SQLiteDatabase) {
			for(meta in metaList) {
				meta.onDBCreate(db)
			}
		}
		
		override fun onUpgrade(db : SQLiteDatabase, v_old : Int, v_new : Int) {
			for(meta in metaList) {
				meta.onDBUpgrade(db, v_old, v_new)
			}
		}
	}
	
	private lateinit var mDBHelper : DBHelper1
	
	// super.getContext()はNullableなのでラップする
	// コンストラクタ以外ではnullになることはないが、常時同じ値ではないはず
	private val contextEx :Context
		get() = super.getContext()!!
	
	override fun onCreate() : Boolean {
		this.mDBHelper = DBHelper1(contextEx)
		return true
	}
	
	// URIを照合して、mime type 文字列を返す
	override fun getType(uri : Uri) : String? {
		val match = TableMeta.matchUri(uri)
		return match?.meta?.getType(match)
	}
	
	override fun insert(
		uri : Uri, values : ContentValues
	) : Uri? {
		val match = TableMeta.matchUri(uri)
		
		return match?.meta?.insert(
			contextEx.contentResolver,
			mDBHelper.writableDatabase,
			match,
			uri,
			values
		)
	}
	
	override fun delete(
		uri : Uri, selection : String?, selectionArgs : Array<String>?
	) : Int {
		val match = TableMeta.matchUri(uri)
		
		return match?.meta?.delete(
			contextEx.contentResolver,
			mDBHelper.writableDatabase,
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
			contextEx.contentResolver,
			mDBHelper.writableDatabase,
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
			contextEx.contentResolver,
			mDBHelper.writableDatabase,
			match,
			uri,
			projection,
			selection,
			selectionArgs,
			sortOrder
		)
	}
	
}
