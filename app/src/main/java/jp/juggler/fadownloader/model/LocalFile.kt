package jp.juggler.fadownloader.model

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
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
	val name : String? = null,
	private var local_file : Any? = null // 未探索の間はnull
) {
	
	companion object {
		const val DOCUMENT_FILE_VERSION = 21
		
		private val Any.name : String
			get() = if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
				(this as DocumentFile).name ?: ""
			} else {
				(this as File).name
			}
	}
	
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
		val list = prepareFileList(log, bCreate)
		if(list != null) {
			var start = 0
			var end = list.size
			while(end - start > 0) {
				val mid = start + end shr 1
				val x = list[mid]
				val i = target_name.compareTo(x.name)
				when {
					i < 0 -> end = mid
					i > 0 -> start = mid + 1
					else -> return x
				}
			}
		}
		return null
	}
	
	private fun prepareFileList(log : LogWriter, bCreate : Boolean) : ArrayList<Any>? {
		if(child_list == null) {
			if(local_file == null && parent != null && name != null) {
				local_file = parent.prepareDirectory(log, bCreate)?.findChild(log, bCreate, name)
			}
			if(local_file != null) {
				try {
					child_list = ArrayList<Any>().apply{
						if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
							Collections.addAll(this, *(local_file as DocumentFile).listFiles())
						} else {
							Collections.addAll(this, *(local_file as File).listFiles())
						}
						this.sortBy { it.name }
					}
				} catch(ex : Throwable) {
					log.trace(ex, "listFiles() failed.")
					log.e(ex, "listFiles() failed.")
				}
			}
		}
		return child_list
	}
	
	private fun prepareDirectory(log : LogWriter, bCreate : Boolean) : LocalFile? {
		try {
			if(local_file == null && parent != null && name != null) {
				local_file = parent.prepareDirectory(log, bCreate)?.findChild(log, bCreate, name)
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
		} catch(ex : Throwable) {
			log.e(ex, R.string.folder_create_failed)
		}
		
		return if( local_file == null ) null else this
	}
	
	fun prepareFile(log : LogWriter, bCreate : Boolean, mimeTypeArg : String?) : LocalFile? {
		try {
			if(local_file == null && parent != null && name != null) {
				local_file = parent.prepareDirectory(log, bCreate)?.findChild(log, bCreate, name)
				if(local_file == null && bCreate) {
					local_file = when {
						Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION ->
							(parent.local_file as DocumentFile)
								.createFile(
									if(mimeTypeArg?.isNotEmpty() == true) {
										mimeTypeArg
									} else {
										"application/octet-stream"
									}
									, name
								)
						else ->
							File(parent.local_file as File?, name)
					}
					if(local_file == null) {
						log.e(R.string.file_create_failed)
					}
				}
			}
			
		} catch(ex : Throwable) {
			log.e(ex, R.string.file_create_failed)
		}
		
		return if( local_file == null ) null else this
	}
	
	fun length(log : LogWriter) : Long =
		prepareFile(log, false, null)?.local_file ?.let{
			if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
				( it as DocumentFile).length()
			} else {
				( it as File).length()
			}
		} ?: 0L
	
	//	fun isFile(log : LogWriter) : Boolean {
	//		return if(prepareFile(log, false, null)) {
	//			if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
	//				(local_file as DocumentFile).isFile
	//			} else {
	//				(local_file as File).isFile
	//			}
	//		} else false
	//	}
	
	@Throws(FileNotFoundException::class)
	fun openOutputStream(context : Context) : OutputStream? {
		return if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
			val file_uri = (local_file as DocumentFile).uri
			context.contentResolver.openOutputStream(file_uri)
		} else {
			FileOutputStream(local_file as File?)
		}
	}
	
	@Throws(FileNotFoundException::class)
	fun openInputStream(context : Context) : InputStream? {
		return if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
			val file_uri = (local_file as DocumentFile).uri
			context.contentResolver.openInputStream(file_uri)
		} else {
			FileInputStream(local_file as File?)
		}
	}
	
	fun delete() : Boolean {
		return if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
			(local_file as DocumentFile).delete()
		} else {
			(local_file as File).delete()
		}
	}
	
	//	fun renameTo(name : String) : Boolean {
	//		return if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
	//			(local_file as DocumentFile).renameTo(name)
	//		} else {
	//			(local_file as File).renameTo(
	//				File((local_file as File).parentFile, name)
	//			)
	//		}
	//	}
	
	fun getFileUri(log : LogWriter) : String? =
		prepareFile(log, false, null) ?.local_file ?.let{
			if(Build.VERSION.SDK_INT >= DOCUMENT_FILE_VERSION) {
				(it as DocumentFile).uri.toString()
			} else {
				(it as File).absolutePath
			}
		}
	
	fun getFile(context : Context, log : LogWriter) : File? {
		try {
			val str_uri = getFileUri(log) ?: return null
			return Utils.getFile(context, str_uri)
		} catch(ex : Throwable) {
			log.trace(ex, "getFile() failed.")
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
			log.trace(ex, "setLastModified() failed.")
			log.e(ex, "setLastModified() failed.")
		}
		
	}
	
}
