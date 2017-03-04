package jp.juggler.fadownloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;

public class LogWriter{

	final ContentResolver cr;
	final Resources res;
	final ContentValues cv = new ContentValues();

	public LogWriter( Context c  ){
		this.cr = c.getContentResolver();
		this.res = c.getResources();
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



	@SuppressWarnings( "unused" )
	public void e(int string_id,Object... args){
		String fmt = res.getString( string_id,args );
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_ERROR, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void w(int string_id,Object... args){
		String fmt = res.getString( string_id,args );
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_WARNING, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void i(int string_id,Object... args){
		String fmt = res.getString( string_id,args );
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_INFO, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void v(int string_id,Object... args){
		String fmt = res.getString( string_id,args );
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_VERBOSE, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void d(int string_id,Object... args){
		String fmt = res.getString( string_id,args );
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_DEBUG, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void h(int string_id,Object... args){
		String fmt = res.getString( string_id,args );
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_HEARTBEAT, fmt );
		}
	}

	@SuppressWarnings( "unused" )
	public void f(int string_id,Object... args){
		String fmt = res.getString( string_id,args );
		synchronized(cv){
			LogData.insert( cr, cv, System.currentTimeMillis(), LogData.LEVEL_FLOOD, fmt );
		}
	}
}
