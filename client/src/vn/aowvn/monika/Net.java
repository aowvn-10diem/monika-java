package vn.aowvn.monika;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;

/**
 * Lớp mạng duy nhất: hậu tố kết nối (BlackBerry), thử lại 2 lần, mã lỗi ngắn.
 * Chỉ HTTP cổng 80. Phải gọi trên luồng riêng, không gọi trên luồng giao diện.
 *
 * Mã lỗi: E12 không vào được mạng, E20 máy chặn quyền mạng (SecurityException), E13 máy chủ trả mã khác 200 (404 không thử lại), E17 file quá lớn.
 */
final class Net {
    static final int TRIES = 3;

    /** Chi tiết lỗi mạng gần nhất (loại ngoại lệ, thông điệp, đích), hiện ở Thông tin máy để chẩn đoán. */
    static String lastDiag = "";

    private Net() {
    }

    static String userAgent(int w, int h) {
        String p = System.getProperty("microedition.platform");
        if (p == null) {
            p = "unknown";
        }
        return "Monika/" + MonikaMidlet.VERSION + " (" + p + "; " + w + "x" + h + "; "
            + Runtime.getRuntime().totalMemory() / 1024 + "K)";
    }

    static byte[] get(String url, String suffix, String ua, int maxBytes) throws IOException {
        IOException last = new IOException("E12");
        for (int attempt = 0; attempt < TRIES; attempt++) {
            HttpConnection c = null;
            InputStream in = null;
            try {
                String u = (suffix == null || suffix.length() == 0) ? url : url + suffix;
                c = (HttpConnection) Connector.open(u);
                c.setRequestMethod(HttpConnection.GET);
                c.setRequestProperty("User-Agent", ua);
                int rc = c.getResponseCode();
                if (rc == HttpConnection.HTTP_NOT_FOUND) {
                    throw new IOException("E13N");
                }
                if (rc != HttpConnection.HTTP_OK) {
                    throw new IOException("E13");
                }
                in = c.openInputStream();
                return readAll(in, maxBytes);
            } catch (IOException e) {
                String m = e.getMessage();
                if ("E17".equals(m)) {
                    throw e;
                }
                if ("E13N".equals(m)) {
                    // 404: thử lại vô ích (máy chủ chưa có endpoint này), báo E13 ngay
                    throw new IOException("E13");
                }
                diag(e, url);
                last = "E13".equals(m) ? e : new IOException("E12");
            } catch (SecurityException e) {
                diag(e, url);
                throw new IOException("E20");
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignore) {
                        // bỏ qua
                    }
                }
                if (c != null) {
                    try {
                        c.close();
                    } catch (IOException ignore) {
                        // bỏ qua
                    }
                }
            }
            try {
                Thread.sleep(400L * (attempt + 1));
            } catch (InterruptedException ie) {
                break;
            }
        }
        throw last;
    }

    private static void diag(Throwable e, String url) {
        String n = e.getClass().getName();
        int dot = n.lastIndexOf('.');
        String m = e.getMessage();
        if (m != null && m.length() > 60) {
            m = m.substring(0, 60);
        }
        int slash = url.indexOf('/', 8);
        lastDiag = (dot >= 0 ? n.substring(dot + 1) : n) + (m == null ? "" : ": " + m) + " @"
            + (slash > 0 ? url.substring(0, slash) : url);
    }

    static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int total = 0;
        int n;
        while ((n = in.read(buf, 0, buf.length)) > 0) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("E17");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
