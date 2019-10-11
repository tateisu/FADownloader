package jp.juggler.fadownloader

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import jp.juggler.fadownloader.util.LogTag

class ActTaskerSettingCondition : AppCompatActivity(), View.OnClickListener {
	
	companion object {
		val log = LogTag("ActTaskerSettingCondition")
		
	}
	
	override fun onCreate(savedInstanceState : Bundle?) {
		super.onCreate(savedInstanceState)
		
		try {
			val callingApplicationLabel = packageManager.getApplicationLabel(
				packageManager.getApplicationInfo(callingPackage, 0)
			)
			title = if(callingApplicationLabel?.isNotEmpty() == true) {
				"$callingApplicationLabel > ${getString(R.string.tasker_setting_condition)}"
			}else{
				getString(R.string.tasker_setting_condition)
			}
		} catch(ex : PackageManager.NameNotFoundException) {
			log.e(ex, "Calling package couldn't be found")
		}
		
		setContentView(R.layout.act_tasker_setting_condition)
		
		findViewById<View>(R.id.btnSave).setOnClickListener(this)
		
	}
	
	override fun onClick(v : View?) {
		when(v?.id) {
			R.id.btnSave -> {
				val b = Bundle()
				val intent = Intent()
				intent.putExtra(Receiver1.EXTRA_TASKER_BUNDLE, b)
				intent.putExtra(
					"com.twofortyfouram.locale.intent.extra.BLURB",
					getString(R.string.is_alive_service)
				)
				setResult(RESULT_OK, intent)
				finish()
			}
		}
	}
}
