package jp.juggler.fadownloader

import android.content.ContentResolver
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.net.Uri

object LogData {
	
	val COL_TIME = "t"
	val COL_LEVEL = "l"
	val COL_MESSAGE = "m"
	
	val LEVEL_ERROR = 100
	val LEVEL_WARNING = 200
	val LEVEL_INFO = 300
	val LEVEL_VERBOSE = 400
	val LEVEL_DEBUG = 500
	val LEVEL_HEARTBEAT = 600
	val LEVEL_FLOOD = 700
	
	val meta : TableMeta = object : TableMeta() {
		override fun onDBCreate(db : SQLiteDatabase) {
			db.execSQL(
				"create table if not exists " + table
					+ "(_id INTEGER PRIMARY KEY"
					+ ",t integer not null"
					+ ",l integer not null"
					+ ",m text not null"
					+ ")"
			)
			db.execSQL(
				"create index if not exists " + table + "_time on " + table
					+ "(t"
					+ ",l"
					+ ")"
			)
		}
		
		override fun onDBUpgrade(db : SQLiteDatabase, v_old : Int, v_new : Int) {}
	}
	
	fun insert(
		cr : ContentResolver,
		cv : ContentValues,
		time : Long,
		level : Int,
		message : String
	) : Uri? {
		try {
			cv.clear()
			cv.put(COL_TIME, time)
			cv.put(COL_LEVEL, level)
			cv.put(COL_MESSAGE, message)
			return cr.insert(meta.content_uri, cv)
		} catch(ex : Throwable) {
			ex.printStackTrace()
			return null
		}
		
	}
	
	fun getLogLevelString(level : Int) : String {
		return if(level >= LogData.LEVEL_FLOOD) {
			"Flood"
		} else if(level >= LogData.LEVEL_HEARTBEAT) {
			"HeartBeat"
		} else if(level >= LogData.LEVEL_DEBUG) {
			"Debug"
		} else if(level >= LogData.LEVEL_VERBOSE) {
			"Verbose"
		} else if(level >= LogData.LEVEL_INFO) {
			"Info"
		} else if(level >= LogData.LEVEL_WARNING) {
			"Warning"
		} else {
			"Error"
		}
	}
}
