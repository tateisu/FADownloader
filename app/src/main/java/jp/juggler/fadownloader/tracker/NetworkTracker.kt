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
import java.net.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.min

class NetworkTracker(
	internal val context : Context,
	internal val log : LogWriter,
	internal val callback : Callback
) : BroadcastReceiver() {
	
	companion object {
		
		private val logStatic = LogTag("NetworkTracker")
		
		private const val TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
		
		private val reIPAddr = Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)")
		
		private val reNotV4Address = "[^\\d.]+".toRegex()
		
		private val reLastDigits = "\\d+$".toRegex()
		
		private val reArp =
			Pattern.compile("(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s*(0x\\d+)\\s*(0x\\d+)\\s*([0-9A-Fa-f:]+)")
		
	}
	
	interface Callback{
		fun onConnectionStatus(is_connected : Boolean, cause : String)
		fun onTetheringOff()
	}
	
	internal class NetworkStatus(
		var type_name : String,
		var sub_name : String? = null,
		var is_active : Boolean = false,
		var strWifiStatus : String? = null
	)
	
	internal class NetworkStateList : ArrayList<NetworkStatus>() {
		
		var wifi_status : NetworkStatus? = null
		var other_active : String = ""
		
		fun addNetworkInfo(is_active : Boolean, ni : NetworkInfo?) {
			ni ?: return
			
			val is_wifi = ni.type == ConnectivityManager.TYPE_WIFI
			
			// Wi-Fiでもなく接続中でもないなら全くの無関係
			if(! is_wifi && ! ni.isConnected) return
			
			val ns = NetworkStatus(
				type_name = ni.typeName,
				sub_name = ni.subtypeName,
				is_active = is_active
			)
			this.add(ns)
			
			if(is_wifi) {
				wifi_status = ns
			} else if(is_active) {
				other_active = ns.type_name
			}
		}
		
		fun afterAddAll() {
			if(wifi_status == null) {
				val ws = NetworkStatus(type_name = "WIFI")
				wifi_status = ws
				this.add(ws)
			}
		}
		
		internal fun buildCurrentStatus() : String {
			
			sortWith(Comparator { a, b ->
				if(a.is_active && ! b.is_active) {
					- 1
				} else if(! a.is_active && b.is_active) {
					1
				} else {
					a.type_name.compareTo(b.type_name)
				}
			})
			
			val sb = StringBuilder()
			for(ns in this) {
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
		
		var force_status : String? = null
		var error_status : String? = null
	}
	
	// API 26以降でpriorityは使えなくなった
	@Suppress("DEPRECATION")
	private fun getPriority(wc : WifiConfiguration) : Int {
		return wc.priority
	}
	
	// API 26以降でpriorityは使えなくなった
	@Suppress("DEPRECATION")
	private fun updatePriority(
		target_config : WifiConfiguration,
		priority_max : Int
	) : String? {
		try {
			val priority_list = LinkedList<Int>()
			
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
			log.trace(ex, "updateNetwork() or saveConfiguration() failed.")
			return ex.withCaption("updateNetwork() or saveConfiguration() failed.")
		}
		return null
	}
	
	private val wifiManager =
		context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
	private val connectivityManager =
		context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
	
	private val isTetheringEnabled : Boolean
		get() {
			try {
				val rv = wifiManager.javaClass.getMethod("isWifiApEnabled").invoke(wifiManager)
				if(rv is Boolean) return rv
				log.e("isWifiApEnabled returns $rv")
			} catch(ex : Throwable) {
				log.trace(ex, "isTetheringEnabled")
			}
			return false
		}
	
	private val tetheringAddress : String?
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
								return addr.hostAddress.replace(reNotV4Address, "")
							}
						}
					} catch(ex : SocketException) {
						log.trace(ex, "wiFiAPAddress")
					}
				}
			} catch(ex : SocketException) {
				log.trace(ex, "wiFiAPAddress")
			}
			
			return null
		}
	
	// ネットワークアドレス(XXX.XXX.XXX.XXX) と ネットマスク(XXX.XXX.XXX.)を指定してUDPパケットをばら撒く
	private fun sprayUDPPacket(nw_addr : String, ip_base : String) {
		val start = SystemClock.elapsedRealtime()
		
		try {
			val data = ByteArray(1)
			val port = 80
			val socket = DatagramSocket()
			for(n in 2 .. 254) {
				val try_ip = "$ip_base$n"
				if(try_ip == nw_addr) continue
				try {
					val packet = DatagramPacket(
						data,
						data.size,
						InetAddress.getByName(try_ip),
						port
					)
					socket.send(packet)
				} catch(ex : Throwable) {
					log.trace(ex, "sprayUDPPacket")
				}
				
			}
			socket.close()
		} catch(ex : Throwable) {
			log.trace(ex, "sprayUDPPacket")
		}
		
		log.v("sent UDP packet to '$ip_base*' time=${Utils.formatTimeDuration(SystemClock.elapsedRealtime() - start)}")
	}
	
	class Setting(
		val force_wifi : Boolean = false,
		val target_ssid : String = "",
		val target_type : Int = 0,
		val target_url : String = "http://flashair/",
		
		val tetherSprayInterval : Long = 1000L,
		val tetherTestConnectionTimeout : Long = 1000L,
		val wifiChangeApInterval : Long = 1000L,
		val wifiScanInterval : Long = 1000L,
		
		val stopWhenTetheringOff: Boolean =false
	)
	
	private var is_dispose = false
	
	private var worker : Worker? = null
	
	private var setting : Setting = Setting()
	
	private var timeLastSpray = 0L
	private var timeLastWiFiScan : Long = 0
	private var timeLastWiFiApChange : Long = 0
	private var timeLastTargetDetected = 0L
	
	private val testerMap = HashMap<String, NetworkTracker.UrlTester>()
	val bLastConnected = AtomicBoolean()
	val lastTargetUrl = AtomicReferenceNotNull("")
	
	private var last_force_status = AtomicReferenceNotNull("")
	private var last_error_status = AtomicReferenceNotNull("")
	private var last_current_status = AtomicReferenceNotNull("")
	private var last_other_active = AtomicReferenceNotNull("")
	
	val otherActive : String
		get() = last_other_active.get()
	
	init {
		context.registerReceiver(this, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
		context.registerReceiver(this, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
		context.registerReceiver(this, IntentFilter(TETHER_STATE_CHANGED))
		worker = Worker().apply {
			start()
		}
	}
	
	fun dispose() {
		is_dispose = true
		context.unregisterReceiver(this)
		worker?.cancel("disposed")
		worker = null
	}
	
	override fun onReceive(context : Context, intent : Intent) {
		if(intent.action == TETHER_STATE_CHANGED) {
			val sb = StringBuilder("TETHER_STATE_CHANGED. ")
			val extras = intent.extras
			for(key in extras.keySet()) {
				val v = extras[key]
				when(v) {
					is ArrayList<*> -> sb.append("$key=[${v.joinToString("/")}],")
					is Array<*> -> sb.append("$key=[${v.joinToString("/")}],")
					else -> sb.append("$key=$v,")
				}
			}
			log.d(sb.toString())
		}
		if(! is_dispose) worker?.notifyEx()
	}
	
	fun updateSetting(setting : Setting) {
		if(is_dispose) return
		this.setting = setting
		timeLastTargetDetected =0L
		timeLastSpray =0L
		timeLastWiFiApChange =0L
		timeLastWiFiScan = 0L
		lastTargetUrl.set("")
		worker?.notifyEx()
	}
	
	fun getStatus() : String {
		return requireNotNull(last_current_status.get())
	}
	
	private class UrlTester(
		val setting : Setting,
		val log : LogWriter,
		val targetUrl : String,
		val checkUrl : String,
		val callback : (tester : UrlTester) -> Unit
	) : WorkerBase() {
		
		override fun cancel(reason : String) : Boolean {
			val rv = super.cancel(reason)
			try {
				this.interrupt()
			} catch(ignored : Throwable) {
			}
			return rv
		}
		
		override fun run() {
			val timeStart = SystemClock.elapsedRealtime()
			var bFound = false
			var error : Throwable? = null
			try {
				try {
					val urlObject = URL(checkUrl)
					val conn = urlObject.openConnection() as HttpURLConnection
					try {
						conn.doInput = true
						conn.connectTimeout = setting.tetherTestConnectionTimeout.toInt()
						conn.readTimeout = setting.tetherTestConnectionTimeout.toInt()
						conn.doOutput = false
						conn.connect()
						val resCode = conn.responseCode
						if(resCode == 200) {
							bFound = true
						} else {
							logStatic.e("HTTP error %s. url=%s", resCode, checkUrl)
						}
					} finally {
						try {
							conn.disconnect()
						} catch(ignored : Throwable) {
						}
						
					}
				} catch(ex : Throwable) {
					error = ex
				}
			} finally {
				val time = SystemClock.elapsedRealtime() - timeStart
				when(error) {
					null -> {
					
					}
					
					is ConnectException -> {
						// このエラーは500ms程度で出る。
						// ARPテーブルにあるアドレスだが実際にはネットワーク上に相手が存在しない。
						// 頻出するのでログを出力しない
						logStatic.w(error.withCaption("time=${time}ms, url=$checkUrl"))
					}
					
					is SocketTimeoutException -> {
						// タイムアウトは設定がおかしい場合やネットワークが不調な場合に発生する
						// ユーザはエラーログを見て設定値を変更することができる
						log.w(error.withCaption("time=${time}ms, url=$checkUrl"))
					}
					
					else -> {
						log.trace(error, "time=${time}ms, url=$checkUrl")
						log.e(error.withCaption("time=${time}ms, url=$checkUrl"))
					}
				}
				if(bFound) callback(this)
			}
		}
	}
	
	private val onUrlTestComplete : (UrlTester) -> Unit = { tester ->
		synchronized(testerMap) {
			testerMap.remove(tester.checkUrl)
			if(tester.setting.target_type == setting.target_type) {
				if(tester.targetUrl != lastTargetUrl.get()) {
					log.i("target detected. %s", tester.targetUrl)
				}
				lastTargetUrl.set(tester.targetUrl)
				timeLastTargetDetected = SystemClock.elapsedRealtime()
				worker?.notifyEx()
			}
		}
	}
	
	private fun startTestUrl(targetUrl : String, getCheckUrl : (String) -> String) {
		val checkUrl = getCheckUrl(targetUrl)
		synchronized(testerMap) {
			var tester = testerMap[checkUrl]
			if(tester?.isAlive == true) return
			tester = NetworkTracker.UrlTester(setting, log, targetUrl, checkUrl, onUrlTestComplete)
			testerMap[checkUrl] = tester
			tester.start()
		}
	}
	
	// 接続先が見つかったら0L
	// またはリトライまでの秒数を返す
	private fun detectTetheringClient(
		env : NetworkStateList,
		getCheckUrl : (String) -> String
	) : Long {
		
		val now = SystemClock.elapsedRealtime()
		if(now - timeLastTargetDetected < 10000L) {
			// ターゲットが見つかってからしばらくの間は検出を行わない
			return 0L
		}
		
		if(! isTetheringEnabled) {
			env.error_status = "Wi-Fi Tethering is not enabled."
			
			if(setting.stopWhenTetheringOff){
				Utils.runOnMainThread {
					callback.onTetheringOff()
				}
			}
			
			// TETHER_STATE_CHANGED があるのでリトライ間隔は長めでもよさそう
			return 5000L
		}
		
		val tethering_address = tetheringAddress
		if(tethering_address == null) {
			env.error_status = "missing Wi-Fi Tethering IP address."
			return 1000L
		}
		
		if(reIPAddr.matcher(setting.target_url).find()) {
			// 設定で指定されたURLにIPアドレスが書かれているなら、それを試す
			startTestUrl(setting.target_url, getCheckUrl)
		}
		
		
		// "XXX.XXX.XXX."
		val ip_base = tethering_address.replace(reLastDigits, "")
		
		// ARPテーブルの読み出し
		val strArp = Utils.readStringFile("/proc/net/arp")
		if(strArp == null) {
			env.error_status = "Can't read ARP table."
		} else {
			val list = ArrayList<String>()
			// ARPテーブル中のIPアドレスを確認
			val m = reArp.matcher(strArp)
			while(m.find()) {
				val item_ip = m.group(1)
				val item_mac = m.group(4)
				
				// MACアドレスが不明なエントリや
				// テザリング範囲と無関係なエントリは無視する
				if(item_mac == "00:00:00:00:00:00" || ! item_ip.startsWith(ip_base))
					continue
				
				list.add(item_ip)
			}
			if(list.isEmpty()) {
				env.error_status = "missing devices in ARP table."
			} else {
				env.force_status = "devices: ${list.joinToString(",")}"
				for(item_ip in list) {
					startTestUrl("http://$item_ip/", getCheckUrl)
				}
			}
		}
		
		// カードが見つからない場合
		// 直接ARPリクエストを投げるのは難しい？のでUDPパケットをばらまく
		// 次回以降の確認で効果ARPテーブルを読めれば良いのだが…。
		val remain = timeLastSpray + setting.tetherSprayInterval - now
		return if(remain > 0L) {
			remain
		} else {
			timeLastSpray = now
			sprayUDPPacket(tethering_address, ip_base)
			1000L
		}
	}
	
	private fun keep_ap() : Long {
		
		val ns_list = NetworkStateList()
		try {
			if(Build.VERSION.SDK_INT >= 23) {
				var active_handle : Long? = null
				val an = connectivityManager.activeNetwork
				if(an != null) {
					active_handle = an.networkHandle
				}
				val src_list = connectivityManager.allNetworks
				if(src_list != null) {
					for(n in src_list) {
						val is_active =
							active_handle != null && active_handle == n.networkHandle
						val ni = connectivityManager.getNetworkInfo(n)
						ns_list.addNetworkInfo(is_active, ni)
					}
				}
			} else {
				var active_name : String? = null
				val ani = connectivityManager.activeNetworkInfo
				if(ani != null) {
					active_name = ani.typeName
				}
				@Suppress("DEPRECATION")
				val src_list = connectivityManager.allNetworkInfo
				if(src_list != null) {
					for(ni in src_list) {
						val is_active = active_name != null && active_name == ni.typeName
						ns_list.addNetworkInfo(is_active, ni)
					}
				}
			}
			ns_list.afterAddAll()
			last_other_active.set(ns_list.other_active)
			
			// テザリングモードの処理
			when(setting.target_type) {
				Pref.TARGET_TYPE_FLASHAIR_STA -> {
					return detectTetheringClient(ns_list) { "${it}command.cgi?op=108" }
				}
				
				Pref.TARGET_TYPE_PQI_AIR_CARD_TETHER -> {
					return detectTetheringClient(ns_list) { "${it}cgi-bin/get_config.pl" }
				}
			}
			
			val wifi_status = requireNotNull(ns_list.wifi_status)
			
			// Wi-Fiが無効なら有効にする
			try {
				wifi_status.strWifiStatus = "?"
				if(! wifiManager.isWifiEnabled) {
					wifi_status.strWifiStatus = context.getString(R.string.not_enabled)
					return if(setting.force_wifi) {
						wifiManager.isWifiEnabled = true
						1000L
					} else {
						Long.MAX_VALUE
					}
				}
			} catch(ex : Throwable) {
				log.trace(ex, "setWifiEnabled() failed.")
				ns_list.error_status = ex.withCaption("setWifiEnabled() failed.")
				return 10000L
			}
			
			// Wi-Fiの現在の状態を取得する
			val info : WifiInfo?
			var current_supp_state : SupplicantState? = null
			var current_ssid : String? = null
			try {
				info = wifiManager.connectionInfo
				if(info != null) {
					current_supp_state = info.supplicantState
					current_ssid = info.ssid?.filterSsid()
				}
			} catch(ex : Throwable) {
				log.trace(ex, "getConnectionInfo() failed.")
				ns_list.error_status = ex.withCaption("getConnectionInfo() failed.")
				return 10000L
			}
			
			// 設定済みのAPを列挙する
			var current_network_id = 0
			var target_config : WifiConfiguration? = null
			var priority_max = 0
			try {
				wifi_status.strWifiStatus = context.getString(R.string.no_ap_associated)
				
				val wc_list = wifiManager.configuredNetworks
					?: return 5000L // getConfiguredNetworks() はたまにnullを返す
				
				for(wc in wc_list) {
					val ssid = wc.SSID.filterSsid()
					
					val p = getPriority(wc)
					
					if(p > priority_max) {
						priority_max = p
					}
					
					// 目的のAPを覚えておく
					if(ssid == setting.target_ssid) {
						target_config = wc
					}
					
					// 接続中のAPの情報
					if(ssid == current_ssid) {
						current_network_id = wc.networkId
						//
						var strState = Utils.toCamelCase(current_supp_state?.toString() ?: "?")
						if("Completed" == strState) strState = "Connected"
						
						wifi_status.strWifiStatus = "$ssid,$strState"
						
						// AP強制ではないなら、何かアクティブな接続が生きていればOK
						if(! setting.force_wifi && current_supp_state == SupplicantState.COMPLETED) {
							return 0L
						}
					}
				}
				// 列挙終了
				when(setting.force_wifi) {
					false -> {
						// AP強制ではない場合、接続中のAPがなければ待機して再確認
						// 多分通信状況ブロードキャストで起こされる
						return 10000L
					}
					
					true -> if(target_config == null) {
						// 指定されたSSIDはこの端末に設定されていない
						ns_list.force_status =
							context.getString(
								R.string.wifi_target_ssid_not_found,
								setting.target_ssid
							)
						return Long.MAX_VALUE
						
					}
				}
			} catch(ex : Throwable) {
				log.trace(ex, "getConfiguredNetworks() failed.")
				ns_list.error_status = ex.withCaption("getConfiguredNetworks() failed.")
				return 10000L
			}
			
			if(Build.VERSION.SDK_INT < 26) {
				// API level 25まではAPの優先順位を変えることができた
				val error = updatePriority(target_config, priority_max)
				if(error != null) ns_list.error_status = error
			}
			
			// 目的のAPが選択されていた場合
			if(current_ssid != null && current_network_id == target_config.networkId) {
				when(current_supp_state) {
					SupplicantState.ASSOCIATING,
					SupplicantState.ASSOCIATED,
					SupplicantState.AUTHENTICATING,
					SupplicantState.FOUR_WAY_HANDSHAKE,
					SupplicantState.GROUP_HANDSHAKE ->
						// 現在のstateが何か作業中なら、余計なことはしないがOKでもない
						return 500L
					
					SupplicantState.COMPLETED -> {
						return if(ns_list.other_active.isNotEmpty()) {
							// 認証はできたが他の接続がアクティブならさらに待機する
							1000L
						} else {
							// 他の接続もないしこれでOKだと思う
							0L
						}
					}
					
					else -> {
						// fall
					}
				}
			}
			
			// スキャン結果に目的のSSIDがあるか？
			var lastSeen : Long? = null
			var found_in_scan = false
			try {
				for(result in wifiManager.scanResults) {
					if(Build.VERSION.SDK_INT >= 17) {
						if(lastSeen == null || result.timestamp > lastSeen) {
							lastSeen = result.timestamp
						}
					}
					if(setting.target_ssid == result.SSID.filterSsid()) {
						found_in_scan = true
						break
					}
				}
			} catch(ex : Throwable) {
				log.trace(ex, "getScanResults() failed.")
				ns_list.error_status = ex.withCaption("getScanResults() failed.")
				return 10000L
			}
			
			// スキャン範囲内にない場合、定期的にスキャン開始
			if(! found_in_scan) {
				ns_list.force_status =
					context.getString(R.string.wifi_target_ssid_not_scanned, setting.target_ssid)
				val now = SystemClock.elapsedRealtime()
				val remain = timeLastWiFiScan + setting.wifiScanInterval - now
				return if(remain > 0L) {
					val lastSeenBefore = if(lastSeen == null) null else now - (lastSeen / 1000L)
					logStatic.d("${setting.target_ssid} is not found in latest scan result(${lastSeenBefore}ms before). next scan is start after ${remain}ms.")
					min(remain, 3000L)
				} else try {
					timeLastWiFiScan = now
					wifiManager.startScan()
					log.d(R.string.wifi_scan_start)
					3000L
				} catch(ex : Throwable) {
					log.trace(ex, "startScan() failed.")
					ns_list.error_status = ex.withCaption("startScan() failed.")
					10000L
				}
			} else {
				val now = SystemClock.elapsedRealtime()
				val remain = timeLastWiFiApChange + setting.wifiChangeApInterval - now
				return if(remain > 0L) {
					logStatic.d("wait ${remain}ms before force change WiFi AP")
					min(remain, 3000L)
				} else {
					timeLastWiFiApChange = now
					try {
						// 先に既存接続を無効にする
						for(wc in wifiManager.configuredNetworks) {
							if(wc.networkId == target_config.networkId) continue
							val ssid = wc.SSID.filterSsid()
							when(wc.status) {
								WifiConfiguration.Status.CURRENT -> {
									log.v("${ssid}から切断させます")
									wifiManager.disableNetwork(wc.networkId)
								}
								
								WifiConfiguration.Status.ENABLED -> {
									log.v("${ssid}への自動接続を無効化します")
									wifiManager.disableNetwork(wc.networkId)
								}
							}
						}
						
						val target_ssid = target_config.SSID.filterSsid()
						log.i("${target_ssid}への接続を試みます")
						wifiManager.enableNetwork(target_config.networkId, true)
						1000L
					} catch(ex : Throwable) {
						log.trace(ex, "disableNetwork() or enableNetwork() failed.")
						ns_list.error_status =
							ex.withCaption("disableNetwork() or enableNetwork() failed.")
						10000L
					}
				}
			}
		} finally {
			// 状態の変化があればログに出力する
			
			val current_status = ns_list.buildCurrentStatus()
			
			if(current_status != last_current_status.get()) {
				last_current_status.set(current_status)
				log.d(context.getString(R.string.network_status, current_status))
			}
			
			val error_status = ns_list.error_status
			if(error_status != null && error_status != last_error_status.get()) {
				last_error_status.set(error_status)
				log.e(error_status)
			}
			
			val force_status = ns_list.force_status
			if(force_status != null && force_status != last_force_status.get()) {
				last_force_status.set(force_status)
				log.w(force_status)
			}
		}
	}
	
	internal inner class Worker : WorkerBase() {
		
		override fun cancel(reason : String) : Boolean {
			val rv = super.cancel(reason)
			try {
				this.interrupt()
			} catch(ignored : Throwable) {
			}
			return rv
		}
		
		override fun run() {
			while(! isCancelled) {
				
				val result = try {
					keep_ap()
				} catch(ex : Throwable) {
					log.trace(ex, "network check failed.")
					log.e(ex, "network check failed.")
					5000L
				}
				
				if(isCancelled) break
				
				val bConnected = result <= 0L
				if(bConnected != bLastConnected.get()) {
					// 接続状態の変化
					bLastConnected.set(bConnected)
					Utils.runOnMainThread {
						try {
							if(! is_dispose) callback.onConnectionStatus(true, "Wi-Fi tracker")
						} catch(ex : Throwable) {
							log.trace(ex, "connection event handling failed.")
							log.e(ex, "connection event handling failed.")
						}
					}
				}
				
				waitEx(if(result <= 0L) 5000L else result)
			}
		}
	}
}
