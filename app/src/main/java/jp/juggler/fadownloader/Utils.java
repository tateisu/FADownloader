package jp.juggler.fadownloader;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Locale;

import android.net.wifi.SupplicantState;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.SparseBooleanArray;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.FileFilter;
import java.text.DecimalFormat;
import java.util.Comparator;

public class Utils{

	@SuppressLint( "DefaultLocale" )
	public static String formatTimeDuration( long t ){
		StringBuilder sb = new StringBuilder();
		long n;
		// day
		n = t / 86400000L;
		if( n > 0 ){
			sb.append( String.format( Locale.JAPAN, "%dd", n ) );
			t -= n * 86400000L;
		}
		// h
		n = t / 3600000L;
		if( n > 0 || sb.length() > 0 ){
			sb.append( String.format( Locale.JAPAN, "%dh", n ) );
			t -= n * 3600000L;
		}
		// m
		n = t / 60000L;
		if( n > 0 || sb.length() > 0 ){
			sb.append( String.format( Locale.JAPAN, "%dm", n ) );
			t -= n * 60000L;
		}
		// s
		float f = t / 1000f;
		sb.append( String.format( Locale.JAPAN, "%.03fs", f ) );

		return sb.toString();
	}

	public static String getGigaMegaKiro( long t ){
		StringBuilder sb = new StringBuilder();
		long n;
		// Giga
		n = t / 1000000000L;
		if( n > 0 ){
			sb.append( String.format( Locale.JAPAN, "%dg", n ) );
			t -= n * 1000000000L;
		}
		// Mega
		n = t / 1000000L;
		if( sb.length() > 0 ){
			sb.append( String.format( Locale.JAPAN, "%03dm", n ) );
			t -= n * 1000000L;
		}else if( n > 0 ){
			sb.append( String.format( Locale.JAPAN, "%dm", n ) );
			t -= n * 1000000L;
		}
		// Kiro
		n = t / 1000L;
		if( sb.length() > 0 ){
			sb.append( String.format( Locale.JAPAN, "%03dk", n ) );
			t -= n * 1000L;
		}else if( n > 0 ){
			sb.append( String.format( Locale.JAPAN, "%dk", n ) );
			t -= n * 1000L;
		}
		// remain
		if( sb.length() > 0 ){
			sb.append( String.format( Locale.JAPAN, "%03d", t ) );
		}else if( n > 0 ){
			sb.append( String.format( Locale.JAPAN, "%d", t ) );
		}

		return sb.toString();
	}

	public static PendingIntent createAlarmPendingIntent( Context context ){
		Intent i = new Intent( context.getApplicationContext(), Receiver1.class );
		i.setAction( Receiver1.ACTION_ALARM );
		return PendingIntent.getBroadcast( context.getApplicationContext(), 0, i, 0 );
	}

	// 文字列とバイト列の変換
	public static byte[] encodeUTF8( String str ){
		try{
			return str.getBytes( "UTF-8" );
		}catch( Throwable ex ){
			return null; // 入力がnullの場合のみ発生
		}
	}

	// 文字列とバイト列の変換
	public static String decodeUTF8( byte[] data ){
		try{
			return new String( data, "UTF-8" );
		}catch( Throwable ex ){
			return null; // 入力がnullの場合のみ発生
		}
	}

	// 文字列と整数の変換
	public static int parse_int( String v, int defval ){
		try{
			return Integer.parseInt( v, 10 );
		}catch( Throwable ex ){
			return defval;
		}
	}

	static final char[] hex = new char[]{ '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f' };

	public static void addHex( StringBuilder sb, byte b ){
		sb.append( hex[ ( b >> 4 ) & 15 ] );
		sb.append( hex[ ( b ) & 15 ] );
	}

	public static int hex2int( int c ){
		switch( c ){
		default:
			return 0;
		case '0':
			return 0;
		case '1':
			return 1;
		case '2':
			return 2;
		case '3':
			return 3;
		case '4':
			return 4;
		case '5':
			return 5;
		case '6':
			return 6;
		case '7':
			return 7;
		case '8':
			return 8;
		case '9':
			return 9;
		case 'a':
			return 0xa;
		case 'b':
			return 0xb;
		case 'c':
			return 0xc;
		case 'd':
			return 0xd;
		case 'e':
			return 0xe;
		case 'f':
			return 0xf;
		case 'A':
			return 0xa;
		case 'B':
			return 0xb;
		case 'C':
			return 0xc;
		case 'D':
			return 0xd;
		case 'E':
			return 0xe;
		case 'F':
			return 0xf;
		}
	}

	// 16進ダンプ
	public static String encodeHex( byte[] data ){
		if( data == null ) return null;
		StringBuilder sb = new StringBuilder();
		for( byte b : data ){
			addHex( sb, b );
		}
		return sb.toString();
	}

	public static byte[] encodeSHA256( byte[] src ){
		try{
			MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
			digest.reset();
			return digest.digest( src );
		}catch( NoSuchAlgorithmException e1 ){
			return null;
		}
	}

	public static String encodeBase64Safe( byte[] src ){
		try{
			return Base64.encodeToString( src, Base64.URL_SAFE );
		}catch( Throwable ex ){
			return null;
		}
	}

	public static String url2name( String url ){
		if( url == null ) return null;
		return encodeBase64Safe( encodeSHA256( encodeUTF8( url ) ) );
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

	// MD5ハッシュの作成
	public static String digestMD5( String s ){
		if( s == null ) return null;
		try{
			MessageDigest md = MessageDigest.getInstance( "MD5" );
			md.reset();
			return encodeHex( md.digest( s.getBytes( "UTF-8" ) ) );
		}catch( Throwable ex ){
			ex.printStackTrace();
		}
		return null;
	}

	/////////////////////////////////////////////

	static HashMap<Character, String> taisaku_map = new HashMap<>();
	static SparseBooleanArray taisaku_map2 = new SparseBooleanArray();

	static void _taisaku_add_string( String z, String h ){
		for( int i = 0, e = z.length() ; i < e ; ++ i ){
			char zc = z.charAt( i );
			taisaku_map.put( zc, "" + Character.toString( h.charAt( i ) ) );
			taisaku_map2.put( (int) zc, true );
		}
	}

	static{
		taisaku_map = new HashMap<>();
		taisaku_map2 = new SparseBooleanArray();

		// tilde,wave dash,horizontal ellipsis,minus sign
		_taisaku_add_string(
			"\u2073\u301C\u22EF\uFF0D"
			, "\u007e\uFF5E\u2026\u2212"
		);
		// zenkaku to hankaku
		_taisaku_add_string(
			"　！”＃＄％＆’（）＊＋，－．／０１２３４５６７８９：；＜＝＞？＠ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺ［］＾＿｀ａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ｛｜｝"
			, " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`abcdefghijklmnopqrstuvwxyz{|}"
		);

	}

	static boolean isBadChar2( char c ){
		return c == 0xa || taisaku_map2.get( (int) c );
	}

	//! フォントによって全角文字が化けるので、その対策
	public static String font_taisaku( String text, boolean lf2br ){
		if( text == null ) return null;
		int l = text.length();
		StringBuilder sb = new StringBuilder( l );
		if( ! lf2br ){
			for( int i = 0 ; i < l ; ++ i ){
				int start = i;
				while( i < l && ! taisaku_map2.get( (int) text.charAt( i ) ) ) ++ i;
				if( i > start ){
					sb.append( text.substring( start, i ) );
					if( i >= l ) break;
				}
				sb.append( taisaku_map.get( text.charAt( i ) ) );
			}
		}else{
			for( int i = 0 ; i < l ; ++ i ){
				int start = i;
				while( i < l && ! isBadChar2( text.charAt( i ) ) ) ++ i;
				if( i > start ){
					sb.append( text.substring( start, i ) );
					if( i >= l ) break;
				}
				char c = text.charAt( i );
				if( c == 0xa ){
					sb.append( "<br/>" );
				}else{
					sb.append( taisaku_map.get( c ) );
				}
			}
		}
		return sb.toString();
	}

	////////////////////////////

	public static String toLower( String from ){
		if( from == null ) return null;
		return from.toLowerCase( Locale.US );
	}

	public static String toUpper( String from ){
		if( from == null ) return null;
		return from.toUpperCase( Locale.US );
	}

	public static String getString( Bundle b, String key, String defval ){
		try{
			String v = b.getString( key );
			if( v != null ) return v;
		}catch( Throwable ignored ){
		}
		return defval;
	}

	public static byte[] loadFile( File file ) throws IOException{
		int size = (int) file.length();
		byte[] data = new byte[ size ];
		FileInputStream in = new FileInputStream( file );
		try{
			int nRead = 0;
			while( nRead < size ){
				int delta = in.read( data, nRead, size - nRead );
				if( delta <= 0 ) break;
			}
			return data;
		}finally{
			try{
				in.close();
			}catch( Throwable ignored ){
			}
		}
	}

	public static String ellipsize( String t, int max ){
		return ( t.length() > max ? t.substring( 0, max - 1 ) + "…" : t );
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

	public static String getConnectionResultErrorMessage( ConnectionResult connectionResult ){
		int code = connectionResult.getErrorCode();
		String msg = connectionResult.getErrorMessage();
		if( TextUtils.isEmpty( msg ) ){
			switch( code ){
			case ConnectionResult.SUCCESS:
				msg = "SUCCESS";
				break;
			case ConnectionResult.SERVICE_MISSING:
				msg = "SERVICE_MISSING";
				break;
			case ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED:
				msg = "SERVICE_VERSION_UPDATE_REQUIRED";
				break;
			case ConnectionResult.SERVICE_DISABLED:
				msg = "SERVICE_DISABLED";
				break;
			case ConnectionResult.SIGN_IN_REQUIRED:
				msg = "SIGN_IN_REQUIRED";
				break;
			case ConnectionResult.INVALID_ACCOUNT:
				msg = "INVALID_ACCOUNT";
				break;
			case ConnectionResult.RESOLUTION_REQUIRED:
				msg = "RESOLUTION_REQUIRED";
				break;
			case ConnectionResult.NETWORK_ERROR:
				msg = "NETWORK_ERROR";
				break;
			case ConnectionResult.INTERNAL_ERROR:
				msg = "INTERNAL_ERROR";
				break;
			case ConnectionResult.SERVICE_INVALID:
				msg = "SERVICE_INVALID";
				break;
			case ConnectionResult.DEVELOPER_ERROR:
				msg = "DEVELOPER_ERROR";
				break;
			case ConnectionResult.LICENSE_CHECK_FAILED:
				msg = "LICENSE_CHECK_FAILED";
				break;
			case ConnectionResult.CANCELED:
				msg = "CANCELED";
				break;
			case ConnectionResult.TIMEOUT:
				msg = "TIMEOUT";
				break;
			case ConnectionResult.INTERRUPTED:
				msg = "INTERRUPTED";
				break;
			case ConnectionResult.API_UNAVAILABLE:
				msg = "API_UNAVAILABLE";
				break;
			case ConnectionResult.SIGN_IN_FAILED:
				msg = "SIGN_IN_FAILED";
				break;
			case ConnectionResult.SERVICE_UPDATING:
				msg = "SERVICE_UPDATING";
				break;
			case ConnectionResult.SERVICE_MISSING_PERMISSION:
				msg = "SERVICE_MISSING_PERMISSION";
				break;
			case ConnectionResult.RESTRICTED_PROFILE:
				msg = "RESTRICTED_PROFILE";
				break;

			}
		}
		return msg;
	}

	public static String getConnectionSuspendedMessage( int i ){
		switch( i ){
		default:
			return "?";
		case GoogleApiClient.ConnectionCallbacks.CAUSE_NETWORK_LOST:
			return "NETWORK_LOST";
		case GoogleApiClient.ConnectionCallbacks.CAUSE_SERVICE_DISCONNECTED:
			return "SERVICE_DISCONNECTED";
		}
	}

	public static String getSupplicantStateString( SupplicantState state ){
		if( state == null ) return null;
		return String.format( "(%d)%s", state.ordinal(), state.toString() );
	}

	static class FileInfo{

		Uri uri;
		String mime_type;

		FileInfo( ContentResolver cr, String any_uri ){
			if( any_uri == null ) return;

			if( any_uri.startsWith( "/" ) ){
				uri = Uri.fromFile( new File( any_uri ) );
			}else{
				uri = Uri.parse( any_uri );
				if( Build.VERSION.SDK_INT >= LocalFile.DOCUMENT_FILE_VERSION ){
					{
						Cursor cursor = cr.query( uri, null, null, null, null );
						if( cursor != null ){
							try{
								if( cursor.moveToFirst() ){
									int col_count = cursor.getColumnCount();
									for( int i = 0 ; i < col_count ; ++ i ){
										int type = cursor.getType( i );
										if( type != Cursor.FIELD_TYPE_STRING ) continue;
										String name = cursor.getColumnName( i );
										String value = cursor.isNull( i ) ? null : cursor.getString( i );
										Log.d( "DownloadRecordViewer", String.format( "%s %s", name, value ) );
										if( ! TextUtils.isEmpty( value ) ){
											if( "filePath".equals( name ) ){
												uri = Uri.fromFile( new File( value ) );
											}else if( "drmMimeType".equals( name ) ){
												mime_type = value;
											}
										}
									}
								}
							}catch( Throwable ex ){
								ex.printStackTrace();
							}finally{
								cursor.close();
							}
						}
					}
				}
			}

			if( mime_type == null ){
				String ext = MimeTypeMap.getFileExtensionFromUrl( any_uri );
				if( ext != null ){
					mime_type =  MimeTypeMap.getSingleton().getMimeTypeFromExtension( ext.toLowerCase(  ) );
				}
			}
		}
	}

}
