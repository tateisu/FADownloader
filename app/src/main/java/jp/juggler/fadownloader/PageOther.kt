package jp.juggler.fadownloader

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.View
import jp.juggler.fadownloader.table.DownloadRecord
import jp.juggler.fadownloader.util.LogTag
import jp.juggler.fadownloader.util.PagerAdapterBase
import java.util.*

class PageOther(activity : Activity, ignored : View) :
	PagerAdapterBase.PageViewHolder(activity, ignored), View.OnClickListener {
	
	companion object {
		private val log = LogTag("PageOther")
	}
	private lateinit var btnRemoveAd : View
	
	@Throws(Throwable::class)
	override fun onPageCreate(page_idx : Int, root : View) {
		btnRemoveAd = root.findViewById(R.id.btnRemoveAd)
		
		root.findViewById<View>(R.id.btnOSSLicence).setOnClickListener(this)
		root.findViewById<View>(R.id.btnWifiSetting).setOnClickListener(this)
		root.findViewById<View>(R.id.btnOpenMap).setOnClickListener(this)
		root.findViewById<View>(R.id.btnRecordClear).setOnClickListener(this)
		root.findViewById<View>(R.id.btnRemoveAd).setOnClickListener(this)
		
		updatePurchaseButton()
	}
	
	@Throws(Throwable::class)
	override fun onPageDestroy(page_idx : Int, root : View) {
	}
	
	override fun onClick(view : View) {
		when(view.id) {
			
			R.id.btnRecordClear -> activity.contentResolver.delete(
				DownloadRecord.meta.content_uri,
				null,
				null
			)
			
			R.id.btnOSSLicence -> (activity as ActMain).openHelp(activity.getString(R.string.oss_license_long_help))
			
			R.id.btnRemoveAd -> (activity as ActMain).startRemoveAdPurchase()
			
			R.id.btnWifiSetting -> try {
				activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
			} catch(ex : Throwable) {
				log.trace(ex,"btnWifiSetting failed")
			}
			
			R.id.btnOpenMap -> try {
				val location = DownloadService.location
				if(location != null) {
					val intent = Intent(
						Intent.ACTION_VIEW,
						Uri.parse(
							String.format(
								Locale.JAPAN,
								"geo:%f,%f",
								location.latitude,
								location.longitude
							)
						)
					)
					activity.startActivity(intent)
				} else {
					val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:"))
					activity.startActivity(intent)
				}
			} catch(ex : Throwable) {
				log.trace(ex,"btnOpenMap failed")
			}
			
		}
	}
	
	fun updatePurchaseButton() {
		val act = activity as ActMain
		
		btnRemoveAd.visibility = if(act.bSetupCompleted && ! act.bRemoveAdPurchased) View.VISIBLE else View.GONE
	}
}
