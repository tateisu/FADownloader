package jp.juggler.fadownloader;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.v4.content.ContextCompat;

import java.util.ArrayList;

public class PermissionChecker{

	static final String[] permission_list = new String[]{
		Manifest.permission.INTERNET,
		Manifest.permission.ACCESS_WIFI_STATE,
		Manifest.permission.ACCESS_NETWORK_STATE,
		Manifest.permission.WRITE_EXTERNAL_STORAGE,
		Manifest.permission.READ_EXTERNAL_STORAGE,
	};

	static ArrayList<String> getMissingPermissionList( Context context ){
		ArrayList<String> list = new ArrayList<>();
		if( Build.VERSION.SDK_INT >= 23 ){
			for( String p : permission_list ){
				int r = ContextCompat.checkSelfPermission( context, p );
				if( r != PackageManager.PERMISSION_GRANTED ){
					list.add( p );
				}
			}
		}
		return list;
	}

}
