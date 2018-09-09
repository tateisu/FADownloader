package jp.juggler.fadownloader.picker

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.AsyncTask
import android.os.Bundle
import android.os.Environment
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import jp.juggler.fadownloader.R
import jp.juggler.fadownloader.util.LogTag
import jp.juggler.fadownloader.util.Utils
import jp.juggler.fadownloader.util.withCaption
import java.io.File
import java.util.*

class FolderPicker : AppCompatActivity(), View.OnClickListener, AdapterView.OnItemClickListener {
	
	
	companion object {
		private val log = LogTag("FolderPicker")
		
		internal fun parseExistPath(pathArg : String?) : File {
			var path = pathArg
			while(true) {
				try {
					if(path?.isEmpty() != false ) path =
						Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
							.absolutePath
				} catch(ignored : Throwable) {
				}
				
				try {
					if(path?.isEmpty() != false) path =
						Environment.getExternalStorageDirectory().absolutePath
				} catch(ignored : Throwable) {
				}
				
				try {
					if(path?.isEmpty() != false) path = "/"
				} catch(ignored : Throwable) {
				}
				
				val f = File(path )
				if(f.isDirectory) return f
				path = null
			}
		}
		
		const val EXTRA_FOLDER = "folder"
		
		fun open(activity : Activity, request_code : Int, path : String) {
			try {
				val intent = Intent(activity, FolderPicker::class.java)
				intent.putExtra(EXTRA_FOLDER, path)
				activity.startActivityForResult(intent, request_code)
			} catch(ex : Throwable) {
				log.trace(ex,"open failed.")
			}
			
		}
	}
	
	internal lateinit var tvCurrentFolder : TextView
	internal lateinit var btnFolderUp : View
	internal lateinit var btnSubFolder : View
	private lateinit var lvFileList : ListView
	internal lateinit var btnSelectFolder : Button
	internal lateinit var list_adapter : ArrayAdapter<String>

	internal var showing_folder : File = File("/")
	
	override fun onClick(v : View) {
		when(v.id) {
			R.id.btnFolderUp -> loadFolder(showing_folder.parentFile)
			
			R.id.btnSelectFolder -> {
				val intent = Intent()
				intent.putExtra(EXTRA_FOLDER, showing_folder.absolutePath)
				setResult(Activity.RESULT_OK, intent)
				finish()
			}
			
			R.id.btnSubFolder -> openFolderCreateDialog()
		}
	}
	
	override fun onItemClick(parent : AdapterView<*>, view : View, position : Int, id : Long) {
		val name = list_adapter.getItem(position)
		if(name != null) {
			val folder = File(showing_folder, name)
			if(! folder.isDirectory) {
				Utils.showToast(this, false, R.string.folder_not_directory)
			} else if(! folder.canWrite()) {
				Utils.showToast(this, false, R.string.folder_not_writable)
			} else {
				loadFolder(folder)
			}
		}
	}
	
	override fun onSaveInstanceState(outState : Bundle) {
		super.onSaveInstanceState(outState)
		outState.putString(EXTRA_FOLDER, showing_folder.absolutePath)
	}
	
	override fun onCreate(savedInstanceState : Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.folder_picker)
		
		tvCurrentFolder = findViewById<View>(R.id.tvCurrentFolder) as TextView
		btnFolderUp = findViewById(R.id.btnFolderUp)
		btnSubFolder = findViewById(R.id.btnSubFolder)
		lvFileList = findViewById<View>(R.id.lvFileList) as ListView
		btnSelectFolder = findViewById<View>(R.id.btnSelectFolder) as Button
		
		btnFolderUp.setOnClickListener(this)
		btnSelectFolder.setOnClickListener(this)
		btnSubFolder.setOnClickListener(this)
		list_adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
		lvFileList.adapter = list_adapter
		lvFileList.onItemClickListener = this
		
		showing_folder = if(savedInstanceState == null) {
			parseExistPath(
				intent.getStringExtra(
					EXTRA_FOLDER
				)
			)
		} else {
			parseExistPath(
				savedInstanceState.getString(
					EXTRA_FOLDER
				)
			)
		}
		
		loadFolder(showing_folder)
	}
	
	private fun loadFolder(folder : File) {
		tvCurrentFolder.setText(R.string.loading)
		btnFolderUp.isEnabled = false
		btnSubFolder.isEnabled = false
		btnSelectFolder.isEnabled = false
		list_adapter.clear()

		@SuppressLint("StaticFieldLeak")
		val task : AsyncTask<Void, Void, ArrayList<String>> = object : AsyncTask<Void, Void, ArrayList<String>>() {
			override fun doInBackground(vararg params : Void) : ArrayList<String> {
				val result = ArrayList<String>()
				try {
					for(sub in folder.listFiles()) {
						if(! sub.isDirectory) continue
						val name = sub.name
						if("build/generated/source/rs/rc" == name) continue
						if("" == name) continue
						result.add(name)
					}
				} catch(ex : Throwable) {
					log.trace(ex,"loadFolder failed.")
				}
				
				Collections.sort(result, String.CASE_INSENSITIVE_ORDER)
				return result
			}
			
			override fun onPostExecute(result : ArrayList<String>?) {
				if(result != null) {
					showing_folder = folder
					tvCurrentFolder.text = folder.absolutePath
					btnFolderUp.isEnabled = folder.absolutePath != "/"
					btnSubFolder.isEnabled = true
					btnSelectFolder.text = getString(R.string.folder_select, folder.absolutePath)
					btnSelectFolder.isEnabled = true
					list_adapter.addAll(result)
				}
			}
		}
		task.execute()
	}
	
	private fun openFolderCreateDialog() {
		val root = layoutInflater.inflate(R.layout.folder_create_dialog, null, false)
		val btnCancel = root.findViewById<View>(R.id.btnCancel)
		val btnOk = root.findViewById<View>(R.id.btnOk)
		val etName = root.findViewById<View>(R.id.etName) as EditText
		val d = Dialog(this)
		d.setTitle(getString(R.string.create_sub_folder))
		d.setContentView(root)
		
		d.window ?.setLayout(
			WindowManager.LayoutParams.MATCH_PARENT,
			WindowManager.LayoutParams.WRAP_CONTENT
		)
		d.show()
		etName.setOnEditorActionListener(TextView.OnEditorActionListener { _, actionId, _ ->
			if(actionId == EditorInfo.IME_ACTION_DONE) {
				btnOk.performClick()
				return@OnEditorActionListener true
			}
			false
		})
		btnCancel.setOnClickListener { d.dismiss() }
		btnOk.setOnClickListener {_->
			try {
				val name = etName.text.toString().trim { it <= ' ' }
				if(name.isEmpty()) {
					Utils.showToast(this@FolderPicker, false,
						R.string.folder_name_empty
					)
				} else {
					val folder = File(showing_folder, name)
					if(folder.exists()) {
						Utils.showToast(this@FolderPicker, false,
							R.string.folder_already_exist
						)
					} else if(! folder.mkdir()) {
						Utils.showToast(this@FolderPicker, false,
							R.string.folder_creation_failed
						)
					} else {
						d.dismiss()
						loadFolder(folder)
					}
				}
			} catch(ex : Throwable) {
				log.trace(ex,"folder creation failed.")
				Utils.showToast(this@FolderPicker,true, ex.withCaption( "folder creation failed."))
			}
		}
	}

}
