package jp.juggler.fadownloader.targets

import android.net.Uri
import android.os.SystemClock
import jp.juggler.fadownloader.*
import jp.juggler.fadownloader.model.LocalFile
import jp.juggler.fadownloader.model.ScanItem
import jp.juggler.fadownloader.table.DownloadRecord
import jp.juggler.fadownloader.util.Utils
import jp.juggler.fadownloader.util.decodeUTF8
import java.io.File
import java.util.*
import java.util.regex.Pattern

class FlashAir(private val service : DownloadService, internal val thread : DownloadWorker) {
	
	companion object {
		// private val logStatic = LogTag("FlashAir")
		internal val reLine = Pattern.compile("([^\\x0d\\x0a]+)")
		internal val reAttr = Pattern.compile(",(\\d+),(\\d+),(\\d+),(\\d+)$")
		
		// static final long ERROR_BREAK = -1L;
		internal const val ERROR_CONTINUE = - 2L
	}
	
	internal val log = service.log
	
	private var flashair_update_status = 0L
	
	private fun getFlashAirUpdateStatus(network : Any?) : Long {
		val cgi_url = "${thread.target_url}command.cgi?op=121"
		val data = thread.client.getHTTP(log, network, cgi_url)
		if(thread.isCancelled) return ERROR_CONTINUE
		
		if(data == null) {
			thread.checkHostError()
			log.e(R.string.flashair_update_check_failed, cgi_url, thread.client.last_error)
			return ERROR_CONTINUE
		}
		return try {
			java.lang.Long.parseLong(data.decodeUTF8().trim { it <= ' ' })
		} catch(ex : Throwable) {
			log.e(R.string.flashair_update_status_error)
			thread.cancel(service.getString(R.string.flashair_update_status_error))
			ERROR_CONTINUE
		}
		
	}
	
	// フォルダを読む
	private fun loadFolder(network : Any?, item : ScanItem) {

		val cgi_url =  "${thread.target_url}command.cgi?op=100&DIR=${Uri.encode(item.remote_path)}"
		val data = thread.client.getHTTP(log, network, cgi_url)
		if(thread.isCancelled) return
		
		if(data == null) {
			thread.checkHostError()
			log.e(
				R.string.folder_list_failed,
				item.remote_path,
				cgi_url,
				thread.client.last_error
			)
			thread.file_error = true
			return
		}
		
		val mLine = try {
			reLine.matcher(data.decodeUTF8())
		} catch(ex : Throwable) {
			log.trace(ex, "folder list parse error.")
			log.e(ex, "folder list parse error.")
			return
		}
		
		val calendar = GregorianCalendar(TimeZone.getDefault())
		
		while(! thread.isCancelled && mLine.find()) {
			val line = mLine.group(1)
			val mAttr = reAttr.matcher(line)
			if(! mAttr.find()) continue
			
			try {
				val size = java.lang.Long.parseLong(mAttr.group(1), 10)
				val attr = Integer.parseInt(mAttr.group(2), 10)
				//
				val time : Long = run {
					val bits_date = Integer.parseInt(mAttr.group(3), 10)
					val bits_time = Integer.parseInt(mAttr.group(4), 10)
					val y = (bits_date shr 9 and 0x7f) + 1980
					val m = bits_date shr 5 and 0xf
					val d = bits_date and 0x1f
					val h = bits_time shr 11 and 0x1f
					val j = bits_time shr 5 and 0x3f
					val s = (bits_time and 0x1f) * 2
					//	log.f( "time=%s,%s,%s,%s,%s,%s", y, m, d, h, j, s );
					calendar.set(y, m - 1, d, h, j, s)
					calendar.set(Calendar.MILLISECOND, 500)
					calendar.timeInMillis
				}
				
				// https://flashair-developers.com/ja/support/forum/#/discussion/3/%E3%82%AB%E3%83%B3%E3%83%9E%E5%8C%BA%E5%88%87%E3%82%8A
				val dir = if(item.remote_path == "/") "" else item.remote_path
				val file_name = line.substring(dir.length + 1, mAttr.start())

				if(attr and 2 != 0) {
					// skip hidden file
					continue
				} else if(attr and 4 != 0) {
					// skip system file
					continue
				}
				
				val remote_path = "$dir/$file_name"
				val local_file = LocalFile(item.local_file, file_name)
				
				if(attr and 0x10 != 0) {
					// フォルダはキューの頭に追加
					thread.job_queue!!.addFolder(
						ScanItem(
							file_name,
							remote_path,
							local_file,
							mime_type = ScanItem.MIME_TYPE_FOLDER
						)
					)
				} else {
					
					if(thread.protected_only) {
						if(attr and 1 == 0) {
							// リードオンリー属性がオフ
							continue
						}
					}
					
					var matched = false
					for(re in thread.file_type_list) {
						if(re.matcher(file_name).find()){
							matched = true
							break
						}
					}
					if(!matched) {
						// logStatic.d("$file_name not match in file_type_list")
						continue
					}
					// ローカルのファイルサイズを調べて既読スキップ
					if(thread.checkSkip(local_file, log, size)) {
						// logStatic.d("$file_name already downloaded.")
						continue
					}
						
					val mime_type = Utils.getMimeType(log, file_name)
					
					// ファイルはキューの末尾に追加
					val sub_item =
						ScanItem(
							file_name,
							remote_path,
							local_file,
							size,
							time = time,
							mime_type = mime_type
						)
					thread.job_queue!!.addFile(sub_item)
					thread.record(sub_item, 0L, DownloadRecord.STATE_QUEUED, "queued.")
						
				}
			} catch(ex : Throwable) {
				log.trace(ex, "folder list parse error: $line" )
				log.e(ex, "folder list parse error: %s", line)
			}
			
		}
	}
	
	private fun loadFile(network : Any?, item : ScanItem) {
		val time_start = SystemClock.elapsedRealtime()
		val file_name = File(item.remote_path).name
		val remote_path = item.remote_path
		val local_file = item.local_file
		
		try {
			
			if(! local_file.prepareFile(log, true, item.mime_type)) {
				log.e("%s//%s :skip. can not prepare local file.", item.remote_path, file_name)
				thread.record(
					item,
					SystemClock.elapsedRealtime() - time_start,
					DownloadRecord.STATE_LOCAL_FILE_PREPARE_ERROR,
					"can not prepare local file."
				)
				return
			}
			
			val get_url = "${thread.target_url}${Uri.encode(remote_path)}"
			val buf = ByteArray(2048)
			val data = thread.client.getHTTP(
				log,
				network,
				get_url
			) { log, cancel_checker, inStream, _ ->
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
					log.e(ex,"HTTP read error.")
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
			
			if(thread.job_queue == null && ! thread.callback.hasHiddenDownloadCount()) {
				// 指定時刻まで待機する
				while(! thread.isCancelled) {
					val now = System.currentTimeMillis()
					val last_file_listing = Pref.lastIdleStart(Pref.pref(service))
					val remain = last_file_listing + thread.intervalSeconds * 1000L - now
					if(remain <= 0) break
					
					if(thread.isTetheringType || remain < 15 * 1000L) {
						thread.setShortWait(remain)
						
					} else {
						thread.setAlarm(now, remain)
						break
					}
				}
				if(thread.isCancelled) break
				// 待機が終わった
			}
			
			thread.callback.acquireWakeLock()
			var network : Any? = null
			
			// 通信状態の確認
			thread.setStatus(false, service.getString(R.string.network_check))
			val network_check_start = SystemClock.elapsedRealtime()
			
			if(thread.target_type == Pref.TARGET_TYPE_FLASHAIR_STA) {
				while(! thread.isCancelled) {
					val tracker_last_result = service.wifi_tracker.bLastConnected.get()
					val air_url = service.wifi_tracker.lastTargetUrl.get()
					if(tracker_last_result && air_url.isNotEmpty() ) {
						thread.target_url = air_url
						break
					}
					
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
			} else {
				
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
			}
			if(thread.isCancelled) break
			
			// ファイルスキャンの開始
			if(thread.job_queue == null) {
				Pref.pref(service).edit().put(Pref.lastIdleStart, System.currentTimeMillis())
					.apply()
				
				// FlashAir アップデートステータスを確認
				thread.setStatus(false, service.getString(R.string.flashair_update_status_check))
				flashair_update_status = getFlashAirUpdateStatus(network)
				if(flashair_update_status == ERROR_CONTINUE) {
					continue
				} else {
					val old = Pref.flashAirUpdateStatusOld(Pref.pref(service))
					if(flashair_update_status == old && old != - 1L) {
						// 前回スキャン開始時と同じ数字なので変更されていない
						log.d(R.string.flashair_not_updated)
						thread.onFileScanComplete(0)
						continue
					} else {
						log.d(
							"flashair updated %d %d", old, flashair_update_status
						)
					}
				}
				
				// 未取得状態のファイルを履歴から消す
				@Suppress("ConstantConditionIf")
				if(DownloadWorker.RECORD_QUEUED_STATE) {
					service.contentResolver.delete(
						DownloadRecord.meta.content_uri,
						DownloadRecord.COL_STATE_CODE + "=?",
						arrayOf(Integer.toString(DownloadRecord.STATE_QUEUED))
					)
				}
				
				// フォルダスキャン開始
				thread.onFileScanStart()
				thread.job_queue ?.addFolder(
					ScanItem(
						"",
						"/",
						LocalFile(service, thread.folder_uri),
						mime_type = ScanItem.MIME_TYPE_FOLDER
					)
				)
			}
			
			try {
				if(! thread.job_queue !!.queue_folder.isEmpty()) {
					val item = thread.job_queue !!.queue_folder.removeFirst()
					thread.setStatus(
						false,
						service.getString(R.string.progress_folder, item.remote_path)
					)
					loadFolder(network, item)
				} else if(! thread.job_queue !!.queue_file.isEmpty()) {
					// キューから除去するまえに残りサイズを計算したい
					val item = thread.job_queue !!.queue_file.first
					thread.setStatus(
						true,
						service.getString(R.string.download_file, item.remote_path)
					)
					thread.job_queue !!.queue_file.removeFirst()
					loadFile(network, item)
				} else {
					// ファイルスキャンの終了
					val file_count = thread.job_queue !!.file_count
					thread.job_queue = null
					thread.setStatus(false, service.getString(R.string.file_scan_completed))
					if(! thread.file_error) {
						Pref.pref(service).edit()
							.put(Pref.flashAirUpdateStatusOld, flashair_update_status)
							.apply()
						thread.onFileScanComplete(file_count)
					}
				}
			} catch(ex : Throwable) {
				log.trace(ex, "error.")
				log.e(ex, "error.")
			}
			
		}
	}
	
}
