package jp.juggler.fadownloader

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import jp.juggler.fadownloader.util.LogTag

class ActTaskerSettingAction : AppCompatActivity(), View.OnClickListener {
	
	companion object {
		val log = LogTag("ActTaskerSettingAction")
		
		const val STATE_ACTION = "action"
	}
	
	private lateinit var spAction : Spinner
	
	override fun onCreate(savedInstanceState : Bundle?) {
		super.onCreate(savedInstanceState)
		
		try {
			val callingApplicationLabel = packageManager.getApplicationLabel(
				packageManager.getApplicationInfo(callingPackage, 0)
			)
			title = if(callingApplicationLabel?.isNotEmpty() == true) {
				"$callingApplicationLabel > ${getString(R.string.tasker_setting_action)}"
			}else{
				getString(R.string.tasker_setting_action)
			}
		} catch(ex : PackageManager.NameNotFoundException) {
			log.e(ex, "Calling package couldn't be found")
		}
		
		
		setContentView(R.layout.act_tasker_setting_action)
		
		spAction = findViewById(R.id.spAction)
		val btnSave : View = findViewById(R.id.btnSave)
		
		btnSave.setOnClickListener(this)
		
		initSpinner(
			spAction,
			arrayOf(
				getString(R.string.repeat),
				getString(R.string.once),
				getString(R.string.stop)
			),
			null
		)
		if(savedInstanceState != null) {
			spAction.setSelection(savedInstanceState.getInt(STATE_ACTION, 0))
		} else {
			val b = intent?.extras
			val actionInt = b?.getInt(Receiver1.EXTRA_ACTION, - 1)
			when(actionInt) {
				Pref.LAST_MODE_REPEAT -> spAction.setSelection(0)
				Pref.LAST_MODE_ONCE -> spAction.setSelection(1)
				Pref.LAST_MODE_STOP -> spAction.setSelection(2)
			}
			
		}
	}
	
	override fun onSaveInstanceState(outState : Bundle?) {
		outState ?: return
		super.onSaveInstanceState(outState)
		outState.putInt(STATE_ACTION, spAction.selectedItemPosition)
	}
	
	override fun onClick(v : View?) {
		when(v?.id) {
			R.id.btnSave -> {
				val actionInt = when(spAction.selectedItemPosition) {
					0 -> Pref.LAST_MODE_REPEAT
					1 -> Pref.LAST_MODE_ONCE
					else -> Pref.LAST_MODE_STOP
				}
				val actionString = when(spAction.selectedItemPosition) {
					0 -> "REPEAT"
					1 -> "ONCE"
					else -> "STOP"
				}
				val b = Bundle()
				b.putInt(Receiver1.EXTRA_ACTION, actionInt)
				val intent = Intent()
				intent.putExtra(Receiver1.EXTRA_TASKER_BUNDLE, b)
				intent.putExtra(
					"com.twofortyfouram.locale.intent.extra.BLURB",
					"action=$actionString"
				)
				setResult(RESULT_OK, intent)
				finish()
			}
		}
	}
	
	private fun initSpinner(
		sp : Spinner,
		choices : Array<CharSequence>,
		action : AdapterView.OnItemSelectedListener?
	) {
		val adapter = ArrayAdapter<CharSequence>(this, android.R.layout.simple_spinner_item)
		adapter.setDropDownViewResource(R.layout.spinner_dropdown)
		adapter.addAll(*choices)
		sp.adapter = adapter
		sp.onItemSelectedListener = action
	}
}
