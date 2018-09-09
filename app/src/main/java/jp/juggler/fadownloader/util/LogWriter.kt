package jp.juggler.fadownloader.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.res.Resources
import android.util.Log
import jp.juggler.fadownloader.table.LogData

class LogWriter(c : Context) {
	companion object {
		private const val TAG = LogTag.TAG
	}
	
	private val cr : ContentResolver = c.contentResolver
	internal val res : Resources = c.resources
	private val cv = ContentValues()
	
	fun dispose() {}
	
//	fun addLog(level : Int, message : String) {
//		synchronized(cv) {
//			LogData.insert(cr, cv, System.currentTimeMillis(), level, message)
//		}
//	}
	
	fun e(fmtArg : String, vararg args : Any?) {
		val fmt = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		Log.e(TAG,fmt)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_ERROR, fmt)
		}
	}
	
	fun w(fmtArg : String, vararg args : Any?) {
		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		Log.w(TAG,fmt)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_WARNING, fmt)
		}
	}
	
	fun i(fmtArg : String, vararg args : Any?) {
		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		Log.i(TAG,fmt)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_INFO, fmt)
		}
	}
	
	fun v(fmtArg : String, vararg args : Any?) {
		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		Log.v(TAG,fmt)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_VERBOSE, fmt)
		}
	}
	
	fun d(fmtArg : String, vararg args : Any?) {
		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		Log.d(TAG,fmt)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_DEBUG, fmt)
		}
	}
	
	
	fun e(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		Log.e(TAG,fmt)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_ERROR, fmt)
		}
	}
	
	fun w(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		Log.d(TAG,fmt)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_WARNING, fmt)
		}
	}
	
	fun i(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		Log.i(TAG,fmt)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_INFO, fmt)
		}
	}
	
	fun v(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		Log.v(TAG,fmt)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_VERBOSE, fmt)
		}
	}
	
	fun d(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		Log.d(TAG,fmt)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_DEBUG, fmt)
		}
	}
	
	
	fun e(ex : Throwable, fmtArg : String, vararg args : Any?) {
		val text = ex.withCaption(fmtArg,*args)
		Log.e(TAG,text)
		synchronized(cv) {
			LogData.insert(
				cr,
				cv,
				System.currentTimeMillis(),
				LogData.LEVEL_ERROR,
				text
			)
		}
	}
	
	fun e(ex : Throwable, string_id : Int, vararg args : Any?) {
		val text = ex.withCaption(res,string_id,*args)
		Log.e(TAG,text)
		synchronized(cv) {
			LogData.insert(
				cr,
				cv,
				System.currentTimeMillis(),
				LogData.LEVEL_ERROR,
				text
			)
		}
	}
	
	fun trace(ex:Throwable,caption:String="?"){
		Log.e(TAG,caption,ex)
	}
	
}
