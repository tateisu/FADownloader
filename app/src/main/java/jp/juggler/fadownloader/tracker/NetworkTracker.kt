package jp.juggler.fadownloader.tracker

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.*
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
import java.util.regex.Pattern
import kotlin.math.min

/*
	DownloadService のonCreateで作られ、onDestroy でdisposeされる
	生存中は何かしらのタイミングでworkerスレッドがnotifyされて処理を開始する
	通信状態を確認・変更を行ってから適当な時間待機、を繰り返す
*/

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
	
	interface Callback {
		fun onConnectionStatus(is_connected : Boolean, cause : String)
		fun onTetheringOff()
	}
	
	// 設定。初期状態は無害な値にすること
	class Setting(
		val force_wifi : Boolean = false,
		val target_ssid : String = "",
		val target_type : Int = 0,
		val target_url : String = "http://flashair/",
		
		val tetherSprayInterval : Long = 1000L,
		val tetherTestConnectionTimeout : Long = 1000L,
		val wifiChangeApInterval : Long = 1000L,
		val wifiScanInterval : Long = 1000L,
		
		val stopWhenTetheringOff : Boolean = false
	)
	
	@Volatile
	private var worker : Worker? = null
	
	@Volatile
	private var setting : Setting = Setting()
	
	@Volatile
	private var timeLastSpray = 0L
	
	@Volatile
	private var timeLastWiFiScan : Long = 0
	
	@Volatile
	private var timeLastWiFiApChange : Long = 0
	
	@Volatile
	private var timeLastTargetDetected = 0L
	
	@Volatile
	var bLastConnected : Boolean = false
	
	private val testerMap = HashMap<String, UrlTester>()
	
	val lastTargetUrl = AtomicReferenceNotNull("")
	
	private var lastStatusWarning = AtomicReferenceNotNull("")
	private var lastStatusError = AtomicReferenceNotNull("")
	private var lastStatusNetwork = AtomicReferenceNotNull("")
	private var lastOtherActive = AtomicReferenceNotNull("")
	
	val otherActive : String
		get() = lastOtherActive.get()
	
	private val isDisposed : Boolean
		get() = worker == null
	
	private val wifiManager =
		context.applicationContext.getSystemService(Context.WIFI_SERVICE)
			as WifiManager
	
	private val connectivityManager =
		context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
			as ConnectivityManager
	
	private lateinit var networkCallback : Any
	
	init {
		context.registerReceiver(this, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
		if(Build.VERSION.SDK_INT >= 28) {
			networkCallback = object : ConnectivityManager.NetworkCallback() {
				override fun onCapabilitiesChanged(
					network : Network?,
					networkCapabilities : NetworkCapabilities?
				) {
					super.onCapabilitiesChanged(network, networkCapabilities)
					worker?.notifyEx()
				}
				
				override fun onLost(network : Network?) {
					super.onLost(network)
					worker?.notifyEx()
				}
				
				override fun onLinkPropertiesChanged(
					network : Network?,
					linkProperties : LinkProperties?
				) {
					super.onLinkPropertiesChanged(network, linkProperties)
					worker?.notifyEx()
				}
				
				override fun onUnavailable() {
					super.onUnavailable()
					worker?.notifyEx()
				}
				
				override fun onLosing(network : Network?, maxMsToLive : Int) {
					super.onLosing(network, maxMsToLive)
					worker?.notifyEx()
				}
				
				override fun onAvailable(network : Network?) {
					super.onAvailable(network)
					worker?.notifyEx()
				}
			}
			
			(context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
				?.registerNetworkCallback(
					NetworkRequest.Builder()
						.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
						.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
						.build()
					, networkCallback as ConnectivityManager.NetworkCallback
				)
		} else {
			@Suppress("DEPRECATION")
			context.registerReceiver(this, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
		}
		context.registerReceiver(this, IntentFilter(TETHER_STATE_CHANGED))
		worker = Worker().apply {
			start()
		}
	}
	
	fun dispose() {
		if(Build.VERSION.SDK_INT >= 28) {
			(context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
				?.unregisterNetworkCallback(networkCallback as ConnectivityManager.NetworkCallback)
		}
		context.unregisterReceiver(this)
		worker?.cancel("disposed")
		worker = null
	}
	
	// 設定の変化
	fun updateSetting(setting : Setting) {
		if(isDisposed) return
		
		this.setting = setting
		logStatic.d("updateSetting: targetType=${setting.target_type}")
		
		timeLastTargetDetected = 0L
		timeLastSpray = 0L
		timeLastWiFiApChange = 0L
		timeLastWiFiScan = 0L
		lastTargetUrl.set("")
		
		worker?.notifyEx()
	}
	
	// ネットワーク状態の表示
	fun getStatus() : String {
		return lastStatusNetwork.get()
	}
	
	// WiFiスキャン完了、ネットワーク接続状態の変化、テザリング状態の変化などのブロードキャスト受信イベント
	override fun onReceive(context : Context, intent : Intent) {
		
		if(intent.action == TETHER_STATE_CHANGED) {
			val sb = StringBuilder("TETHER_STATE_CHANGED. ")
			val extras = intent.extras
			if(extras != null) for(key in extras.keySet()) {
				when(val v = extras[key]) {
					is ArrayList<*> -> sb.append("$key=[${v.joinToString("/")}],")
					is Array<*> -> sb.append("$key=[${v.joinToString("/")}],")
					else -> sb.append("$key=$v,")
				}
			}
			log.d(sb.toString())
		}
		
		worker?.notifyEx()
	}
	
	///////////////////////////////////////////////////////////////////////////
	// テザリングモード用のユーティリティ
	
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
		
		try {
			val timeStart = SystemClock.elapsedRealtime()
			
			DatagramSocket().use { socket ->
				
				val data = ByteArray(1)
				
				for(n in 2 .. 254) {
					val try_ip = "$ip_base$n"
					if(try_ip == nw_addr) continue
					try {
						val packet = DatagramPacket(
							data,
							data.size,
							InetAddress.getByName(try_ip),
							80
						)
						socket.send(packet)
					} catch(ex : Throwable) {
						log.trace(ex, "DatagramPacket.send failed")
					}
					
				}
			}
			
			val time = SystemClock.elapsedRealtime() - timeStart
			log.v("sent UDP packet to '$ip_base*' (${time}ms)")
			
		} catch(ex : Throwable) {
			log.trace(ex, "sprayUDPPacket failed.")
			log.e(ex, "sprayUDPPacket failed.")
		}
	}
	
	///////////////////////////////////////////////////////////////////////////
	// API 26以降でpriorityは使えなくなった
	
	@Suppress("DEPRECATION")
	private fun getPriority(wc : WifiConfiguration) : Int {
		return wc.priority
	}
	
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
	
	///////////////////////////////////////////////////////////////////////////
	
	// 種別ごとのネットワーク接続の状況
	private class NetworkStatus(
		
		val type_name : String,
		val sub_name : String? = null,
		var is_active : Boolean = false,
		var strWifiStatus : String? = null
	)
	
	private class NetworkStatusList : ArrayList<NetworkStatus>() {
		
		var statusWarning : String? = null
		var statusError : String? = null
		
		var wifi_status : NetworkStatus? = null
		
		var other_active : String = ""
		
		@TargetApi(23)
		fun addNetwork(nc : NetworkCapabilities?, is_active : Boolean) {
			nc ?: return
			
			val ns = NetworkStatus(
				type_name = when {
					nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
					nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE) -> "WIFI_AWARE"
					nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
					nc.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
					nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
					nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
					nc.hasTransport(NetworkCapabilities.TRANSPORT_LOWPAN) -> "LOWPAN"
					else -> "?"
				},
				sub_name = null,
				is_active = is_active
			)
			
			// hasTransport は isConnectedを兼ねてるらしい
			val wifiConnected = nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
			if(wifiConnected) {
				this.add(ns)
				wifi_status = ns
			} else if(is_active) {
				this.add(ns)
				other_active = ns.type_name
			}
		}
		
		fun addNetworkInfo(ni : NetworkInfo?, is_active : Boolean) {
			ni ?: return
			
			@Suppress("DEPRECATION")
			val ns = NetworkStatus(
				type_name = ni.typeName,
				sub_name = ni.subtypeName,
				is_active = is_active
			)
			
			@Suppress("DEPRECATION")
			val is_wifi = ni.type == ConnectivityManager.TYPE_WIFI
			
			if(is_wifi) {
				this.add(ns)
				wifi_status = ns
			} else if(is_active) {
				this.add(ns)
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
		
		override fun toString() : String {
			
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
				val conn = URL(checkUrl).openConnection() as HttpURLConnection
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
						log.e("HTTP error $resCode. url=$checkUrl")
					}
				} finally {
					try {
						conn.disconnect()
					} catch(ignored : Throwable) {
					}
				}
			} catch(ex : Throwable) {
				error = ex
			} finally {
				val time = SystemClock.elapsedRealtime() - timeStart
				
				when(error) {
					null -> {
						if(bFound) callback(this)
					}
					
					is InterruptedException -> {
						// キャンセル時に発生する。このエラーは報告しない
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
			}
		}
	}
	
	private val onUrlTestComplete : (UrlTester) -> Unit = { tester ->
		synchronized(testerMap) {
			testerMap.remove(tester.checkUrl)
			if(! isDisposed && tester.setting.target_type == setting.target_type) {
				val targetUrl = tester.targetUrl
				if(targetUrl != lastTargetUrl.get()) {
					log.i("target detected. %s", targetUrl)
					lastTargetUrl.set(targetUrl)
				}
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
			// log.v("${checkUrl}の確認を開始")
			tester = UrlTester(setting, log, targetUrl, checkUrl, onUrlTestComplete)
			testerMap[checkUrl] = tester
			tester.start()
		}
	}
	
	// テザリングの状態確認や検出を行う
	// 接続先が見つかったら0L、またはリトライまでの時間(ミリ秒)を返す
	private fun detectTetheringClient(
		env : NetworkStatusList,
		getCheckUrl : (String) -> String
	) : Long {
		
		if(! isTetheringEnabled) {
			env.statusError = "Wi-Fi Tethering is not enabled."
			
			if(setting.stopWhenTetheringOff) {
				Utils.runOnMainThread {
					if(! isDisposed) callback.onTetheringOff()
				}
			}
			
			// TETHER_STATE_CHANGED があるのでリトライ間隔は長めでもよさそう
			return 5000L
		}
		
		val tethering_address = tetheringAddress
		if(tethering_address == null) {
			env.statusError = "Wi-Fi Tethering is missing IP address."
			return 1000L
		}
		
		val now = SystemClock.elapsedRealtime()
		
		// ターゲットが見つかってからしばらくの間は検出を行わない
		if(now - timeLastTargetDetected < 10000L) {
			return 0L
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
			env.statusError = "Can't read ARP table."
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
				env.statusError = "missing devices in ARP table."
			} else {
				env.statusWarning = "devices: ${list.joinToString(",")}"
				for(item_ip in list) {
					startTestUrl("http://$item_ip/", getCheckUrl)
				}
			}
		}
		
		// デバイスのアドレスを知るため、定期的にUDPパケットをばらまく
		// (アプリからARPリクエストを投げるのは難しい)
		// 次回以降のチェックでARPテーブルに反映されるかもしれない
		val remain = timeLastSpray + setting.tetherSprayInterval - now
		return if(remain > 0L) {
			min(remain, 1000L)
		} else {
			timeLastSpray = now
			sprayUDPPacket(tethering_address, ip_base)
			1000L
		}
	}
	
	// Wi-Fi AP の状態確認や強制を行う
	// 接続先が見つかったら0L、またはリトライまでの時間(ミリ秒)を返す
	private fun checkWiFiAp(ns_list : NetworkStatusList) : Long {
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
			ns_list.statusError = ex.withCaption("setWifiEnabled() failed.")
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
			ns_list.statusError = ex.withCaption("getConnectionInfo() failed.")
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
					ns_list.statusWarning =
						context.getString(
							R.string.wifi_target_ssid_not_found,
							setting.target_ssid
						)
					return Long.MAX_VALUE
					
				}
			}
		} catch(ex : Throwable) {
			log.trace(ex, "getConfiguredNetworks() failed.")
			ns_list.statusError = ex.withCaption("getConfiguredNetworks() failed.")
			return 10000L
		}
		
		if(Build.VERSION.SDK_INT < 26) {
			// API level 25まではAPの優先順位を変えることができた
			val error = updatePriority(target_config, priority_max)
			if(error != null) ns_list.statusError = error
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
			ns_list.statusError = ex.withCaption("getScanResults() failed.")
			return 10000L
		}
		
		// スキャン範囲内にない場合、定期的にスキャン開始
		if(! found_in_scan) {
			ns_list.statusWarning =
				context.getString(R.string.wifi_target_ssid_not_scanned, setting.target_ssid)
			val now = SystemClock.elapsedRealtime()
			val remain = timeLastWiFiScan + setting.wifiScanInterval - now
			return if(remain > 0L) {
				val lastSeenBefore = if(lastSeen == null) null else now - (lastSeen / 1000L)
				logStatic.d("${setting.target_ssid} is not found in latest scan result(${lastSeenBefore}ms before). next scan is start after ${remain}ms.")
				min(remain, 3000L)
			} else try {
				timeLastWiFiScan = now
				try {
					log.d(R.string.wifi_scan_start)
					@Suppress("DEPRECATION")
					wifiManager.startScan()
				} catch(ex : Throwable) {
				}
				3000L
			} catch(ex : Throwable) {
				log.trace(ex, "startScan() failed.")
				ns_list.statusError = ex.withCaption("startScan() failed.")
				10000L
			}
		}
		
		// スキャン範囲内にある場合、定期的にAP変更
		
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
				ns_list.statusError =
					ex.withCaption("disableNetwork() or enableNetwork() failed.")
				10000L
			}
		}
	}
	
	// 接続先が見つかったら0L
	// またはリトライまでの時間(ミリ秒)を返す
	private fun checkNetwork() : Long {
		
		val ns_list = NetworkStatusList()
		
		try {
			
			// 現在のネットワーク接続を列挙する
			if(Build.VERSION.SDK_INT >= 23) {
				val active_handle : Long? = connectivityManager.activeNetwork?.networkHandle
				
				connectivityManager.allNetworks?.forEach { n ->
					n ?: return@forEach
					val isActive = active_handle?.equals(n.networkHandle) == true
					if(Build.VERSION.SDK_INT >= 28) {
						ns_list.addNetwork(
							connectivityManager.getNetworkCapabilities(n),
							isActive
						)
					} else {
						ns_list.addNetworkInfo(
							connectivityManager.getNetworkInfo(n),
							isActive
						)
					}
				}
				
			} else {
				@Suppress("DEPRECATION")
				val active_name = connectivityManager.activeNetworkInfo?.typeName
				
				@Suppress("DEPRECATION")
				connectivityManager.allNetworkInfo?.forEach { ni ->
					ni ?: return@forEach
					val isActive = active_name?.equals(ni.typeName) == true
					ns_list.addNetworkInfo(ni, isActive)
				}
			}
			ns_list.afterAddAll()
			lastOtherActive.set(ns_list.other_active)
			
			logStatic.d("checkNetwork:targetType =${setting.target_type}")
			// ターゲット種別により、テザリング用とAP用の処理に分かれる
			return when(setting.target_type) {
				
				Pref.TARGET_TYPE_FLASHAIR_STA -> {
					detectTetheringClient(ns_list) { "${it}command.cgi?op=108" }
				}
				
				Pref.TARGET_TYPE_PQI_AIR_CARD_TETHER -> {
					detectTetheringClient(ns_list) { "${it}cgi-bin/get_config.pl" }
				}
				
				else -> {
					checkWiFiAp(ns_list)
				}
			}
			
		} finally {
			// 状態の変化があった時だけログに出力する
			
			var sv : String? = ns_list.toString()
			
			if(sv?.isNotEmpty() == true && sv != lastStatusNetwork.get()) {
				lastStatusNetwork.set(sv)
				log.d(context.getString(R.string.network_status, sv))
			}
			
			sv = ns_list.statusError
			if(sv?.isNotEmpty() == true && sv != lastStatusError.get()) {
				lastStatusError.set(sv)
				log.e(sv)
			}
			
			sv = ns_list.statusWarning
			if(sv?.isNotEmpty() == true && sv != lastStatusWarning.get()) {
				lastStatusWarning.set(sv)
				log.w(sv)
			}
		}
	}
	
	private inner class Worker : WorkerBase() {
		
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
				
				val remain = try {
					checkNetwork()
				} catch(ex : Throwable) {
					log.trace(ex, "network check failed.")
					log.e(ex, "network check failed.")
					5000L
				}
				
				if(isCancelled) break
				
				// 接続状態の変化
				val bConnected = remain <= 0L
				if(bConnected != bLastConnected) {
					bLastConnected = bConnected
					Utils.runOnMainThread {
						if(isDisposed) return@runOnMainThread
						try {
							callback.onConnectionStatus(true, "Wi-Fi tracker")
						} catch(ex : Throwable) {
							log.trace(ex, "connection event handling failed.")
							log.e(ex, "connection event handling failed.")
						}
					}
				}
				
				logStatic.d("run: remain=$remain")
				waitEx(if(remain <= 0L) 5000L else if(remain > 10000L) 10000L else remain)
			}
		}
	}
}
