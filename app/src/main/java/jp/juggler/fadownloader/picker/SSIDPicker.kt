package jp.juggler.fadownloader.picker

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import jp.juggler.fadownloader.R
import jp.juggler.fadownloader.util.LogTag
import java.util.*

class SSIDPicker : AppCompatActivity(), AdapterView.OnItemClickListener, View.OnClickListener {
	
	companion object {
		
		private val log = LogTag("SSIDPicker")
		
		const val EXTRA_SSID = "ssid"
		
		fun open(activity : Activity, request_code : Int) {
			try {
				val intent = Intent(activity, SSIDPicker::class.java)
				activity.startActivityForResult(intent, request_code)
			} catch(ex : Throwable) {
				log.trace(ex, "open failed.")
			}
			
		}
	}
	
	private val receiver : BroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context : Context, intent : Intent) {
			updateList()
		}
	}
	
	private lateinit var networkCallback : Any
	
	private lateinit var listView : ListView
	private lateinit var list_adapter : ArrayAdapter<String>
	private lateinit var wifi_manager : WifiManager
	
	override fun onClick(v : View) {
		when(v.id) {
			R.id.btnReload -> reload()
		}
	}
	
	override fun onItemClick(parent : AdapterView<*>, view : View, position : Int, id : Long) {
		val name = list_adapter.getItem(position)
		if(name != null) {
			val intent = Intent()
			intent.putExtra(EXTRA_SSID, name)
			setResult(Activity.RESULT_OK, intent)
			finish()
			
		}
	}
	
	override fun onStart() {
		super.onStart()
		registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
		if( Build.VERSION.SDK_INT >= 28 ){
			(getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
				?. registerNetworkCallback(
					NetworkRequest.Builder()
						.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
						.build()
					,networkCallback as ConnectivityManager.NetworkCallback
				)
		}else{
			@Suppress("DEPRECATION")
			registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
		}
		
		updateList()
		reload()
	}
	
	override fun onStop() {
		super.onStop()
		unregisterReceiver(receiver)
		if( Build.VERSION.SDK_INT >= 28 ){
			(getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
				?. unregisterNetworkCallback( networkCallback as ConnectivityManager.NetworkCallback)
		}
	}
	
	override fun onCreate(savedInstanceState : Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.ssid_picker)
		
		findViewById<View>(R.id.btnReload).setOnClickListener(this)
		
		listView = findViewById<View>(R.id.listView) as ListView
		list_adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
		listView.adapter = list_adapter
		listView.onItemClickListener = this
		
		wifi_manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
		
		if( Build.VERSION.SDK_INT >= 28){
			networkCallback = object: ConnectivityManager.NetworkCallback() {
				override fun onCapabilitiesChanged(
					network : Network?,
					networkCapabilities : NetworkCapabilities?
				) {
					super.onCapabilitiesChanged(network, networkCapabilities)
					updateList()
				}
				
				override fun onLost(network : Network?) {
					super.onLost(network)
					updateList()
				}
				
				override fun onLinkPropertiesChanged(
					network : Network?,
					linkProperties : LinkProperties?
				) {
					super.onLinkPropertiesChanged(network, linkProperties)
					updateList()
				}
				
				override fun onUnavailable() {
					super.onUnavailable()
					updateList()
				}
				
				override fun onLosing(network : Network?, maxMsToLive : Int) {
					super.onLosing(network, maxMsToLive)
					updateList()
				}
				
				override fun onAvailable(network : Network?) {
					super.onAvailable(network)
					updateList()
				}
			}
		}
	}
	
	private fun reload() {
		try {
			// API level 28 でdeprecated
			//  Each foreground app is restricted to 4 scans every 2 minutes.
			@Suppress("DEPRECATION")
			wifi_manager.startScan()
		}catch(ex:Throwable){
		}
	}
	
	private fun updateList() {
		val set = TreeSet<String>()
		try {
			for(wc in wifi_manager.configuredNetworks) {
				val ssid = wc.SSID.replace("\"", "")
				if(ssid.isNotEmpty()) set.add(ssid)
			}
		} catch(ignored : Throwable) {
		}
		
		try {
			for(result in wifi_manager.scanResults) {
				val ssid = result.SSID.replace("\"", "")
				if(ssid.isNotEmpty()) set.add(ssid)
			}
		} catch(ignored : Throwable) {
		}
		
		list_adapter.clear()
		list_adapter.addAll(set)
	}
	
}
