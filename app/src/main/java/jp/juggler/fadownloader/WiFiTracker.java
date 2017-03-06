package jp.juggler.fadownloader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;

import java.util.LinkedList;

public class WifiTracker{

	interface Callback{
		void onConnectionEvent(boolean is_connected);
	}

	final LogWriter log;
	final Context context;
	final Callback callback;

	final Handler handler;
	final WifiManager wifiManager;

	public WifiTracker( Context context, LogWriter log ,Callback callback){
		this.log = log;
		this.context = context;
		this.callback = callback;
		this.handler = new Handler();
		this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService( Context.WIFI_SERVICE );

		context.registerReceiver( receiver, new IntentFilter( WifiManager.SCAN_RESULTS_AVAILABLE_ACTION ) );
		context.registerReceiver( receiver, new IntentFilter( ConnectivityManager.CONNECTIVITY_ACTION ) );
	}

	boolean is_dispose = false;

	public void dispose(){
		is_dispose = true;
		handler.removeCallbacks( proc_interval );
		context.unregisterReceiver( receiver );
	}

	boolean force_wifi;
	String target_ssid;

	public void updateSetting( boolean force_wifi, String ssid ){
		this.force_wifi = force_wifi;
		this.target_ssid = ssid;
		proc_interval.run();
	}

	static final int SCAN_INTERVAL = 100000;
	long last_scan_start;
	final LinkedList<Integer> priority_list = new LinkedList<>();

	final Runnable proc_interval = new Runnable(){
		@Override public void run(){
			if( is_dispose || ! force_wifi ) return;
			try{
				keep_ap();
			}catch(Throwable ex){
				log.e(ex,"connection event handling failed.");
			}
			handler.postDelayed( proc_interval, 30000L );
		}
	};

	final BroadcastReceiver receiver = new BroadcastReceiver(){
		@Override public void onReceive( Context context, Intent intent ){
			try{
				callback.onConnectionEvent( keep_ap() );
			}catch(Throwable ex){
				log.e(ex,"connection event handling failed.");
			}
		}
	};

	String last_current_status;
	String last_force_status;
	String last_error_status;
	long last_wifi_ap_change ;

	boolean keep_ap(){
		if( is_dispose ) return false;

		String current_status = null;
		String force_status = null;
		String error_status = null;
		try{
			// Wi-Fiが無効なら有効にする
			try{
				current_status = context.getString( R.string.wifi_status_unknown );
				if( ! wifiManager.isWifiEnabled() ){
					current_status = context.getString( R.string.wifi_not_enabled );
					if( force_wifi ) wifiManager.setWifiEnabled( true );
					return false;
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				error_status = log.formatError( ex, "setWifiEnabled() failed." );
				return false;
			}

			WifiConfiguration target_config = null;

			boolean current_connection_found = false;

			try{
				// 設定済みのAPを列挙
				for( WifiConfiguration wc : wifiManager.getConfiguredNetworks() ){
					String ssid = wc.SSID.replace( "\"", "" );

					// 目的のAPを覚えておく
					if( target_ssid != null && target_ssid.equals( ssid ) ){
						target_config = wc;
					}

					// 接続中のAPを調べる
					if( wc.status == WifiConfiguration.Status.CURRENT ){
						current_connection_found = true;
						current_status = context.getString( R.string.wifi_current_connection
							, ssid
							, wc.networkId
							, wc.priority
						);
					}
				}
				if( ! current_connection_found ){
					current_status = context.getString( R.string.wifi_not_connected );
				}

				if( ! force_wifi ){
					// AP強制ではないなら、何かアクティブな接続があるかどうかを返す
					return current_connection_found;
				}else if( target_config == null ){
					// AP強制の場合、接続設定にSSIDがなければNG
					force_status = context.getString( R.string.wifi_target_ssid_not_found, target_ssid );
					return false;
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				error_status = log.formatError( ex, "getConfiguredNetworks() failed." );
				return false;
			}

			try{
				// priority の変更
				int p = target_config.priority;
				priority_list.add( p );
				if( priority_list.size() > 5 ) priority_list.removeFirst();
				if( priority_list.size() < 5
					|| priority_list.getFirst().intValue() != priority_list.getLast().intValue()
					){
					// まだ上がるか試してみる
					target_config.priority = p * 2;
					wifiManager.updateNetwork( target_config );
					wifiManager.saveConfiguration();
					////頻出するのでログ出さない log.d( R.string.wifi_ap_priority_changed );
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				error_status = log.formatError( ex, "updateNetwork() or saveConfiguration() failed." );
			}

			// 目的のAPに既に接続済み
			if( target_config.status == WifiConfiguration.Status.CURRENT ){
				return true;
			}

			// スキャン範囲内に目的のSSIDがあるか？
			boolean found_in_scan = false;
			try{
				for( ScanResult result : wifiManager.getScanResults() ){
					if( target_ssid != null && target_ssid.equals( result.SSID.replace( "\"", "" ) ) ){
						found_in_scan = true;
						break;
					}
				}

			}catch( Throwable ex ){
				ex.printStackTrace();
				error_status = log.formatError( ex, "getScanResults() failed." );
				return false;
			}

			// スキャン範囲内にない場合、定期的にスキャン開始
			if( ! found_in_scan ){
				force_status = context.getString( R.string.wifi_target_ssid_not_scanned, target_ssid );

				// 定期的にスキャン開始
				try{
					long now = SystemClock.elapsedRealtime();
					if( now - last_scan_start >= SCAN_INTERVAL ){
						last_scan_start = now;
						wifiManager.startScan();
						log.d( R.string.wifi_scan_start );
					}
				}catch( Throwable ex ){
					ex.printStackTrace();
					error_status = log.formatError( ex, "startScan() failed." );
				}

				return false;
			}

			long now = SystemClock.elapsedRealtime();
			if( now- last_wifi_ap_change >= 5000L ){
				last_wifi_ap_change = now;

				try{
					// 先に既存接続を無効にする
					for( WifiConfiguration wc : wifiManager.getConfiguredNetworks() ){
						if( wc.networkId != target_config.networkId ){
							wifiManager.disableNetwork( wc.networkId );
						}
					}
					// 目的のAPに接続する
					wifiManager.enableNetwork( target_config.networkId, true );
					log.i( R.string.wifi_ap_force_changed );

				}catch( Throwable ex ){
					ex.printStackTrace();
					error_status = log.formatError( ex, "disableNetwork() or enableNetwork() failed." );
				}
			}

			return false;
		}finally{
			if( current_status != null && ! current_status.equals( last_current_status ) ){
				log.i( last_current_status = current_status );
			}
			if( force_status != null && ! force_status.equals( last_force_status ) ){
				log.w( last_force_status = force_status );
			}
			if( error_status != null && ! error_status.equals( last_error_status ) ){
				log.e( last_error_status = error_status );
			}
		}
	}
}
