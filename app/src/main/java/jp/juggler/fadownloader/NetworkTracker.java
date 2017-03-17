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

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class NetworkTracker{

	interface Callback{

		void onConnectionEvent( boolean is_connected, String cause );
	}

	final LogWriter log;
	final Context context;
	final Callback callback;

	final Handler handler;
	final WifiManager wifiManager;
	final ConnectivityManager cm;

	Worker worker;

	public NetworkTracker( Context context, LogWriter log, Callback callback ){
		this.log = log;
		this.context = context;
		this.callback = callback;
		this.handler = new Handler();
		this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService( Context.WIFI_SERVICE );
		this.cm = (ConnectivityManager) context.getApplicationContext().getSystemService( Context.CONNECTIVITY_SERVICE );

		context.registerReceiver( receiver, new IntentFilter( WifiManager.SCAN_RESULTS_AVAILABLE_ACTION ) );
		context.registerReceiver( receiver, new IntentFilter( ConnectivityManager.CONNECTIVITY_ACTION ) );
		worker = new Worker();
		worker.start();
	}

	volatile boolean is_dispose = false;

	public void dispose(){
		is_dispose = true;
		context.unregisterReceiver( receiver );
		if( worker != null ){
			worker.cancel( "disposed" );
			worker = null;
		}
	}

	final BroadcastReceiver receiver = new BroadcastReceiver(){
		@Override public void onReceive( Context context, Intent intent ){
			if( is_dispose ) return;
			if( worker != null ) worker.notifyEx();
		}
	};

	volatile boolean force_wifi;
	volatile String target_ssid;
	volatile int target_type; // カードはAPモードではなくSTAモードもしくはインターネット同時接続もーどで動作している
	volatile String target_url;

	public void updateSetting( boolean force_wifi, String ssid, int target_type, String target_url ){
		if( is_dispose ) return;
		this.force_wifi = force_wifi;
		this.target_ssid = ssid;
		this.target_type = target_type;
		this.target_url = target_url;
		if( worker != null ) worker.notifyEx();
	}

	////////////////////////////////////////////////////////////////////////

	static String readStringFile( String path ){
		try{
			FileInputStream fis = new FileInputStream( new File( path ) );
			try{
				ByteArrayOutputStream bao = new ByteArrayOutputStream();
				IOUtils.copy( fis, bao );
				return Utils.decodeUTF8( bao.toByteArray() );
			}finally{
				try{
					fis.close();
				}catch( Throwable ignored ){

				}
			}
		}catch( Throwable ex ){
			ex.printStackTrace();
		}
		return null;
	}

	static class NetworkStatus{

		boolean is_active;
		String type_name;
		String sub_name;
		String strWifiStatus;
	}

	static class NetworkStateList extends ArrayList<NetworkStatus>{

		NetworkStatus wifi_status = null;
		boolean other_active = false;

		public void eachNetworkInfo( boolean is_active, NetworkInfo ni ){
			boolean is_wifi = ( ni.getType() == ConnectivityManager.TYPE_WIFI );
			if( ! is_wifi && ! ni.isConnected() ) return;
			NetworkStatus ns = new NetworkStatus();
			this.add( ns );
			if( is_wifi ) wifi_status = ns;
			ns.type_name = ni.getTypeName();
			ns.sub_name = ni.getSubtypeName();

			if( is_active ){
				ns.is_active = true;
				if( ! is_wifi ) other_active = true;
			}
		}

		public void afterAllNetwork(){
			if( wifi_status == null ){
				wifi_status = new NetworkStatus();
				this.add( wifi_status );
				wifi_status.type_name = "WIFI";
			}
		}
	}

	static String buildCurrentStatus( ArrayList<NetworkStatus> ns_list ){
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
			if( ns.is_active ) sb.append( "(Active)" );
			if( ns.strWifiStatus != null ){
				sb.append( "Wi-Fi(" ).append( ns.strWifiStatus ).append( ')' );
			}else{
				sb.append( ns.type_name );
				if( ! TextUtils.isEmpty( ns.sub_name ) ){
					sb.append( '(' ).append( ns.sub_name ).append( ')' );
				}
			}
		}
		return sb.toString();
	}

	static final Pattern reIPAddr = Pattern.compile( "(\\d+\\.\\d+\\.\\d+\\.\\d+)" );
	static final Pattern reArp = Pattern.compile( "(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s*(0x\\d+)\\s*(0x\\d+)\\s*([0-9A-Fa-f:]+)" );

	////////////////////////////////////////////////////////////////////////

	final AtomicBoolean last_result = new AtomicBoolean();
	final AtomicReference<String> last_flash_air_url = new AtomicReference<>();
	final AtomicReference<String> last_current_status = new AtomicReference<>();

	public void getStatus( StringBuilder sb ){
		sb.append( last_current_status );
	}

	class Worker extends WorkerBase{

		public boolean cancel( String reason ){
			boolean rv = super.cancel( reason );
			try{
				this.interrupt();
			}catch( Throwable ignored ){
			}
			return rv;
		}

		boolean isWifiAPEnabled(){
			try{
				Boolean b = (Boolean)wifiManager.getClass().getMethod( "isWifiApEnabled" ).invoke( wifiManager );
				if(b!=null) return b;
			}catch(Throwable ex){
				ex.printStackTrace(  );
			}
			return false;
		}

		String getWiFiAPAddress(){
			try{
				Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
				while( en.hasMoreElements() ){
					NetworkInterface ni = en.nextElement();
					try{
						if( ! ni.isUp() ) continue;
						if( ni.isLoopback() ) continue;
						if( ni.isVirtual() ) continue;
						if( ni.isPointToPoint() ) continue;
						if( ni.getHardwareAddress() == null ) continue;
						Enumeration<InetAddress> eip = ni.getInetAddresses();
						while( eip.hasMoreElements() ){
							InetAddress addr = eip.nextElement();
							if( addr.getAddress().length == 4 ){
								return addr.getHostAddress().replaceAll( "[^\\d\\.]+", "" );
							}
						}
					}catch( SocketException ex ){
						ex.printStackTrace();
					}
				}
			}catch( SocketException ex ){
				ex.printStackTrace();
			}
			return null;
		}

		void sprayUDPPacket( String nw_addr, String ip_base ){
			long start = SystemClock.elapsedRealtime();


			try{
				byte[] data = new byte[ 1 ];
				int port = 80;
				DatagramSocket socket = new DatagramSocket();
				for( int n = 2 ; n <= 254 ; ++ n ){
					String try_ip = ip_base + n;
					if( try_ip.equals( nw_addr ) ) continue;
					try{

						DatagramPacket packet = new DatagramPacket(
							data, data.length
							, InetAddress.getByName( try_ip )
							, port );

						socket.send( packet );
					}catch( Throwable ex ){
						ex.printStackTrace();
					}
				}
				socket.close();
			}catch( Throwable ex ){
				ex.printStackTrace();
			}
			log.d( "sent UDP packet to '%s*' time=%s", ip_base, Utils.formatTimeDuration( SystemClock.elapsedRealtime() - start ) );
		}

		boolean checkFlashAirUrl( String check_url ){

			log.h( "checkFlashAirUrl %s", check_url );

			final String test_url = check_url + "command.cgi?op=108";
			int rcode;

			URL urlObject;
			try{
				urlObject = new URL( test_url );
			}catch( MalformedURLException ex ){
				ex.printStackTrace();
				return false;
			}

			HttpURLConnection conn;
			try{
				conn = (HttpURLConnection) urlObject.openConnection();
			}catch( IOException ex ){
				ex.printStackTrace();
				return false;
			}

			try{
				conn.setDoInput( true );
				conn.setConnectTimeout( 5000 );
				conn.setReadTimeout( 5000 );
				conn.setDoOutput( false );
				conn.connect();
			}catch( IOException ignored ){
				return false;
			}

			try{
				rcode = conn.getResponseCode();
			}catch( IOException ex ){
				ex.printStackTrace();
				return false;
			}

			boolean bFound = false;

			if( rcode == 200 ){
				if( ! check_url.equals( last_flash_air_url.get() ) ){
					log.i( "FlashAir found. %s", check_url );
				}
				last_flash_air_url.set( check_url );
				bFound = true;
			}

			try{
				conn.disconnect();
			}catch( Throwable ignored ){
			}

			return bFound;
		}

		boolean checkStaModeFlashAir(){

			// 設定で指定されたURLを最初に試す
			// ただしURLにIPアドレスが書かれている場合のみ
			if( reIPAddr.matcher( target_url ).find() ){
				if( checkFlashAirUrl( target_url ) ){
					return true;
				}
			}

			if(! isWifiAPEnabled()){
				log.d( "Wi-Fi Tethering is not enabled." );
				return false;
			}

			final String tethering_address = getWiFiAPAddress();
			if( tethering_address == null ){
				log.w( "missing Wi-Fi Tethering IP address." );
				return false;
			}
			final String ip_base = tethering_address.replaceAll( "\\d+$", "" );

			// ARPテーブルの読み出し
			String strArp = readStringFile( "/proc/net/arp" );
			if( strArp == null ){
				log.e( "can not read ARP table." );
				return false;
			}
			// ARPテーブル中のIPアドレスを確認
			Matcher m = reArp.matcher( strArp );
			while( m.find() ){
				String item_ip =  m.group( 1 );
				String item_mac = m.group( 4 );

				// MACアドレスが不明なエントリや
				// テザリング範囲と無関係なエントリは無視する
				if( item_mac.equals( "00:00:00:00:00:00" )
					|| ! item_ip.startsWith( ip_base )
					) continue;

				if( checkFlashAirUrl( "http://" +item_ip + "/" ) ){
					return true;
				}
			}

			// カードが見つからない場合
			// 直接ARPリクエストを投げるのは難しい？ので
			// UDPパケットをばらまく
			// 次回以降の確認で効果があるといいな
			sprayUDPPacket( tethering_address, ip_base );

			return false;
		}

		final LinkedList<Integer> priority_list = new LinkedList<>();

		String last_force_status;
		String last_error_status;
		long last_wifi_ap_change;

		static final int WIFI_SCAN_INTERVAL = 100000;
		long last_wifi_scan_start;

		@SuppressWarnings( "ConstantConditions" ) boolean keep_ap(){
			if( isCancelled() ) return false;

			final NetworkStateList ns_list = new NetworkStateList();
			String force_status = null;
			String error_status = null;
			try{
				if( Build.VERSION.SDK_INT >= 23 ){
					Long active_handle = null;
					Network an = cm.getActiveNetwork();
					if( an != null ){
						active_handle = an.getNetworkHandle();
					}
					Network[] src_list = cm.getAllNetworks();
					if( src_list != null ){
						for( Network n : src_list ){
							boolean is_active = ( active_handle != null && active_handle == n.getNetworkHandle() );
							NetworkInfo ni = cm.getNetworkInfo( n );
							ns_list.eachNetworkInfo( is_active, ni );
						}
					}
				}else{
					String active_name = null;
					NetworkInfo ani = cm.getActiveNetworkInfo();
					if( ani != null ){
						active_name = ani.getTypeName();
					}
					@SuppressWarnings( "deprecation" )
					NetworkInfo[] src_list = cm.getAllNetworkInfo();
					if( src_list != null ){
						for( NetworkInfo ni : src_list ){
							boolean is_active = ( active_name != null && active_name.equals( ni.getTypeName() ) );
							ns_list.eachNetworkInfo( is_active, ni );
						}
					}
				}
				ns_list.afterAllNetwork();

				// FlashAir STAモードの時の処理
				if( target_type == DownloadWorker.TARGET_TYPE_FLASHAIR_STA ){
					return checkStaModeFlashAir();
				}

				// Wi-Fiが無効なら有効にする
				try{
					ns_list.wifi_status.strWifiStatus = "?";
					if( ! wifiManager.isWifiEnabled() ){
						ns_list.wifi_status.strWifiStatus = context.getString( R.string.not_enabled );
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
					ns_list.wifi_status.strWifiStatus = context.getString( R.string.no_ap_associated );

					List<WifiConfiguration> wc_list = wifiManager.getConfiguredNetworks();
					if( wc_list == null ){
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
								ns_list.wifi_status.strWifiStatus = ssid + "," + strState;
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
						return ! ns_list.other_active;
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
						if( now - last_wifi_scan_start >= WIFI_SCAN_INTERVAL ){
							last_wifi_scan_start = now;
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
				if( current_status != null && ! current_status.equals( last_current_status.get() ) ){
					last_current_status.set( current_status );
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

		@Override public void run(){
			while( ! isCancelled() ){
				boolean result;
				try{
					result = keep_ap();
					if( isCancelled() ) break;
				}catch( Throwable ex ){
					log.e( ex, "network check failed." );
					result = false;
				}

				if( result != last_result.get() ){
					last_result.set( result );
					handler.post( new Runnable(){
						@Override public void run(){
							if( is_dispose ) return;
							try{
								callback.onConnectionEvent( true, "Wi-Fi tracker" );
							}catch( Throwable ex ){
								log.e( ex, "connection event handling failed." );
							}
						}
					} );
				}
				long next = result ? 30000L : 3000L;
				waitEx( next );
			}
		}
	}
}
