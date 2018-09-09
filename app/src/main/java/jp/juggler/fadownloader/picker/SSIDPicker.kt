package jp.juggler.fadownloader.picker

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import jp.juggler.fadownloader.R
import java.util.*

class SSIDPicker : AppCompatActivity(), AdapterView.OnItemClickListener, View.OnClickListener {
	
	companion object {
		
		const val EXTRA_SSID = "ssid"
		
		fun open(activity : Activity, request_code : Int) {
			try {
				val intent = Intent(activity, SSIDPicker::class.java)
				activity.startActivityForResult(intent, request_code)
			} catch(ex : Throwable) {
				ex.printStackTrace()
			}
			
		}
	}
	
	private val receiver : BroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context : Context, intent : Intent) {
			updateList()
		}
	}
	
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
		registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
		updateList()
		reload()
	}
	
	override fun onStop() {
		super.onStop()
		unregisterReceiver(receiver)
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
	}
	
	private fun reload() {
		wifi_manager.startScan()
	}
	
	private fun updateList() {
		val set = TreeSet<String>()
		try {
			for(wc in wifi_manager.configuredNetworks) {
				val ssid = wc.SSID.replace("\"", "")
				if(! TextUtils.isEmpty(ssid)) set.add(ssid)
			}
		} catch(ignored : Throwable) {
		}
		
		try {
			for(result in wifi_manager.scanResults) {
				val ssid = result.SSID.replace("\"", "")
				if(! TextUtils.isEmpty(ssid)) set.add(ssid)
			}
		} catch(ignored : Throwable) {
		}
		
		list_adapter.clear()
		list_adapter.addAll(set)
	}
	
}
