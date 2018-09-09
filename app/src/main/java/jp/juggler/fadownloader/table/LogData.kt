package jp.juggler.fadownloader.table

import android.content.ContentResolver
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.BaseColumns

object LogData {
	
	const val COL_ID = BaseColumns._ID
	const val COL_TIME = "t"
	const val COL_LEVEL = "l"
	const val COL_MESSAGE = "m"
	
	
	val meta : TableMeta = object : TableMeta( DataProvider.AUTHORITY,"log") {
		override fun onDBCreate(db : SQLiteDatabase) {
			db.execSQL(
				"""create table if not exists $table
					($COL_ID INTEGER PRIMARY KEY
					,$COL_TIME integer not null
					,$COL_LEVEL integer not null
					,$COL_MESSAGE text not null
					)"""
			)
			db.execSQL(
				"create index if not exists ${table}_time on $table($COL_TIME,$COL_LEVEL)"
			)
		}
		
		override fun onDBUpgrade(db : SQLiteDatabase, v_old : Int, v_new : Int) {
		
		}
	}
	
	const val LEVEL_ERROR = 100
	const val LEVEL_WARNING = 200
	const val LEVEL_INFO = 300
	const val LEVEL_VERBOSE = 400
	const val LEVEL_DEBUG = 500
	const val LEVEL_HEARTBEAT = 600
	const val LEVEL_FLOOD = 700

	fun getLogLevelString(level : Int) : String {
		return when {
			level >= LEVEL_FLOOD -> "Flood"
			level >= LEVEL_HEARTBEAT -> "HeartBeat"
			level >= LEVEL_DEBUG -> "Debug"
			level >= LEVEL_VERBOSE -> "Verbose"
			level >= LEVEL_INFO -> "Info"
			level >= LEVEL_WARNING -> "Warning"
			else -> "Error"
		}
	}
	
	fun insert(
		cr : ContentResolver,
		cv : ContentValues,
		time : Long,
		level : Int,
		message : String
	) : Uri? {
		return try {
			cv.clear()
			cv.put(COL_TIME, time)
			cv.put(COL_LEVEL, level)
			cv.put(COL_MESSAGE, message)
			cr.insert(meta.content_uri, cv)
		} catch(ex : Throwable) {
			ex.printStackTrace()
			null
		}
		
	}
	
}
