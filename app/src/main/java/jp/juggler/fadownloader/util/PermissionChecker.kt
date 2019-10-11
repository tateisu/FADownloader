package jp.juggler.fadownloader.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

import java.util.ArrayList

object PermissionChecker {
	
	private val permission_list = arrayOf(
		Manifest.permission.INTERNET,
		Manifest.permission.ACCESS_WIFI_STATE,
		Manifest.permission.ACCESS_NETWORK_STATE,
		Manifest.permission.WRITE_EXTERNAL_STORAGE,
		"android.permission.READ_EXTERNAL_STORAGE",
		Manifest.permission.ACCESS_FINE_LOCATION,
		Manifest.permission.ACCESS_COARSE_LOCATION
	)
	
	internal fun getMissingPermissionList(context : Context) : ArrayList<String> {
		val list = ArrayList<String>()
		if(Build.VERSION.SDK_INT >= 23) {
			for(p in permission_list) {
				val r = ContextCompat.checkSelfPermission(context, p)
				if(r != PackageManager.PERMISSION_GRANTED) {
					list.add(p)
				}
			}
		}
		return list
	}
}
