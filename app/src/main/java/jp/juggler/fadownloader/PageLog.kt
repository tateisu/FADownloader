package jp.juggler.fadownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.AsyncTask
import android.os.Process
import android.support.v4.content.FileProvider
import android.support.v7.app.AlertDialog
import android.view.View
import android.widget.ListView
import config.BuildVariant
import jp.juggler.fadownloader.table.LogData
import jp.juggler.fadownloader.util.PagerAdapterBase
import jp.juggler.fadownloader.util.ProgressDialogEx
import jp.juggler.fadownloader.util.Utils
import java.io.File
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.*

class PageLog(activity : Activity, ignored : View) :
	PagerAdapterBase.PageViewHolder(activity, ignored), View.OnClickListener {
	
	companion object {
		internal val filename_date_fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
	}
	
	internal lateinit var lvLog : ListView
	private lateinit var log_viewer : LogViewer
	
	override fun onClick(v : View) {
		when(v.id) {
			R.id.btnLogSend -> log_send()
			
			R.id.btnLogClear -> AlertDialog.Builder(activity)
				.setTitle(R.string.log_clear)
				.setMessage(R.string.log_clear_confirm)
				.setCancelable(true)
				.setNegativeButton(R.string.cancel, null)
				.setPositiveButton(R.string.ok) { _, _ ->
					activity.contentResolver.delete(
						LogData.meta.content_uri,
						null,
						null
					)
				}
				.show()
		}
		
	}
	
	@Throws(Throwable::class)
	override fun onPageCreate(page_idx : Int, root : View) {
		lvLog = root.findViewById<View>(R.id.lvLog) as ListView
		log_viewer = LogViewer()
		
		root.findViewById<View>(R.id.btnLogSend).setOnClickListener(this)
		root.findViewById<View>(R.id.btnLogClear).setOnClickListener(this)
		
		if((activity as ActMain).is_start) {
			onStart()
		}
	}
	
	@Throws(Throwable::class)
	override fun onPageDestroy(page_idx : Int, root : View) {
		onStop()
	}
	
	internal fun onStart() {
		log_viewer.onStart(activity as ActMain, lvLog, ActMain.LOADER_ID_LOG)
	}
	
	internal fun onStop() {
		log_viewer.onStop()
	}
	
	private fun log_send() {

		val progress = ProgressDialogEx(activity)
		
		val task = @SuppressLint("StaticFieldLeak")
		object : AsyncTask<Void, Int, File>() {
			override fun doInBackground(vararg params : Void) : File? {
				try {
					val cache_dir = activity.externalCacheDir
					
					val log_file = File(
						cache_dir, String.format(
							"%s-FADownloader-%s-%s.txt",
							filename_date_fmt.format(System.currentTimeMillis()),
							Process.myPid(),
							Thread.currentThread().id
						)
					)
					val fos = PrintStream(log_file, "UTF-8")
					try {
						activity.contentResolver.query(
							LogData.meta.content_uri,
							null,
							null,
							null,
							LogData.COL_TIME + " asc"
						)?.use { cursor ->
							var i = 0
							var count = cursor.count
							if(count <= 0) count = 1
							val colidx_time = cursor.getColumnIndex(LogData.COL_TIME)
							val colidx_message = cursor.getColumnIndex(LogData.COL_MESSAGE)
							val colidx_level = cursor.getColumnIndex(LogData.COL_LEVEL)
							while(cursor.moveToNext()) {
								if(isCancelled) return null
								publishProgress(i ++, count)
								fos.printf(
									"%s %s/%s\n",
									LogViewer.date_fmt.format(cursor.getLong(colidx_time)),
									LogData.getLogLevelString(cursor.getInt(colidx_level)),
									cursor.getString(colidx_message)
								)
							}
						}
						
					} finally {
						try {
							fos.flush()
							fos.close()
						} catch(ignored : Throwable) {
						
						}
						
					}
					return log_file
					
				} catch(ex : Throwable) {
					ex.printStackTrace()
					Utils.showToast(activity, ex, "log data collection failed.")
				}
				
				return null
			}
			
			override fun onProgressUpdate(vararg values : Int?) {
				if(progress.isShowing) {
					if(values.size > 1) values[1]?.let { progress.max = it }
					if(values.isNotEmpty()) values[0]?.let { progress.progress = it }
				}
			}
			
			override fun onCancelled() {
				progress.dismiss()
			}
			
			override fun onPostExecute(file : File?) {
				progress.dismiss()
				if(file == null) return
				try {
					val uri = FileProvider.getUriForFile(
						activity,
						BuildVariant.FILE_PROVIDER_AUTHORITY,
						file
					)
					// LGV32(Android 6.0)でSDカードを使うと例外発生
					// IllegalArgumentException: Failed to find configured root that contains /storage/3136-6334/image1.jpg
					// ワークアラウンド： FileProviderに指定するpath xml に <root-path  name="pathRoot" path="." /> を追加
					if(uri == null) {
						Utils.showToast(
							activity,
							true,
							"can't get FileProvider URI from %s",
							file.absolutePath
						)
						return
					}
					val mime_type =
						"application/octet-stream" // Utils.getMimeType( file.getAbsolutePath() );
					val intent = Intent(Intent.ACTION_SEND)
					intent.type = mime_type
					intent.addFlags(
						Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
					)
					intent.putExtra(Intent.EXTRA_STREAM, uri)
					activity.startActivityForResult(intent, ActMain.REQUEST_CODE_SEND)
				} catch(ex : Throwable) {
					ex.printStackTrace()
					Utils.showToast(activity, ex, "send failed.")
				}
				
			}
			
		}
		progress.setMessage(activity.getString(R.string.log_collection_progress))
		
		progress.isIndeterminate = false
		progress.setProgressStyle(ProgressDialogEx.STYLE_HORIZONTAL)
		progress.setCancelable(true)
		progress.setOnCancelListener { task.cancel(false) }
		progress.show()
		task.execute()
		
	}
	
}
