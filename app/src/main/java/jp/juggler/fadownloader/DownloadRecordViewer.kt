package jp.juggler.fadownloader

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.BaseColumns
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.support.v4.app.LoaderManager
import android.support.v4.content.CursorLoader
import android.support.v4.content.Loader
import android.support.v7.app.AppCompatActivity
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import it.sephiroth.android.library.exif2.ExifInterface
import it.sephiroth.android.library.exif2.Rational
import jp.juggler.fadownloader.table.DownloadRecord
import jp.juggler.fadownloader.table.LogData
import jp.juggler.fadownloader.util.Utils
import org.apache.commons.io.IOUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

@SuppressLint("SetTextI18n")
class DownloadRecordViewer {
	
	companion object {
		
		internal val date_fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
		internal val default_thumbnail : Drawable = ColorDrawable(- 0x7f7f80)
		internal val reJPEG = Pattern.compile("\\.jp(g|eg?)\\z", Pattern.CASE_INSENSITIVE)
		
		fun isExternalStorageDocument(uri : Uri) : Boolean {
			return "com.android.externalstorage.documents" == uri.authority
		}
	}
	
	class Holder(
		val activity : AppCompatActivity,
		val listView : ListView
	) : AdapterView.OnItemClickListener, LoaderManager.LoaderCallbacks<Cursor> {
		
		
		private var loader : Loader<Cursor>? = null
		
		init {
			listView.onItemClickListener = this
			
		}
		
		internal fun onStart(loader_id : Int) {
			loader = activity.supportLoaderManager.initLoader(loader_id, null, this)
		}
		
		fun onStop() : Int {
			val loader = this.loader
			if(loader != null) {
				activity.supportLoaderManager.destroyLoader(loader.id)
				this.loader = null
			}
			val rv = listView.firstVisiblePosition
			listView.adapter = null
			return rv
		}
		
		override fun onCreateLoader(id : Int, args : Bundle) : Loader<Cursor>? {
			return CursorLoader(
				activity,
				DownloadRecord.meta.content_uri,
				null,
				null,
				null,
				LogData.COL_TIME + " desc"
			)
		}
		
		override fun onLoadFinished(loader : Loader<Cursor>, cursor : Cursor) {
			val adapter = listView.adapter as? RecordAdapter
			if(adapter == null) {
				listView.adapter = RecordAdapter(activity, cursor)
			} else {
				adapter.swapCursor(cursor)
			}
		}
		
		override fun onLoaderReset(loader : Loader<Cursor>) {
			(listView.adapter as? RecordAdapter)?.swapCursor(null)
		}
		
		fun reload() = (listView.adapter as? RecordAdapter)?.reload()
		
		internal inner class RecordAdapter(val context : Context, c : Cursor) :
			CursorAdapter(context, c, false) {
			
			private val colIdx = DownloadRecord.ColIdx()
			private val inflater : LayoutInflater = activity.layoutInflater
			private val thumbnail_size : Int =
				(0.5f + 64f * context.resources.displayMetrics.density).toInt()
			
			val data = DownloadRecord()
			
			private var bThumbnailAutoRotate =
				Pref.uiAutoRotateThumbnail(Pref.pref(context))
			
			fun loadAt(position : Int) : DownloadRecord? {
				val cursor = cursor
				if(cursor.moveToPosition(position)) {
					val result = DownloadRecord()
					result.loadFrom(cursor, colIdx)
					return result
				}
				return null
			}
			
			fun reload() {
				this.bThumbnailAutoRotate = Pref.uiAutoRotateThumbnail(Pref.pref(context))
				notifyDataSetChanged()
			}
			
			override fun newView(context : Context, cursor : Cursor, viewGroup : ViewGroup) : View {
				val root = inflater.inflate(R.layout.lv_download_record, viewGroup, false)
				root.tag = ViewHolder(root)
				return root
			}
			
			override fun bindView(view : View, context : Context, cursor : Cursor) {
				data.loadFrom(cursor, colIdx)
				
				(view.tag as? ViewHolder)
					?.bind(data, thumbnail_size, bThumbnailAutoRotate)
			}
			
		}
		
		internal inner class ViewHolder(root : View) {
			
			val tvName : TextView = root.findViewById<View>(R.id.tvName) as TextView
			val tvTime : TextView = root.findViewById<View>(R.id.tvTime) as TextView
			val tvStateCode : TextView = root.findViewById<View>(R.id.tvStateCode) as TextView
			val ivThumbnail : ImageView = root.findViewById<View>(R.id.ivThumbnail) as ImageView
			
			var last_image_uri : String? = null
			var last_task : AsyncTask<Void, Void, Bitmap>? = null
			var last_bitmap : Bitmap? = null
			val matrix = Matrix()
			private var time_str : String = ""
			var exif_info : String? = null
			private var bThumbnailAutoRotate : Boolean = false
			
			fun bind(
				data : DownloadRecord,
				thumbnail_size : Int,
				bThumbnailAutoRotate : Boolean
			) {
				this.time_str = date_fmt.format(data.time)
				tvName.text = File(data.air_path).name
				tvStateCode.text =
					DownloadRecord.formatStateText(activity, data.state_code, data.state_message)
				tvStateCode.setTextColor(DownloadRecord.getStateCodeColor(data.state_code))
				
				if(last_image_uri != null
					&& last_image_uri == data.local_file
					&& this.bThumbnailAutoRotate == bThumbnailAutoRotate) {
					// 画像を再ロードする必要がない
					showTimeAndExif()
				} else {
					ivThumbnail.setImageDrawable(default_thumbnail)
					exif_info = null
					showTimeAndExif()
					
					if(last_task != null) {
						last_task !!.cancel(true)
						last_task = null
					}
					
					if(last_bitmap != null) {
						last_bitmap !!.recycle()
						last_bitmap = null
					}
					
					this.bThumbnailAutoRotate = bThumbnailAutoRotate
					this.last_image_uri = data.local_file
					
					val image_uri = this.last_image_uri
					
					if(image_uri?.isNotEmpty() == true && reJPEG.matcher(data.air_path).find()) {
						last_task = @SuppressLint("StaticFieldLeak")
						object : AsyncTask<Void, Void, Bitmap>() {
							var orientation : Int? = null
							var exif_list = LinkedList<String>()
							
							override fun doInBackground(vararg params : Void) : Bitmap? {
								// たくさんキューイングされるので、開始した時点で既にキャンセルされていることがありえる
								if(isCancelled) return null
								try {
									val inStream : InputStream? = if(image_uri.startsWith("/")) {
										FileInputStream(image_uri)
									} else {
										activity.contentResolver.openInputStream(
											Uri.parse(image_uri)
										)
									}
									if(inStream != null) {
										var rv : Rational?
										var sv : String
										try {
											if(isCancelled) return null
											val exif = ExifInterface()
											exif.readExif(
												inStream,
												ExifInterface.Options.OPTION_ALL
											)
											
											if(bThumbnailAutoRotate) {
												orientation =
													exif.getTagIntValue(ExifInterface.TAG_ORIENTATION)
											}
											
											rv =
												exif.getTagRationalValue(ExifInterface.TAG_FOCAL_LENGTH)
											if(rv != null) {
												sv = String.format("%.1f", rv.toDouble())
													.replace("\\.0*$".toRegex(), "")
												exif_list.add(sv + "mm")
											}
											
											rv =
												exif.getTagRationalValue(ExifInterface.TAG_F_NUMBER)
											if(rv != null) {
												sv = String.format("%.1f", rv.toDouble())
													.replace("\\.0*$".toRegex(), "")
												exif_list.add("F$sv")
											}
											
											rv =
												exif.getTagRationalValue(ExifInterface.TAG_EXPOSURE_TIME)
											if(rv != null) {
												val dv = rv.toDouble()
												if(dv > 0.25) {
													sv = String.format("%.1f", dv)
														.replace("\\.0*$".toRegex(), "")
													exif_list.add(sv + "s")
												} else {
													sv = String.format("%.1f", 1 / dv)
														.replace("\\.0*$".toRegex(), "")
													exif_list.add("1/" + sv + "s")
												}
											}
											
											var iso_done = false
											var iv =
												exif.getTagIntValue(ExifInterface.TAG_SENSITIVITY_TYPE)
											if(iv?.toShort() == ExifInterface.SensitivityType.SOS) {
												val lv =
													exif.getTagLongValue(ExifInterface.TAG_STANDARD_OUTPUT_SENSITIVITY)
												if(lv != null) {
													exif_list.add("ISO$lv")
													iso_done = true
												}
											}
											if(! iso_done) {
												iv =
													exif.getTagIntValue(ExifInterface.TAG_ISO_SPEED_RATINGS /*旧形式*/)
												if(iv != null) {
													exif_list.add("ISO$iv")
												}
											}
											
											rv =
												exif.getTagRationalValue(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)
											if(rv != null) {
												val d = rv.toDouble()
												exif_list.add(
													when {
														d == 0.0 -> String.format("\u00b1%.1f", d)
														d > 0f -> String.format("+%.1f", d)
														else -> String.format("%.1f", d)
													}
												)
											}
											
											sv = exif.getTagStringValue(ExifInterface.TAG_MODEL)
											if(! TextUtils.isEmpty(sv)) {
												exif_list.add(trimModelName(sv))
											}
											
											return if(isCancelled) null else exif.thumbnailBitmap
										} finally {
											try {
												inStream.close()
											} catch(ignored : Throwable) {
											}
											
										}
									}
								} catch(ex : Throwable) {
									ex.printStackTrace()
								}
								
								return null
							}
							
							override fun onPostExecute(bitmap : Bitmap?) {
								if(bitmap == null) return
								
								val bitmap_w = bitmap.width
								val bitmap_h = bitmap.height
								if(bitmap_w < 1 || bitmap_h < 1) {
									bitmap.recycle()
									return
								}
								if(isCancelled || image_uri != last_image_uri) {
									bitmap.recycle()
									return
								}
								last_task = null
								
								exif_info = joinList(" ", exif_list)
								showTimeAndExif()
								
								last_bitmap = bitmap
								ivThumbnail.setImageDrawable(
									BitmapDrawable(
										activity.resources,
										bitmap
									)
								)
								
								val scale : Float
								scale = if(bitmap_w >= bitmap_h) {
									thumbnail_size / bitmap_w.toFloat()
								} else {
									thumbnail_size / bitmap_h.toFloat()
								}
								matrix.reset()
								// 画像の中心が原点に来るようにして
								matrix.postTranslate(bitmap_w * - 0.5f, bitmap_h * - 0.5f)
								// スケーリング
								matrix.postScale(scale, scale)
								// 回転情報があれば回転
								when(orientation) {
									// 上下反転、左右反転
									2 -> matrix.postScale(1f, - 1f)
									4 -> matrix.postScale(- 1f, 1f)
									
									// 回転
									3 -> matrix.postRotate(180f)
									6 -> matrix.postRotate(90f)
									8 -> matrix.postRotate(- 90f)
									
									// 反転して回転
									5 -> {
										matrix.postScale(1f, - 1f)
										matrix.postRotate(- 90f)
									}
									
									7 -> {
										matrix.postScale(1f, - 1f)
										matrix.postRotate(90f)
									}
								}
								// 表示領域に埋まるように平行移動
								matrix.postTranslate(thumbnail_size * 0.5f, thumbnail_size * 0.5f)
								// ImageView にmatrixを設定
								ivThumbnail.scaleType = ImageView.ScaleType.MATRIX
								ivThumbnail.imageMatrix = matrix
							}
						}
						last_task !!.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR)
					}
				}
			}
			
			private fun trimModelName(sv : String) : String {
				// 制御文字は空白に置き換える
				val sb = StringBuilder(sv)
				for(i in sb.length - 1 downTo 0) {
					val c = sb[i]
					if(c.toInt() < 0x20 || c.toInt() == 0x7f) {
						sb.setCharAt(i, ' ')
					}
				}
				// 連続する空白を１文字にする。始端と終端の空白を除去する。
				return sb.toString().replace("\\s+".toRegex(), " ").trim { it <= ' ' }
			}
			
			private fun joinList(delimiter : String, exif_list : LinkedList<String>?) : String? {
				if(exif_list == null || exif_list.isEmpty()) return null
				val sb = StringBuilder()
				for(s in exif_list) {
					if(TextUtils.isEmpty(s)) continue
					if(sb.isNotEmpty()) sb.append(delimiter)
					sb.append(s)
				}
				return sb.toString()
			}
			
			fun showTimeAndExif() {
				if(TextUtils.isEmpty(exif_info)) {
					tvTime.text = time_str
				} else {
					tvTime.text = "$time_str\n$exif_info"
				}
			}
			
		}
		
		override fun onItemClick(
			parent : AdapterView<*>,
			view : View,
			position : Int,
			id : Long
		) {
			try {
				val adapter = parent.adapter as RecordAdapter
				val data = adapter.loadAt(position)
				if(data == null) {
					Utils.showToast(activity, false, "missing record data at clicked position.")
					return
				}
				val name = File(data.air_path).name
				openDetailDialog(data, name)
				
			} catch(ex : Throwable) {
				ex.printStackTrace()
			}
			
		}
		
		private fun openDetailDialog(data : DownloadRecord, name : String) {
			val v = activity.layoutInflater.inflate(R.layout.dlg_download_record, null, false)
			
			val tvStateCode = v.findViewById<View>(R.id.tvStateCode) as TextView
			tvStateCode.text =
				DownloadRecord.formatStateText(activity, data.state_code, data.state_message)
			tvStateCode.setTextColor(DownloadRecord.getStateCodeColor(data.state_code))
			
			(v.findViewById<View>(R.id.tvName) as TextView).text = name
			(v.findViewById<View>(R.id.tvTime) as TextView).text =
				("update: " + date_fmt.format(data.time)
					+ "\nsize: " + Utils.formatBytes(data.size) + "bytes"
					+ "\ndownload time: " + Utils.formatTimeDuration(data.lap_time)
					+ "\ndownload speed: " + Utils.formatBytes((data.size * 1000L / data.lap_time.toFloat()).toLong()) + "bytes/seconds")
			(v.findViewById<View>(R.id.tvAirPath) as TextView).text = "remote_path: " +
				data.air_path
			(v.findViewById<View>(R.id.tvLocalFile) as TextView).text = "local_file: " +
				data.local_file !!
			
			val d = Dialog(activity)
			d.setTitle(name)
			d.setContentView(v)
			
			d.window !!.setLayout(
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.WRAP_CONTENT
			)
			d.show()
			v.findViewById<View>(R.id.btnClose).setOnClickListener { d.dismiss() }
			v.findViewById<View>(R.id.btnView).setOnClickListener { action_view(data) }
			v.findViewById<View>(R.id.btnSend).setOnClickListener { action_send(data) }
		}
		
		private fun copyToLocal(data : DownloadRecord) : Utils.FileInfo? {
			try {
				val local_file = data.local_file
				if(local_file == null) {
					Utils.showToast(activity, false, "missing local file uri.")
					return null
				}
				val tmp_info = Utils.FileInfo(local_file)
				
				// 端末のダウンロードフォルダ
				val tmp_dir =
					Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
				if(tmp_dir == null) {
					Utils.showToast(activity, false, "can not find temporary directory.")
					return null
				}
				
				// フォルダがなければ作成する
				if(! tmp_dir.exists()) {
					if(! tmp_dir.mkdir()) {
						Utils.showToast(activity, false, "temporary directory not exist.")
						return null
					}
				}
				
				// ファイルをコピー
				val name = File(data.air_path).name
				val tmp_file = File(tmp_dir, name)
				FileOutputStream(tmp_file).use { os ->
					
					if(local_file.startsWith("/")) {
						FileInputStream(local_file)
					} else {
						activity.contentResolver.openInputStream(Uri.parse(data.local_file))
					}?.use { inStream ->
						IOUtils.copy(inStream, os)
					}
				}
				
				// 正常終了
				tmp_info.uri = Uri.fromFile(tmp_file)
				return tmp_info
			} catch(ex : Throwable) {
				ex.printStackTrace()
				Utils.showToast(activity, ex, "failed to copy to temporary folder.")
				return null
			}
			
		}
		
		private fun fixFileURL(data : DownloadRecord) : Utils.FileInfo? {
			try {
				if(data.local_file == null) {
					Utils.showToast(activity, false, "missing local file uri.")
					return null
				}
				
				val tmp_info = Utils.FileInfo(data.local_file)
				val tmp_uri = tmp_info.uri
				
				if(Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION) {
					
					if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
						if(tmp_uri != null && DocumentsContract.isDocumentUri(activity, tmp_uri)) {
							if(isExternalStorageDocument(tmp_uri)) {
								val docId = DocumentsContract.getDocumentId(tmp_uri)
								val split =
									docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }
										.toTypedArray()
								if(split.size >= 2) {
									val uuid = split[0]
									if("primary".equals(uuid, ignoreCase = true)) {
										tmp_info.uri =
											Uri.fromFile(File(Environment.getExternalStorageDirectory().toString() + "/" + split[1]))
										return tmp_info
									} else {
										val volume_map =
											Utils.getSecondaryStorageVolumesMap(activity)
										val volume_path = volume_map[uuid]
										if(volume_path != null) {
											tmp_info.uri =
												Uri.fromFile(File(volume_path + "/" + split[1]))
											return tmp_info
										}
									}
								}
							}
						}
					}
					
					activity.contentResolver.query(
						tmp_info.uri,
						null,
						null,
						null,
						null
					)?.use { cursor ->
						try {
							if(cursor.moveToFirst()) {
								val col_count = cursor.columnCount
								for(i in 0 until col_count) {
									val type = cursor.getType(i)
									if(type != Cursor.FIELD_TYPE_STRING) continue
									val name = cursor.getColumnName(i)
									val value = if(cursor.isNull(i)) null else cursor.getString(i)
									Log.d(
										"DownloadRecordViewer",
										String.format("%s %s", name, value)
									)
									if(! TextUtils.isEmpty(value)) {
										if("filePath" == name) {
											tmp_info.uri = Uri.fromFile(File(value !!))
										}
									}
								}
							}
						} catch(ex : Throwable) {
							ex.printStackTrace()
						}
					}
				}
				
				return tmp_info
			} catch(ex : Throwable) {
				ex.printStackTrace()
				Utils.showToast(activity, ex, "failed to fix file URI.")
				return null
			}
			
		}
		
		private fun action_view(data : DownloadRecord) {
			
			try {
				val tmp_info =
					if(Pref.uiCopyBeforeSend(Pref.pref(activity)) ) {
						copyToLocal(data)
					} else {
						fixFileURL(data)
					} ?: return
				
				registerMediaURI(tmp_info)
				
				val intent = Intent(Intent.ACTION_VIEW)
				if(tmp_info.mime_type != null) {
					intent.setDataAndType(tmp_info.uri, tmp_info.mime_type)
				} else {
					intent.data = tmp_info.uri
				}
				activity.startActivity(intent)
			} catch(ex : Throwable) {
				ex.printStackTrace()
				Utils.showToast(activity, ex, "view failed.")
			}
		}
		
		private fun action_send(data : DownloadRecord) {
			try {
				val tmp_info : Utils.FileInfo =
					if(Pref.uiCopyBeforeSend(Pref.pref(activity)) ) {
						copyToLocal(data)
					} else {
						fixFileURL(data)
					}
						?: return
				
				registerMediaURI(tmp_info)
				
				val intent = Intent(Intent.ACTION_SEND)
				if(tmp_info.mime_type != null) {
					intent.type = tmp_info.mime_type
				}
				intent.putExtra(Intent.EXTRA_STREAM, tmp_info.uri)
				activity.startActivity(
					Intent.createChooser(
						intent,
						activity.getString(R.string.send)
					)
				)
			} catch(ex : Throwable) {
				ex.printStackTrace()
				Utils.showToast(activity, ex, "send failed.")
			}
			
		}
		
		private fun registerMediaURI(tmp_info : Utils.FileInfo) {
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				val tmp_uri = tmp_info.uri
				if(tmp_uri == null || "file" != tmp_uri.scheme) return
				val path = tmp_uri.path
				val files_uri = MediaStore.Files.getContentUri("external")
				var newUri = getUriFromDb(path, files_uri)
				if(newUri != null) {
					tmp_info.uri = newUri
				} else {
					val cv = ContentValues()
					val name = File(path).name
					cv.put(MediaStore.Files.FileColumns.DATA, path)
					cv.put(MediaStore.Files.FileColumns.DISPLAY_NAME, name)
					cv.put(MediaStore.Files.FileColumns.TITLE, name)
					cv.put(MediaStore.Files.FileColumns.MIME_TYPE, tmp_info.mime_type)
					newUri = activity.contentResolver.insert(files_uri, cv)
					if(newUri != null) tmp_info.uri = newUri
				}
			}
		}
		
		private fun getUriFromDb(path : String, files_uri : Uri) : Uri? {
			val cr = activity.contentResolver
			cr.query(
				files_uri,
				null,
				MediaStore.Files.FileColumns.DATA + "=?",
				arrayOf(path),
				null
			)?.use { cursor ->
				if(cursor.moveToFirst()) {
					val colIdx_id = cursor.getColumnIndex(BaseColumns._ID)
					val id = cursor.getLong(colIdx_id)
					return Uri.parse(files_uri.toString() + "/" + id)
				}
			}
			return null
		}
	}
	
	private var holder : Holder? = null
	private var last_view_start : Int = 0
	
	internal fun onStart(activity : AppCompatActivity, listView : ListView, loader_id : Int) {
		val holder = Holder(activity, listView)
		this.holder = holder
		holder.onStart(loader_id)
	}
	
	internal fun onStop() {
		val rv = holder?.onStop()
		if(rv != null) last_view_start = rv
		holder = null
	}
	
	fun reload() {
		holder?.reload()
	}
	
}
