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
    /** False sau khi máy chủ báo không có endpoint v2: dùng /v1/l/ và /v1/home.bin. */
    private boolean v2 = true;

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
        v2 = true;
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

    /** key: app, game, new, hot, top1, top7, dl, c&lt;id&gt;[.sắp xếp][.r&lt;W&gt;x&lt;H&gt;], i&lt;id&gt; hoặc "s:&lt;từ khóa&gt;". */
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

    /** Lỗi do máy chủ không có/không hiểu endpoint v2 (404, mã khác 200, sai định dạng): rơi về v1. */
    private static boolean notV2(IOException e) {
        String m = e.getMessage();
        return "E13".equals(m) || "E14".equals(m) || "E15".equals(m);
    }

    private Home loadHome() throws IOException {
        if (!online()) {
            stale = false;
            source = "danh mục mẫu";
            return Home.fromCatalog(embedded());
        }
        v2 = true;
        IOException err = null;
        try {
            byte[] b = Net.get(base + "/v1/home2.bin", suffix, ua, 16 * 1024);
            Home h = Home.parse(b);
            if (!h.v2) {
                throw new IOException("E14");
            }
            store.cachePut("home2", b);
            stale = false;
            source = "mạng (v2)";
            return h;
        } catch (IOException e) {
            if (notV2(e)) {
                v2 = false;
            } else {
                err = e;
            }
        }
        if (!v2) {
            try {
                byte[] b = Net.get(base + "/v1/home.bin", suffix, ua, 16 * 1024);
                Home h = Home.parse(b);
                store.cachePut("home", b);
                stale = false;
                source = "mạng (v1)";
                return h;
            } catch (IOException e) {
                err = e;
            }
        }
        for (int i = 0; i < 2; i++) {
            byte[] c = store.cacheGet(i == 0 ? "home2" : "home");
            if (c != null) {
                try {
                    Home h = Home.parse(c);
                    stale = true;
                    source = "cache";
                    return h;
                } catch (IOException ignore) {
                    // cache hỏng: thử bản kia
                }
            }
        }
        throw err;
    }

    /** Khóa v1 tương ứng: bỏ mod (.new, .r240x320...), xếp hạng theo ngày/tuần dùng hot. */
    private static String v1Key(String key) {
        int dot = key.indexOf('.');
        String base = dot < 0 ? key : key.substring(0, dot);
        if ("top1".equals(base) || "top7".equals(base)) {
            return "hot";
        }
        return base;
    }

    private Page loadList(String key, int page) throws IOException {
        if (!online()) {
            return Page.local(local(key), page);
        }
        if (key.startsWith("s:")) {
            // tìm kiếm: /v1/s/ (định dạng v1, dl = 0), không cache
            Page p = Page.parse(Net.get(base + "/v1/s/" + enc(key.substring(2)) + "/" + page + ".bin", suffix, ua,
                64 * 1024));
            stale = false;
            return p;
        }
        String ck = page == 0 ? key : null;
        try {
            if (v2) {
                try {
                    Page p = getPage2(key, page, ck);
                    int dot = key.lastIndexOf('.');
                    if (page == 0 && p.total == 0 && dot > 0 && key.length() > dot + 2 && key.charAt(dot + 1) == 'r'
                        && isSize(key.substring(dot + 2))) {
                        // bộ lọc theo màn hình không còn gì: tải lại không lọc
                        p = getPage2(key.substring(0, dot), 0, key.substring(0, dot));
                        p.unfiltered = true;
                    }
                    stale = false;
                    return p;
                } catch (IOException e) {
                    if (!notV2(e)) {
                        throw e;
                    }
                }
            }
            byte[] b = Net.get(base + "/v1/l/" + v1Key(key) + "/" + page + ".bin", suffix, ua, 64 * 1024);
            Page p = Page.parse(b);
            if (ck != null) {
                store.cachePut("l:" + ck, b);
            }
            stale = false;
            return p;
        } catch (IOException e) {
            if (ck != null) {
                // mất mạng: dùng cache của đúng khóa (v2 trước, rồi v1)
                for (int i = 0; i < 2; i++) {
                    byte[] c = store.cacheGet((i == 0 ? "l2:" : "l:") + ck);
                    if (c != null) {
                        try {
                            Page p = Page.parse(c);
                            stale = true;
                            return p;
                        } catch (IOException ignore) {
                            // cache hỏng: thử bản kia
                        }
                    }
                }
            }
            throw e;
        }
    }

    private Page getPage2(String key, int page, String ck) throws IOException {
        byte[] b = Net.get(base + "/v1/l2/" + key + "/" + page + ".bin", suffix, ua, 64 * 1024);
        Page p = Page.parse(b);
        if (!p.v2) {
            throw new IOException("E14");
        }
        if (page == 0 && ck != null) {
            store.cachePut("l2:" + ck, b);
        }
        return p;
    }

    /** True nếu s có dạng WxH. */
    private static boolean isSize(String s) {
        int x = s.indexOf('x');
        return x > 0 && num(s.substring(0, x)) > 0 && num(s.substring(x + 1)) > 0;
    }

    /** Lọc danh mục mẫu theo khóa danh sách. */
    private Vector local(String key) {
        Catalog c = embedded();
        Vector v = new Vector();
        if (key.startsWith("s:")) {
            return c.search(key.substring(2));
        }
        int dot = key.indexOf('.');
        if (dot > 0) {
            key = key.substring(0, dot);
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
            } else if ("dl".equals(key) || "top1".equals(key) || "top7".equals(key)) {
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
