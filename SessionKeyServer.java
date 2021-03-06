package com.att.research.RCloud;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.net.URI;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.Date;
import java.util.concurrent.Executors;
import java.security.MessageDigest;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;

import com.sleepycat.je.DatabaseException;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;

public class SessionKeyServer {
    public static KeyStore ks;
    public static void main(String[] args) throws IOException, KeyStoreException {
	int i = 0;
	int port = 4431;
	String bdb = null, listen = "*", tls_ks = null;
	while (i < args.length) {
	    if (args[i].equals("-d") && ++i < args.length) bdb = args[i];
	    else if (args[i].equals("-l") && ++i < args.length) listen = args[i];
	    else if (args[i].equals("-p") && ++i < args.length) port = Integer.parseInt(args[i]);
	    else if (args[i].equals("-tls") && ++i < args.length) tls_ks = args[i];
	    else if (args[i].equals("-h")) {
		System.out.println("\n Usage: SessionKeyServer [-d <db-path>] [-l <address>] [-p <port>]\n\n");
		System.exit(0);
	    }
	    i++;
	}
	if (bdb != null)
	    ks = new BDBKeyStore(bdb);
	else
	    ks = new HashKeyStore();
	
	InetSocketAddress addr = listen.equals("*") ? new InetSocketAddress(port) : new InetSocketAddress(listen, port);
	HttpServer server;
	if (tls_ks != null) {
	    HttpsServer tls = HttpsServer.create(addr, 0); 
	    server = tls;
	} else server = HttpServer.create(addr, 0);
	
	server.createContext("/", new SKSHandler());
	server.setExecutor(Executors.newCachedThreadPool());
	server.start();
	System.out.println("SessionKeyServer is listening on " + listen + ":" + port);
    }
}

class KeyStoreException extends Exception {
    public KeyStoreException() { super(); }
    public KeyStoreException(String message) { super(message); }
    public KeyStoreException(String message, Throwable cause) { super(message, cause); }
    public KeyStoreException(Throwable cause) { super(cause); }
}

interface KeyStore {
    public String get(String key) throws KeyStoreException;
    public void   put(String key, String value) throws KeyStoreException;
    public void   rm(String key) throws KeyStoreException;
}

class HashKeyStore implements KeyStore {
    Map<String, String> map;

    public HashKeyStore() {
	map = new HashMap<String, String>();
    }

    public String get(String key) throws KeyStoreException {
	try {
	    return map.get(key);
	} catch (Exception e) {
	    throw new KeyStoreException("unable to retrieve key", e);
	}
    }

    public void   put(String key, String value) throws KeyStoreException {
	try {
	    map.put(key, value);
	} catch (Exception e) {
	    throw new KeyStoreException("unable to retrieve key", e);
	}
    }

    public void   rm(String key) throws KeyStoreException {
	try {
	    map.remove(key);
	} catch (Exception e) {
	    throw new KeyStoreException("unable to retrieve key", e);
	}
    }	
}

class BDBKeyStore implements KeyStore {
    Environment env;
    Database db;

    public BDBKeyStore(String path) throws KeyStoreException {
	try {
	    EnvironmentConfig envConfig = new EnvironmentConfig();
	    envConfig.setAllowCreate(true);
	    env = new Environment(new File(path), envConfig);
	    DatabaseConfig dbConfig = new DatabaseConfig();
	    dbConfig.setAllowCreate(true);
	    db = env.openDatabase(null, "sessionKeyStore", dbConfig); 
	} catch (DatabaseException dbe) {
	    throw new KeyStoreException("unable to open sessionKeyStore ("+path+") database", dbe);
	} 
    }

    public String get(String key) throws KeyStoreException {
	try {
	    DatabaseEntry theKey = new DatabaseEntry(key.getBytes("UTF-8"));
	    DatabaseEntry theData = new DatabaseEntry();
	    if (db.get(null, theKey, theData, LockMode.DEFAULT) == OperationStatus.SUCCESS)
		return new String(theData.getData(), "UTF-8");
	    return null;
	} catch (Exception e) {
	    throw new KeyStoreException("unable to store key/value pair into sessionKeyStore database", e);
	} 	
    }
    
    public void put(String key, String value) throws KeyStoreException {
	try {
	    DatabaseEntry theKey = new DatabaseEntry(key.getBytes("UTF-8"));
	    DatabaseEntry theValue = new DatabaseEntry(value.getBytes("UTF-8"));
	    db.put(null, theKey, theValue);
	    env.flushLog(false); // essentially treating puts as atomic transactions but with fsync=false we don't enforce disk I/O
	} catch (Exception e) {
	    throw new KeyStoreException("unable to store key/value pair into sessionKeyStore database", e);
	} 
    }

    public void rm(String key) throws KeyStoreException {
	try {
	    DatabaseEntry theKey = new DatabaseEntry(key.getBytes("UTF-8"));
	    db.delete(null, theKey);
	    env.flushLog(false); // essentially treating rms as atomic transactions but with fsync=false we don't enforce disk I/O
	} catch (Exception e) {
	    throw new KeyStoreException("unable to remove key from sessionKeyStore database", e);
	}
    }
}

// -- KeyStore format (key => value)  --
// t:<realm>:<token>  =>  <uid>\n<source>[\n<auxiliary data>]
// ut:<realm>:<uid>   =>  <token>

class SKSHandler implements HttpHandler {
    static String bytes2hex(byte[] a) {
	StringBuilder sb = new StringBuilder();
	for(int i = 0; i < a.length; i++)
	    sb.append(String.format("%02x", a[i]));
	return sb.toString();
    }

    void respond(HttpExchange exchange, int code, String body) throws IOException {
	Headers responseHeaders = exchange.getResponseHeaders();
	responseHeaders.set("Content-Type", "text/plain");
	OutputStream responseBody = exchange.getResponseBody();
	byte outBytes[] = body.getBytes();
	exchange.sendResponseHeaders(code, outBytes.length);
	responseBody.write(outBytes);
	responseBody.close();
    }

    public void handle(HttpExchange exchange) throws IOException {
	try {
	    MessageDigest md = MessageDigest.getInstance("SHA-1");
	    String requestMethod = exchange.getRequestMethod();
	    String requestPath = exchange.getRequestURI().getPath();
	    String requestQuery = exchange.getRequestURI().getRawQuery();
	    Map<String, String> queryMap = new HashMap<String, String>();  
	    if (requestQuery != null) for (String param : requestQuery.split("\\&")) {  
		    String kvp[] = param.split("=", 2);
		    if (kvp.length > 1) queryMap.put(kvp[0], java.net.URLDecoder.decode(kvp[1]));  
		}
	    String realm = queryMap.get("realm"), realm_txt;
	    if (realm == null) {
		respond(exchange, 400, "ERR: missing realm\n");
		exchange.close();
		return;
	    } else {
		realm_txt = realm;
		md.update(realm.getBytes());
		realm = bytes2hex(md.digest());
	    }
	    if (requestMethod.equalsIgnoreCase("GET")) {
		if (requestPath.equals("/valid")) {
		    String token = queryMap.get("token");
		    if (token != null) {
			String val = SessionKeyServer.ks.get("t:" + realm + ":" + token);
			if (val != null) {
			    String info[] = val.split("\n");
			    if (info.length > 1) {
				String tok = SessionKeyServer.ks.get("ut:" + realm + ":" + info[0]);
				if (tok != null && tok.equals(token)) {
				    respond(exchange, 200, "YES\n" + info[0] + "\n" + info[1] + "\n");
				    System.out.println("token: "+((new Date()).getTime())+" user='"+info[0]+"' "+info[1]+", "+realm_txt+":"+token+", VALID");
				    return;
				} else {
				    respond(exchange, 200, "SUPERCEDED\n" + info[0] + "\n" + info[1] + "\n");
				    System.out.println("token: "+((new Date()).getTime())+" user='"+info[0]+"' "+info[1]+", "+realm_txt+":"+token+", SUPERCEDED");
				    return;
				}
			    }
			}
		    }
		    respond(exchange, 200, "NO\n");
		    System.out.println("token: "+((new Date()).getTime())+" "+token+", INVALID");
		    return;
		} else if (requestPath.equals("/revoke")) {
		    String token = queryMap.get("token");
		    if (token != null) {
			String val = SessionKeyServer.ks.get("t:" + realm + ":" + token);
			if (val != null) {
			    SessionKeyServer.ks.rm("t:" + realm + ":" + token);
			    String info[] = val.split("\n");
			    if (info.length > 1) {
				String tok = SessionKeyServer.ks.get("ut:" + realm + ":" + info[0]);
				if (tok != null && tok.equals(token))
				    SessionKeyServer.ks.put("ut:" + realm + ":" + info[0], "revoked");
			    }
			    System.out.println("revoked: "+((new Date()).getTime())+" user='"+info[0]+"', "+token+", VALID");
			    respond(exchange, 200, "OK\n");
			    return;
			}
		    }
		    System.out.println("token: "+((new Date()).getTime())+" "+token+", INVALID");
		    respond(exchange, 200, "INVALID\n");
		    return;
		} else if (requestPath.equals("/stored_token")) {
		    String user = queryMap.get("user");
		    String token = queryMap.get("token");
		    if (user != null && user.length() > 0 && token != null && token.length() > 0) {
			SessionKeyServer.ks.put("t:" + realm + ":" + token, user + "\nstored\n");
			SessionKeyServer.ks.put("ut:" + realm + ":" + user, token);
			respond(exchange, 200, token + "\n" + user + "\nstored\n");
			exchange.close();
			return;
		    }
		} else if (requestPath.equals("/pam_token")) {
		    String user = queryMap.get("user");
		    String pwd = queryMap.get("pwd");
		    boolean succ = false;
		    if (com.att.research.RCloud.PAM.checkUser(realm, user, pwd)) {
			md.update(java.util.UUID.randomUUID().toString().getBytes());
			md.update(java.util.UUID.randomUUID().toString().getBytes());
			String sha1 = bytes2hex(md.digest());
			SessionKeyServer.ks.put("t:" + realm + ":" + sha1, user + "\npam\n");
			SessionKeyServer.ks.put("ut:" + realm + ":" + user, sha1);
			respond(exchange, 200, sha1 + "\n" + user + "\npam\n");
			exchange.close();
			succ = true;
		    } else {
			exchange.sendResponseHeaders(403, -1);
                        exchange.close();
		    }
		    System.out.println("PAM: "+((new Date()).getTime())+" user='"+user+"', "+realm_txt+", "+(succ?"OK":"FAILED"));
		} else {
		    exchange.sendResponseHeaders(404, -1);
		    exchange.close();
		    return;
		}
	    } else {
		respond(exchange, 404, "Unknown path");
		exchange.close();
	    }
	} catch (java.security.NoSuchAlgorithmException noae) {
		exchange.sendResponseHeaders(500, -1);
		exchange.close();
	} catch (KeyStoreException kse) {
		exchange.sendResponseHeaders(500, -1);
		exchange.close();	    
	}
    }
}
