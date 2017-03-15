package jp.juggler.fadownloader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

public class WifiTracker{

	interface Callback{

		void onConnectionEvent( boolean is_connected, String cause );
	}

	final LogWriter log;
	final Context context;
	final Callback callback;

	final Handler handler;
	final WifiManager wifiManager;
	final ConnectivityManager cm;

	public WifiTracker( Context context, LogWriter log, Callback callback ){
		this.log = log;
		this.context = context;
		this.callback = callback;
		this.handler = new Handler();
		this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService( Context.WIFI_SERVICE );
		this.cm = (ConnectivityManager) context.getApplicationContext().getSystemService( Context.CONNECTIVITY_SERVICE );

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
		handler.post( proc_interval );
	}

	static final int SCAN_INTERVAL = 100000;
	long last_scan_start;
	final LinkedList<Integer> priority_list = new LinkedList<>();
	boolean last_status = false;
	final Runnable proc_interval = new Runnable(){
		@Override public void run(){
			handler.removeCallbacks( proc_interval );
			if( is_dispose || ! force_wifi ) return;
			long next = 3000L;
			try{
				boolean b = keep_ap();
				if( b != last_status ){
					callback.onConnectionEvent( true, "Wi-Fi tracker" );
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
				if( b != last_status ){
					callback.onConnectionEvent( keep_ap(), intent.getAction() );
				}
				last_status = b;
			}catch( Throwable ex ){
				log.e( ex, "connection event handling failed." );
			}
		}
	};

	static class NetworkStatus{

		boolean is_active;
		String type_name;
		String sub_name;
		String strWifiStatus;

	}

	private String buildCurrentStatus( ArrayList<NetworkStatus> ns_list ){
		Collections.sort( ns_list, new Comparator<NetworkStatus>(){
			@Override public int compare( NetworkStatus a, NetworkStatus b ){
				if( a.is_active && ! b.is_active ) return - 1;
				if( ! a.is_active && b.is_active ) return 1;
				return a.type_name.compareTo( b.type_name );
			}
		} );
		StringBuilder sb = new StringBuilder();
		for( NetworkStatus ns : ns_list ){
			if( sb.length() > 0 ) sb.append( " / " );
			if( ns.is_active ) sb.append("(Active)");
			if( ns.strWifiStatus != null ){
				sb.append( "Wi-Fi(" ).append( ns.strWifiStatus ).append(')');
			}else{
				sb.append( ns.type_name );
				if( ! TextUtils.isEmpty( ns.sub_name ) ){
					sb.append( '(' ).append( ns.sub_name ).append( ')' );
				}
			}
		}
		return sb.toString();
	}

	public void getStatus( StringBuilder sb ){
		sb.append( last_current_status );
	}

	String last_current_status;
	String last_force_status;
	String last_error_status;
	long last_wifi_ap_change;

	@SuppressWarnings( "ConstantConditions" ) boolean keep_ap(){
		if( is_dispose ) return false;

		ArrayList<NetworkStatus> ns_list = new ArrayList<>();
		String force_status = null;
		String error_status = null;
		try{
			NetworkStatus wifi_status = null;
			boolean other_active = false;
			String active_name = null;
			if( Build.VERSION.SDK_INT >= 23 ){
				Network n = cm.getActiveNetwork();
				if( n != null ){
					NetworkInfo ni = cm.getNetworkInfo( n );
					if( ni != null ){
						active_name = ni.getTypeName();
					}
				}
			}else{
				NetworkInfo ni = cm.getActiveNetworkInfo();
				if( ni != null ){
					active_name = ni.getTypeName();
				}
			}

			NetworkInfo[] ni_list;
			if( Build.VERSION.SDK_INT >= 21 ){
				Network[] src_list = cm.getAllNetworks();
				ni_list = new NetworkInfo[ src_list == null ? 0 : src_list.length ];
				for( int i = 0, ie = ni_list.length ; i < ie ; ++ i ){
					ni_list[ i ] = cm.getNetworkInfo( src_list[ i ] );
				}
			}else{
				ni_list = cm.getAllNetworkInfo();
			}
			for( NetworkInfo ni : ni_list ){
				boolean is_wifi = ( ni.getType() == ConnectivityManager.TYPE_WIFI );
				if( ! is_wifi && ! ni.isConnected() ) continue;
				NetworkStatus ns = new NetworkStatus();
				ns_list.add( ns );
				if( is_wifi ) wifi_status = ns;
				ns.type_name = ni.getTypeName();
				ns.sub_name = ni.getSubtypeName();

				if( active_name != null && active_name.equals( ns.type_name ) ){
					ns.is_active = true;
					if( !is_wifi) other_active = true;
				}
			}

			if( wifi_status == null ){
				wifi_status = new NetworkStatus();
				ns_list.add( wifi_status );
				wifi_status.type_name = "WIFI";
			}

			// Wi-Fiが無効なら有効にする
			try{
				wifi_status.strWifiStatus = "?";
				if( ! wifiManager.isWifiEnabled() ){
					wifi_status.strWifiStatus = context.getString( R.string.not_enabled );
					if( force_wifi ) wifiManager.setWifiEnabled( true );
					return false;
				}
			}catch( Throwable ex ){
				ex.printStackTrace();
				error_status = LogWriter.formatError( ex, "setWifiEnabled() failed." );
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
				error_status = LogWriter.formatError( ex, "getConnectionInfo() failed." );
				return false;
			}

			// 設定済みのAPを列挙する
			int current_network_id = 0;
			WifiConfiguration target_config = null;
			int priority_max = 0;
			try{
				wifi_status.strWifiStatus = context.getString( R.string.no_ap_associated );

				List<WifiConfiguration> wc_list = wifiManager.getConfiguredNetworks();
				if( wc_list == null){
					// getConfiguredNetworks() はたまにnullを返す
					return false;
				}else{
					for( WifiConfiguration wc : wc_list ){
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
							//
							String strState = ( current_supp_state == null ? "?" : current_supp_state.toString() );
							strState = Utils.toCamelCase( strState );
							if( "Completed".equals( strState ) ) strState = "Connected";
							wifi_status.strWifiStatus = ssid + "," + strState;
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
				}

			}catch( Throwable ex ){
				ex.printStackTrace();
				error_status = LogWriter.formatError( ex, "getConfiguredNetworks() failed." );
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
				error_status = LogWriter.formatError( ex, "updateNetwork() or saveConfiguration() failed." );
			}

			// 目的のAPが選択されていた場合
			if( current_ssid != null && current_network_id == target_config.networkId ){
				switch( current_supp_state ){
				case COMPLETED:
					// その接続の認証が終わっていて、他の種類の接続がActiveでなければOK
					return ! other_active;
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
				error_status = LogWriter.formatError( ex, "getScanResults() failed." );
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
					error_status = LogWriter.formatError( ex, "startScan() failed." );
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
					error_status = LogWriter.formatError( ex, "disableNetwork() or enableNetwork() failed." );
				}
			}

			return false;
		}finally{
			String current_status = buildCurrentStatus( ns_list );
			if( current_status != null && ! current_status.equals( last_current_status ) ){
				last_current_status = current_status;
				log.d( context.getString( R.string.network_status, current_status ) );
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
