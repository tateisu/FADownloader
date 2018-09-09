package jp.juggler.fadownloader.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.HashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

import javax.net.ssl.SSLHandshakeException

import android.net.Network
import android.os.Build
import android.os.SystemClock

//! リトライつきHTTPクライアント
class HTTPClient {
	
	companion object {
		
		internal const val debug_http = false
		
		var user_agent : String? = null
		
		private var reHostName = Pattern.compile("//([^/]+)/")
		
		internal fun toHostName(url : String) : String {
			val m = reHostName.matcher(url)
			return if(m.find()) m.group(1) else url
		}
	}
	
	private var extra_header : Array<String>? = null
	private var rcode : Int = 0
	private var allow_error = false
	private var response_header : Map<String, List<String>>? = null
	private var cookie_pot : HashMap<String, String>? = null
	private var max_try : Int = 0
	private var timeout_connect : Int = 0
	private var timeout_read : Int = 0
	private var caption : String = "?"
	private var silent_error = false
	private var time_expect_connect : Long = 3000
	private var bDisableKeepAlive = false
	
	///////////////////////////////
	// デフォルトの入力ストリームハンドラ
	
	private var default_receiver =
		{ log : LogWriter, cancel_checker : CancelChecker, inStream : InputStream, _ : Int ->
			defaultReceiverImpl(log, cancel_checker, inStream)
		}
	
	private fun defaultReceiverImpl(
		log : LogWriter,
		cancel_checker : CancelChecker,
		inStream : InputStream
	) : ByteArray? {
		val buf = ByteArray(2048)
		val bao = ByteArrayOutputStream(0)
		try {
			bao.reset()
			while(true) {
				if(cancel_checker.isCancelled) {
					@Suppress("ConstantConditionIf")
					if(debug_http) log.w("[$caption,read]cancelled!")
					return null
				}
				val delta = inStream.read(buf)
				if(delta <= 0) break
				bao.write(buf, 0, delta)
			}
			return bao.toByteArray()
		} catch(ex : Throwable) {
			log.e(
				"[%s,read] %s:%s", caption, ex.javaClass.simpleName, ex.message
			)
		}
		
		return null
	}
	
	///////////////////////////////
	// 別スレッドからのキャンセル処理
	
	private var cancel_checker : CancelChecker
	@Volatile
	internal var io_thread : Thread? = null
	
	val isCancelled : Boolean
		get() = cancel_checker.isCancelled
	
	private var post_content : ByteArray? = null
	private var post_content_type : String? = null
	private var quit_network_error = false
	
	var last_error : String? = null
	private var mtime : Long = 0
	/////////////////////////////////////////////////////////
	// 複数URLに対応したリクエスト処理
	
	private var no_cache = false
	
	constructor(timeout : Int, max_try : Int, caption : String, cancel_checker : CancelChecker) {
		this.cancel_checker = cancel_checker
		this.timeout_read = timeout
		this.timeout_connect = this.timeout_read
		this.max_try = max_try
		this.caption = caption
	}
	
	@Suppress("unused")
	constructor(timeout : Int, max_try : Int, caption : String, _cancel_checker : AtomicBoolean) {
		this.cancel_checker = object : CancelChecker {
			override val isCancelled : Boolean
				get() = _cancel_checker.get()
		}
		this.timeout_read = timeout
		this.timeout_connect = this.timeout_read
		this.max_try = max_try
		this.caption = caption
	}
	
	@Suppress("unused")
	fun setCookiePot(enabled : Boolean) {
		if(enabled == (cookie_pot != null)) return
		cookie_pot = if(enabled) HashMap() else null
	}
	
	@Synchronized
	fun cancel(log : LogWriter) {
		val t = io_thread ?: return
		log.i(
			"[%s,cancel] %s", caption, t
		)
		try {
			t.interrupt()
		} catch(ex : Throwable) {
			ex.printStackTrace()
		}
		
	}
	
	///////////////////////////////
	// HTTPリクエスト処理
	
	fun getHTTP(log : LogWriter, network : Any?, url : String) : ByteArray? {
		return getHTTP(log, network, url, default_receiver)
	}
	
	fun getHTTP(
		log : LogWriter,
		network : Any?,
		url : String,
		receiver : (
			log : LogWriter,
			cancel_checker : CancelChecker,
			inStream : InputStream,
			content_length : Int
		) -> ByteArray?
	) : ByteArray? {
		
		//		// http://android-developers.blogspot.jp/2011/09/androids-http-clients.html
		//		// HTTP connection reuse which was buggy pre-froyo
		//		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO ){
		//			System.setProperty( "http.keepAlive", "false" );
		//		}
		
		try {
			synchronized(this) {
				this.io_thread = Thread.currentThread()
			}
			val urlObject : URL
			try {
				urlObject = URL(url)
			} catch(ex : MalformedURLException) {
				log.d("[%s,init] bad url %s %s", caption, url, ex.message)
				return null
			}
			
			/*
			// desire だと、どうもリソースリークしているようなので行わないことにした。
			// DNSを引けるか確認する
			if(debug_http) Log.d(logcat,"check hostname "+url);
			if( !checkDNSResolver(urlObject) ){
				Log.w(logcat,"broken name resolver");
				return null;
			}
*/
			val timeStart = SystemClock.elapsedRealtime()
			for(nTry in 0 until max_try) {
				val t1 : Long
				val t2 : Long
				val lap : Long
				try {
					this.rcode = 0
					// キャンセルされたか確認
					if(cancel_checker.isCancelled) return null
					
					// http connection
					var conn : HttpURLConnection? = null
					if(Build.VERSION.SDK_INT >= 21) {
						if(network is Network) {
							conn = network.openConnection(urlObject) as HttpURLConnection
						}
					}
					if(conn == null) conn = urlObject.openConnection() as HttpURLConnection
					
					if(user_agent != null) conn.setRequestProperty(
						"User-Agent",
						user_agent
					)
					
					// 追加ヘッダがあれば記録する
					if(extra_header != null) {
						var i = 0
						while(i < extra_header !!.size) {
							conn.addRequestProperty(extra_header !![i], extra_header !![i + 1])
							if(debug_http) log.d(
								"%s: %s",
								extra_header !![i],
								extra_header !![i + 1]
							)
							i += 2
						}
					}
					if(bDisableKeepAlive) {
						conn.setRequestProperty("Connection", "close")
					}
					// クッキーがあれば指定する
					if(cookie_pot != null) {
						val sb = StringBuilder()
						for((key, value) in cookie_pot !!) {
							if(sb.isNotEmpty()) sb.append("; ")
							sb.append(key)
							sb.append('=')
							sb.append(value)
						}
						conn.addRequestProperty("Cookie", sb.toString())
					}
					
					// リクエストを送ってレスポンスの頭を読む
					try {
						t1 = SystemClock.elapsedRealtime()
						if(debug_http) log.d(
							"[%s,connect] start %s", caption,
							toHostName(url)
						)
						conn.doInput = true
						conn.connectTimeout = this.timeout_connect
						conn.readTimeout = this.timeout_read
						if(post_content == null) {
							conn.doOutput = false
							conn.connect()
						} else {
							conn.doOutput = true
							//							if( Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB ){
							//								conn.setRequestProperty( "Content-Length", Integer.toString( post_content.length ) );
							//							}
							if(post_content_type != null) {
								conn.setRequestProperty("Content-Type", post_content_type)
							}
							val out = conn.outputStream
							out.write(post_content !!)
							out.flush()
							out.close()
						}
						// http://stackoverflow.com/questions/12931791/java-io-ioexception-received-authentication-challenge-is-null-in-ics-4-0-3
						var rcode : Int
						try {
							// Will throw IOException if server responds with 401.
							this.rcode = conn.responseCode
							rcode = this.rcode
						} catch(ex : IOException) {
							val sv = ex.message
							if(sv != null && sv.contains("authentication challenge")) {
								log.d("retry getResponseCode!")
								// Will return 401, because now connection has the correct internal state.
								this.rcode = conn.responseCode
								rcode = this.rcode
							} else {
								throw ex
							}
						}
						
						mtime = conn.lastModified
						t2 = SystemClock.elapsedRealtime()
						lap = t2 - t1
						if(lap > time_expect_connect) log.d(
							"[%s,connect] time=%sms %s",
							caption,
							lap,
							toHostName(url)
						)
						
						// ヘッダを覚えておく
						response_header = conn.headerFields
						
						// クッキーが来ていたら覚える
						if(cookie_pot != null) {
							val v = conn.getHeaderField("set-cookie")
							if(v != null) {
								val pos = v.indexOf('=')
								cookie_pot !![v.substring(0, pos)] = v.substring(pos + 1)
							}
						}
						
						if(rcode >= 500) {
							if(! silent_error) log.e(
								"[%s,connect] temporary error %d",
								caption,
								rcode
							)
							last_error = String.format("(HTTP error %d)", rcode)
							continue
						} else if(! allow_error && rcode >= 300) {
							if(! silent_error) log.e(
								"[%s,connect] permanent error %d",
								caption,
								rcode
							)
							last_error = String.format("(HTTP error %d)", rcode)
							return null
						}
						
					} catch(ex : UnknownHostException) {
						rcode = 0
						last_error = ex.javaClass.simpleName
						// このエラーはリトライしてもムリ
						conn.disconnect()
						return null
					} catch(ex : SSLHandshakeException) {
						last_error = String.format(
							"SSL handshake error. Please check device's date and time. (%s %s)",
							ex.javaClass.simpleName,
							ex.message
						)
						
						if(! silent_error) {
							log.e(
								"[%s,connect] %s", caption, last_error
							)
							if(ex.message == null) {
								ex.printStackTrace()
							}
						}
						this.rcode = - 1
						return null
					} catch(ex : Throwable) {
						last_error =
							String.format("%s %s", ex.javaClass.simpleName, ex.message)
						
						if(! silent_error) {
							log.e(
								"[%s,connect] %s", caption, last_error
							)
							if(ex.message == null) {
								ex.printStackTrace()
							}
						}
						
						// 時計が合ってない場合は Received authentication challenge is null なエラーが出るらしい
						// getting a 401 Unauthorized error, due to a malformed Authorization header.
						if(ex is IOException
							&& ex.message?.contains("authentication challenge") == true
						) {
							ex.printStackTrace()
							log.d("Please check device's date and time.")
							this.rcode = 401
							return null
						} else if(ex is ConnectException
							&& ex.message?.contains("ENETUNREACH") == true
						) {
							// このアプリの場合は network unreachable はリトライしない
							return null
						}
						if(quit_network_error) return null
						
						// 他のエラーはリトライしてみよう。キャンセルされたなら次のループの頭で抜けるはず
						conn.disconnect()
						continue
					}
					
					var inStream : InputStream? = null
					try {
						if(debug_http) if(rcode != 200) log.d(
							"[%s,read] start status=%d",
							caption,
							this.rcode
						)
						inStream = try {
							conn.inputStream
						} catch(ex : FileNotFoundException) {
							conn.errorStream
						}
						
						if(inStream == null) {
							log.d("[%s,read] missing input stream. rcode=%d", caption, rcode)
							return null
						}
						val content_length = conn.contentLength
						val data =
							receiver(log, cancel_checker, inStream, content_length) ?: continue
						if(data.isNotEmpty()) {
							if(nTry > 0)
								log.w(
									"[%s] OK. retry=%d,time=%dms",
									caption,
									nTry,
									SystemClock.elapsedRealtime() - timeStart
								)
							return data
						}
						if(! cancel_checker.isCancelled && ! silent_error) {
							log.w(
								"[%s,read] empty data.", caption
							)
						}
					} finally {
						try {
							inStream?.close()
						} catch(ignored : Throwable) {
						}
						
						conn.disconnect()
					}
				} catch(ex : Throwable) {
					last_error = String.format("%s %s", ex.javaClass.simpleName, ex.message)
					ex.printStackTrace()
				}
				
			}
			if(! silent_error) log.e("[%s] fail. try=%d. rcode=%d", caption, max_try, rcode)
		} catch(ex : Throwable) {
			ex.printStackTrace()
			last_error = String.format("%s %s", ex.javaClass.simpleName, ex.message)
		} finally {
			synchronized(this) {
				io_thread = null
			}
		}
		return null
	}
	
	//! HTTPレスポンスのヘッダを読む
	@Suppress("unused")
	fun dump_res_header(log : LogWriter) {
		log.d("HTTP code %d", rcode)
		if(response_header != null) {
			for((k, value) in response_header !!) {
				for(v in value) {
					log.d("%s: %s", k, v)
				}
			}
		}
	}
	
	@Suppress("unused")
	fun get_cache(log : LogWriter, file : File, url : String) : String? {
		var last_error : String? = null
		for(nTry in 0 .. 9) {
			if(cancel_checker.isCancelled) return "cancelled"
			
			val now = System.currentTimeMillis()
			try {
				val conn = URL(url).openConnection() as HttpURLConnection
				try {
					conn.connectTimeout = 1000 * 10
					conn.readTimeout = 1000 * 10
					if(file.exists()) conn.ifModifiedSince = file.lastModified()
					conn.connect()
					this.rcode = conn.responseCode
					if(rcode == 304) {
						if(file.exists()) {
							
							file.setLastModified(now)
						}
						return null
					}
					if(rcode == 200) {
						val `in` = conn.inputStream
						try {
							val bao = ByteArrayOutputStream()
							try {
								val tmp = ByteArray(4096)
								while(true) {
									if(cancel_checker.isCancelled) return "cancelled"
									val delta = `in`.read(tmp, 0, tmp.size)
									if(delta <= 0) break
									bao.write(tmp, 0, delta)
								}
								val data = bao.toByteArray()
								if(data != null) {
									val out = FileOutputStream(file)
									try {
										out.write(data)
										return null
									} finally {
										try {
											out.close()
										} catch(ignored : Throwable) {
										}
										
									}
								}
							} finally {
								try {
									bao.close()
								} catch(ignored : Throwable) {
								}
								
							}
						} catch(ex : Throwable) {
							ex.printStackTrace()
							if(file.exists()) {
								
								file.delete()
							}
							last_error =
								String.format("%s %s", ex.javaClass.simpleName, ex.message)
						} finally {
							try {
								`in`.close()
							} catch(ignored : Throwable) {
							}
							
						}
						break
					}
					log.e("http error: %d %s", rcode, url)
					if(rcode in 400 .. 499) {
						last_error = String.format("HTTP error %d", rcode)
						break
					}
				} finally {
					conn.disconnect()
				}
				// retry ?
			} catch(ex : MalformedURLException) {
				ex.printStackTrace()
				last_error = String.format("bad URL:%s", ex.message)
				break
			} catch(ex : IOException) {
				ex.printStackTrace()
				last_error = String.format("%s %s", ex.javaClass.simpleName, ex.message)
			}
			
		}
		return last_error
	}
	
	@Suppress("unused")
	fun getFile(
		log : LogWriter,
		cache_dir : File?,
		url_list : Array<String>?,
		_file : File?
	) : File? {
		//
		if(url_list?.isEmpty() != false) {
			setError(0, "missing url argument.")
			return null
		}
		// make cache_dir
		if(cache_dir != null) {
			if(! cache_dir.mkdirs() && ! cache_dir.isDirectory) {
				setError(0, "can not create cache_dir")
				return null
			}
		}
		for(nTry in 0 .. 9) {
			if(cancel_checker.isCancelled) {
				setError(0, "cancelled.")
				return null
			}
			//
			val url = url_list[nTry % url_list.size]
			val file = _file ?: File(cache_dir, Utils.url2name(url) !!)
			
			//
			
			try {
				val conn = URL(url).openConnection() as HttpURLConnection
				if(user_agent != null) conn.setRequestProperty(
					"User-Agent",
					user_agent
				)
				try {
					conn.connectTimeout = 1000 * 10
					conn.readTimeout = 1000 * 10
					if(! no_cache && file.exists()) conn.ifModifiedSince = file.lastModified()
					conn.connect()
					this.rcode = conn.responseCode
					
					if(debug_http) if(rcode != 200) log.d("getFile %s %s", rcode, url)
					
					// 変更なしの場合
					if(rcode == 304) {
						/// log.d("304: %s",file);
						return file
					}
					
					// 変更があった場合
					if(rcode == 200) {
						// メッセージボディをファイルに保存する
						var inStream : InputStream? = null
						var out : FileOutputStream? = null
						try {
							val tmp = ByteArray(4096)
							inStream = conn.inputStream
							out = FileOutputStream(file)
							while(true) {
								if(cancel_checker.isCancelled) {
									setError(0, "cancelled")
									if(file.exists()) {
										
										file.delete()
									}
									return null
								}
								val delta = inStream.read(tmp, 0, tmp.size)
								if(delta <= 0) break
								out.write(tmp, 0, delta)
							}
							out.close()
							out = null
							//
							val mtime = conn.lastModified
							if(mtime >= 1000) {
								
								file.setLastModified(mtime)
							}
							//
							/// log.d("200: %s",file);
							return file
						} catch(ex : Throwable) {
							setError(ex)
						} finally {
							try {
								inStream?.close()
							} catch(ignored : Throwable) {
							}
							
							try {
								out?.close()
							} catch(ignored : Throwable) {
							}
							
						}
						// エラーがあったらリトライ
						if(file.exists()) {
							
							file.delete()
						}
						
						continue
					}
					
					// その他、よく分からないケース
					log.e("http error: %d %s", rcode, url)
					
					// URLが複数提供されている場合、404エラーはリトライ対象
					if(rcode == 404 && url_list.size > 1) {
						last_error = String.format("(HTTP error %d)", rcode)
						continue
					}
					
					// それ以外の永続エラーはリトライしない
					if(rcode in 400 .. 499) {
						last_error = String.format("(HTTP error %d)", rcode)
						break
					}
				} finally {
					conn.disconnect()
				}
				// retry ?
			} catch(ex : UnknownHostException) {
				rcode = 0
				last_error = ex.javaClass.simpleName
				// このエラーはリトライしてもムリ
				break
			} catch(ex : MalformedURLException) {
				setError(ex)
				break
			} catch(ex : SocketTimeoutException) {
				setError_silent(log, ex)
			} catch(ex : ConnectException) {
				setError_silent(log, ex)
			} catch(ex : IOException) {
				setError(ex)
			}
			
		}
		return null
	}
	
	///////////////////////////////////////////////////////////////////
	
	private fun setError(i : Int, string : String) : Boolean {
		rcode = i
		last_error = string
		return false
	}
	
	private fun setError(ex : Throwable) : Boolean {
		ex.printStackTrace()
		rcode = 0
		last_error = String.format("%s %s", ex.javaClass.simpleName, ex.message)
		return false
	}
	
	private fun setError_silent(log : LogWriter, ex : Throwable) : Boolean {
		log.d("ERROR: %s %s", ex.javaClass.name, ex.message)
		rcode = 0
		last_error = String.format("%s %s", ex.javaClass.simpleName, ex.message)
		return false
	}
	
	//! HTTPレスポンスのヘッダを読む
	fun getHeaderString(key : String, defVal : String?) : String? {
		val v = response_header?.get(key)?.firstOrNull()
		return v ?: defVal
	}
	
	//! HTTPレスポンスのヘッダを読む
	@Suppress("unused")
	fun getHeaderInt(key : String, defval : Int) : Int {
		val v = getHeaderString(key, null)
		return try {
			Integer.parseInt(v, 10)
		} catch(ex : Throwable) {
			defval
		}
	}
}
