package jp.juggler.fadownloader;

import android.content.ContentResolver;
import android.content.ContentValues;

public class LogWriter{

	final ContentResolver cr;
	final ContentValues cv = new ContentValues();

	public LogWriter( ContentResolver cr  ){
		this.cr = cr;
	}

	public void dispose(){
	}

	@SuppressWarnings( "unused" )
	public void addLog( int level, String message){
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), level, message );
		}
	}

	@SuppressWarnings( "unused" )
	public void e(String fmt,Object... args){
		if( args.length > 0) fmt = String.format( fmt,args);
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_ERROR, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void w(String fmt,Object... args){
		if( args.length > 0) fmt = String.format( fmt,args);
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_WARNING, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void i(String fmt,Object... args){
		if( args.length > 0) fmt = String.format( fmt,args);
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_INFO, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void v(String fmt,Object... args){
		if( args.length > 0) fmt = String.format( fmt,args);
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_VERBOSE, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void d(String fmt,Object... args){
		if( args.length > 0) fmt = String.format( fmt,args);
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_DEBUG, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void h(String fmt,Object... args){
		if( args.length > 0) fmt = String.format( fmt,args);
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_HEARTBEAT, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void f(String fmt,Object... args){
		if( args.length > 0) fmt = String.format( fmt,args);
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_FLOOD, fmt );
		}
	}

}
