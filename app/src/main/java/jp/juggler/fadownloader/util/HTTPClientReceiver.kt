package jp.juggler.fadownloader.util

import jp.juggler.fadownloader.CancelChecker
import java.io.InputStream

//! HTTPClientのバッファ管理を独自に行いたい場合に使用する.
//! このインタフェースを実装したものをHTTPClient.getHTTP()の第二引数に指定する
interface HTTPClientReceiver {
	
	fun onHTTPClientStream(
		log : LogWriter,
		cancel_checker : CancelChecker,
		inStream : InputStream,
		content_length : Int
	) : ByteArray?
}
