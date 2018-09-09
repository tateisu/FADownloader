package jp.juggler.fadownloader.util

import android.annotation.TargetApi
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationHelper {
	
	
	@TargetApi(26)
	fun createNotificationChannel(
		context : Context, channel_id : String // id
		, name : String // The user-visible name of the channel.
		, description : String? // The user-visible description of the channel.
		, importance : Int
		, log : LogWriter
	) : NotificationChannel {
		val notification_manager =
			context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
				?: throw NotImplementedError("missing NotificationManager system service")
		
		var channel : NotificationChannel? = null
		try {
			channel = notification_manager.getNotificationChannel(channel_id)
		} catch(ex : Throwable) {
			log.e(ex, "getNotificationChannel failed.")
		}
		
		if(channel == null) {
			channel = NotificationChannel(channel_id, name, importance)
		}
		channel.name = name
		channel.importance = importance
		if(description != null) channel.description = description
		notification_manager.createNotificationChannel(channel)
		return channel
		
	}
}