package jp.juggler.fadownloader.targets

import android.net.Uri
import android.os.SystemClock

import com.neovisionaries.ws.client.OpeningHandshakeException
import com.neovisionaries.ws.client.WebSocket
import com.neovisionaries.ws.client.WebSocketAdapter
import com.neovisionaries.ws.client.WebSocketException
import com.neovisionaries.ws.client.WebSocketFactory
import com.neovisionaries.ws.client.WebSocketFrame
import com.neovisionaries.ws.client.WebSocketState
import jp.juggler.fadownloader.*
import jp.juggler.fadownloader.model.LocalFile
import jp.juggler.fadownloader.model.ScanItem
import jp.juggler.fadownloader.table.DownloadRecord
import jp.juggler.fadownloader.util.Utils
import jp.juggler.fadownloader.util.decodeUTF8

import org.json.JSONObject

import java.io.IOException
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

class PentaxKP(private val service : DownloadService, internal val thread : DownloadWorker) {
	
	companion object {
		
		internal const val CHECK_FILE_TIME = false
		internal const val CHECK_FILE_SIZE = false
		
		internal val reDateTime =
			Pattern.compile("(\\d+)\\D+(\\d+)\\D+(\\d+)\\D+(\\d+)\\D+(\\d+)\\D+(\\d+)")
	}
	
	internal val log = service.log
	
	internal var mCameraUpdateTime = AtomicLong(0L)
	internal var mLastBusyTime = AtomicLong(0L)
	internal var mIsCameraBusy = AtomicBoolean(false)
	internal var mLastFileListed = AtomicLong(0L)
	
	private var ws_client : WebSocket? = null
	private val ws_listener : WebSocketAdapter = object : WebSocketAdapter() {
		
		var bBusy = false
		
		@Throws(Exception::class)
		override fun onUnexpectedError(websocket : WebSocket?, ex : WebSocketException) {
			super.onUnexpectedError(websocket, ex)
			log.e(ex, "WebSocket onUnexpectedError")
		}
		
		@Throws(Exception::class)
		override fun onError(websocket : WebSocket?, ex : WebSocketException) {
			super.onError(websocket, ex)
			log.e(ex, "WebSocket onError")
		}
		
		@Throws(Exception::class)
		override fun onConnectError(websocket : WebSocket?, ex : WebSocketException) {
			super.onConnectError(websocket, ex)
			log.e(ex, "WebSocket onConnectError();")
		}
		
		@Throws(Exception::class)
		override fun onTextMessageError(
			websocket : WebSocket?,
			ex : WebSocketException,
			data : ByteArray?
		) {
			super.onTextMessageError(websocket, ex, data)
			log.e(ex, "WebSocket onTextMessageError")
		}
		
		@Throws(Exception::class)
		override fun onDisconnected(
			websocket : WebSocket?,
			serverCloseFrame : WebSocketFrame?,
			clientCloseFrame : WebSocketFrame?,
			closedByServer : Boolean
		) {
			super.onDisconnected(websocket, serverCloseFrame, clientCloseFrame, closedByServer)
			log.d("WebSocket onDisconnects")
		}
		
		@Throws(Exception::class)
		override fun onConnected(websocket : WebSocket?, headers : Map<String, List<String>>?) {
			super.onConnected(websocket, headers)
			log.d("WebSocket onConnect();")
		}
		
		@Throws(Exception::class)
		override fun onTextMessage(websocket : WebSocket?, text : String?) {
			super.onTextMessage(websocket, text)
			try {
				val info = JSONObject(text)
				if(200 == info.optInt("errCode", 0)) {
					val changed = info.optString("changed")
					if("camera" == changed) {
						// 何もしていない状態でも定期的に発生する
					} else if("cameraDirect" == changed) {
						bBusy = (info.optBoolean("capturing", false)
							|| "idle" != info.optString("stateStill")
							|| "idle" != info.optString("stateMovie"))
						if(bBusy && mIsCameraBusy.compareAndSet(false, true)) {
							// busy になった
							mLastBusyTime.set(System.currentTimeMillis())
						} else if(! bBusy && mIsCameraBusy.compareAndSet(true, false)) {
							// busyではなくなった
							mLastBusyTime.set(System.currentTimeMillis())
						}
					} else if("storage" == changed) {
						mLastFileListed.set(0L)
						mCameraUpdateTime.set(System.currentTimeMillis())
						thread.notifyEx()
					} else {
						log.d("WebSocket onTextMessage %s", text)
					}
				}
			} catch(ex : Throwable) {
				log.trace(ex, "WebSocket message handling error.")
				log.e(ex, "WebSocket message handling error.")
			}
			
		}
	}
	
	private fun loadFolder(network : Any?) : Boolean {
		val cgi_url = "${thread.target_url}v1/photos"
		var data = thread.client.getHTTP(log, network, cgi_url)
		if(thread.isCancelled) return false
		if(data == null) {
			thread.checkHostError()
			log.e(R.string.folder_list_failed, "/", cgi_url, thread.client.last_error)
			return false
		}
		// job_queue.add( new Item( "/", new LocalFile( service, folder_uri ), false, 0L ) );
		
		val buf = ByteArray(1)
		val calendar = GregorianCalendar(TimeZone.getDefault())
		
		try {
			val info = JSONObject(data.decodeUTF8())
			if(info.optInt("errCode", 0) != 200) {
				throw RuntimeException("server's errMsg:" + info.optString("errMsg"))
			}
			val root_dir = info.optJSONArray("dirs")
			if(root_dir == null) {
				log.e("missing root folder.")
				return false
			}
			val local_root =
				LocalFile(service, thread.folder_uri)
			val local_dcim = LocalFile(local_root, "DCIM")
			var i = 0
			val ie = root_dir.length()
			while(i < ie) {
				if(thread.isCancelled) return false
				
				val o = root_dir.opt(i)
				if(o !is JSONObject) {
					++ i
					continue
				}
				
				val sub_dir_name = o.optString("name", null)
				if(sub_dir_name?.isEmpty()!=false) {
					++ i
					continue
				}
				
				val sub_dir_local =
					LocalFile(local_dcim, sub_dir_name)
				val files = o.optJSONArray("files")
				if(files == null) {
					++ i
					continue
				}
				
				var j = 0
				val je = files.length()
				while(j < je) {
					if(thread.isCancelled) return false
					val file_name = files.optString(j)
					if(file_name?.isEmpty()!=false) {
						++ j
						continue
					}
					// file type matching
					for(re in thread.file_type_list) {
						if(thread.isCancelled) return false
						if(! re.matcher(file_name).find()) continue
						// マッチした
						
						val remote_path = "/$sub_dir_name/$file_name"
						val local_file =
							LocalFile(sub_dir_local, file_name)
						
						// ローカルのファイルサイズを調べて既読スキップ
						if(thread.checkSkip(local_file, log, 1L)) continue
						
						// 進捗表示用のファイルサイズは超適当
						var size = 1000000L
						if(CHECK_FILE_SIZE) {
							// ダウンロード進捗のためにサイズを調べる
							try {
								log.d("get file size for %s", remote_path)
								val get_url = "${thread.target_url}v1/photos${Uri.encode(remote_path,"/_")}?size=full"
								data = thread.client.getHTTP(
									log,
									network,
									get_url
								) { _, _, _, _ ->
									buf
								}
								if(thread.isCancelled) return false
								if(data == null) {
									thread.checkHostError()
									log.e("can not get file size. %s", thread.client.last_error)
								} else {
									//// thread.client.dump_res_header( log );
									val sv = thread.client.getHeaderString("Content-Length", null)
									if(sv?.isNotEmpty()==true) {
										size = java.lang.Long.parseLong(sv, 10)
									}
								}
							} catch(ex : Throwable) {
								log.e(ex, "can not get file size.")
							}
							
						}
						var time = 0L
						if(CHECK_FILE_TIME) {
							try {
								log.d("get file time for %s", remote_path)
								val get_url ="${thread.target_url}v1/photos${Uri.encode(remote_path,"/_")}/info"
								data = thread.client.getHTTP(log, network, get_url)
								if(thread.isCancelled) return false
								if(data == null) {
									thread.checkHostError()
									log.e("can not get file time. %s", thread.client.last_error)
								} else {
									val file_info = JSONObject(data.decodeUTF8())
									if(file_info.optInt("errCode", 0) != 200) {
										throw RuntimeException("server's errMsg:" + info.optString("errMsg"))
									}
									val matcher =
										reDateTime.matcher(file_info.optString("datetime", ""))
									if(! matcher.find()) {
										log.e("can not get file time. missing 'datetime' property.")
									} else {
										val y = Integer.parseInt(matcher.group(1), 10)
										val m = Integer.parseInt(matcher.group(2), 10)
										val d = Integer.parseInt(matcher.group(3), 10)
										val h = Integer.parseInt(matcher.group(4), 10)
										val min = Integer.parseInt(matcher.group(5), 10)
										val s = Integer.parseInt(matcher.group(6), 10)
										// log.d("time=%s,%s,%s,%s,%s,%s", y, m, d, h, min, s)
										calendar.set(y, m, d, h, min, s)
										calendar.set(Calendar.MILLISECOND, 500)
										time = calendar.timeInMillis
									}
								}
							} catch(ex : Throwable) {
								log.e(ex, "can not get file time.")
							}
							
						}
						val mime_type = Utils.getMimeType(log, file_name)
						
						val item =
							ScanItem(
								file_name,
								remote_path,
								local_file,
								size,
								time,
								mime_type
							)
						
						// ファイルはキューの末尾に追加
						thread.job_queue !!.addFile(item)
						
						thread.record(
							item, 0L, DownloadRecord.STATE_QUEUED, "queued."
						)
						
						break
					}
					++ j
				}
				++ i
			}
			log.i("%s files queued.", thread.job_queue !!.file_count)
			return true
		} catch(ex : Throwable) {
			log.e(ex, R.string.remote_file_list_parse_error)
		}
		
		thread.job_queue = null
		return false
	}
	
	private fun loadFile(network : Any?, item : ScanItem) {
		val time_start = SystemClock.elapsedRealtime()
		
		try {
			val remote_path = item.remote_path
			val file_name = item.local_file.name

			val local_file = item.local_file
				.prepareFile(log, true, item.mime_type)
			if(local_file == null ) {
				log.e("%s//%s :skip. can not prepare local file.", item.remote_path, file_name)
				thread.record(
					item,
					SystemClock.elapsedRealtime() - time_start,
					DownloadRecord.STATE_LOCAL_FILE_PREPARE_ERROR,
					"can not prepare local file."
				)
				return
			}
			
			val get_url = "${thread.target_url}v1/photos${Uri.encode(remote_path, "/_")}?size=full"
			val buf = ByteArray(2048)
			val data =
				thread.client.getHTTP(log, network, get_url) { log, cancel_checker, inStream, _ ->
					try {
						
						val os = local_file.openOutputStream(service)
						if(os == null) {
							log.e("cannot open local output file.")
						} else {
							try {
								while(true) {
									if(cancel_checker.isCancelled) {
										return@getHTTP null
									}
									val delta = inStream.read(buf)
									if(delta <= 0) break
									os.write(buf, 0, delta)
								}
								return@getHTTP buf
							} finally {
								try {
									os.close()
								} catch(ignored : Throwable) {
								}
								
							}
						}
					} catch(ex : Throwable) {
						log.e("HTTP read error. %s:%s", ex.javaClass.simpleName, ex.message)
					}
					
					null
					
				}
			
			thread.afterDownload(time_start, data, item)
			
		} catch(ex : Throwable) {
			thread.afterDownload(time_start, ex, item)
		}
		
	}
	
	fun run() {
		
		while(! thread.isCancelled) {
			
			// WakeLockやWiFiLockを確保
			thread.callback.acquireWakeLock()
			
			// 通信状態の確認
			thread.setStatus(false, service.getString(R.string.network_check))
			val network_check_start = SystemClock.elapsedRealtime()
			var network : Any? = null
			while(! thread.isCancelled) {
				network = thread.wifiNetwork
				if(network != null) break
				
				// 一定時間待機してもダメならスレッドを停止する
				// 通信状態変化でまた起こされる
				val er_now = SystemClock.elapsedRealtime()
				if(er_now - network_check_start >= 60 * 1000L) {
					// Pref.pref( service ).edit().putLong( Pref.LAST_IDLE_START, System.currentTimeMillis() ).apply();
					thread.job_queue = null
					thread.cancel(service.getString(R.string.network_not_good))
					break
				}
				
				// 少し待って再確認
				thread.waitEx(10000L)
			}
			if(thread.isCancelled) break
			
			// WebSocketが閉じられていたら後処理をする
			if(ws_client != null && ws_client !!.state == WebSocketState.CLOSED) {
				thread.setStatus(false, "WebSocket closing")
				ws_client !!.removeListener(ws_listener)
				ws_client !!.disconnect()
				ws_client = null
			}
			if(thread.isCancelled) break
			
			// WebSocketがなければ開く
			if(ws_client == null) {
				thread.setStatus(false, "WebSocket creating")
				try {
					val factory = WebSocketFactory()
					val ws = factory.createSocket( "${thread.target_url}v1/changes", 30000)
					ws_client = ws
					ws.addListener(ws_listener)
					ws.connect()
					thread.waitEx(2000L)
				} catch(ex : OpeningHandshakeException) {
					log.trace(ex,"WebSocket connection failed(1).")
					log.e(ex, "WebSocket connection failed(1).")
					thread.waitEx(5000L)
					continue
				} catch(ex : WebSocketException) {
					log.trace(ex,"WebSocket connection failed(2).")
					log.e(ex, "WebSocket connection failed(2).")
					
					val active_other = service.wifi_tracker.otherActive
					if( active_other.isNotEmpty() ) {
						log.w(R.string.other_active_warning, active_other)
					}
					
					thread.waitEx(5000L)
					continue
				} catch(ex : IOException) {
					log.trace(ex,"WebSocket connection failed(3).")
					log.e(ex, "WebSocket connection failed(3).")
					thread.waitEx(5000L)
					continue
				}
				
			}
			if(thread.isCancelled) break
			
			val now = System.currentTimeMillis()
			val remain : Long
			if(mIsCameraBusy.get()) {
				// ビジー状態なら待機を続ける
				thread.setStatus(false, "camera is busy.")
				remain = 2000L
			} else if(now - mLastBusyTime.get() < 2000L) {
				// ビジーが終わっても数秒は待機を続ける
				thread.setStatus(false, "camera was busy.")
				remain = mLastBusyTime.get() + 2000L - now
			} else if(now - mCameraUpdateTime.get() < 2000L) {
				// カメラが更新された後数秒は待機する
				thread.setStatus(false, "waiting camera storage.")
				remain = mCameraUpdateTime.get() + 2000L - now
			} else if(thread.job_queue != null || thread.callback.hasHiddenDownloadCount()) {
				// キューにある項目を処理する
				// 隠れたダウンロードカウントがある場合もスキャンをやり直す
				remain = 0L
			} else {
				
				// キューがカラなら、最後にファイル一覧を取得した時刻から一定は待つ
				remain = mLastFileListed.get() + thread.intervalSeconds * 1000L - now
				if(remain > 0L) {
					thread.setShortWait(remain)
					continue
				}
			}
			if(remain > 0) {
				thread.waitEx(if(remain < 1000L) remain else 1000L)
				continue
			}
			
			// ファイルスキャンの開始
			if(thread.job_queue == null) {
				Pref.pref(service).edit().put(Pref.lastIdleStart, System.currentTimeMillis())
					.apply()
				
				// 未取得状態のファイルを履歴から消す
				if(DownloadWorker.RECORD_QUEUED_STATE) {
					service.contentResolver.delete(
						DownloadRecord.meta.content_uri,
						DownloadRecord.COL_STATE_CODE + "=?",
						arrayOf(Integer.toString(DownloadRecord.STATE_QUEUED))
					)
				}
				
				// フォルダスキャン開始
				thread.onFileScanStart()
				thread.setStatus(false, service.getString(R.string.remote_file_listing))
				if(! loadFolder(network)) {
					thread.job_queue = null
					continue
				}
				mLastFileListed.set(System.currentTimeMillis())
			}
			
			try {
				if(! thread.job_queue !!.queue_folder.isEmpty()) {
					val head = thread.job_queue !!.queue_folder.removeFirst()
					thread.setStatus(
						false,
						service.getString(R.string.progress_folder, head.remote_path)
					)
					// ここは通らない
				} else if(! thread.job_queue !!.queue_file.isEmpty()) {
					// キューから除去するまえに残りサイズを計算したい
					val head = thread.job_queue !!.queue_file.first
					thread.setStatus(
						true,
						service.getString(R.string.download_file, head.remote_path)
					)
					thread.job_queue !!.queue_file.removeFirst()
					loadFile(network, head)
				} else {
					// ファイルスキャンの終了
					val file_count = thread.job_queue !!.file_count
					thread.job_queue = null
					thread.setStatus(false, service.getString(R.string.file_scan_completed))
					if(! thread.file_error) {
						thread.onFileScanComplete(file_count)
					}
				}
			} catch(ex : Throwable) {
				log.trace(ex, "error.")
				log.e(ex, "error.")
			}
			
		}
		
		// WebSocketの解除
		ws_client ?.removeListener(ws_listener)
		ws_client ?.disconnect()
		ws_client = null
		
	}
	
}
