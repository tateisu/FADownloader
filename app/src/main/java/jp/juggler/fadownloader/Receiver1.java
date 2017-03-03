package jp.juggler.fadownloader;

import android.content.Context;
import android.content.Intent;
import android.support.v4.content.WakefulBroadcastReceiver;

public class Receiver1 extends WakefulBroadcastReceiver{

	public static final String ACTION_ALARM = "alarm";

	@Override public void onReceive( Context context, Intent broadcast_intent ){
		try{

			String action = broadcast_intent.getAction();

			if( Intent.ACTION_BOOT_COMPLETED.equals( action ) ){
				int last_mode = Pref.pref( context ).getInt( Pref.LAST_MODE, Pref.LAST_MODE_STOP );
				if( last_mode != Pref.LAST_MODE_REPEAT ){
					// 繰り返しモード以外では起動時の常駐は行わない
					return;
				}
			}else if( ACTION_ALARM.equals( action ) ){
				int last_mode = Pref.pref( context ).getInt( Pref.LAST_MODE, Pref.LAST_MODE_STOP );
				if( last_mode == Pref.LAST_MODE_STOP ){
					// 停止ボタンを押した後はアラームによる起動は行わない
					return;
				}
			}

			Intent service_intent = new Intent( context, DownloadService.class );
			service_intent.setAction( DownloadService.ACTION_BROADCAST_RECEIVED );
			service_intent.putExtra( DownloadService.EXTRA_BROADCAST_INTENT, broadcast_intent );
			startWakefulService( context, service_intent );
		}catch( Throwable ex ){
			ex.printStackTrace();
		}
	}

}
