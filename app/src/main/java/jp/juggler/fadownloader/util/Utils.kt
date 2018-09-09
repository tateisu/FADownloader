package jp.juggler.fadownloader.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.os.storage.StorageManager
import android.text.TextUtils
import android.util.Base64
import android.util.SparseBooleanArray
import android.util.SparseIntArray
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.GoogleApiClient
import jp.juggler.fadownloader.Receiver1
import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.DecimalFormat
import java.util.*
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

object Utils {
	
	private var bytes_format = DecimalFormat("#,###")
	
	private val hex =
		charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')
	
	/////////////////////////////////////////////
	
	private var taisaku_map = HashMap<Char, String>()
	private var taisaku_map2 = SparseBooleanArray()
	
	private var mime_type_ex = HashMap<String, String>().apply {
		this["BDM"] = "application/vnd.syncml.dm+wbxml"
		this["DAT"] = ""
		this["TID"] = ""
		this["js"] = "text/javascript"
		this["sh"] = "application/x-sh"
		this["lua"] = "text/x-lua"
	}
	
	const val MIME_TYPE_APPLICATION_OCTET_STREAM = "application/octet-stream"
	
	private var xml_builder : DocumentBuilder? = null
	private const val PATH_TREE = "tree"
	private const val PATH_DOCUMENT = "document"
	
	@SuppressLint("DefaultLocale")
	fun formatTimeDuration(tArg : Long) : String {
		var t = tArg
		val sb = StringBuilder()
		var n : Long
		// day
		n = t / 86400000L
		if(n > 0) {
			sb.append(String.format(Locale.JAPAN, "%dd", n))
			t -= n * 86400000L
		}
		// h
		n = t / 3600000L
		if(n > 0 || sb.isNotEmpty()) {
			sb.append(String.format(Locale.JAPAN, "%dh", n))
			t -= n * 3600000L
		}
		// m
		n = t / 60000L
		if(n > 0 || sb.isNotEmpty()) {
			sb.append(String.format(Locale.JAPAN, "%dm", n))
			t -= n * 60000L
		}
		// s
		val f = t / 1000f
		sb.append(String.format(Locale.JAPAN, "%.03fs", f))
		
		return sb.toString()
	}
	
	fun formatBytes(t : Long) : String {
		return bytes_format.format(t)
		
		//		StringBuilder sb = new StringBuilder();
		//		long n;
		//		// giga
		//		n = t / 1000000000L;
		//		if( n > 0 ){
		//			sb.append( String.format( Locale.JAPAN, "%dg", n ) );
		//			t -= n * 1000000000L;
		//		}
		//		// Mega
		//		n = t / 1000000L;
		//		if( sb.length() > 0 ){
		//			sb.append( String.format( Locale.JAPAN, "%03dm", n ) );
		//			t -= n * 1000000L;
		//		}else if( n > 0 ){
		//			sb.append( String.format( Locale.JAPAN, "%dm", n ) );
		//			t -= n * 1000000L;
		//		}
		//		// kilo
		//		n = t / 1000L;
		//		if( sb.length() > 0 ){
		//			sb.append( String.format( Locale.JAPAN, "%03dk", n ) );
		//			t -= n * 1000L;
		//		}else if( n > 0 ){
		//			sb.append( String.format( Locale.JAPAN, "%dk", n ) );
		//			t -= n * 1000L;
		//		}
		//		// remain
		//		if( sb.length() > 0 ){
		//			sb.append( String.format( Locale.JAPAN, "%03d", t ) );
		//		}else if( n > 0 ){
		//			sb.append( String.format( Locale.JAPAN, "%d", t ) );
		//		}
		//
		//		return sb.toString();
	}
	
	fun createAlarmPendingIntent(context : Context) : PendingIntent {
		val i = Intent(context.applicationContext, Receiver1::class.java)
		i.action = Receiver1.ACTION_ALARM
		return PendingIntent.getBroadcast(context.applicationContext, 0, i, 0)
	}
	
	// 文字列と整数の変換
	fun parse_int(v : String, defVal : Int) : Int {
		return try {
			Integer.parseInt(v, 10)
		} catch(ex : Throwable) {
			defVal
		}
		
	}
	
	fun addHex(sb : StringBuilder, b : Int) {
		sb.append(hex[(b shr 4) and 15])
		sb.append(hex[b and 15])
	}
	
	private val mapHexInt = SparseIntArray().apply {
		for(c in '0' .. '9') put(c.toInt(), (c - '0'))
		for(c in 'A' .. 'F') put(c.toInt(), (c - 'A') + 10)
		for(c in 'a' .. 'f') put(c.toInt(), (c - 'a') + 10)
	}
	
	fun hex2int(c : Int) : Int {
		val v : Int? = mapHexInt[c]
		return v ?: 0
	}
	
	fun url2name(url : String?) : String? {
		return url?.encodeUTF8()?.digestSHA256()?.encodeBase64Safe()
	}
	
	//	public static String name2url(String entry) {
	//		if(entry==null) return null;
	//		byte[] b = new byte[entry.length()/2];
	//		for(int i=0,ie=b.length;i<ie;++i){
	//			b[i]= (byte)((hex2int(entry.charAt(i*2))<<4)| hex2int(entry.charAt(i*2+1)));
	//		}
	//		return decodeUTF8(b);
	//	}
	
	///////////////////////////////////////////////////
	
	private fun _taisaku_add_string(z : String, h : String) {
		var i = 0
		val e = z.length
		while(i < e) {
			val zc = z[i]
			taisaku_map[zc] = "" + Character.toString(h[i])
			taisaku_map2.put(zc.toInt(), true)
			++ i
		}
	}
	
	init {
		taisaku_map = HashMap()
		taisaku_map2 = SparseBooleanArray()
		
		// tilde,wave dash,horizontal ellipsis,minus sign
		_taisaku_add_string(
			"\u2073\u301C\u22EF\uFF0D", "\u007e\uFF5E\u2026\u2212"
		)
		// zenkaku to hankaku
		_taisaku_add_string(
			"　！”＃＄％＆’（）＊＋，－．／０１２３４５６７８９：；＜＝＞？＠ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺ［］＾＿｀ａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ｛｜｝",
			" !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`abcdefghijklmnopqrstuvwxyz{|}"
		)
		
	}
	
	private fun isBadChar2(c : Char) : Boolean {
		return c.toInt() == 0xa || taisaku_map2.get(c.toInt())
	}
	
	//! フォントによって全角文字が化けるので、その対策
	fun font_taisaku(text : String?, lf2br : Boolean) : String? {
		if(text == null) return null
		val l = text.length
		val sb = StringBuilder(l)
		if(! lf2br) {
			var i = 0
			while(i < l) {
				val start = i
				while(i < l && ! taisaku_map2.get(text[i].toInt())) ++ i
				if(i > start) {
					sb.append(text.substring(start, i))
					if(i >= l) break
				}
				sb.append(taisaku_map[text[i]])
				++ i
			}
		} else {
			var i = 0
			while(i < l) {
				val start = i
				while(i < l && ! isBadChar2(text[i])) ++ i
				if(i > start) {
					sb.append(text.substring(start, i))
					if(i >= l) break
				}
				val c = text[i]
				if(c.toInt() == 0xa) {
					sb.append("<br/>")
				} else {
					sb.append(taisaku_map[c])
				}
				++ i
			}
		}
		return sb.toString()
	}
	
	////////////////////////////
	

	fun getString(b : Bundle, key : String, defval : String) : String {
		try {
			val v = b.getString(key)
			if(v != null) return v
		} catch(ignored : Throwable) {
		}
		
		return defval
	}
	
	@Throws(IOException::class)
	fun loadFile(file : File) : ByteArray {
		val size = file.length().toInt()
		val data = ByteArray(size)
		val `in` = FileInputStream(file)
		try {
			val nRead = 0
			while(nRead < size) {
				val delta = `in`.read(data, nRead, size - nRead)
				if(delta <= 0) break
			}
			return data
		} finally {
			try {
				`in`.close()
			} catch(ignored : Throwable) {
			}
			
		}
	}
	
	fun ellipsize(t : String, max : Int) : String {
		return if(t.length > max) t.substring(0, max - 1) + "…" else t
	}
	
	//	public static int getEnumStringId( String residPrefix, String name,Context context ) {
	//		name = residPrefix + name;
	//		try{
	//			int iv = context.getResources().getIdentifier(name,"string",context.getPackageName() );
	//			if( iv != 0 ) return iv;
	//		}catch(Throwable ex){
	//		}
	//		log.e("missing resid for %s",name);
	//		return R.string.Dialog_Cancel;
	//	}
	
	fun getConnectionResultErrorMessage(connectionResult : ConnectionResult) : String {
		val code = connectionResult.errorCode
		val msg = connectionResult.errorMessage
		if(msg?.isNotEmpty() == true) return msg
		return when(code) {
			ConnectionResult.SUCCESS -> "SUCCESS"
			ConnectionResult.SERVICE_MISSING -> "SERVICE_MISSING"
			ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED -> "SERVICE_VERSION_UPDATE_REQUIRED"
			ConnectionResult.SERVICE_DISABLED -> "SERVICE_DISABLED"
			ConnectionResult.SIGN_IN_REQUIRED -> "SIGN_IN_REQUIRED"
			ConnectionResult.INVALID_ACCOUNT -> "INVALID_ACCOUNT"
			ConnectionResult.RESOLUTION_REQUIRED -> "RESOLUTION_REQUIRED"
			ConnectionResult.NETWORK_ERROR -> "NETWORK_ERROR"
			ConnectionResult.INTERNAL_ERROR -> "INTERNAL_ERROR"
			ConnectionResult.SERVICE_INVALID -> "SERVICE_INVALID"
			ConnectionResult.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
			ConnectionResult.LICENSE_CHECK_FAILED -> "LICENSE_CHECK_FAILED"
			ConnectionResult.CANCELED -> "CANCELED"
			ConnectionResult.TIMEOUT -> "TIMEOUT"
			ConnectionResult.INTERRUPTED -> "INTERRUPTED"
			ConnectionResult.API_UNAVAILABLE -> "API_UNAVAILABLE"
			ConnectionResult.SIGN_IN_FAILED -> "SIGN_IN_FAILED"
			ConnectionResult.SERVICE_UPDATING -> "SERVICE_UPDATING"
			ConnectionResult.SERVICE_MISSING_PERMISSION -> "SERVICE_MISSING_PERMISSION"
			ConnectionResult.RESTRICTED_PROFILE -> "RESTRICTED_PROFILE"
			else -> "Unknwonn($code,$msg)"
		}
	}
	
	fun getConnectionSuspendedMessage(i : Int) : String {
		return when(i) {
			GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST -> "NETWORK_LOST"
			GoogleApiClient.ConnectionCallbacks.CAUSE_SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
			else -> "?"
		}
	}
	
	private fun findMimeTypeEx(ext : String?) : String? {
		ext ?: return null
		return mime_type_ex[ext]
	}
	
	fun getMimeType(log : LogWriter?, src : String) : String {
		var ext = MimeTypeMap.getFileExtensionFromUrl(src)
		if(! TextUtils.isEmpty(ext)) {
			ext = ext.toLowerCase(Locale.US)
			
			//
			var mime_type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
			if(! TextUtils.isEmpty(mime_type)) return mime_type
			
			//
			mime_type = findMimeTypeEx(ext)
			if(! TextUtils.isEmpty(mime_type)) return mime_type
			
			// 戻り値が空文字列の場合とnullの場合があり、空文字列の場合は既知でありログ出力しない
			
			if(mime_type == null && log != null) log.w(
				"getMimeType(): unknown file extension '%s'",
				ext
			)
		}
		return MIME_TYPE_APPLICATION_OCTET_STREAM
	}
	
	internal class FileInfo(any_uri : String?) {
		
		var uri : Uri? = null
		var mime_type : String? = null
		
		init {
			if(any_uri != null) {
				uri = if(any_uri.startsWith("/")) {
					Uri.fromFile(File(any_uri))
				} else {
					Uri.parse(any_uri)
				}
				
				val ext = MimeTypeMap.getFileExtensionFromUrl(any_uri)
				if(ext != null) {
					mime_type =
						MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase())
				}
			}
		}
	}
	
	internal fun getSecondaryStorageVolumesMap(context : Context) : Map<String, String> {
		val result = HashMap<String, String>()
		try {
			
			val sm =
				context.applicationContext.getSystemService(Context.STORAGE_SERVICE) as StorageManager
			
			// SDカードスロットのある7.0端末が手元にないから検証できない
			//			if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N ){
			//				for(StorageVolume volume : sm.getStorageVolumes() ){
			//					// String path = volume.getPath();
			//					String state = volume.getState();
			//
			//				}
			//			}
			
			val getVolumeList = sm.javaClass.getMethod("getVolumeList")
			val volumes = getVolumeList.invoke(sm) as Array<Any>
			//
			for(volume in volumes) {
				val volume_clazz = volume.javaClass
				
				val path = volume_clazz.getMethod("getPath").invoke(volume) as String
				val state = volume_clazz.getMethod("getState").invoke(volume) as String
				if(! TextUtils.isEmpty(path) && "mounted" == state) {
					//
					val isPrimary = volume_clazz.getMethod("isPrimary").invoke(volume) as Boolean
					if(isPrimary) result["primary"] = path
					//
					val uuid = volume_clazz.getMethod("getUuid").invoke(volume) as String
					if(! TextUtils.isEmpty(uuid)) result[uuid] = path
				}
			}
		} catch(ex : Throwable) {
			ex.printStackTrace()
		}
		
		return result
	}
	
	fun toCamelCase(src : String) : String {
		val sb = StringBuilder()
		for(s in src.split("_".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
			if(TextUtils.isEmpty(s)) continue
			sb.append(Character.toUpperCase(s[0]))
			sb.append(s.substring(1, s.length).toLowerCase())
		}
		return sb.toString()
	}
	
	fun parseXml(src : ByteArray) : Element? {
		if(xml_builder == null) {
			try {
				xml_builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
			} catch(ex : Throwable) {
				ex.printStackTrace()
				return null
			}
			
		}
		return try {
			xml_builder !!.parse(ByteArrayInputStream(src)).documentElement
		} catch(ex : Throwable) {
			ex.printStackTrace()
			null
		}
		
	}
	
	fun getAttribute(attr_map : NamedNodeMap, name : String) : String? {
		return attr_map.getNamedItem(name)?.nodeValue
	}
	
	fun runOnMainThread(proc : () -> Unit) {
		if(Looper.getMainLooper().thread === Thread.currentThread()) {
			proc()
		} else {
			Handler(Looper.getMainLooper()).post {
				proc()
			}
		}
	}
	
	fun showToast(context : Context, bLong : Boolean, fmt : String, vararg args : Any) {
		runOnMainThread {
			Toast.makeText(
				context,
				if(args.isEmpty()) fmt else String.format(fmt, *args),
				if(bLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
			).show()
		}
	}
	
	fun showToast(context : Context, ex : Throwable, fmt : String, vararg args : Any) {
		runOnMainThread {
			Toast.makeText(
				context, LogWriter.formatError(ex, fmt, *args), Toast.LENGTH_LONG
			).show()
		}
	}
	
	fun showToast(context : Context, bLong : Boolean, string_id : Int, vararg args : Any) {
		runOnMainThread {
			Toast.makeText(
				context,
				context.getString(string_id, *args),
				if(bLong) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
			).show()
		}
	}
	
	fun showToast(context : Context, ex : Throwable, string_id : Int, vararg args : Any) {
		runOnMainThread {
			Toast.makeText(
				context,
				LogWriter.formatError(ex, context.resources, string_id, *args),
				Toast.LENGTH_LONG
			).show()
		}
	}
	
	private fun isExternalStorageDocument(uri : Uri) : Boolean {
		return "com.android.externalstorage.documents" == uri.authority
	}
	
	private fun getDocumentId(documentUri : Uri) : String {
		val paths = documentUri.pathSegments
		if(paths.size >= 2 && PATH_DOCUMENT == paths[0]) {
			// document
			return paths[1]
		}
		if(paths.size >= 4 && PATH_TREE == paths[0]
			&& PATH_DOCUMENT == paths[2]) {
			// document in tree
			return paths[3]
		}
		if(paths.size >= 2 && PATH_TREE == paths[0]) {
			// tree
			return paths[1]
		}
		throw IllegalArgumentException("Invalid URI: $documentUri")
	}
	
	fun getFile(context : Context, path : String) : File? {
		try {
			if(path.startsWith("/")) return File(path)
			val uri = Uri.parse(path)
			if("file" == uri.scheme) return File(uri.path)
			
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
				if(isExternalStorageDocument(uri)) {
					try {
						val docId = getDocumentId(uri)
						val split = docId.split(":".toRegex()).dropLastWhile { it.isEmpty() }
							.toTypedArray()
						if(split.size >= 2) {
							val uuid = split[0]
							if("primary".equals(uuid, ignoreCase = true)) {
								return File(Environment.getExternalStorageDirectory().toString() + "/" + split[1])
							} else {
								val volume_map =
									getSecondaryStorageVolumesMap(
										context
									)
								val volume_path = volume_map[uuid]
								if(volume_path != null) {
									return File(volume_path + "/" + split[1])
								}
							}
						}
					} catch(ex2 : Throwable) {
						ex2.printStackTrace()
					}
					
				}
			}
			// MediaStore Uri
			context.contentResolver.query(uri, null, null, null, null)
				?.use{cursor->
					if(cursor.moveToFirst()) {
						val col_count = cursor.columnCount
						for(i in 0 until col_count) {
							val type = cursor.getType(i)
							if(type != Cursor.FIELD_TYPE_STRING) continue
							val name = cursor.getColumnName(i)
							val value = if(cursor.isNull(i)) null else cursor.getString(i)
							if(! TextUtils.isEmpty(value)) {
								if("filePath" == name) {
									return File(value !!)
								}
							}
						}
					}
				}
		} catch(ex : Throwable) {
			ex.printStackTrace()
		}
		
		return null
	}
	
}

// 文字列とバイト列の変換
fun String.encodeUTF8() = this.toByteArray(charset("UTF-8"))

// 文字列とバイト列の変換
fun ByteArray.decodeUTF8() = String(this, charset("UTF-8"))

// 16進ダンプ
fun ByteArray.encodeHex() : String {
	val sb = StringBuilder()
	for(b in this) {
		Utils.addHex(sb, b.toInt())
	}
	return sb.toString()
}

fun ByteArray.digestSHA256() : ByteArray {
	val digest = MessageDigest.getInstance("SHA-256")
	digest.reset()
	return digest.digest(this)
}

fun ByteArray.encodeBase64Safe() :String = Base64.encodeToString(this, Base64.URL_SAFE)

fun ByteArray.digestMD5() : String {
	val md = MessageDigest.getInstance("MD5")
	md.reset()
	return md.digest(this).encodeHex()
}

// MD5ハッシュの作成
fun String.digestMD5() = this.encodeUTF8().digestMD5()

fun Cursor.getStringOrNull(idx:Int) = if(isNull(idx)) null else getString(idx)

fun String.toLower()  = toLowerCase(Locale.US)

fun String.toUpper() = toUpperCase(Locale.US)