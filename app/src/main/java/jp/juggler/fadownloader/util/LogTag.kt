package jp.juggler.fadownloader.util

import android.util.Log

class LogTag(category:String){
	companion object {
		const val TAG = "FADownloader"
		
		fun format(fmt:String,args:Array<out Any?>) = if( args.isEmpty() ){
			fmt
		} else{
			String.format(fmt, *args)
		}
	}
	
	private val tag = "$TAG:$category"
	
	////////////////////////
	
	fun e(fmt:String,vararg args:Any?)=Log.e(tag,format(fmt,args))
	
	fun w(fmt:String,vararg args:Any?)=Log.w(tag,format(fmt,args))
	
	fun i(fmt:String,vararg args:Any?)=Log.i(tag,format(fmt,args))

	fun d(fmt:String,vararg args:Any?)=Log.d(tag,format(fmt,args))

	fun v(fmt:String,vararg args:Any?)=Log.v(tag,format(fmt,args))

	////////////////////////

	fun e(ex:Throwable,fmt:String,vararg args:Any?)=Log.e(tag,ex.withCaption(fmt,*args))

	fun w(ex:Throwable,fmt:String,vararg args:Any?)=Log.w(tag,ex.withCaption(fmt,*args))
	
	////////////////////////
	
	fun trace(ex:Throwable,fmt:String,vararg args:Any?){
		Log.e(tag,format(fmt,args),ex)
	}
}
