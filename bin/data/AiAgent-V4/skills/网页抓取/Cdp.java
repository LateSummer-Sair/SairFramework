import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.Base64;
import java.util.Random;

import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * 极简 CDP（Chrome DevTools Protocol）客户端 —— 给"Web 容器"用。
 *
 * <p>为什么自己写：JDK 8 <b>没有</b> {@code java.net.http.WebSocket}（Java 11 才有），而项目纪律是零依赖。
 * 所以这里手写 RFC 6455 客户端握手 + 文本帧收发（客户端帧必须掩码），够 CDP 用。</p>
 *
 * <p>为什么要 CDP 而不是每次重新拉起浏览器：<b>状态要跨调用保持</b> —— 登录 cookie、页面 JS 变量、滚动位置。
 * 一次性 {@code --screenshot} 每次都是全新会话，没法"先点一下再截"。</p>
 */
public class Cdp {

    static final Random RND = new Random();

    // ---------------------------------------------------------------- HTTP（调试口）

    /** 对调试口发一个 GET，返回响应体（调试口只认本机回环，不走 NetGuard）。 */
    static String http(String url, int timeoutMs) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 这个端口上有没有活的调试口？返回版本串，没有返回空串。 */
    static String version(int port) {
        String body = http("http://127.0.0.1:" + port + "/json/version", 1500);
        JsonObject o = J.obj(body);
        return o == null ? "" : J.s(o, "Browser", "");
    }

    static String httpPut(String url, int timeoutMs) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("PUT");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 取第一个 page target 的 webSocketDebuggerUrl（没有就新建一个）。 */
    static String firstPageWs(int port) {
        String body = http("http://127.0.0.1:" + port + "/json/list", 3000);
        try {
            com.google.gson.JsonArray arr = J.arr(body);
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject t = J.obj(arr.get(i).toString());
                    if (t == null) continue;
                    if (!"page".equals(J.s(t, "type", ""))) continue;
                    String ws = J.s(t, "webSocketDebuggerUrl", "");
                    if (Str.has(ws)) return ws;
                }
            }
        } catch (Throwable ignored) { }
        String created = httpPut("http://127.0.0.1:" + port + "/json/new?about:blank", 3000);
        JsonObject o = J.obj(created);
        return o == null ? "" : J.s(o, "webSocketDebuggerUrl", "");
    }

    // ---------------------------------------------------------------- WebSocket（RFC 6455 客户端）

    /** 最小 WS 客户端：握手 + 掩码文本帧 + 读完整消息（拼 continuation、应 ping）。 */
    static final class Ws {
        Socket sock;
        InputStream in;
        OutputStream out;

        static Ws open(String wsUrl, int timeoutMs) throws IOException {
            URI u = URI.create(wsUrl);
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : 80;
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
            if (u.getRawQuery() != null) path = path + "?" + u.getRawQuery();
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            s.setTcpNoDelay(true);
            byte[] r16 = new byte[16];
            RND.nextBytes(r16);
            String key = Base64.getEncoder().encodeToString(r16);
            String req = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(req.getBytes("UTF-8"));
            o.flush();
            InputStream i = s.getInputStream();
            String head = readHead(i);
            if (head.indexOf(" 101") < 0) throw new IOException("ws upgrade refused: " + firstLine(head));
            Ws w = new Ws();
            w.sock = s;
            w.in = i;
            w.out = o;
            return w;
        }

        void close() {
            try { sock.close(); } catch (Throwable ignored) { }
        }

        void writeFrame(int op, byte[] payload) throws IOException {
            ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
            f.write(0x80 | op);
            int len = payload.length;
            if (len < 126) {
                f.write(0x80 | len);
            } else if (len < 65536) {
                f.write(0x80 | 126);
                f.write((len >> 8) & 0xFF);
                f.write(len & 0xFF);
            } else {
                f.write(0x80 | 127);
                for (int k = 7; k >= 0; k--) f.write((int) (((long) len) >> (8 * k)) & 0xFF);
            }
            byte[] mask = new byte[4];
            RND.nextBytes(mask);
            f.write(mask, 0, 4);
            for (int k = 0; k < len; k++) f.write(payload[k] ^ mask[k % 4]);
            out.write(f.toByteArray());
            out.flush();
        }

        void sendText(String text) throws IOException {
            writeFrame(0x1, text.getBytes("UTF-8"));
        }

        int readByte() throws IOException {
            int b = in.read();
            if (b < 0) throw new EOFException("ws closed");
            return b;
        }

        void readFully(byte[] dst) throws IOException {
            int off = 0;
            while (off < dst.length) {
                int n = in.read(dst, off, dst.length - off);
                if (n <= 0) throw new EOFException("ws closed");
                off += n;
            }
        }

        /** 读一条完整文本消息（自动拼 continuation、跳过 ping/pong、遇 close 抛异常）。 */
        String readText() throws IOException {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            while (true) {
                int b0 = readByte();
                int op = b0 & 0x0F;
                int b1 = readByte();
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((long) readByte() << 8) | readByte();
                } else if (len == 127) {
                    len = 0;
                    for (int k = 0; k < 8; k++) len = (len << 8) | readByte();
                }
                if (len > 32L * 1024L * 1024L) throw new IOException("ws frame too big: " + len);
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload);
                if (masked) for (int k = 0; k < payload.length; k++) payload[k] ^= mask[k % 4];
                if (op == 0x8) throw new EOFException("ws close frame");
                if (op == 0x9) {
                    writeFrame(0xA, payload);
                    continue;
                }
                if (op == 0xA) continue;
                bo.write(payload);
                if ((b0 & 0x80) != 0) break;
            }
            return new String(bo.toByteArray(), "UTF-8");
        }
    }

    static String firstLine(String head) {
        int i = head.indexOf("\r\n");
        return i > 0 ? head.substring(0, i) : Str.cut(head, 120);
    }

    static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int state = 0;
        while (bo.size() < 16384) {
            int b = in.read();
            if (b < 0) throw new EOFException("ws closed during handshake");
            bo.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = 0;
        }
        return new String(bo.toByteArray(), "UTF-8");
    }

    // ---------------------------------------------------------------- CDP 调用

    /** 发一条 CDP 命令并等它的响应（同 id）；期间收到的事件直接丢掉。 */
    static JsonObject call(Ws w, int id, String method, JsonObject params, int timeoutMs) throws IOException {
        JsonObject req = new JsonObject();
        req.addProperty("id", id);
        req.addProperty("method", method);
        req.add("params", params == null ? new JsonObject() : params);
        w.sendText(req.toString());
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String msg = w.readText();
            JsonObject o = J.obj(msg);
            if (o == null) continue;
            if (o.has("id") && !o.get("id").isJsonNull() && o.get("id").getAsInt() == id) return o;
        }
        throw new IOException("cdp timeout waiting id=" + id + " (" + method + ")");
    }

    /** 把 CDP 响应里的 error 翻成一句人话。 */
    static String err(JsonObject res) {
        if (res == null) return "";
        JsonObject e = res.has("error") ? J.obj(res.get("error").toString()) : null;
        if (e == null) return "";
        return J.s(e, "message", "unknown error");
    }

    /** 从 Runtime.evaluate 的响应里取回值。 */
    static String evalResult(JsonObject res) {
        if (res == null) return "";
        JsonObject result = res.has("result") ? J.obj(res.get("result").toString()) : null;
        if (result == null) return "";
        JsonObject ex = result.has("exceptionDetails") ? J.obj(result.get("exceptionDetails").toString()) : null;
        if (ex != null) return "[exception] " + J.s(ex, "text", "");
        JsonObject remote = result.has("result") ? J.obj(result.get("result").toString()) : null;
        if (remote == null) return "";
        String type = J.s(remote, "type", "");
        if (remote.has("value")) {
            com.google.gson.JsonElement v = remote.get("value");
            if (v.isJsonNull()) return "null";
            if (v.isJsonPrimitive()) return v.getAsJsonPrimitive().toString();
            return v.toString();
        }
        String desc = J.s(remote, "description", "");
        if (Str.has(desc)) return desc;
        return type.isEmpty() ? "" : ("(" + type + ")");
    }

    /** 把 base64 的 PNG 写进文件。 */
    static boolean writeB64(String b64, File dst) {
        try {
            byte[] raw = Base64.getDecoder().decode(b64);
            FileOutputStream fo = new FileOutputStream(dst);
            try { fo.write(raw); } finally { fo.close(); }
            return dst.isFile() && dst.length() > 0L;
        } catch (Throwable t) {
            return false;
        }
    }
}
