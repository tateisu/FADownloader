package jp.juggler.fadownloader.model

import android.content.Context
import android.net.Uri
import android.os.Build
import android.support.v4.provider.DocumentFile
import android.text.TextUtils
import jp.juggler.fadownloader.R
import jp.juggler.fadownloader.util.LogWriter
import jp.juggler.fadownloader.util.Utils
import java.io.*
import java.util.*

/*

 端末上にあるかもしれないファイルの抽象化

 機能1
 OSバージョンによってFileとDocumentFileを使い分ける

 機能2
 転送対象ファイルが存在しないフォルダを作成したくない
 prepareFile()した時点で親フォルダまで遡って作成したい
 しかし DocumentFile だと作成する前のフォルダを表現できない
 親フォルダがまだ作成されてなくても「親フォルダ＋名前」の形式でファイルパスを保持する

*/

class LocalFile(
	val parent : LocalFile? = null,
	val name : String? =null,
	var local_file : Any? = null // 未探索の間はnull
){
	// local_fileで表現されたフォルダ中に含まれるエントリの一覧
	// 適当にキャッシュする
	private var child_list : ArrayList<Any>? = null
	
	constructor(context : Context, folder_uri : String) : this(
		local_file = if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
			DocumentFile.fromTreeUri(context, Uri.parse(folder_uri))
		} else {
			File(folder_uri)
		}
	)
	
	// エントリを探索
	private fun findChild(log : LogWriter, bCreate : Boolean, target_name : String) : Any? {
		if(prepareFileList(log, bCreate)) {
			var start = 0
			var end = child_list !!.size
			while(end - start > 0) {
				val mid = start + end shr 1
				val x = child_list !![mid]
				val i : Int
				if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
					i = target_name.compareTo((x as DocumentFile).name)
				} else {
					i = target_name.compareTo((x as File).name)
				}
				if(i < 0) {
					end = mid
				} else if(i > 0) {
					start = mid + 1
				} else {
					return x
				}
			}
		}
		return null
	}
	
	private fun prepareFileList(log : LogWriter, bCreate : Boolean) : Boolean {
		if(child_list == null) {
			if(local_file == null && parent != null && name != null) {
				if(parent.prepareDirectory(log, bCreate)) {
					local_file = parent.findChild(log, bCreate, name)
				}
			}
			if(local_file != null) {
				try {
					val result = ArrayList<Any>()
					if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
						Collections.addAll(result, *(local_file as DocumentFile).listFiles())
						result.sortWith(Comparator { a, b -> (a as DocumentFile).name.compareTo((b as DocumentFile).name) })
					} else {
						Collections.addAll(result, *(local_file as File).listFiles())
						result.sortWith(Comparator { a, b -> (a as File).name.compareTo((b as File).name) })
					}
					child_list = result
				} catch(ex : Throwable) {
					ex.printStackTrace()
					log.e(ex, "listFiles() failed.")
				}
				
			}
		}
		return child_list != null
	}
	
	private fun prepareDirectory(log : LogWriter, bCreate : Boolean) : Boolean {
		try {
			if(local_file == null && parent != null && name != null) {
				if(parent.prepareDirectory(log, bCreate)) {
					local_file = parent.findChild(log, bCreate, name)
					if(local_file == null && bCreate) {
						log.i(R.string.folder_create, name)
						if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
							local_file = (parent.local_file as DocumentFile).createDirectory(name)
						} else {
							local_file = File(parent.local_file as File?, name)
							if(! (local_file as File).mkdir()) {
								local_file = null
							}
						}
						if(local_file == null) {
							log.e(R.string.folder_create_failed)
						}
					}
				}
			}
		} catch(ex : Throwable) {
			log.e(ex, R.string.folder_create_failed)
		}
		
		return local_file != null
	}
	
	fun prepareFile(log : LogWriter, bCreate : Boolean, mime_type : String?) : Boolean {
		var mime_type = mime_type
		try {
			if(local_file == null && parent != null && name != null ) {
				if(parent.prepareDirectory(log, bCreate)) {
					local_file = parent.findChild(log, bCreate, name)
					if(local_file == null && bCreate) {
						if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
							if(TextUtils.isEmpty(mime_type)) mime_type = "application/octet-stream"
							local_file =
								(parent.local_file as DocumentFile).createFile(mime_type, name)
						} else {
							local_file = File(parent.local_file as File?, name)
						}
						if(local_file == null) {
							log.e(R.string.file_create_failed)
						}
					}
				}
			}
			
		} catch(ex : Throwable) {
			log.e(ex, R.string.file_create_failed)
		}
		
		return local_file != null
	}
	
	fun length(log : LogWriter) : Long {
		return if(prepareFile(log, false, null)) {
			if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
				(local_file as DocumentFile).length()
			} else {
				(local_file as File).length()
			}
		} else 0L
	}
	
	fun isFile(log : LogWriter) : Boolean {
		return if(prepareFile(log, false, null)) {
			if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
				(local_file as DocumentFile).isFile
			} else {
				(local_file as File).isFile
			}
		} else false
	}
	
	@Throws(FileNotFoundException::class)
	fun openOutputStream(context : Context) : OutputStream? {
		if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
			val file_uri = (local_file as DocumentFile).uri
			return context.contentResolver.openOutputStream(file_uri)
		} else {
			return FileOutputStream(local_file as File?)
		}
	}
	
	@Throws(FileNotFoundException::class)
	fun openInputStream(context : Context) : InputStream? {
		if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
			val file_uri = (local_file as DocumentFile).uri
			return context.contentResolver.openInputStream(file_uri)
		} else {
			return FileInputStream(local_file as File?)
		}
	}
	
	fun delete() : Boolean {
		return if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
			(local_file as DocumentFile).delete()
		} else {
			(local_file as File).delete()
		}
	}
	
	fun renameTo(name : String) : Boolean {
		return if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
			(local_file as DocumentFile).renameTo(name)
		} else {
			(local_file as File).renameTo(
				File((local_file as File).parentFile, name)
			)
		}
	}
	
	fun getFileUri(log : LogWriter) : String? {
		if(! prepareFile(log, false, null)) return null
		return if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
			(local_file as DocumentFile).uri.toString()
		} else {
			(local_file as File).absolutePath
		}
	}
	
	fun getFile(context : Context, log : LogWriter) : File? {
		try {
			val str_uri = getFileUri(log) ?: return null
			return Utils.getFile(context, str_uri)
		} catch(ex : Throwable) {
			ex.printStackTrace()
			log.e(ex, "getFile() failed.")
		}
		
		return null
		
	}
	
	fun setFileTime(context : Context, log : LogWriter, time : Long) {
		try {
			val path = getFile(context, log)
			if(path == null || ! path.isFile) return
			
			path.setLastModified(time)
		} catch(ex : Throwable) {
			ex.printStackTrace()
			log.e("setLastModified() failed.")
		}
		
	}
	
	companion object {
		
		val DOCUMENT_FILE_VERSION = 21
	}
	
}
