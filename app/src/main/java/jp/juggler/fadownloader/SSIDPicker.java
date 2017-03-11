package jp.juggler.fadownloader;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import java.util.TreeSet;


public class SSIDPicker extends AppCompatActivity implements AdapterView.OnItemClickListener, View.OnClickListener{

	public static final String EXTRA_SSID = "ssid";

	public static void open( Activity activity, int request_code ){
		try{
			Intent intent = new Intent( activity, SSIDPicker.class );
			activity.startActivityForResult( intent, request_code );
		}catch( Throwable ex ){
			ex.printStackTrace();
		}
	}

	@Override public void onClick( View v ){
		switch(v.getId()){
		case R.id.btnReload:
			reload();
		}
	}

	@Override public void onItemClick( AdapterView<?> parent, View view, int position, long id ){
		String name = list_adapter.getItem( position );
		if( name != null ){
			Intent intent = new Intent();
			intent.putExtra( EXTRA_SSID, name );
			setResult( Activity.RESULT_OK, intent );
			finish();

		}
	}

	@Override protected void onStart(){
		super.onStart();
		registerReceiver( receiver, new IntentFilter( WifiManager.SCAN_RESULTS_AVAILABLE_ACTION ) );
		registerReceiver( receiver, new IntentFilter( ConnectivityManager.CONNECTIVITY_ACTION ) );
		updateList();
		reload();
	}


	@Override protected void onStop(){
		super.onStop();
		unregisterReceiver( receiver );
	}

	final BroadcastReceiver receiver = new BroadcastReceiver(){
		@Override public void onReceive( Context context, Intent intent ){
			updateList();
		}
	};

	ListView listView;
	ArrayAdapter<String> list_adapter;
	WifiManager wifi_manager;

	@Override protected void onCreate( @Nullable Bundle savedInstanceState ){
		super.onCreate( savedInstanceState );
		setContentView( R.layout.ssid_picker );

		findViewById( R.id.btnReload ).setOnClickListener( this );

		listView = (ListView) findViewById( R.id.listView );
		list_adapter = new ArrayAdapter<>( this, android.R.layout.simple_list_item_1 );
		listView.setAdapter( list_adapter );
		listView.setOnItemClickListener( this );

		wifi_manager = (WifiManager)getApplicationContext().getSystemService( WIFI_SERVICE );
	}


	private void reload(){
		wifi_manager.startScan();
	}

	private void updateList(){
		TreeSet<String> set = new TreeSet<>();
		try{
			for( WifiConfiguration wc : wifi_manager.getConfiguredNetworks() ){
				String ssid = wc.SSID.replace( "\"", "" );
				if(! TextUtils.isEmpty(ssid)) set.add( ssid );
			}
		}catch( Throwable ignored ){
		}
		try{
			for( ScanResult result : wifi_manager.getScanResults() ){
				String ssid = result.SSID.replace( "\"", "" );
				if(! TextUtils.isEmpty(ssid)) set.add( ssid );
			}
		}catch( Throwable ignored ){
		}
		list_adapter.clear();
		list_adapter.addAll( set );
	}
}
