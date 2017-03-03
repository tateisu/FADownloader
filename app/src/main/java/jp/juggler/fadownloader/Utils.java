package jp.juggler.fadownloader;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkInfo;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;


import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.SparseBooleanArray;

public class Utils{

	public static Network getWiFiNetwork( Context context ){
		ConnectivityManager cm = (ConnectivityManager) context.getSystemService( Context.CONNECTIVITY_SERVICE );
		for( Network n : cm.getAllNetworks() ){
			NetworkInfo info = cm.getNetworkInfo( n );
			if( info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI ) return n;
		}
		return null;
	}

	@SuppressLint( "DefaultLocale" )
	public static String formatTimeDuration( long t ){
		StringBuilder sb = new StringBuilder();
		long n;
		// day
		n = t / 86400000L;
		if( n > 0 ){
			sb.append( String.format( "%dd", n ) );
			t -= n * 86400000L;
		}
		// h
		n = t / 3600000L;
		if( n > 0 || sb.length() > 0 ){
			sb.append( String.format( "%dh", n ) );
			t -= n * 3600000L;
		}
		// m
		n = t / 60000L;
		if( n > 0 || sb.length() > 0 ){
			sb.append( String.format( "%dm", n ) );
			t -= n * 60000L;
		}
		// s
		n = t / 1000L;
		if( n > 0 || sb.length() > 0 ){
			sb.append( String.format( "%ds", n ) );
			t -= n * 1000L;
		}
		// ms
		if( sb.length() == 0 ){
			sb.append( String.format( "%dms", t ) );
		}

		return sb.toString();
	}

	public static PendingIntent createAlarmPendingIntent(Context context){
		Intent i = new Intent( context.getApplicationContext(), Receiver1.class );
		i.setAction( Receiver1.ACTION_ALARM );
		return PendingIntent.getBroadcast(  context.getApplicationContext(), 0, i, 0 );
	}

	// 文字列とバイト列の変換
	public static byte[] encodeUTF8(String str){
		try{
			return str.getBytes("UTF-8");
		}catch(Throwable ex){
			return null; // 入力がnullの場合のみ発生
		}
	}

	// 文字列とバイト列の変換
	public static String decodeUTF8(byte[] data){
		try{
			return new String(data,"UTF-8");
		}catch(Throwable ex){
			return null; // 入力がnullの場合のみ発生
		}
	}

	// 文字列と整数の変換
	public static int parse_int(String v,int defval){
		try{
			return Integer.parseInt(v,10);
		}catch(Throwable ex){
			return defval;
		}
	}

	static final char[] hex = new char[]{ '0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f' };
	public static final void addHex(StringBuilder sb,byte b){
		sb.append( hex[ (b>>4)&15] );
		sb.append( hex[ (b   )&15] );
	}
	public static final int hex2int(int c){
		switch(c){
		default: return 0;
		case '0': return 0;
		case '1': return 1;
		case '2': return 2;
		case '3': return 3;
		case '4': return 4;
		case '5': return 5;
		case '6': return 6;
		case '7': return 7;
		case '8': return 8;
		case '9': return 9;
		case 'a': return 0xa;
		case 'b': return 0xb;
		case 'c': return 0xc;
		case 'd': return 0xd;
		case 'e': return 0xe;
		case 'f': return 0xf;
		case 'A': return 0xa;
		case 'B': return 0xb;
		case 'C': return 0xc;
		case 'D': return 0xd;
		case 'E': return 0xe;
		case 'F': return 0xf;
		}
	}

	// 16進ダンプ
	public static String encodeHex(byte[] data){
		if(data==null) return null;
		StringBuilder sb = new StringBuilder();
		for(int i=0;i<data.length;++i){
			addHex(sb,data[i]);
		}
		return sb.toString();
	}




	public static byte[] encodeSHA256(byte[] src){
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.reset();
			return digest.digest(src);
		}catch(NoSuchAlgorithmException e1) {
			return null;
		}
	}
	public static String encodeBase64Safe(byte[] src){
		try{
			return Base64.encodeToString( src,Base64.URL_SAFE);
		}catch(Throwable ex){
			return null;
		}
	}


	public static String url2name(String url){
		if(url==null) return null;
		return encodeBase64Safe(encodeSHA256(encodeUTF8(url)));
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
	public static String digestMD5(String s){
		if(s==null) return null;
		try{
			MessageDigest md = MessageDigest.getInstance("MD5");
			md.reset();
			return encodeHex(md.digest(s.getBytes("UTF-8")));
		}catch(Throwable ex){
			ex.printStackTrace();
		}
		return null;
	}


	//! デバイス名の正規化. デバイス名に使えない文字を潰す
	static final Pattern reFixDevice1 = Pattern.compile("[^A-Za-z0-9_\\.\\-]+");
	//! デバイス名の正規化. 始端と終端の記号を潰す
	static Pattern reFixDevice2 = Pattern.compile("\\A[^A-Za-z0-9]+|[^A-Za-z0-9]+\\Z");

	//! デバイス名の正規化
	public static String normalizeDeviceName(String s){
		s = reFixDevice1.matcher(s).replaceAll("_");
		s = reFixDevice2.matcher(s).replaceAll("");
		return s;
	}

	/////////////////////////////////////////////


	static HashMap<Character,String> taisaku_map= new HashMap<Character,String>();
	static SparseBooleanArray taisaku_map2 = new SparseBooleanArray();

	static void _taisaku_add_string(String z,String h){
		for(int i=0,e=z.length();i<e;++i){
			char zc = z.charAt(i);
			taisaku_map.put(zc,""+Character.toString(h.charAt(i)));
			taisaku_map2.put((int)zc,true);
		}
	}

	static{
		taisaku_map= new HashMap<Character,String>();
		taisaku_map2 = new SparseBooleanArray();

		// tilde,wave dash,horizontal ellipsis,minus sign
		_taisaku_add_string(
			"\u2073\u301C\u22EF\uFF0D"
			,"\u007e\uFF5E\u2026\u2212"
		);
		// zenkaku to hankaku
		_taisaku_add_string(
			"　！”＃＄％＆’（）＊＋，－．／０１２３４５６７８９：；＜＝＞？＠ＡＢＣＤＥＦＧＨＩＪＫＬＭＮＯＰＱＲＳＴＵＶＷＸＹＺ［］＾＿｀ａｂｃｄｅｆｇｈｉｊｋｌｍｎｏｐｑｒｓｔｕｖｗｘｙｚ｛｜｝"
			," !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_`abcdefghijklmnopqrstuvwxyz{|}"
		);

	}

	static final boolean isBadChar2(char c){
		return c== 0xa || taisaku_map2.get((int)c);
	}

	//! フォントによって全角文字が化けるので、その対策
	public static String font_taisaku(String text,boolean lf2br){
		if(text==null) return null;
		int l = text.length();
		StringBuilder sb = new StringBuilder(l);
		if(!lf2br){
			for(int i=0; i<l; ++i ){
				int start = i;
				while( i< l && !taisaku_map2.get((int)text.charAt(i))  ) ++i;
				if( i >start ){
					sb.append(text.substring(start,i));
					if( i >= l ) break;
				}
				sb.append(taisaku_map.get(text.charAt(i)));
			}
		}else{
			for(int i=0; i<l; ++i ){
				int start = i;
				while( i< l && !isBadChar2(text.charAt(i))) ++i;
				if( i >start ){
					sb.append(text.substring(start,i));
					if( i >= l ) break;
				}
				char c = text.charAt(i);
				if( c==0xa ){
					sb.append("<br/>");
				}else{
					sb.append(taisaku_map.get(c));
				}
			}
		}
		return sb.toString();
	}

	////////////////////////////////////////////////////////
	// XML

	public static void getTextContent_rec(StringBuilder sb,Node node,boolean trim){
		int type = node.getNodeType();
		if( type == Node.TEXT_NODE ){
			String s = node.getNodeValue();
			if(trim) s= s.trim();
			sb.append(s);
		}else{
			NodeList list = node.getChildNodes();
			for (int k=0;k<list.getLength();k++){
				getTextContent_rec(sb,list.item(k),trim);
			}
		}
	}

	// AndroidのDOM API で、テキストコンテンツを求める
	public static String getTextContent(Node node,boolean trim){
		StringBuilder sb = new StringBuilder();
		getTextContent_rec(sb,node,trim);
		return sb.toString();
	}


	public static String getAttrValue(NamedNodeMap map,String attr_name){
		if( map==null || TextUtils.isEmpty( attr_name )) return null;
		Node node = map.getNamedItem(attr_name);
		return node==null ? null : node.getNodeValue();
	}

	////////////////////////////////////////////////////////////

	static Object factory_lock = new Object();
	static DocumentBuilderFactory factory = null;


	public static Element xml_document(byte[] data) throws SAXException, IOException, ParserConfigurationException {
		synchronized (factory_lock) {
			if( factory==null) factory = DocumentBuilderFactory.newInstance();
			return factory.newDocumentBuilder().parse(new ByteArrayInputStream(data)).getDocumentElement();
		}
	}
	public static Element xml_document(String data) throws SAXException, IOException, ParserConfigurationException {
		synchronized (factory_lock) {
			if( factory==null) factory = DocumentBuilderFactory.newInstance();
			return factory.newDocumentBuilder().parse(new InputSource(new StringReader(data))).getDocumentElement();
		}
	}
	public static Element xml_document(File file) throws SAXException, IOException, ParserConfigurationException {
		synchronized (factory_lock) {
			if( factory==null) factory = DocumentBuilderFactory.newInstance();
			return factory.newDocumentBuilder().parse(file).getDocumentElement();
		}
	}

	// JSONObject.optString("string")は、nullリテラルが存在した場合に文字列"null"を返す
	// この挙動は都合が悪いので、nullリテラルの場合はdefvalに指定した文字列を返すようなメソッドを用意する
	public static String optString( JSONObject o, String key, String defval ){
		if( o==null || o.isNull( key ) ) return defval;
		return o.optString(key,defval);
	}
	public static String optString( JSONObject o, String key ){
		return optString(o,key,null);
	}
	public static String optString( JSONArray o, int key, String defval ){
		if( o==null || o.isNull( key ) ) return defval;
		return o.optString(key,defval);
	}
	public static String optString( JSONArray o, int key ){
		return optString(o,key,null);
	}


	public static class NodeListIterator implements Iterator<Node>{
		int i;
		int ie;
		NodeList list;
		@Override
		public boolean hasNext() {
			return i<ie;
		}

		@Override
		public Node next() {
			return list.item(i++);
		}

		@Override
		public void remove() {
			// 実装しない
		}
	}

	public static class NodeListIterable implements Iterable<Node>{
		Node n;

		@Override
		public Iterator<Node> iterator() {
			NodeListIterator it = new NodeListIterator();
			it.list = n.getChildNodes();
			it.i = 0;
			it.ie = it.list.getLength();
			return it;
		}
	}


	public static NodeListIterable childnodes( Node n ){
		NodeListIterable v = new NodeListIterable();
		v.n = n;
		return v;
	}

	static Pattern reControlChar = Pattern.compile("[\\x00-\\x1f\\x7f]");
	static Pattern reEscape      = Pattern.compile("\\\\(\\\\|n)");
	public static String unescape_lf(String text){
		// メッセージ中の制御文字を全て除去する
		text = reControlChar.matcher(text).replaceAll("");

		//  \\,\n を \ および改行に変換する。改行はダイアログ上で正しく表示されていればOK。
		Matcher m = reEscape.matcher(text);
		StringBuilder sb = new StringBuilder();
		int pre_end = 0;
		while( m.find() ){
			sb.append( text.substring( pre_end, m.start() ));
			pre_end = m.end();
			switch( m.group(1).charAt(0) ){
			default:
			case '\\': sb.append('\\'); break;
			case 'n': sb.append('\n'); break;
			}
		}
		sb.append(text.substring( pre_end, text.length() ));
		text = sb.toString();

		//  メッセージ始端と終端の空白文字及び制御文字を全て除去する。
		text = text.trim();

		return text;
	}

	////////////////////////////////////////////////////////
	// AndroidのDOM API で、テキストコンテンツを求める

	public static void getTextContent_rec(StringBuilder sb,Node node){
		int type = node.getNodeType();
		if( type == Node.TEXT_NODE ){
			sb.append(node.getNodeValue());
		}else{
			NodeList list = node.getChildNodes();
			for (int k=0;k<list.getLength();k++){
				getTextContent_rec(sb,list.item(k));
			}
		}
	}
	public static String getTextContent(Node node){
		StringBuilder sb = new StringBuilder();
		getTextContent_rec(sb,node);
		return sb.toString();
	}

	////////////////////////////

	public static String toLower(String from){
		if( from==null) return null;
		return from.toLowerCase(Locale.US);
	}
	public static String toUpper(String from){
		if( from==null) return null;
		return from.toUpperCase(Locale.US);
	}


	public static final String getString(Bundle b,String key,String defval){
		try{
			String v = b.getString(key);
			if( v != null ) return v;
		}catch(Throwable ex){
		}
		return defval;
	}

	public static JSONArray encodeJSONStringList(Iterable<String> list){
		if( list == null ) return null;
		JSONArray array = new JSONArray();
		for(String s : list ){
			array.put(s);
		}
		return array;
	}

	public static ArrayList<String> decodeJSONStringList(JSONArray src){
		if( src==null ) return null;
		ArrayList<String> list = new ArrayList<String>();
		for(int i=0,ie=src.length();i<ie;++i){
			Object o = src.opt(i);
			if( o instanceof String ) list.add( (String)o );
		}
		return list;
	}

	public static byte[] loadFile(File file) throws IOException{
		int size =(int) file.length();
		byte[] data = new byte[size];
		FileInputStream in = new FileInputStream(file);
		try{
			int nRead = 0;
			while ( nRead < size){
				int delta = in.read(data,nRead,size-nRead);
				if(delta<=0) break;
			}
			return data;
		}finally{
			try{
				in.close();
			}catch(Throwable ignored){
			}
		}
	}

	public static String ellipsize (String t,int max){
		return ( t.length() > max ? t.substring(0,max-1)+"…" : t );
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
}
