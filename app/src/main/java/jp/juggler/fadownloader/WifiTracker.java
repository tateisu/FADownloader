package jp.juggler.fadownloader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.SystemClock;

import java.util.LinkedList;

public class WifiTracker{

	interface Callback{

		void onConnectionEvent( boolean is_connected ,String cause);
	}

	final LogWriter log;
	final Context context;
	final Callback callback;

	final Handler handler;
	final WifiManager wifiManager;

	public WifiTracker( Context context, LogWriter log, Callback callback ){
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
		handler.post( proc_interval);
	}

	static final int SCAN_INTERVAL = 100000;
	long last_scan_start;
	final LinkedList<Integer> priority_list = new LinkedList<>();
	boolean last_status =false;
	final Runnable proc_interval = new Runnable(){
		@Override public void run(){
			handler.removeCallbacks( proc_interval );
			if( is_dispose || ! force_wifi ) return;
			long next = 3000L;
			try{
				boolean b = keep_ap();
				if(b != last_status){
					callback.onConnectionEvent( true, "Wi-Fi tracker");
				}
				last_status = b;
				next = b ? 30000L : 3000L;
			}catch( Throwable ex ){
				log.e( ex, "connection event handling failed." );
			}
			handler.postDelayed( proc_interval, next );
		}
	};

	final BroadcastReceiver receiver = new BroadcastReceiver(){
		@Override public void onReceive( Context context, Intent intent ){
			try{
				boolean b = keep_ap();
				if(b != last_status){
					callback.onConnectionEvent( keep_ap(), intent.getAction() );
				}
				last_status = b;
			}catch( Throwable ex ){
				log.e( ex, "connection event handling failed." );
			}
		}
	};

	String last_current_status;
	String last_force_status;
	String last_error_status;
	long last_wifi_ap_change;

	@SuppressWarnings( "ConstantConditions" ) boolean keep_ap(){
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

			// Wi-Fiの現在の状態を取得する
			WifiInfo info;
			SupplicantState current_supp_state = null;
			String current_ssid = null;
			try{
				info = wifiManager.getConnectionInfo();
				if( info != null ){
					current_supp_state = info.getSupplicantState();
					String sv = info.getSSID();
					current_ssid = sv == null ? null : sv.replace( "\"", "" );
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				error_status = log.formatError( ex, "getConnectionInfo() failed." );
				return false;
			}

			// 設定済みのAPを列挙する
			int current_network_id = 0;
			WifiConfiguration target_config = null;
			int priority_max = 0;
			try{
				current_status = context.getString( R.string.wifi_no_ap_associated );

				for( WifiConfiguration wc : wifiManager.getConfiguredNetworks() ){
					String ssid = wc.SSID.replace( "\"", "" );

					if( wc.priority > priority_max ){
						priority_max = wc.priority;
					}

					// 目的のAPを覚えておく
					if( target_ssid != null && target_ssid.equals( ssid ) ){
						target_config = wc;
					}

					// 接続中のAPの情報
					if( ssid.equals( current_ssid ) ){
						current_network_id = wc.networkId;
						current_status = context.getString( R.string.wifi_current_connection
							, ssid
							, Integer.toString( wc.networkId )
							, Integer.toString( wc.priority )
							, Utils.getSupplicantStateString( current_supp_state )
						);
						if( ! force_wifi ){
							// AP強制ではないなら、何かアクティブな接続が生きていればOK
							return current_supp_state == SupplicantState.COMPLETED;
						}
					}
				}

				if( ! force_wifi ){
					// AP強制ではない場合、接続中のAPがなければNGを返す
					return false;
				}else if( target_config == null ){
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
				if( p != priority_max ){
					priority_list.add( p );
					if( priority_list.size() > 5 ) priority_list.removeFirst();
					if( priority_list.size() < 5
						|| priority_list.getFirst().intValue() != priority_list.getLast().intValue()
						){
						// まだ上がるか試してみる
						target_config.priority = priority_max + 1;
						wifiManager.updateNetwork( target_config );
						wifiManager.saveConfiguration();
						////頻出するのでログ出さない log.d( R.string.wifi_ap_priority_changed );
					}
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				error_status = log.formatError( ex, "updateNetwork() or saveConfiguration() failed." );
			}

			// 目的のAPが選択されていた場合
			if( current_ssid != null && current_network_id == target_config.networkId ){
				switch( current_supp_state ){
				case COMPLETED:
					// その接続の認証が終わっていたらOK
					return true;
				case ASSOCIATING:
				case ASSOCIATED:
				case AUTHENTICATING:
				case FOUR_WAY_HANDSHAKE:
				case GROUP_HANDSHAKE:
					// 現在のstateが何か作業中なら、余計なことはしないがOKでもない
					return false;
				}
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
			if( now - last_wifi_ap_change >= 5000L ){
				last_wifi_ap_change = now;

				try{
					// 先に既存接続を無効にする
					for( WifiConfiguration wc : wifiManager.getConfiguredNetworks() ){
						if( wc.networkId != target_config.networkId ){
							String ssid = wc.SSID.replace( "\"", "" );
							if( wc.status == WifiConfiguration.Status.CURRENT ){
								log.d( "%sから切断させます", ssid );
								wifiManager.disableNetwork( wc.networkId );
							}else if( wc.status == WifiConfiguration.Status.ENABLED ){
								log.d( "%sへの自動接続を無効化します", ssid );
								wifiManager.disableNetwork( wc.networkId );
							}
						}
					}

					String target_ssid = target_config.SSID.replace( "\"", "" );
					log.d( "%s への接続を試みます", target_ssid );
					wifiManager.enableNetwork( target_config.networkId, true );

					return false;

				}catch( Throwable ex ){
					ex.printStackTrace();
					error_status = log.formatError( ex, "disableNetwork() or enableNetwork() failed." );
				}
			}

			return false;
		}finally{

			if( current_status != null && ! current_status.equals( last_current_status ) ){
				log.d( last_current_status = current_status );
			}

			if( error_status != null && ! error_status.equals( last_error_status ) ){
				log.e( last_error_status = error_status );
			}

			if( force_status != null && ! force_status.equals( last_force_status ) ){
				log.w( last_force_status = force_status );
			}
		}
	}
}
