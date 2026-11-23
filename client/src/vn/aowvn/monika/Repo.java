package vn.aowvn.monika;

import java.io.IOException;
import java.util.Vector;

/**
 * Nguồn dữ liệu: gọi API Cloudflare qua {@link Net}, rơi về cache RMS, hoặc danh mục mẫu
 * trong JAR khi chưa có máy chủ. Một luồng nền xử lý lần lượt các yêu cầu
 * (máy cũ thường chỉ mở được ít kết nối cùng lúc).
 */
final class Repo implements Runnable {
    /** Kết quả trả về luồng nền; result là Home, Page hoặc byte[] (ảnh), err là mã lỗi hoặc null. */
    interface Callback {
        void done(Object tag, Object result, String err);
    }

    static final int K_HOME = 0;
    static final int K_LIST = 1;
    static final int K_IMAGE = 2;

    private static final class Req {
        int kind;
        String key;
        int page;
        Object tag;
        Object group;
        Callback cb;
        boolean cancelled;
        boolean urgent;
    }

    private final Store store;
    private final Vector queue = new Vector();
    private String base = "";
    private String suffix = "";
    private String ua = "Monika";
    private Catalog embedded;
    private Thread worker;

    /** True nếu lần gọi gần nhất phải dùng cache hoặc bản mẫu vì mạng lỗi. */
    boolean stale;
    String source = "";

    Repo(Store store) {
        this.store = store;
    }

    void configure(String baseUrl, String netSuffix, String userAgent) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.length() > 0 && !b.startsWith("http://") && !b.startsWith("https://")) {
            b = "http://" + b;
        }
        base = b;
        suffix = netSuffix == null ? "" : netSuffix;
        ua = userAgent;
    }

    boolean online() {
        return base.length() > 0;
    }

    String base() {
        return base;
    }

    /** Địa chỉ tuyệt đối cho một đường dẫn của máy chủ. */
    String url(String path) {
        if (path.startsWith("http")) {
            return path;
        }
        return base + path;
    }

    Catalog embedded() {
        if (embedded == null) {
            try {
                embedded = Catalog.loadEmbedded();
            } catch (IOException e) {
                embedded = new Catalog();
            }
        }
        return embedded;
    }

    // ---------- yêu cầu ----------

    void home(Object tag, Callback cb) {
        enqueue(K_HOME, "", 0, tag, null, cb);
    }

    /** key: app, game, new, hot, dl, c&lt;id&gt;, i&lt;id&gt; hoặc "s:&lt;từ khóa&gt;". */
    void list(String key, int page, Object tag, Callback cb) {
        enqueue(K_LIST, key, page, tag, null, cb);
    }

    void image(String path, Object tag, Callback cb) {
        enqueue(K_IMAGE, path, 0, tag, null, cb);
    }

    /** Ảnh thuộc một nhóm (vd. icon của một danh sách): hủy cả nhóm bằng {@link #cancel}. */
    void image(String path, Object tag, Object group, Callback cb) {
        enqueue(K_IMAGE, path, 0, tag, group, cb);
    }

    /** Như {@link #image(String, Object, Object, Callback)} nhưng đứng trước các ảnh thường đang chờ (ảnh đang hiện trên màn). */
    void imageFirst(String path, Object tag, Object group, Callback cb) {
        enqueue(K_IMAGE, path, 0, tag, group, cb, true);
    }

    void cancel(Object tag) {
        synchronized (queue) {
            for (int i = 0; i < queue.size(); i++) {
                Req r = (Req) queue.elementAt(i);
                if (r.tag == tag || r.group == tag) {
                    r.cancelled = true;
                }
            }
        }
    }

    private void enqueue(int kind, String key, int page, Object tag, Object group, Callback cb) {
        enqueue(kind, key, page, tag, group, cb, false);
    }

    private void enqueue(int kind, String key, int page, Object tag, Object group, Callback cb, boolean urgent) {
        Req r = new Req();
        r.urgent = urgent;
        r.kind = kind;
        r.key = key;
        r.page = page;
        r.tag = tag;
        r.group = group;
        r.cb = cb;
        synchronized (queue) {
            if (kind == K_IMAGE && !urgent) {
                queue.addElement(r);
            } else if (kind == K_IMAGE) {
                int at = queue.size();
                for (int i = 0; i < queue.size(); i++) {
                    Req q = (Req) queue.elementAt(i);
                    if (q.kind == K_IMAGE && !q.urgent) {
                        at = i;
                        break;
                    }
                }
                queue.insertElementAt(r, at);
            } else {
                // yêu cầu của người dùng đi trước ảnh đang chờ (icon, banner)
                int at = queue.size();
                for (int i = 0; i < queue.size(); i++) {
                    if (((Req) queue.elementAt(i)).kind == K_IMAGE) {
                        at = i;
                        break;
                    }
                }
                queue.insertElementAt(r, at);
            }
            if (worker == null) {
                worker = new Thread(this);
                worker.start();
            }
            queue.notify();
        }
    }

    public void run() {
        while (true) {
            Req r;
            synchronized (queue) {
                while (queue.size() == 0) {
                    try {
                        queue.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                r = (Req) queue.elementAt(0);
                queue.removeElementAt(0);
            }
            if (r.cancelled) {
                continue;
            }
            Object result = null;
            String err = null;
            try {
                if (r.kind == K_HOME) {
                    result = loadHome();
                } else if (r.kind == K_LIST) {
                    result = loadList(r.key, r.page);
                } else {
                    result = Net.get(url(r.key), suffix, ua, 48 * 1024);
                }
            } catch (IOException e) {
                err = e.getMessage() == null ? "E12" : e.getMessage();
            } catch (OutOfMemoryError e) {
                err = "E18";
            } catch (RuntimeException e) {
                err = "E19";
            }
            if (!r.cancelled) {
                r.cb.done(r.tag, result, err);
            }
        }
    }

    // ---------- nạp ----------

    private Home loadHome() throws IOException {
        if (!online()) {
            stale = false;
            source = "danh mục mẫu";
            return Home.fromCatalog(embedded());
        }
        try {
            byte[] b = Net.get(base + "/v1/home.bin", suffix, ua, 16 * 1024);
            Home h = Home.parse(b);
            store.cachePut("home", b);
            stale = false;
            source = "mạng";
            return h;
        } catch (IOException e) {
            byte[] c = store.cacheGet("home");
            if (c != null) {
                stale = true;
                source = "cache";
                return Home.parse(c);
            }
            throw e;
        }
    }

    private Page loadList(String key, int page) throws IOException {
        if (!online()) {
            return Page.local(local(key), page);
        }
        String path = key.startsWith("s:") ? "/v1/s/" + enc(key.substring(2)) + "/" + page + ".bin"
            : "/v1/l/" + key + "/" + page + ".bin";
        String ck = page == 0 && !key.startsWith("s:") ? key : null;
        try {
            byte[] b = Net.get(base + path, suffix, ua, 64 * 1024);
            Page p = Page.parse(b);
            if (ck != null) {
                store.cachePut("l:" + ck, b);
            }
            stale = false;
            return p;
        } catch (IOException e) {
            if (ck != null) {
                byte[] c = store.cacheGet("l:" + ck);
                if (c != null) {
                    stale = true;
                    return Page.parse(c);
                }
            }
            throw e;
        }
    }

    /** Lọc danh mục mẫu theo khóa danh sách. */
    private Vector local(String key) {
        Catalog c = embedded();
        Vector v = new Vector();
        if (key.startsWith("s:")) {
            return c.search(key.substring(2));
        }
        for (int i = 0; i < c.size(); i++) {
            AppInfo a = c.get(i);
            boolean ok;
            if ("app".equals(key)) {
                ok = a.cat != AppInfo.CAT_GAME;
            } else if ("game".equals(key)) {
                ok = a.cat == AppInfo.CAT_GAME;
            } else if ("new".equals(key)) {
                ok = a.has(AppInfo.F_NEW) || a.has(AppInfo.F_UPDATE);
            } else if ("hot".equals(key)) {
                ok = a.has(AppInfo.F_HOT) || a.id % 2 == 0;
            } else if ("dl".equals(key)) {
                ok = true;
            } else if (key.length() > 1 && key.charAt(0) == 'c') {
                ok = a.cat == num(key.substring(1));
            } else if (key.length() > 1 && key.charAt(0) == 'i') {
                ok = a.id == num(key.substring(1));
            } else {
                ok = false;
            }
            if (ok) {
                v.addElement(a);
            }
        }
        return v;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Mã hóa từ khóa tìm kiếm đã bỏ dấu: chỉ giữ a-z, 0-9, khoảng trắng thành %20. */
    static String enc(String q) {
        StringBuffer sb = new StringBuffer();
        boolean space = true;
        for (int i = 0; i < q.length() && sb.length() < 60; i++) {
            char c = q.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                space = false;
            } else if (!space) {
                sb.append("%20");
                space = true;
            }
        }
        String r = sb.toString();
        return r.endsWith("%20") ? r.substring(0, r.length() - 3) : r;
    }
}
