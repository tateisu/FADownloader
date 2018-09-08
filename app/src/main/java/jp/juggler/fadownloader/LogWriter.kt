package jp.juggler.fadownloader

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.res.Resources

class LogWriter(c : Context) {
	
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
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_ERROR, fmt)
		}
	}
	
	fun w(fmtArg : String, vararg args : Any?) {
		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_WARNING, fmt)
		}
	}
	
	fun i(fmtArg : String, vararg args : Any?) {
		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_INFO, fmt)
		}
	}
	
	fun v(fmtArg : String, vararg args : Any?) {
		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_VERBOSE, fmt)
		}
	}
	
	fun d(fmtArg : String, vararg args : Any?) {
		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_DEBUG, fmt)
		}
	}
	
//	fun h(fmtArg : String, vararg args : Any?) {
//		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
//		synchronized(cv) {
//			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_HEARTBEAT, fmt)
//		}
//	}
	
	fun f(fmtArg : String, vararg args : Any?) {
		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_FLOOD, fmt)
		}
	}
	
	fun e(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_ERROR, fmt)
		}
	}
	
	fun w(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_WARNING, fmt)
		}
	}
	
	fun i(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_INFO, fmt)
		}
	}
	
	fun v(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_VERBOSE, fmt)
		}
	}
	
	fun d(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_DEBUG, fmt)
		}
	}
	
	fun h(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_HEARTBEAT, fmt)
		}
	}
	
	fun f(string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		synchronized(cv) {
			LogData.insert(cr, cv, System.currentTimeMillis(), LogData.LEVEL_FLOOD, fmt)
		}
	}
	
	fun e(ex : Throwable, fmtArg : String, vararg args : Any?) {
		val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
		synchronized(cv) {
			LogData.insert(
				cr,
				cv,
				System.currentTimeMillis(),
				LogData.LEVEL_ERROR,
				fmt + String.format(":%s %s", ex.javaClass.simpleName, ex.message)
			)
		}
	}
	
	fun e(ex : Throwable, string_id : Int, vararg args : Any?) {
		val fmt = res.getString(string_id, *args)
		synchronized(cv) {
			LogData.insert(
				cr,
				cv,
				System.currentTimeMillis(),
				LogData.LEVEL_ERROR,
				fmt + String.format(":%s %s", ex.javaClass.simpleName, ex.message)
			)
		}
	}
	
	companion object {
		
		fun formatError(ex : Throwable, fmtArg : String, vararg args : Any?) : String {
			val fmt  = if( args.isEmpty() ) fmtArg else String.format(fmtArg, *args)
			return fmt + String.format(" :%s %s", ex.javaClass.simpleName, ex.message)
		}
		
		fun formatError(
			ex : Throwable,
			resources : Resources,
			string_id : Int,
			vararg args : Any?
		) : String {
			return resources.getString(string_id, *args) + String.format(
				" :%s %s",
				ex.javaClass.simpleName,
				ex.message
			)
		}
	}
	
}
