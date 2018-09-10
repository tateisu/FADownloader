package jp.juggler.fadownloader.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import jp.juggler.fadownloader.Pref
import jp.juggler.fadownloader.R
import jp.juggler.fadownloader.util.*
import org.apache.commons.io.IOUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

class NetworkTracker(
	internal val context : Context,
	internal val log : LogWriter,
	internal val callback : (is_connected : Boolean, cause : String)->Unit
) {
	
	companion object {
		
		private val logStatic = LogTag("NetworkTracker")
		
		const val WIFI_SCAN_INTERVAL = 10000
		
		////////////////////////////////////////////////////////////////////////
		
		internal fun readStringFile(path : String) : String? {
			try {
				FileInputStream(File(path)).use{fis->
					val bao = ByteArrayOutputStream()
					IOUtils.copy(fis, bao)
					return (bao.toByteArray() as ByteArray).decodeUTF8()
				}
			} catch(ex : Throwable) {
				logStatic.trace(ex,"readStringFile")
				return null
			}
		}
		
		internal fun buildCurrentStatus(ns_list : ArrayList<NetworkStatus>) : String {
			Collections.sort(ns_list, Comparator { a, b ->
				if(a.is_active && ! b.is_active) return@Comparator - 1
				if(! a.is_active && b.is_active) 1 else a.type_name !!.compareTo(b.type_name !!)
			})
			val sb = StringBuilder()
			for(ns in ns_list) {
				if(sb.isNotEmpty()) sb.append(" / ")
				if(ns.is_active) sb.append("(Active)")
				if(ns.strWifiStatus != null) {
					sb.append("Wi-Fi(").append(ns.strWifiStatus).append(')')
				} else {
					sb.append(ns.type_name)
					val sub_name = ns.sub_name
					if(sub_name?.isNotEmpty() == true) {
						sb.append('(').append(sub_name).append(')')
					}
				}
			}
			return sb.toString()
		}
		
		internal val reIPAddr = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)")
		internal val reArp =
			Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s*(0x\\d+)\\s*(0x\\d+)\\s*([0-9A-Fa-f:]+)")
	}
	
	internal interface UrlChecker {
		fun checkUrl(url : String?) : Boolean
	}
	
	
	internal val wifiManager : WifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
	internal val cm : ConnectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
	
	internal var worker : Worker? = null
	
	@Volatile
	internal var is_dispose = false
	
	private val receiver : BroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context : Context, intent : Intent) {
			if(is_dispose) return
			worker ?.notifyEx()
		}
	}
	
	@Volatile
	internal var force_wifi : Boolean = false
	@Volatile
	internal var target_ssid : String? = null
	@Volatile
	internal var target_type : Int = 0 // カードはAPモードではなくSTAモードもしくはインターネット同時接続もーどで動作している

	@Volatile
	internal var target_url : String? = null
	
	////////////////////////////////////////////////////////////////////////
	
	val last_result = AtomicBoolean()
	val last_flash_air_url = AtomicReference<String>()
	internal val last_current_status = AtomicReference<String>()
	
	internal val last_other_active = AtomicReference<String>()
	
	val otherActive : String?
		get() = last_other_active.get()
	
	internal val urlChecker_FlashAir : UrlChecker = object :
		UrlChecker {
		override fun checkUrl(url : String?) : Boolean {
			return checkUrl_sub(url, url + "command.cgi?op=108")
		}
	}
	
	internal val urlChecker_PqiAirCard : UrlChecker = object :
		UrlChecker {
		override fun checkUrl(url : String?) : Boolean {
			return checkUrl_sub(url, url + "cgi-bin/get_config.pl")
		}
	}
	

	init {
		
		context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
		context.registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
		worker = Worker()
		worker !!.start()
	}
	
	fun dispose() {
		is_dispose = true
		context.unregisterReceiver(receiver)
		if(worker != null) {
			worker !!.cancel("disposed")
			worker = null
		}
	}
	
	fun updateSetting(force_wifi : Boolean, ssid : String?, target_type : Int, target_url : String?) {
		if(is_dispose) return
		this.force_wifi = force_wifi
		this.target_ssid = ssid
		this.target_type = target_type
		this.target_url = target_url
		if(worker != null) worker !!.notifyEx()
	}
	
	internal class NetworkStatus {
		
		var is_active : Boolean = false
		var type_name : String? = null
		var sub_name : String? = null
		var strWifiStatus : String? = null
	}
	
	internal class NetworkStateList : ArrayList<NetworkStatus>() {
		
		var wifi_status : NetworkStatus? = null
		var other_active : String? = null
		
		fun eachNetworkInfo(is_active : Boolean, ni : NetworkInfo) {
			val is_wifi = ni.type == ConnectivityManager.TYPE_WIFI
			if(! is_wifi && ! ni.isConnected) return
			val ns = NetworkStatus()
			this.add(ns)
			if(is_wifi) wifi_status = ns
			ns.type_name = ni.typeName
			ns.sub_name = ni.subtypeName
			
			if(is_active) {
				ns.is_active = true
				if(! is_wifi) other_active = ns.type_name
			}
		}
		
		fun afterAllNetwork() {
			if(wifi_status == null) {
				val ws = NetworkStatus()
				ws.type_name = "WIFI"
				wifi_status = ws
				this.add(ws)
			}
		}
	}
	
	fun getStatus(sb : StringBuilder) {
		sb.append(last_current_status)
	}
	
	
	internal fun checkUrl_sub(target_url : String?, check_url : String) : Boolean {
		target_url ?: return false

		var bFound = false
		try {
			val urlObject = URL(check_url)
			val conn = urlObject.openConnection() as HttpURLConnection
			try {
				conn.doInput = true
				conn.connectTimeout = 30000
				conn.readTimeout = 30000
				conn.doOutput = false
				conn.connect()
				val resCode = conn.responseCode
				if(resCode != 200) {
					log.e("HTTP error %s. url=%s", resCode, check_url)
				} else {
					if(target_url != last_flash_air_url.get()) {
						log.i("target detected. %s", target_url)
					}
					last_flash_air_url.set(target_url)
					bFound = true
				}
			} finally {
				try {
					conn.disconnect()
				} catch(ignored : Throwable) {
				}
				
			}
		} catch(ex : Throwable) {
			log.trace(ex,"failed: $check_url")
			log.e(ex, check_url)
		}
		
		return bFound
	}
	
	internal inner class Worker : WorkerBase() {
		
		private val isWifiAPEnabled : Boolean
			get() {
				try {
					return wifiManager.javaClass.getMethod("isWifiApEnabled").invoke(wifiManager) as Boolean
				} catch(ex : Throwable) {
					log.trace(ex,"isWifiAPEnabled")
				}
				
				return false
			}
		
		private val wiFiAPAddress : String?
			get() {
				try {
					val en = NetworkInterface.getNetworkInterfaces()
					while(en.hasMoreElements()) {
						val ni = en.nextElement()
						try {
							if(! ni.isUp) continue
							if(ni.isLoopback) continue
							if(ni.isVirtual) continue
							if(ni.isPointToPoint) continue
							if(ni.hardwareAddress == null) continue
							val eip = ni.inetAddresses
							while(eip.hasMoreElements()) {
								val addr = eip.nextElement()
								if(addr.address.size == 4) {
									return addr.hostAddress.replace("[^\\d.]+".toRegex(), "")
								}
							}
						} catch(ex : SocketException) {
							log.trace(ex,"wiFiAPAddress")
						}
					}
				} catch(ex : SocketException) {
					log.trace(ex,"wiFiAPAddress")
				}
				
				return null
			}
		
		private val priority_list = LinkedList<Int>()
		
		private var last_force_status : String? = null
		private var last_error_status : String? = null
		private var last_wifi_ap_change : Long = 0
		private var last_wifi_scan_start : Long = 0
		
		override fun cancel(reason : String) : Boolean {
			val rv = super.cancel(reason)
			try {
				this.interrupt()
			} catch(ignored : Throwable) {
			}
			
			return rv
		}
		
		private fun sprayUDPPacket(nw_addr : String, ip_base : String) {
			val start = SystemClock.elapsedRealtime()
			
			try {
				val data = ByteArray(1)
				val port = 80
				val socket = DatagramSocket()
				for(n in 2 .. 254) {
					val try_ip = ip_base + n
					if(try_ip == nw_addr) continue
					try {
						
						val packet = DatagramPacket(
							data, data.size, InetAddress.getByName(try_ip), port
						)
						
						socket.send(packet)
					} catch(ex : Throwable) {
						log.trace(ex,"sprayUDPPacket")
					}
					
				}
				socket.close()
			} catch(ex : Throwable) {
				log.trace(ex,"sprayUDPPacket")
			}
			
			log.d("sent UDP packet to '$ip_base*' time=${Utils.formatTimeDuration(SystemClock.elapsedRealtime() - start)}")
		}
		
		private fun detectTetheringClient(url_checker : UrlChecker) : Boolean {
			
			// 設定で指定されたURLを最初に試す
			// ただしURLにIPアドレスが書かれている場合のみ
			if(reIPAddr.matcher(target_url).find()) {
				if(url_checker.checkUrl(target_url)) {
					return true
				}
			}
			
			if(! isWifiAPEnabled) {
				log.d("Wi-Fi Tethering is not enabled.")
				return false
			}
			
			val tethering_address = wiFiAPAddress
			if(tethering_address == null) {
				log.w("missing Wi-Fi Tethering IP address.")
				return false
			}
			val ip_base = tethering_address.replace("\\d+$".toRegex(), "")
			
			// ARPテーブルの読み出し
			val strArp =
				readStringFile("/proc/net/arp")
			if(strArp == null) {
				log.e("can not read ARP table.")
				return false
			}
			// ARPテーブル中のIPアドレスを確認
			val m = reArp.matcher(strArp)
			while(m.find()) {
				val item_ip = m.group(1)
				val item_mac = m.group(4)
				
				// MACアドレスが不明なエントリや
				// テザリング範囲と無関係なエントリは無視する
				if(item_mac == "00:00:00:00:00:00" || ! item_ip.startsWith(ip_base))
					continue
				
				if(url_checker.checkUrl("http://$item_ip/")) {
					return true
				}
			}
			
			// カードが見つからない場合
			// 直接ARPリクエストを投げるのは難しい？ので
			// UDPパケットをばらまく
			// 次回以降の確認で効果があるといいな
			sprayUDPPacket(tethering_address, ip_base)
			
			return false
		}
		
		private fun keep_ap() : Boolean {
			if(isCancelled) return false
			
			val ns_list = NetworkStateList()
			var force_status : String? = null
			var error_status : String? = null
			try {
				if(Build.VERSION.SDK_INT >= 23) {
					var active_handle : Long? = null
					val an = cm.activeNetwork
					if(an != null) {
						active_handle = an.networkHandle
					}
					val src_list = cm.allNetworks
					if(src_list != null) {
						for(n in src_list) {
							val is_active =
								active_handle != null && active_handle == n.networkHandle
							val ni = cm.getNetworkInfo(n)
							ns_list.eachNetworkInfo(is_active, ni)
						}
					}
				} else {
					var active_name : String? = null
					val ani = cm.activeNetworkInfo
					if(ani != null) {
						active_name = ani.typeName
					}
					@Suppress("DEPRECATION")
					val src_list = cm.allNetworkInfo
					if(src_list != null) {
						for(ni in src_list) {
							val is_active = active_name != null && active_name == ni.typeName
							ns_list.eachNetworkInfo(is_active, ni)
						}
					}
				}
				ns_list.afterAllNetwork()
				last_other_active.set(ns_list.other_active)
				
				if(target_type == Pref.TARGET_TYPE_FLASHAIR_STA) {
					// FlashAir STAモードの時の処理
					return detectTetheringClient(urlChecker_FlashAir)
				} else if(target_type == Pref.TARGET_TYPE_PQI_AIR_CARD_TETHER) {
					// PQI Air Card Tethering モードの時の処理
					return detectTetheringClient(urlChecker_PqiAirCard)
				}
				
				// Wi-Fiが無効なら有効にする
				try {
					ns_list.wifi_status !!.strWifiStatus = "?"
					if(! wifiManager.isWifiEnabled) {
						ns_list.wifi_status !!.strWifiStatus =
							context.getString(R.string.not_enabled)
						if(force_wifi) wifiManager.isWifiEnabled = true
						return false
					}
				} catch(ex : Throwable) {
					log.trace(ex,"setWifiEnabled() failed.")
					error_status = ex.withCaption( "setWifiEnabled() failed.")
					return false
				}
				
				// Wi-Fiの現在の状態を取得する
				val info : WifiInfo?
				var current_supp_state : SupplicantState? = null
				var current_ssid : String? = null
				try {
					info = wifiManager.connectionInfo
					if(info != null) {
						current_supp_state = info.supplicantState
						val sv = info.ssid
						current_ssid = sv?.replace("\"", "")
					}
				} catch(ex : Throwable) {
					log.trace(ex,"getConnectionInfo() failed.")
					error_status = ex.withCaption( "getConnectionInfo() failed.")
					return false
				}
				
				// 設定済みのAPを列挙する
				var current_network_id = 0
				var target_config : WifiConfiguration? = null
				var priority_max = 0
				try {
					ns_list.wifi_status !!.strWifiStatus =
						context.getString(R.string.no_ap_associated)
					
					val wc_list = wifiManager.configuredNetworks
					if(wc_list == null) {
						// getConfiguredNetworks() はたまにnullを返す
						return false
					} else {
						for(wc in wc_list) {
							val ssid = wc.SSID.replace("\"", "")
							
							val p = getPriority(wc)
							
							if(p > priority_max) {
								priority_max = p
							}
							
							// 目的のAPを覚えておく
							if(target_ssid != null && target_ssid == ssid) {
								target_config = wc
							}
							
							// 接続中のAPの情報
							if(ssid == current_ssid) {
								current_network_id = wc.networkId
								//
								var strState = current_supp_state?.toString() ?: "?"
								strState = Utils.toCamelCase(strState)
								if("Completed" == strState) strState = "Connected"
								ns_list.wifi_status !!.strWifiStatus = "$ssid,$strState"
								if(! force_wifi) {
									// AP強制ではないなら、何かアクティブな接続が生きていればOK
									return current_supp_state == SupplicantState.COMPLETED
								}
							}
						}
						
						if(! force_wifi) {
							// AP強制ではない場合、接続中のAPがなければNGを返す
							return false
						} else if(target_config == null) {
							force_status =
								context.getString(R.string.wifi_target_ssid_not_found, target_ssid)
							return false
						}
					}
					
				} catch(ex : Throwable) {
					log.trace(ex,"getConfiguredNetworks() failed.")
					error_status = ex.withCaption( "getConfiguredNetworks() failed.")
					return false
				}

				if( Build.VERSION.SDK_INT < 26){
					val error = updatePriority(target_config,priority_max)
					if(error != null) error_status = error
				}
				
				// 目的のAPが選択されていた場合
				if(current_ssid != null && current_network_id == target_config.networkId) {
					when(current_supp_state) {
						SupplicantState.COMPLETED ->
							// その接続の認証が終わっていて、他の種類の接続がActiveでなければOK
							return ns_list.other_active == null
						SupplicantState.ASSOCIATING, SupplicantState.ASSOCIATED, SupplicantState.AUTHENTICATING, SupplicantState.FOUR_WAY_HANDSHAKE, SupplicantState.GROUP_HANDSHAKE ->
							// 現在のstateが何か作業中なら、余計なことはしないがOKでもない
							return false
						else->{}
					}
				}
				
				// スキャン範囲内に目的のSSIDがあるか？
				var lastSeen :Long? = null
				var found_in_scan = false
				try {
					for(result in wifiManager.scanResults) {
						if( Build.VERSION.SDK_INT >= 17) {
							if(lastSeen == null || result.timestamp > lastSeen){
								lastSeen = result.timestamp
							}
						}
						if(target_ssid != null && target_ssid == result.SSID.replace("\"", "")) {
							found_in_scan = true
							break
						}
					}
				} catch(ex : Throwable) {
					log.trace(ex,"getScanResults() failed.")
					error_status = ex.withCaption( "getScanResults() failed.")
					return false
				}
				
				// スキャン範囲内にない場合、定期的にスキャン開始
				if(! found_in_scan) {
					try {
						force_status = context.getString(R.string.wifi_target_ssid_not_scanned, target_ssid)
						val now = SystemClock.elapsedRealtime()
						val remain = last_wifi_scan_start + WIFI_SCAN_INTERVAL - now
						if( remain > 0L){
							val lastSeenBefore = if(lastSeen==null) null else now - (lastSeen/1000L)
							logStatic.d("$target_ssid is not found in latest scan result(${lastSeenBefore}ms before). next scan is start after ${remain}ms.")
						}else{
							last_wifi_scan_start = now
							wifiManager.startScan()
							log.d(R.string.wifi_scan_start)
						}
					} catch(ex : Throwable) {
						log.trace(ex,"startScan() failed.")
						error_status = ex.withCaption( "startScan() failed.")
					}
					return false
				}
				
				val now = SystemClock.elapsedRealtime()
				val remain = last_wifi_ap_change + 5000L - now
				if( remain > 0L){
					logStatic.d("wait ${remain}ms before force change WiFi AP")
				}else{
					last_wifi_ap_change = now
					
					try {
						// 先に既存接続を無効にする
						for(wc in wifiManager.configuredNetworks) {
							if(wc.networkId != target_config.networkId) {
								val ssid = wc.SSID.replace("\"", "")
								if(wc.status == WifiConfiguration.Status.CURRENT) {
									log.i("%sから切断させます", ssid)
									wifiManager.disableNetwork(wc.networkId)
								} else if(wc.status == WifiConfiguration.Status.ENABLED) {
									log.i("%sへの自動接続を無効化します", ssid)
									wifiManager.disableNetwork(wc.networkId)
								}
							}
						}
						
						val target_ssid = target_config.SSID.replace("\"", "")
						log.i("%s への接続を試みます", target_ssid)
						wifiManager.enableNetwork(target_config.networkId, true)
						
						return false
						
					} catch(ex : Throwable) {
						log.trace(ex,"disableNetwork() or enableNetwork() failed.")
						error_status =
							ex.withCaption( "disableNetwork() or enableNetwork() failed.")
					}
					
				}
				
				return false
			} finally {
				val current_status =
					buildCurrentStatus(
						ns_list
					)
				if(current_status != last_current_status.get()) {
					last_current_status.set(current_status)
					log.d(context.getString(R.string.network_status, current_status))
				}
				
				if(error_status != null && error_status != last_error_status) {
					last_error_status = error_status
					log.e(error_status)
				}
				
				if(force_status != null && force_status != last_force_status) {
					last_force_status = force_status
					log.w(force_status)
				}
			}
		}
		
		// API 26以降でpriorityは使えなくなった
		@Suppress("DEPRECATION")
		private fun getPriority(wc:WifiConfiguration):Int{
			return wc.priority
		}

		// API 26以降でpriorityは使えなくなった
		@Suppress("DEPRECATION")
		private fun updatePriority(target_config:WifiConfiguration,priority_max:Int) :String? {
			try {
				// priority の変更
				val p = target_config.priority
				if(p != priority_max) {
					priority_list.add(p)
					if(priority_list.size > 5) priority_list.removeFirst()
					if(priority_list.size < 5 || priority_list.first.toInt() != priority_list.last.toInt()) {
						// まだ上がるか試してみる
						target_config.priority = priority_max + 1
						wifiManager.updateNetwork(target_config)
						wifiManager.saveConfiguration()
						////頻出するのでログ出さない log.d( R.string.wifi_ap_priority_changed );
					}
				}
			} catch(ex : Throwable) {
				log.trace(ex,"updateNetwork() or saveConfiguration() failed.")
				return ex.withCaption( "updateNetwork() or saveConfiguration() failed.")
			}
			return null
		}
		
		override fun run() {
			while(! isCancelled) {
				var result : Boolean
				try {
					result = keep_ap()
					if(isCancelled) break
				} catch(ex : Throwable) {
					log.trace(ex,"network check failed.")
					log.e(ex, "network check failed.")
					result = false
				}
				
				if(result != last_result.get()) {
					last_result.set(result)
					Utils.runOnMainThread{
						try {
							if(!is_dispose) callback(true, "Wi-Fi tracker")
						} catch(ex : Throwable) {
							log.trace(ex,"connection event handling failed.")
							log.e(ex, "connection event handling failed.")
						}
					}
				}
				val next = if(result) 5000L else 1000L
				waitEx(next)
			}
		}
		

	}
	
}
