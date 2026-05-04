package at.andreasrohner.spartantimelapserec.rest;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import at.andreasrohner.spartantimelapserec.BuildConfig;
import at.andreasrohner.spartantimelapserec.ForegroundService;
import at.andreasrohner.spartantimelapserec.R;
import at.andreasrohner.spartantimelapserec.ServiceHelper;
import at.andreasrohner.spartantimelapserec.recorder.ImageRecorder;

import static android.os.Environment.DIRECTORY_PICTURES;
import static at.andreasrohner.spartantimelapserec.R.raw;

/**
 * Handle one HTTP Connection.
 *
 * Single-request-per-connection HTTP/1.0-style server. Always sends Connection: close.
 * Andreas Butti, 2024
 */
public class HttpThread extends Thread implements HttpOutput, Closeable {

	private static final String TAG = HttpThread.class.getSimpleName();

	private final SimpleDateFormat HTTP_DATE_FORMAT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);

	private final Socket socket;
	private final OutputStream out;
	private final RestService restService;

	public HttpThread(Socket socket, RestService restService) throws IOException {
		this.socket = socket;
		this.out = new BufferedOutputStream(socket.getOutputStream());
		this.restService = restService;
	}

	@Override
	public void run() {
		try {
			processRequest();
			this.out.flush();
		} catch (IOException e) {
			Log.e(TAG, "Error parsing HTTP Request", e);
		} finally {
			close();
		}
	}

	/**
	 * Parse incoming request line + headers, dispatch.
	 */
	private void processRequest() throws IOException {
		InputStreamReader isr = new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1);
		BufferedReader reader = new BufferedReader(isr);

		String request = reader.readLine();
		if (request == null) {
			return;
		}

		int pos = request.indexOf(' ');
		if (pos == -1) {
			Log.e(TAG, "Invalid Request: «" + request + "»");
			sendText(ReplyCode.NOT_FOUND, "Invalid request");
			return;
		}
		String method = request.substring(0, pos);
		int pos2 = request.indexOf(' ', pos + 1);
		if (pos2 == -1) {
			Log.e(TAG, "Invalid Request: «" + request + "»");
			sendText(ReplyCode.NOT_FOUND, "Invalid request");
			return;
		}
		String url = request.substring(pos + 1, pos2);
		String protocol = request.substring(pos2);

		Log.d(TAG, "Request: «" + method + "» «" + url + "» «" + protocol + "»");

		Map<String, String> header = new HashMap<>();
		String line;
		while ((line = reader.readLine()) != null && !line.isEmpty()) {
			int hp = line.indexOf(':');
			if (hp == -1) {
				continue;
			}
			String key = line.substring(0, hp).trim();
			String value = line.substring(hp + 1).trim();
			header.put(key, value);
		}

		processRequest(method, url, protocol, header);
	}

	/**
	 * Dispatch based on method.
	 */
	private void processRequest(String method, String url, String protocol, Map<String, String> header) throws IOException {
		if ("GET".equals(method)) {
			if (processGetRequest(url, header)) {
				return;
			}
		}

		if ("DELETE".equals(method)) {
			if (processDeleteRequest(url)) {
				return;
			}
		}

		StringBuilder body = new StringBuilder();
		body.append("File not found\r\n\r\n");
		body.append("-------------------------------\r\n");
		body.append("TimeLapseCam\r\n");
		sendText(ReplyCode.NOT_FOUND, body.toString());
	}

	private float getBatteryLevel() {
		Intent batteryStatus = restService.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
		int batteryLevel = -1;
		int batteryScale = 1;
		if (batteryStatus != null) {
			batteryLevel = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, batteryLevel);
			batteryScale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, batteryScale);
		}
		return batteryLevel / (float) batteryScale * 100;
	}

	private boolean processGetRequest(String url, Map<String, String> header) throws IOException {
		if ("/".equals(url)) {
			replyResource(raw.index, "text/html");
			return true;
		}

		if ("/rest".equals(url)) {
			replyResource(R.raw.help, "text/plain");
			return true;
		}

		if ("/favicon.ico".equals(url)) {
			replyResource(raw.favicon, "image/x-icon");
			return true;
		}

		if (url.startsWith("/1/device/battery")) {
			sendText(ReplyCode.FOUND, String.valueOf(getBatteryLevel()));
			return true;
		}

		if ("/1/dashboard".equals(url)) {
			return processDashboard();
		}

		if (url.startsWith("/1/current/")) {
			if (processCurrentRequest(url.substring(11), header)) {
				return true;
			}
		}

		if (url.startsWith("/1/ctrl/")) {
			if (processControlRequest(url.substring(8))) {
				return true;
			}
		}

		if (url.startsWith("/1/img/")) {
			if (processImageRequest(url.substring(6), header)) {
				return true;
			}
		}

		return false;
	}

	private boolean processDashboard() throws IOException {
		StringBuilder b = new StringBuilder();
		b.append("state=").append(ForegroundService.mIsRunning ? "running" : "stopped").append("\r\n");
		b.append("started_ts=").append(ForegroundService.mStartedAt).append("\r\n");
		b.append("battery=").append(getBatteryLevel()).append("\r\n");
		b.append("imgcount=").append(ImageRecorder.getRecordedImagesCount()).append("\r\n");

		File last = ImageRecorder.getCurrentRecordedImage();
		if (last != null) {
			File rootDir = new File(Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES).getPath());
			String relative = rootDir.toURI().relativize(last.toURI()).getPath();
			b.append("lastimg_name=").append(last.getName()).append("\r\n");
			b.append("lastimg_ts=").append(last.lastModified()).append("\r\n");
			b.append("lastimg_path=").append(relative).append("\r\n");
		} else {
			b.append("lastimg_name=\r\n");
			b.append("lastimg_ts=0\r\n");
			b.append("lastimg_path=\r\n");
		}

		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(restService.getApplicationContext());
		int interval = prefs.getInt("pref_capture_rate", 0);
		String mode = prefs.getString("pref_rec_mode", "");
		b.append("interval_ms=").append(interval).append("\r\n");
		b.append("mode=").append(mode).append("\r\n");

		sendText(ReplyCode.FOUND, b.toString());
		return true;
	}

	private boolean processCurrentRequest(String command, Map<String, String> header) throws IOException {
		if ("img".equals(command)) {
			File lastImg = ImageRecorder.getCurrentRecordedImage();
			if (lastImg != null && lastImg.isFile()) {
				sendFileFromFilesystem(lastImg, header);
				return true;
			}
		}

		String result = null;
		if ("imgcount".equals(command)) {
			result = String.valueOf(ImageRecorder.getRecordedImagesCount());
		} else if ("lastimg".equals(command)) {
			File lastImg = ImageRecorder.getCurrentRecordedImage();
			if (lastImg == null) {
				result = "null";
			} else {
				File rootDir = new File(Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES).getPath());
				String relative = rootDir.toURI().relativize(lastImg.toURI()).getPath();
				result = lastImg.getName() + "\r\n" + lastImg.lastModified() + "\r\n" + relative;
			}
		}

		if (result != null) {
			sendText(ReplyCode.FOUND, result);
			return true;
		}

		return false;
	}

	private void replyResource(int fileId, String contentType) throws IOException {
		try (InputStream in = restService.getApplicationContext().getResources().openRawResource(fileId)) {
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			byte[] tmp = new byte[8192];
			int n;
			while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
			byte[] body = buf.toByteArray();
			Map<String, String> fields = new HashMap<>();
			fields.put("Content-Length", String.valueOf(body.length));
			sendReplyHeader(ReplyCode.FOUND, contentType, fields);
			out.write(body);
		}
	}

	/**
	 * Decode + canonicalize a request path under rootDir. Returns null if outside.
	 */
	private File resolveSafe(File rootDir, String req) throws IOException {
		String decoded;
		try {
			decoded = URLDecoder.decode(req, "UTF-8");
		} catch (IllegalArgumentException e) {
			return null;
		}
		File target = new File(rootDir, decoded);
		String rootCanon = rootDir.getCanonicalPath();
		String targetCanon = target.getCanonicalPath();
		if (!targetCanon.equals(rootCanon) && !targetCanon.startsWith(rootCanon + File.separator)) {
			return null;
		}
		return target;
	}

	private boolean processImageRequest(String req, Map<String, String> header) throws IOException {
		Log.d(TAG, "Request image: " + req);

		File rootDir = new File(Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES).getPath());
		if (!rootDir.isDirectory()) {
			return false;
		}

		// List folder
		if (req.endsWith("/list")) {
			String sub = req.substring(0, req.length() - 5);
			File safe = resolveSafe(rootDir, sub);
			if (safe == null) {
				sendText(ReplyCode.FORBIDDEN, "Forbidden");
				return true;
			}
			ListFolderPlain list = new ListFolderPlain(this, rootDir);
			return list.output(sub);
		}

		// HTML interface
		if (req.endsWith("/listhtml")) {
			String sub = req.substring(0, req.length() - 9);
			File safe = resolveSafe(rootDir, sub);
			if (safe == null) {
				sendText(ReplyCode.FORBIDDEN, "Forbidden");
				return true;
			}
			ListFolderHtml list = new ListFolderHtml(this, rootDir);
			return list.output(sub);
		}

		// Download
		File requested = resolveSafe(rootDir, req);
		if (requested == null) {
			sendText(ReplyCode.FORBIDDEN, "Forbidden");
			return true;
		}
		if (requested.isFile()) {
			sendFileFromFilesystem(requested, header);
			return true;
		}

		return false;
	}

	private static String mimeFor(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
		if (n.endsWith(".png")) return "image/png";
		if (n.endsWith(".mp4")) return "video/mp4";
		return "application/octet-stream";
	}

	/**
	 * Send file with optional Range support (HTTP 206).
	 */
	private void sendFileFromFilesystem(File file, Map<String, String> header) throws IOException {
		long total = file.length();
		String mime = mimeFor(file.getName());

		long start = 0;
		long end = total - 1;
		boolean partial = false;

		String rangeHeader = header == null ? null : header.get("Range");
		if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
			String spec = rangeHeader.substring(6);
			int dash = spec.indexOf('-');
			if (dash >= 0) {
				try {
					String s = spec.substring(0, dash).trim();
					String e = spec.substring(dash + 1).trim();
					if (!s.isEmpty()) start = Long.parseLong(s);
					if (!e.isEmpty()) end = Long.parseLong(e);
					partial = true;
				} catch (NumberFormatException nfe) {
					partial = false;
				}
			}
		}

		if (start < 0) start = 0;
		if (end >= total) end = total - 1;
		if (start > end) {
			partial = false;
			start = 0;
			end = total - 1;
		}

		long length = end - start + 1;

		Map<String, String> fields = new HashMap<>();
		fields.put("Content-Length", String.valueOf(length));
		fields.put("Accept-Ranges", "bytes");
		if (partial) {
			fields.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
		}

		sendReplyHeader(partial ? ReplyCode.PARTIAL_CONTENT : ReplyCode.FOUND, mime, fields);

		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			raf.seek(start);
			byte[] buf = new byte[8192];
			long remaining = length;
			while (remaining > 0) {
				int read = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
				if (read == -1) break;
				out.write(buf, 0, read);
				remaining -= read;
			}
		}
	}

	private boolean processControlRequest(String command) throws IOException {
		String result = null;
		if ("status".equals(command)) {
			result = ForegroundService.mIsRunning ? "running" : "stopped";
		} else if ("start".equals(command)) {
			ServiceHelper helper = new ServiceHelper(restService.getApplicationContext());
			helper.start(false);
			result = "ok";
		} else if ("stop".equals(command)) {
			ServiceHelper helper = new ServiceHelper(restService.getApplicationContext());
			helper.stop();
			result = "ok";
		} else if ("param".equals(command)) {
			StringBuilder b = new StringBuilder();
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(restService.getApplicationContext());
			for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
				b.append(e.getKey()).append('=').append(e.getValue()).append("\r\n");
			}
			result = b.toString();
		}

		if (result != null) {
			sendText(ReplyCode.FOUND, result);
			return true;
		}

		return false;
	}

	/**
	 * DELETE: file or recursive folder. Strict canonical containment.
	 */
	private boolean processDeleteRequest(String url) throws IOException {
		if (!url.startsWith("/1/img/")) {
			return false;
		}

		String req = url.substring(6);
		// Strip trailing slash
		while (req.endsWith("/")) {
			req = req.substring(0, req.length() - 1);
		}

		File rootDir = new File(Environment.getExternalStoragePublicDirectory(DIRECTORY_PICTURES).getPath());
		File target = resolveSafe(rootDir, req);

		if (target == null) {
			sendText(ReplyCode.FORBIDDEN, "Forbidden");
			return true;
		}

		// Refuse to delete the root itself
		if (target.getCanonicalPath().equals(rootDir.getCanonicalPath())) {
			sendText(ReplyCode.FORBIDDEN, "Cannot delete root");
			return true;
		}

		if (!target.exists()) {
			sendText(ReplyCode.NOT_FOUND, "Not found");
			return true;
		}

		boolean ok;
		if (target.isFile()) {
			ok = target.delete();
		} else {
			ok = deleteRecursive(target);
		}

		if (ok) {
			sendText(ReplyCode.FOUND, "deleted");
		} else {
			sendText(ReplyCode.SERVER_ERROR, "delete failed");
		}
		return true;
	}

	private static boolean deleteRecursive(File f) {
		if (f.isDirectory()) {
			File[] children = f.listFiles();
			if (children != null) {
				for (File c : children) {
					if (!deleteRecursive(c)) return false;
				}
			}
		}
		return f.delete();
	}

	/**
	 * Send a text reply with Content-Length + Connection: close.
	 */
	private void sendText(ReplyCode code, String body) throws IOException {
		String b = body;
		if (!b.endsWith("\r\n")) b += "\r\n";
		byte[] bytes = b.getBytes(StandardCharsets.UTF_8);
		Map<String, String> fields = new HashMap<>();
		fields.put("Content-Length", String.valueOf(bytes.length));
		sendReplyHeader(code, "text/plain", fields);
		out.write(bytes);
	}

	public void sendReplyHeader(ReplyCode code, String contentType, Map<String, String> additionalFields) throws IOException {
		sendLine("HTTP/1.1 " + code.code + " " + code.text);
		sendLine("Date: " + HTTP_DATE_FORMAT.format(System.currentTimeMillis()));
		sendLine("Server: TimeLapseCam/" + BuildConfig.VERSION_NAME + " (Android)");
		sendLine("Content-Type: " + contentType);
		sendLine("Connection: close");
		for (Map.Entry<String, String> e : additionalFields.entrySet()) {
			sendLine(e.getKey() + ": " + e.getValue());
		}
		// Empty line / end of header
		sendLine("");
	}

	@Override
	public void sendReplyHeader(ReplyCode code, String contentType) throws IOException {
		sendReplyHeader(code, contentType, Collections.emptyMap());
	}

	@Override
	public void sendLine(String line) throws IOException {
		out.write(line.getBytes(StandardCharsets.ISO_8859_1));
		out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
	}

	@Override
	public synchronized void close() {
		if (socket == null) {
			return;
		}
		try {
			socket.close();
		} catch (Exception e) {
			Log.w(TAG, "Error closing socket", e);
		}
	}
}
