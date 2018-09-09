package jp.juggler.fadownloader.table

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.net.Uri
import android.provider.BaseColumns
import jp.juggler.fadownloader.R
import jp.juggler.fadownloader.util.LogTag
import jp.juggler.fadownloader.util.getStringOrNull

class DownloadRecord {
	
	companion object {
		private val log = LogTag("DownloadRecord")
		
		@Suppress("unused")
		const val COL_ID = BaseColumns._ID
		const val COL_TIME = "t"
		const val COL_NAME = "n"
		const val COL_AIR_PATH = "ap"
		const val COL_LOCAL_FILE = "lf"
		const val COL_STATE_CODE = "sc"
		const val COL_STATE_MESSAGE = "sm"
		const val COL_LAP_TIME = "lt"
		const val COL_SIZE = "sz"
		
		val meta : TableMeta = object : TableMeta(DataProvider.AUTHORITY, "dr") {
			
			override fun onDBCreate(db : SQLiteDatabase) {
				db.execSQL(
					"""create table if not exists $table
					($COL_ID INTEGER PRIMARY KEY
					,$COL_TIME integer not null
					,$COL_NAME text not null
					,$COL_AIR_PATH text not null
					,$COL_LOCAL_FILE text
					,$COL_STATE_CODE integer not null
					,$COL_STATE_MESSAGE text not null
					,$COL_LAP_TIME integer not null
					,$COL_SIZE integer not null default 0
					)"""
				)
				db.execSQL(
					"create index if not exists ${table}_time on $table($COL_TIME,$COL_AIR_PATH)"
				)
				db.execSQL(
					"create index if not exists ${table}_state on $table($COL_STATE_CODE,$COL_TIME,$COL_AIR_PATH)"
				)
				db.execSQL(
					"create unique index if not exists ${table}_air_path on $table($COL_AIR_PATH)"
				)
				db.execSQL(
					"create index if not exists ${table}_name on $table($COL_NAME)"
				)
			}
			
			override fun onDBUpgrade(db : SQLiteDatabase, v_old : Int, v_new : Int) {
				if(v_old < 2 && v_new >= 2) {
					onDBCreate(db)
				}
				if(v_old < 3 && v_new >= 3) {
					try {
						db.execSQL("alter table $table add column $COL_SIZE integer not null default 0")
					} catch(ex : Throwable) {
						// 既にカラムが存在する場合、ここを通る
						log.trace(ex,"add column $COL_SIZE")
					}
				}
				
				if(v_old <= 4 && v_new >= 5) {
					// v4はインデックスに間違いがあったので貼り直す必要がある
					try {
						db.execSQL(
							"drop index if exists ${table}_name"
						)
					} catch(ex : Throwable) {
						log.trace(ex,"drop index ${table}_name")
					}
					try {
						db.execSQL(
							"create index if not exists ${table}_name on $table($COL_NAME)"
						)
					} catch(ex : Throwable) {
						log.trace(ex,"create index ${table}_name")
					}
				}
				
			}
		}
		
		fun insert(
			cr : ContentResolver,
			cv : ContentValues,
			name : String,
			air_path : String,
			local_file : String,
			state_code : Int,
			state_message : String,
			lap_time : Long,
			size : Long
		) : Uri? {
			try {
				cv.clear()
				cv.put(COL_TIME, System.currentTimeMillis())
				cv.put(COL_NAME, name)
				cv.put(COL_AIR_PATH, air_path)
				cv.put(COL_LOCAL_FILE, local_file)
				cv.put(COL_STATE_CODE, state_code)
				cv.put(COL_STATE_MESSAGE, state_message)
				cv.put(COL_LAP_TIME, lap_time)
				cv.put(COL_SIZE, size)
				
				return cr.insert(meta.content_uri, cv)
				
			} catch(ex : Throwable) {
				log.trace(ex,"insert failed.")
				return null
			}
		}
		
		const val STATE_COMPLETED = 0
		const val STATE_QUEUED = 1
		const val STATE_LOCAL_FILE_PREPARE_ERROR = 2
		const val STATE_DOWNLOAD_ERROR = 3
		const val STATE_EXIF_MANGLING_ERROR = 4
		const val STATE_CANCELLED = 5
		
		fun formatStateText(context : Context, state_code : Int, state_message : String?) : String =
			when(state_code) {
				STATE_COMPLETED -> context.getString(R.string.download_completed)
				STATE_CANCELLED -> context.getString(R.string.download_cancelled)
				STATE_QUEUED -> context.getString(R.string.queued)
				
				STATE_LOCAL_FILE_PREPARE_ERROR, STATE_DOWNLOAD_ERROR, STATE_EXIF_MANGLING_ERROR ->
					String.format("error: %s", state_message)
				
				else ->
					String.format("(%d)%s", state_code, state_message)
			}
		
		fun getStateCodeColor(code : Int) : Int =
			when(code) {
				STATE_CANCELLED -> Color.BLACK
				STATE_COMPLETED -> Color.BLACK or 0x008000
				STATE_QUEUED -> Color.BLACK or 0x8000cc
				STATE_EXIF_MANGLING_ERROR -> Color.BLACK or 0x800080
				STATE_LOCAL_FILE_PREPARE_ERROR, STATE_DOWNLOAD_ERROR -> Color.BLACK or 0xc00000
				else -> Color.BLACK
			}
	}
	
	var time : Long = 0
	var air_path : String? = null
	var local_file : String? = null // may null
	var state_code : Int = 0
	var state_message : String? = null
	var lap_time : Long = 0
	var size : Long = 0
	
	class ColIdx {
		
		internal var idx_time = - 1
		@Suppress("MemberVisibilityCanBePrivate")
		internal var idx_name : Int = 0
		internal var idx_air_path : Int = 0
		internal var idx_local_file : Int = 0
		internal var idx_state_code : Int = 0
		internal var idx_state_message : Int = 0
		internal var idx_lap_time : Int = 0
		internal var idx_size : Int = 0
		
		internal fun setup(c : Cursor) {
			idx_time = c.getColumnIndex(COL_TIME)
			idx_name = c.getColumnIndex(COL_NAME)
			idx_air_path = c.getColumnIndex(COL_AIR_PATH)
			idx_local_file = c.getColumnIndex(COL_LOCAL_FILE)
			idx_state_code = c.getColumnIndex(COL_STATE_CODE)
			idx_state_message = c.getColumnIndex(COL_STATE_MESSAGE)
			idx_lap_time = c.getColumnIndex(COL_LAP_TIME)
			idx_size = c.getColumnIndex(COL_SIZE)
		}
	}
	
	fun loadFrom(cursor : Cursor, colIdxArg : ColIdx?) {
		val colIdx = colIdxArg ?: ColIdx()
		if(colIdx.idx_time == - 1) colIdx.setup(cursor)
		//
		time = cursor.getLong(colIdx.idx_time)
		air_path = cursor.getString(colIdx.idx_air_path)
		local_file = cursor.getStringOrNull(colIdx.idx_local_file)
		state_code = cursor.getInt(colIdx.idx_state_code)
		state_message = cursor.getStringOrNull(colIdx.idx_state_message)
		lap_time = cursor.getLong(colIdx.idx_lap_time)
		size = cursor.getLong(colIdx.idx_size)
	}
	
}
