package vn.aowvn.monika;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.io.ConnectionNotFoundException;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/**
 * Monika: cửa hàng ứng dụng Java S60 by AOW.VN, dùng chung giao diện và dữ liệu với web
 * (m.justmonika.top) qua API Cloudflare.
 * MIDP 2.0 / CLDC 1.1. Màn chính, danh sách và chi tiết vẽ bằng Canvas theo giao diện web;
 * tìm kiếm, cài đặt, thông tin dùng LCDUI cấp cao để máy tự lo bàn phím.
 *
 * Thuộc tính tùy chọn (JAD hoặc MANIFEST của JAR):
 *   Monika-Base-URL   gốc máy chủ API, ví dụ http://api.justmonika.top (rỗng = chỉ dùng danh mục mẫu)
 *   Monika-Net-Suffix hậu tố kết nối, ví dụ ;deviceside=true cho BlackBerry
 *   Monika-Fullscreen 0 = chế độ thường (giữ thanh trạng thái); mặc định toàn màn hình, tự vẽ phím mềm
 */
public final class MonikaMidlet extends MIDlet implements CommandListener, Repo.Callback {
    static final String VERSION = "0.7.1";

    private static final int LIST_APPS = 0;
    private static final int LIST_GAMES = 1;
    private static final int LIST_CATS = 2;
    private static final int LIST_UPDATES = 3;
    private static final int LIST_FAVS = 4;
    /** Mã dòng "Đề xuất hôm nay" trong menu Danh mục (không trùng id nhóm 1..5). */
    private static final int PICK_ID = 99;
    /** Mục ảo ở nhóm Game: lọc theo hai cỡ màn hình tiêu biểu (khóa .x<W>x<H> của Worker). */
    private static final int SCR_240_ID = 97;
    private static final int SCR_320_ID = 96;

    private static final int MAX_PROMOS = 3;

    /** Tab xếp hạng: Mới / Hot / Hôm nay (top1) / Tuần (top7) / Tải nhiều (dl); nhãn ngắn cho màn hẹp. */
    private static final String[] RANK_LABELS = {"Mới", "Hot", "Hôm nay", "Tuần", "Tải nhiều"};
    private static final String[] RANK_SHORT = {"Mới", "Hot", "Ngày", "Tuần", "Tải"};
    private static final String[] RANK_SORTS = {"new", "hot", "top1", "top7", "dl"};

    /** Cỡ màn hình phổ biến (rộng, cao): đưa cỡ canvas ở chế độ không toàn màn hình về cỡ màn hình thật. */
    private static final int[][] SCREENS = {
        {128, 128}, {128, 160}, {176, 208}, {176, 220}, {240, 240}, {240, 320}, {240, 400}, {320, 240},
        {352, 416}, {360, 640}, {640, 360}, {480, 640}, {480, 800}, {800, 480}
    };

    private Display display;
    private final Store store = new Store();
    private final Repo repo = new Repo(store);
    private HomeCanvas home;
    private Home hdata = new Home();
    private boolean started;

    private String jadBase = "";
    private String netSuffix = "";
    private boolean jadFullscreen;
    private String ua = "Monika";

    private boolean homeAlerted;
    private String updateUrl = "";
    private String updateVer = "";
    private Image[] promoImgs = new Image[0];
    private String[] promoLinksAll = new String[0];
    private int promoPending;

    private final Vector stack = new Vector();

    // màn hình tự vẽ đang mở (nhận diện trong panelAction)
    private static final int P_OPTIONS = 1;
    private static final int P_SEARCH = 2;
    private static final int P_SETTINGS = 3;
    private static final int P_INFO = 4;
    private static final int P_ABOUT = 5;
    private static final int P_ALERT = 6;
    private static final int P_EDIT = 7;
    private static final int P_NET = 8;
    private static final int P_NETHELP = 9;
    private static final int P_FB = 10;

    /** Ô nhập gốc của máy, chỉ dùng khi chạm vào ô nhập (máy cảm ứng, bàn phím ảo). */
    private TextBox nbox;
    private Command cNbOk;
    private Panel nowner;
    /** Hàng đang sửa trong màn Cài đặt. */
    private Panel editFor;
    private int editRow;

    /** Tag yêu cầu danh sách: gắn với canvas và trang đang chờ. */
    private static final class ListReq {
        ListCanvas lc;
    }

    /** Tag yêu cầu ảnh banner. */
    private static final class ImgReq {
        int slot;
        int gen;
    }

    /** Tag yêu cầu icon: mục k của danh sách lc. */
    private static final class IconReq {
        ListCanvas lc;
        int k;
        int id;
    }

    /** Tag yêu cầu ảnh minh họa: mục k của danh sách lc, gen để bỏ kết quả của dữ liệu cũ. */
    private static final class ShotReq {
        ListCanvas lc;
        int k;
        int gen;
    }

    private int promoGen;
    private static final int ICON_CACHE = 40;
    private final Hashtable iconCache = new Hashtable();
    private final Vector iconOrder = new Vector();

    // ---------- vòng đời MIDlet ----------

    protected void startApp() throws MIDletStateChangeException {
        if (started) {
            return;
        }
        started = true;
        display = Display.getDisplay(this);
        jadBase = prop("Monika-Base-URL");
        netSuffix = prop("Monika-Net-Suffix");
        jadFullscreen = !"0".equals(prop("Monika-Fullscreen"));
        if (!store.load()) {
            Vi.mode = Vi.MODE_ACCENT;
            store.save();
        }
        BaseCanvas.numNav = store.numNav;
        begin();
        if (donateDue(store.dlCount)) {
            // đã tải đủ 2 game từ lần chạy trước (máy buộc thoát app khi cài): nhắc ủng hộ ngay khi mở lại
            showDonate();
        }
    }

    protected void pauseApp() {
        // không giữ tài nguyên nặng khi tạm dừng
    }

    protected void destroyApp(boolean unconditional) {
        store.save();
    }

    private String prop(String key) {
        String v = getAppProperty(key);
        return v == null ? "" : v.trim();
    }

    private void exit() {
        destroyApp(true);
        notifyDestroyed();
    }

    private boolean isFull() {
        return store.fullscreen == 1 || (store.fullscreen == 0 && jadFullscreen);
    }

    private void configureRepo() {
        repo.configure(store.server.length() > 0 ? store.server : jadBase, netSuffix, ua);
    }

    // ---------- điều hướng ----------

    private void show(Displayable d) {
        Displayable c = display.getCurrent();
        if (c != null && c != d && c != home) {
            stack.addElement(c);
        } else if (c == home && c != d) {
            stack.removeAllElements();
        }
        display.setCurrent(d);
    }

    private void leave(Displayable d) {
        if (d instanceof ListCanvas) {
            repo.cancel(((ListCanvas) d).pending);
        }
    }

    private void back() {
        leave(display.getCurrent());
        int n = stack.size();
        if (n > 0) {
            Displayable d = (Displayable) stack.elementAt(n - 1);
            stack.removeElementAt(n - 1);
            display.setCurrent(d);
        } else {
            showHome();
        }
    }

    private void showHome() {
        leave(display.getCurrent());
        stack.removeAllElements();
        if (home == null) {
            home = newHome();
        }
        home.setBadge(hdata.newCount);
        display.setCurrent(home);
    }

    private HomeCanvas newHome() {
        HomeCanvas h = new HomeCanvas(this, isFull());
        h.setCommandListener(this);
        ua = Net.userAgent(h.getWidth(), h.getHeight());
        configureRepo();
        if (promoImgs.length > 0) {
            h.setPromos(promoImgs, promoLinksAll);
        }
        return h;
    }

    /** Thông báo tự vẽ; bấm OK (hoặc bất kỳ phím mềm) quay về next, mặc định trang chủ. */
    private void alert(String title, String msg, Displayable next) {
        Panel p = newPanel(Panel.TEXT, P_ALERT, title, "OK", null);
        p.addPara(Vi.tr(msg));
        p.next = next == null ? (Displayable) home : next;
        display.setCurrent(p);
    }

    private Panel newPanel(int kind, int id, String title, String left, String right) {
        Panel p = new Panel(this, kind, id, Vi.tr(title), left == null ? null : Vi.tr(left),
            right == null ? null : Vi.tr(right), isFull());
        p.setCommandListener(this);
        return p;
    }

    // ---------- khởi động và trang chủ ----------

    /** Hiện trang chủ ngay (không chờ mạng), rồi nạp dữ liệu nền. */
    private void begin() {
        home = newHome();
        if (!repo.online()) {
            hdata = Home.fromCatalog(repo.embedded());
        }
        showHome();
        if (!store.tipDone) {
            home.setTip(new String[] {
                Vi.tr("Nhấn * để tìm kiếm."),
                Vi.tr("Phím 2 4 6 8 di chuyển, 5 chọn."),
                Vi.tr("QWERTY: U là *, T F H B, G chọn.")});
            store.tipDone = true;
            store.save();
        }
        loadHome();
    }

    private void loadHome() {
        repo.home(this, this);
    }

    /** Kết quả từ luồng nền của {@link Repo}. */
    public void done(Object tag, Object result, String err) {
        if (tag == this) {
            onHome(result, err);
        } else if (tag instanceof ListReq) {
            ListReq r = (ListReq) tag;
            ListCanvas lc = r.lc;
            if (lc.pending != r) {
                return;
            }
            if (err != null) {
                lc.setError(err);
            } else {
                fillList(lc, (Page) result);
            }
        } else if (tag instanceof ImgReq) {
            onBanner((ImgReq) tag, result);
        } else if (tag instanceof IconReq) {
            onIcon((IconReq) tag, result);
        } else if (tag instanceof ShotReq) {
            onShot((ShotReq) tag, result);
        }
    }

    /**
     * Lưới ảnh: tải icon (cache hoặc mạng) và ảnh minh họa tw x th của mục k. urgent = mục đang hiện trên màn,
     * đứng trước hàng đợi ảnh. Gọi từ ListCanvas khi cuộn tới.
     */
    void listLoad(ListCanvas lc, int k, int gen, int tw, int th, boolean urgent, boolean icon, boolean shot) {
        if (!repo.online() || k < 0 || k >= lc.items.size()) {
            return;
        }
        AppInfo a = (AppInfo) lc.items.elementAt(k);
        if (shot) {
            ShotReq r = new ShotReq();
            r.lc = lc;
            r.k = k;
            r.gen = gen;
            String p = "/v1/sc/" + a.id + "-" + tw + "x" + th + ".jpg";
            if (urgent) {
                repo.imageFirst(p, r, lc, this);
            } else {
                repo.image(p, r, lc, this);
            }
        }
        if (icon) {
            Image im = (Image) iconCache.get(new Integer(a.id));
            if (im != null) {
                lc.setIcon(k, im);
            } else {
                IconReq r = new IconReq();
                r.lc = lc;
                r.k = k;
                r.id = a.id;
                String p = "/v1/ic/" + a.id + "-32.png";
                if (urgent) {
                    repo.imageFirst(p, r, lc, this);
                } else {
                    repo.image(p, r, lc, this);
                }
            }
        }
    }

    private void onShot(ShotReq r, Object result) {
        if (result == null) {
            return;
        }
        byte[] b = (byte[]) result;
        try {
            r.lc.setShot(r.k, r.gen, Image.createImage(b, 0, b.length));
        } catch (RuntimeException e) {
            // ảnh hỏng hoặc máy không giải mã được JPEG: giữ ô icon/chữ cái
        } catch (OutOfMemoryError e) {
            r.lc.dropShots();
        }
    }

    /** Tải icon cho các mục có cờ F_ICON; mục đã có trong bộ nhớ thì gán ngay. */
    private void loadIcons(ListCanvas lc) {
        repo.cancel(lc);
        if (!repo.online()) {
            return;
        }
        if (lc.useGrid()) {
            // lưới: ListCanvas tự xin ảnh theo mục đang hiện (listLoad)
            lc.wantLoad();
            return;
        }
        int n = lc.items.size();
        for (int k = 0; k < n; k++) {
            AppInfo a = (AppInfo) lc.items.elementAt(k);
            if (!a.has(AppInfo.F_ICON)) {
                continue;
            }
            Image im = (Image) iconCache.get(new Integer(a.id));
            if (im != null) {
                lc.setIcon(k, im);
                continue;
            }
            IconReq r = new IconReq();
            r.lc = lc;
            r.k = k;
            r.id = a.id;
            repo.image("/v1/ic/" + a.id + "-32.png", r, lc, this);
        }
    }

    private void onIcon(IconReq r, Object result) {
        if (result == null) {
            return;
        }
        byte[] b = (byte[]) result;
        try {
            Image im = Image.createImage(b, 0, b.length);
            Integer key = new Integer(r.id);
            synchronized (iconCache) {
                if (iconCache.size() >= ICON_CACHE && iconOrder.size() > 0) {
                    iconCache.remove(iconOrder.elementAt(0));
                    iconOrder.removeElementAt(0);
                }
                iconCache.put(key, im);
                iconOrder.addElement(key);
            }
            r.lc.setIcon(r.k, im);
        } catch (RuntimeException e) {
            // icon hỏng: giữ ô chữ cái
        }
    }

    /** Hướng dẫn từng bước tạo và chọn điểm truy cập WLAN khi app không ra được mạng. */
    private void netHelp(String err) {
        Panel p = newPanel(Panel.TEXT, P_NETHELP, "Chưa vào được mạng", "Thử lại", "Đóng");
        p.addPara(Vi.tr("Máy chưa có đường ra mạng (mã " + err + "). Làm theo các bước:"));
        p.addPara(Vi.tr("1. Bật WiFi, vào Menu > Bảng điều khiển > Kết nối > Trình hướng dẫn WLAN. Chọn mạng WiFi, nhập mật khẩu, chọn Bắt đầu duyệt web để máy tạo điểm truy cập."));
        p.addPara(Vi.tr("2. Vào Menu > Ứng dụng > Quản lý ứng dụng, chọn Monika, bấm Tùy chọn > Cài đặt. Ở mục Điểm truy cập, chọn tên mạng WiFi vừa tạo."));
        p.addPara(Vi.tr("3. Bấm Thử lại. Nếu vẫn lỗi, thoát Monika rồi mở lại."));
        p.addPara(Vi.tr("Cần chẩn đoán thêm: Tùy chọn > 6. Kiểm tra mạng."));
        p.addInfo(Vi.tr("Chi tiết lỗi"), Net.lastDiag.length() == 0 ? err : Net.lastDiag);
        display.setCurrent(p);
    }

    private void onHome(Object result, String err) {
        if (err != null) {
            if (!homeAlerted && display.getCurrent() == home) {
                homeAlerted = true;
                netHelp(err);
            }
            return;
        }
        hdata = (Home) result;
        if (home != null) {
            home.setBadge(hdata.newCount);
        }
        if (hdata.latestJar.length() > 0 && newer(hdata.latest, VERSION)) {
            updateUrl = hdata.latestJar;
            updateVer = hdata.latest;
        } else {
            updateUrl = "";
        }
        loadPromos();
    }

    private void loadPromos() {
        int n = Math.min(MAX_PROMOS, hdata.banId.length);
        promoGen++;
        promoImgs = new Image[0];
        promoLinksAll = new String[0];
        if (n == 0 || !repo.online()) {
            if (home != null) {
                home.setPromos(promoImgs, promoLinksAll);
            }
            return;
        }
        int w = home != null ? home.getWidth() : 240;
        String size = w >= 300 ? "320" : "240";
        promoPending = n;
        tmpImgs = new Image[n];
        for (int i = 0; i < n; i++) {
            ImgReq r = new ImgReq();
            r.slot = i;
            r.gen = promoGen;
            repo.image("/v1/b/" + hdata.banId[i] + "-" + size + ".png", r, this);
        }
    }

    private Image[] tmpImgs;

    private synchronized void onBanner(ImgReq r, Object result) {
        if (r.gen != promoGen) {
            return;
        }
        if (result != null) {
            byte[] b = (byte[]) result;
            try {
                tmpImgs[r.slot] = Image.createImage(b, 0, b.length);
            } catch (RuntimeException e) {
                // ảnh hỏng: bỏ slot này
            }
        }
        promoPending--;
        if (promoPending > 0) {
            return;
        }
        int cnt = 0;
        for (int i = 0; i < tmpImgs.length; i++) {
            if (tmpImgs[i] != null) {
                cnt++;
            }
        }
        Image[] imgs = new Image[cnt];
        String[] links = new String[cnt];
        int k = 0;
        for (int i = 0; i < tmpImgs.length; i++) {
            if (tmpImgs[i] != null) {
                imgs[k] = tmpImgs[i];
                links[k] = hdata.banLink[i];
                k++;
            }
        }
        promoImgs = imgs;
        promoLinksAll = links;
        if (home != null) {
            home.setPromos(imgs, links);
        }
    }

    /** So sánh phiên bản dạng 1.2.3: true nếu a mới hơn b. */
    static boolean newer(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() || j < b.length()) {
            int x = 0;
            while (i < a.length() && a.charAt(i) != '.') {
                char c = a.charAt(i++);
                if (c >= '0' && c <= '9') {
                    x = x * 10 + (c - '0');
                }
            }
            i++;
            int y = 0;
            while (j < b.length() && b.charAt(j) != '.') {
                char c = b.charAt(j++);
                if (c >= '0' && c <= '9') {
                    y = y * 10 + (c - '0');
                }
            }
            j++;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    // ---------- hành động từ màn hình chính ----------

    void homeAction(int act, int sel) {
        if (act == HomeCanvas.ACT_EXIT) {
            exit();
        } else if (act == HomeCanvas.ACT_SEARCH) {
            openSearch();
        } else if (act == HomeCanvas.ACT_OPTIONS) {
            openOptions();
        } else if (act == HomeCanvas.ACT_PROMO) {
            followLink(home.promoLink());
        } else if (act == HomeCanvas.ACT_OPEN) {
            switch (sel) {
                case LIST_APPS:
                    openList("Ứng dụng", "app", null);
                    break;
                case LIST_GAMES:
                    openList("Trò chơi", "game", null);
                    break;
                case LIST_CATS:
                    openCategories();
                    break;
                case LIST_UPDATES:
                    openList("Cập nhật", "new", RANK_SORTS);
                    break;
                default:
                    openFavorites();
                    break;
            }
        }
    }

    /** Link banner: l:khóa (mở danh sách), g:id (mở một app). */
    private void followLink(String link) {
        if (link == null || link.length() < 3) {
            return;
        }
        String v = link.substring(2);
        if (link.startsWith("l:")) {
            openList(v.equals("hot") ? "Hot" : v.equals("new") ? "Cập nhật" : "Danh sách", v, null);
        } else if (link.startsWith("g:")) {
            ListCanvas lc = newList("Ứng dụng", 0);
            lc.openOnly = true;
            loadList(lc, "i" + v, 0);
            show(lc);
        }
    }

    // ---------- danh sách ----------

    private ListCanvas newList(String title, int kind) {
        ListCanvas lc = new ListCanvas(this, isFull(), title, kind);
        lc.setCommandListener(this);
        return lc;
    }

    /** tabKeys khác null: hiện tab xếp hạng (Mới, Hot, Hôm nay, Tuần, Tải nhiều) với khóa tương ứng. */
    private void openList(String title, String key, String[] tabKeys) {
        ListCanvas lc = newList(title, 0);
        if (tabKeys != null) {
            lc.tabLabels = RANK_LABELS;
            lc.tabShort = RANK_SHORT;
            lc.tabKeys = tabKeys;
        }
        loadList(lc, key, 0);
        show(lc);
    }

    /** Cỡ màn hình để lọc, dạng "240x320" (cỡ hiện tại của canvas; không toàn màn hình thì quy về cỡ máy). */
    private String screenSize() {
        Displayable d = display.getCurrent();
        Canvas c = d instanceof Canvas ? (Canvas) d : home;
        if (c == null) {
            return "";
        }
        int w = c.getWidth();
        int h = c.getHeight();
        if (!isFull()) {
            int best = h;
            for (int i = 0; i < SCREENS.length; i++) {
                int sh = SCREENS[i][1];
                if (SCREENS[i][0] == w && sh >= h && sh - h <= h / 3 && (best == h || sh < best)) {
                    best = sh;
                }
            }
            h = best;
        }
        return w + "x" + h;
    }

    /** Khóa gửi máy chủ: thêm .r&lt;W&gt;x&lt;H&gt; nếu bật "chỉ hiện game hợp màn hình" (trừ tìm kiếm và mục đơn). */
    private String withFilter(String key) {
        if (key.startsWith("s:") || (key.length() > 1 && key.charAt(0) == 'i' && Character.isDigit(key.charAt(1)))) {
            return key;
        }
        String sz = screenSize();
        if (sz.length() == 0) {
            return key;
        }
        // .r = chỉ hiện đúng cỡ (khi bật lọc); .s = ưu tiên mềm: cỡ khớp lên đầu, màn lớn thì Worker ưu tiên bản smartphone
        return key + (store.fitScreen ? ".r" + sz : "") + ".s" + sz;
    }

    private void loadList(ListCanvas lc, String key, int page) {
        lc.key = key;
        lc.reqKey = withFilter(key);
        repo.cancel(lc);
        ListReq r = new ListReq();
        r.lc = lc;
        lc.pending = r;
        lc.setLoading();
        repo.list(lc.reqKey, page, r, this);
    }

    private void fillList(ListCanvas lc, Page p) {
        int n = p.items.size();
        if (lc.openOnly) {
            // banner trỏ tới một app: vào thẳng trang chi tiết
            if (n > 0) {
                AppInfo a = (AppInfo) p.items.elementAt(0);
                DetailCanvas d = newDetail(a);
                display.setCurrent(d);
            } else {
                lc.setData(new String[0], new String[0], new int[0], new int[0], 0, 0, Page.SIZE);
            }
            return;
        }
        if (p.unfiltered) {
            // bộ lọc theo màn hình rỗng: các trang sau cũng không lọc
            lc.reqKey = lc.key;
        }
        lc.items = p.items;
        String[] names = new String[n];
        String[] subs = new String[n];
        int[] flags = new int[n];
        int[] badges = new int[n];
        for (int i = 0; i < n; i++) {
            AppInfo a = (AppInfo) p.items.elementAt(i);
            names[i] = (store.isFavorite(a.id) ? "* " : "") + a.name;
            subs[i] = a.has(AppInfo.F_POST | AppInfo.F_MEDIA) ? ""
                : a.sizeKb + " KB · v" + a.version + (a.isFree() ? "" : " · " + money(a.price));
            flags[i] = a.flags;
        }
        lc.setData(names, subs, flags, badges, p.page, p.total, Page.SIZE);
        loadIcons(lc);
    }

    void listOpen(ListCanvas lc, int k) {
        if (lc.kind == 1 && lc.group == 0) {
            if (lc.ids[k] == PICK_ID) {
                openList("Đề xuất hôm nay", "pick", null);
            } else {
                openGroup(lc.ids[k]);
            }
        } else if (lc.kind == 1 && (lc.ids[k] == SCR_240_ID || lc.ids[k] == SCR_320_ID)) {
            String res = lc.ids[k] == SCR_240_ID ? "240x320" : "320x240";
            String[] keys = new String[RANK_SORTS.length];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = "game.x" + res + "." + RANK_SORTS[i];
            }
            openList("Game " + res, keys[0], keys);
        } else if (lc.kind == 1 && hdata.kindOfCat(lc.ids[k]) >= Home.KIND_READ) {
            // bài viết, giao diện, âm thanh: không có lượt tải nên không có tab xếp hạng
            int id = lc.ids[k];
            openList(hdata.nameOfCat(id), "c" + id, null);
        } else if (lc.kind == 1) {
            int id = lc.ids[k];
            String[] keys = new String[RANK_SORTS.length];
            for (int i = 0; i < keys.length; i++) {
                keys[i] = "c" + id + "." + RANK_SORTS[i];
            }
            openList(hdata.nameOfCat(id).length() > 0 ? hdata.nameOfCat(id)
                : Catalog.CATEGORIES[Math.min(k, Catalog.CATEGORIES.length - 1)], keys[0], keys);
        } else if (k >= 0 && k < lc.items.size()) {
            DetailCanvas d = newDetail((AppInfo) lc.items.elementAt(k));
            show(d);
        }
    }

    void listPage(ListCanvas lc, int delta) {
        int target = lc.page + delta;
        if (target < 0) {
            return;
        }
        ListReq r = new ListReq();
        r.lc = lc;
        lc.pending = r;
        lc.setLoading();
        repo.list(lc.reqKey, target, r, this);
    }

    void listTab(ListCanvas lc, int tab) {
        repo.cancel(lc.pending);
        lc.tab = tab;
        loadList(lc, lc.tabKeys[tab], 0);
    }

    void listRetry(ListCanvas lc) {
        if (lc.kind == 1) {
            openCategoriesInto(lc);
        } else {
            loadList(lc, lc.key, lc.page);
        }
    }

    void listBack() {
        back();
    }

    void listSearch() {
        openSearch();
    }

    /** Danh mục cấp 1: nhóm Trò chơi / Ứng dụng (lấy từ home2, theo kind). */
    private void openCategories() {
        ListCanvas lc = newList("Danh mục", 1);
        lc.group = 0;
        openCategoriesInto(lc);
        show(lc);
    }

    /** Danh mục cấp 2: các thể loại của một nhóm (kind 1 game, 2 app). */
    private void openGroup(int kind) {
        ListCanvas lc = newList(Home.kindName(kind), 1);
        lc.group = kind;
        fillCats(lc);
        show(lc);
    }

    private void openCategoriesInto(ListCanvas lc) {
        if (hdata.catId.length == 0) {
            // chưa có dữ liệu trang chủ: nạp lại rồi dựng danh sách
            lc.setLoading();
            final ListCanvas target = lc;
            repo.home(target.token, new Repo.Callback() {
                public void done(Object tag, Object result, String err) {
                    if (err != null) {
                        target.setError(err);
                        return;
                    }
                    onHome(result, null);
                    fillCats(target);
                }
            });
            return;
        }
        fillCats(lc);
    }

    private void fillCats(ListCanvas lc) {
        int[] sel = new int[Math.max(hdata.catId.length, 7) + 3];
        String[] nm = new String[sel.length];
        int[] cnt = new int[sel.length];
        int n = 0;
        if (lc.group == 0) {
            if (hdata.countOfKind(Home.KIND_GAME) > 0) {
                // Đề xuất hôm nay: Worker chọn game cho cả ngày (khóa pick)
                sel[n] = PICK_ID;
                nm[n] = "Đề xuất hôm nay";
                cnt[n++] = 0;
            }
            for (int i = 0; i < Home.KIND_ORDER.length; i++) {
                int kind = Home.KIND_ORDER[i];
                int c = hdata.countOfKind(kind);
                if (c > 0) {
                    sel[n] = kind;
                    nm[n] = Home.kindName(kind);
                    cnt[n++] = c;
                }
            }
        } else {
            if (lc.group == Home.KIND_GAME) {
                // theo màn hình: người dùng máy cũ quan tâm cỡ màn hình ngang với thể loại
                sel[n] = SCR_240_ID;
                nm[n] = "Game 240x320";
                cnt[n++] = 0;
                sel[n] = SCR_320_ID;
                nm[n] = "Game 320x240";
                cnt[n++] = 0;
            }
            for (int i = 0; i < hdata.catId.length; i++) {
                if (hdata.catKind[i] == lc.group) {
                    sel[n] = hdata.catId[i];
                    nm[n] = hdata.catName[i];
                    cnt[n++] = hdata.catCount[i];
                }
            }
        }
        String[] names = new String[n];
        String[] subs = new String[n];
        int[] flags = new int[n];
        int[] badges = new int[n];
        lc.ids = new int[n];
        for (int i = 0; i < n; i++) {
            lc.ids[i] = sel[i];
            names[i] = nm[i];
            badges[i] = cnt[i];
        }
        lc.setData(names, subs, flags, badges, 0, n, 0);
    }

    private void openFavorites() {
        Vector v = store.favorites();
        if (v.size() == 0) {
            alert("Thông báo", "Chưa có mục yêu thích nào.", home);
            return;
        }
        ListCanvas lc = newList("Yêu thích", 2);
        lc.items = v;
        int n = v.size();
        String[] names = new String[n];
        String[] subs = new String[n];
        int[] flags = new int[n];
        int[] badges = new int[n];
        for (int i = 0; i < n; i++) {
            AppInfo a = (AppInfo) v.elementAt(i);
            names[i] = a.name;
            subs[i] = a.sizeKb + " KB · v" + a.version;
            flags[i] = a.flags;
        }
        lc.setData(names, subs, flags, badges, 0, n, 0);
        loadIcons(lc);
        show(lc);
    }

    // ---------- chi tiết ----------

    static String money(int vnd) {
        String s = Integer.toString(vnd);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) {
                sb.append('.');
            }
            sb.append(s.charAt(i));
        }
        return sb.append("đ").toString();
    }

    private DetailCanvas newDetail(AppInfo a) {
        String cat = hdata.nameOfCat(a.cat);
        if (cat.length() == 0 && a.cat >= 0 && a.cat < Catalog.CATEGORIES.length) {
            cat = Catalog.CATEGORIES[a.cat];
        }
        boolean can = a.path.length() > 0 && a.isFree() && (a.path.startsWith("http") || repo.online());
        String note = null;
        if (a.path.length() == 0) {
            note = Vi.tr("Mục mẫu: chưa có file cài đặt.");
        } else if (!a.isFree()) {
            note = Vi.tr("App trả phí: tính năng mua sẽ có ở bản sau.");
        } else if (!can) {
            note = Vi.tr("Chưa cấu hình máy chủ, chưa tải được.");
        }
        DetailCanvas d = new DetailCanvas(this, isFull(), a, cat, store.isFavorite(a.id), can, note);
        d.setCommandListener(this);
        return d;
    }

    void detailInstall(DetailCanvas d) {
        AppInfo a = d.info;
        String url = repo.url(a.path);
        // đếm lượt tải (bỏ qua bấm lại đúng game vừa tải); lưu trước vì máy có thể buộc thoát app khi cài
        boolean counted = a.id != store.lastDl;
        if (counted) {
            store.dlCount++;
            store.lastDl = a.id;
        }
        store.save();
        d.setStatus(Vi.tr("Đang mở trình tải..."));
        try {
            if (platformRequest(url)) {
                // máy yêu cầu thoát app trước khi cài
                exit();
            }
            if (counted && donateDue(store.dlCount)) {
                donateWait = 1;
            }
        } catch (ConnectionNotFoundException e) {
            if (counted) {
                store.dlCount--;
                store.lastDl = 0;
                store.save();
            }
            d.setStatus("");
            alert("Thông báo", "Máy không mở được liên kết. Gõ địa chỉ này vào trình duyệt: " + url, d);
        }
    }

    // ---------- thông báo ủng hộ ----------

    /** Nhắc ở lượt tải thứ 2, rồi cứ 5 lượt một lần; mỗi mốc chỉ nhắc một lần. */
    private boolean donateDue(int n) {
        return n >= 2 && (n == 2 || (n - 2) % 5 == 0) && n > store.donateAt;
    }

    /** 0 = không chờ; 1 = vừa bấm tải, chờ app bị ẩn (trình tải mở); 2 = đã ẩn, hiện khi quay lại app. */
    private int donateWait;

    void detailHidden() {
        if (donateWait == 1) {
            donateWait = 2;
        }
    }

    void detailShown() {
        if (donateWait == 2) {
            donateWait = 0;
            showDonate();
        }
    }

    /** Hiện thông báo ủng hộ (một lần cho mỗi mốc tải). */
    private void showDonate() {
        donateWait = 0;
        Displayable c = display.getCurrent();
        if (c instanceof DonateCanvas) {
            return;
        }
        store.donateAt = store.dlCount;
        store.save();
        DonateCanvas d = new DonateCanvas(this, isFull());
        d.setCommandListener(this);
        show(d);
    }

    void donateClose(DonateCanvas d) {
        back();
    }

    /** Máy J2ME không có clipboard: mở ô chữ chứa số tài khoản để người dùng tự chọn và chép. */
    void donateCopy(DonateCanvas d) {
        cback = d;
        cbox = new TextBox(Vi.tr("Số tài khoản Timo"), DonateCanvas.ACCOUNT, 20, TextField.NUMERIC);
        cbox.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 1));
        cbox.setCommandListener(this);
        display.setCurrent(cbox);
    }

    private TextBox cbox;
    private Displayable cback;

    // ---------- kiểm tra phím ----------

    private void openKeyTest() {
        show(new KeyTestCanvas(this));
    }

    void keyTestExit() {
        back();
    }

    void detailFav(DetailCanvas d) {
        d.setFav(store.toggleFavorite(d.info));
    }

    // ---------- góp ý / báo lỗi ----------

    /** Ô của form góp ý: 0 = game, 1 = loại, 2 = nội dung, 3 = liên hệ, 4 = phép tính. */
    private int fbItem;
    private String fbToken = "";
    private boolean fbBusy;

    void detailReport(DetailCanvas d) {
        openFeedback(d.info.id, d.info.name);
    }

    private void openFeedback(int itemId, String itemName) {
        if (!repo.online()) {
            alert("Góp ý", "Chưa cấu hình máy chủ nên chưa gửi được góp ý.", display.getCurrent());
            return;
        }
        Panel p = newPanel(Panel.FORM, P_FB, "Góp ý / báo lỗi", "Gửi", "Hủy");
        p.addInfo(Vi.tr("Về"), itemName == null ? Vi.tr("Chung (cả Monika)") : itemName);
        p.addChoice(Vi.tr("Loại"), new String[] {Vi.tr("Báo lỗi game"), Vi.tr("Góp ý"), Vi.tr("Khác")},
            itemId > 0 ? 0 : 1);
        p.addText(Vi.tr("Nội dung"), "");
        p.addText(Vi.tr("Liên hệ (không bắt buộc)"), "");
        p.addText(Vi.tr("Phép tính"), "");
        p.addPara(Vi.tr("Mô tả lỗi (game nào, lỗi gì, máy gì). Để lại email hoặc số điện thoại nếu muốn được trả lời. Trả lời phép tính để chống spam."));
        fbItem = itemId;
        fbToken = "";
        show(p);
        loadChallenge(p, false);
    }

    private void editFeedback(Panel p, int sel) {
        Panel.Row r = p.row(sel);
        boolean calc = sel == 4;
        Panel e = newPanel(Panel.INPUT, P_EDIT, sel == 2 ? "Nội dung góp ý" : sel == 3 ? "Liên hệ" : "Phép tính", "OK", "Hủy");
        e.maxLen = sel == 2 ? Feedback.MSG_MAX : sel == 3 ? Feedback.CONTACT_MAX : 3;
        e.setInput(r.value, Vi.tr(calc ? "Gõ kết quả: " + fbQuestion(p) : sel == 2 ? "Mô tả lỗi hoặc góp ý (tối thiểu 5 ký tự)" : "Email hoặc số điện thoại"));
        e.setDirect(calc);
        editFor = p;
        editRow = sel;
        show(e);
    }

    private static String fbQuestion(Panel p) {
        String l = p.row(4).label;
        int i = l.indexOf(':');
        return i < 0 ? "" : l.substring(i + 1).trim();
    }

    /** Lấy câu hỏi phép tính mới; again = true khi máy chủ báo token đã dùng/hết hạn (xóa đáp án cũ). */
    private void loadChallenge(final Panel p, final boolean again) {
        final String agent = ua;
        final String base = repo.base();
        final String sfx = repo.suffix();
        p.row(4).label = Vi.tr("Phép tính: đang tải...");
        if (again) {
            p.row(4).value = "";
        }
        p.repaint();
        new Thread(new Runnable() {
            public void run() {
                try {
                    String[] c = Feedback.challenge(base, sfx, agent);
                    fbToken = c[1];
                    p.row(4).label = Vi.tr("Phép tính: ") + c[0];
                } catch (IOException e) {
                    fbToken = "";
                    p.row(4).label = Vi.tr("Phép tính: chưa tải được, bấm Gửi để thử lại");
                } catch (RuntimeException e) {
                    fbToken = "";
                    p.row(4).label = Vi.tr("Phép tính: chưa tải được, bấm Gửi để thử lại");
                }
                p.repaint();
            }
        }).start();
    }

    private void sendFeedback(final Panel p) {
        if (fbBusy) {
            return;
        }
        final String msg = p.row(2).value == null ? "" : p.row(2).value.trim();
        final String contact = p.row(3).value == null ? "" : p.row(3).value.trim();
        final String ans = p.row(4).value == null ? "" : p.row(4).value.trim();
        if (msg.length() < Feedback.MSG_MIN) {
            alert("Góp ý", "Nội dung quá ngắn. Bấm vào ô Nội dung để gõ (tối thiểu 5 ký tự).", p);
            return;
        }
        if (fbToken.length() == 0) {
            alert("Góp ý", "Chưa có phép tính. Đang tải lại, đợi vài giây rồi bấm Gửi.", p);
            loadChallenge(p, true);
            return;
        }
        if (ans.length() == 0) {
            alert("Góp ý", "Hãy trả lời phép tính ở ô cuối cùng.", p);
            return;
        }
        fbBusy = true;
        final int kind = p.row(1).idx;
        final String agent = ua;
        final String base = repo.base();
        final String sfx = repo.suffix();
        final String token = fbToken;
        final int item = fbItem;
        final String dev = store.deviceId;
        p.row(4).label = Vi.tr("Đang gửi...");
        p.repaint();
        new Thread(new Runnable() {
            public void run() {
                String r = Feedback.send(base, sfx, agent, kind, item, dev, msg, contact, token, ans);
                fbBusy = false;
                if ("ok".equals(r)) {
                    fbToken = "";
                    Displayable prev = home;
                    int n = stack.size();
                    if (n > 0) {
                        prev = (Displayable) stack.elementAt(n - 1);
                        stack.removeElementAt(n - 1);
                    }
                    alert("Góp ý", Feedback.describe(r), prev);
                    return;
                }
                if (Feedback.needsNewChallenge(r)) {
                    loadChallenge(p, true);
                } else {
                    p.row(4).label = Vi.tr("Phép tính: ") + fbQuestion(p);
                    p.repaint();
                }
                alert("Góp ý", Feedback.describe(r), p);
            }
        }).start();
    }

    // ---------- tìm kiếm ----------

    void openSearch() {
        Displayable c = display.getCurrent();
        if (c instanceof Panel && ((Panel) c).id == P_SEARCH) {
            return;
        }
        Panel p = newPanel(Panel.INPUT, P_SEARCH, "Tìm ứng dụng", "Tìm", "Quay lại");
        p.setInput("", Vi.tr("Gõ tên game hoặc ứng dụng cần tìm"));
        show(p);
    }

    private void doSearch(Panel p) {
        String text = p.inputText().trim();
        String key = Vi.key(text).trim();
        if (Repo.enc(key).length() == 0) {
            alert("Thông báo", "Nhập từ khóa để tìm.", p);
            return;
        }
        ListCanvas lc = newList("Tìm: " + text, 0);
        loadList(lc, "s:" + key, 0);
        show(lc);
    }

    // ---------- tùy chọn, cài đặt, thông tin ----------

    private void openOptions() {
        Panel p = newPanel(Panel.MENU, P_OPTIONS, "Tùy chọn", "Chọn", "Quay lại");
        p.addItem(Vi.tr(mi(1, "Mở mục đang chọn")));
        p.addItem(Vi.tr(mi(2, "Tìm kiếm  (phím *)")));
        p.addItem(Vi.tr(mi(3, "Cài đặt")));
        p.addItem(Vi.tr(mi(4, "Thông tin máy")));
        p.addItem(Vi.tr(mi(5, "Giới thiệu")));
        p.addItem(Vi.tr(mi(6, "Kiểm tra mạng")));
        p.addItem(Vi.tr(mi(7, "Kiểm tra phím")));
        p.addItem(Vi.tr(mi(8, "Góp ý / báo lỗi")));
        if (updateUrl.length() > 0) {
            p.addItem(Vi.tr(mi(9, "Cập nhật Monika " + updateVer)));
        }
        show(p);
    }

    /** Nhãn mục menu: có số đầu dòng (bấm số để chọn nhanh) chỉ khi tắt chế độ phím số di chuyển. */
    private static String mi(int n, String s) {
        return BaseCanvas.numNav ? s : n + ". " + s;
    }

    private void openSettings() {
        Panel p = newPanel(Panel.FORM, P_SETTINGS, "Cài đặt", "Lưu", "Hủy");
        p.addChoice(Vi.tr("Chữ tiếng Việt"), new String[] {Vi.tr("Có dấu"), Vi.tr("Không dấu")},
            Vi.mode == Vi.MODE_PLAIN ? 1 : 0);
        p.addToggle(Vi.tr("Toàn màn hình"), isFull());
        p.addToggle(Vi.tr("Chỉ hiện game hợp màn hình"), store.fitScreen);
        p.addToggle(Vi.tr("Phím số di chuyển: 2468, 5 là OK"), store.numNav);
        p.addToggle(Vi.tr("Xóa dữ liệu đã lưu"), false);
        p.addText(Vi.tr("Máy chủ"), store.server);
        p.addPara(Vi.tr("Máy chủ để trống là dùng mặc định. Bấm OK để đổi mục, Lưu để áp dụng."));
        show(p);
    }

    private void saveSettings(Panel p) {
        Vi.mode = p.row(0).idx == 1 ? Vi.MODE_PLAIN : Vi.MODE_ACCENT;
        store.fullscreen = p.row(1).on ? 1 : 2;
        store.fitScreen = p.row(2).on;
        store.numNav = p.row(3).on;
        BaseCanvas.numNav = store.numNav;
        if (p.row(4).on) {
            store.cacheClear();
        }
        String sv = p.row(5).value;
        store.server = sv == null ? "" : sv.trim();
        store.save();
        home = newHome();
        hdata = repo.online() ? new Home() : Home.fromCatalog(repo.embedded());
        stack.removeAllElements();
        alert("Cài đặt", "Đã lưu.", home);
        loadHome();
    }

    private void showInfo() {
        Panel p = newPanel(Panel.TEXT, P_INFO, "Thông tin máy", null, "Quay lại");
        p.addInfo(Vi.tr("Nền tảng"), System.getProperty("microedition.platform"));
        p.addInfo(Vi.tr("Cấu hình"), System.getProperty("microedition.configuration"));
        p.addInfo(Vi.tr("Profile"), System.getProperty("microedition.profiles"));
        p.addInfo(Vi.tr("Ngôn ngữ máy"), System.getProperty("microedition.locale"));
        Runtime rt = Runtime.getRuntime();
        p.addInfo(Vi.tr("Bộ nhớ"), (rt.freeMemory() / 1024) + " / " + (rt.totalMemory() / 1024) + " KB");
        p.addInfo(Vi.tr("Màn hình"), home.getWidth() + "x" + home.getHeight()
            + (display.isColor() ? ", " + display.numColors() + " màu" : ", đơn sắc"));
        p.addInfo(Vi.tr("Mã thiết bị"), store.deviceId);
        p.addInfo(Vi.tr("Dữ liệu"), repo.source + (repo.stale ? " (mạng lỗi)" : ""));
        p.addInfo(Vi.tr("Lọc màn hình"), store.fitScreen ? screenSize() : "tắt");
        p.addInfo(Vi.tr("Máy chủ"), repo.online() ? repo.base() : "(chưa cấu hình)");
        p.addInfo(Vi.tr("Lỗi mạng"), Net.lastDiag.length() == 0 ? "chưa có" : Net.lastDiag);
        p.addInfo("Monika", VERSION);
        show(p);
    }

    private void showAbout() {
        Panel p = newPanel(Panel.TEXT, P_ABOUT, "Giới thiệu", null, "Quay lại");
        p.addInfo("Monika", VERSION);
        p.addPara(Vi.tr("Cửa hàng ứng dụng Java S60 by AOW.VN. Dữ liệu đồng bộ với web m.justmonika.top."));
        p.addPara(Vi.tr("Phím tắt: * tìm kiếm (QWERTY: phím U), phím giữa hoặc 5 để chọn, 2 4 6 8 để di chuyển (QWERTY: T F H B), phím xóa để quay lại."));
        show(p);
    }

    // ---------- nhận hành động từ màn hình tự vẽ ----------

    void panelAction(Panel p, int act, int sel) {
        if (p.id == P_ALERT) {
            if (act == Panel.A_LEFT || act == Panel.A_RIGHT) {
                display.setCurrent(p.next);
            }
            return;
        }
        if (act == Panel.A_SEARCH) {
            openSearch();
            return;
        }
        if (act == Panel.A_NATIVE) {
            nativeInput(p);
            return;
        }
        switch (p.id) {
            case P_OPTIONS:
                if (act == Panel.A_RIGHT) {
                    back();
                } else if (act == Panel.A_PICK || act == Panel.A_LEFT) {
                    optionPicked(sel);
                }
                break;
            case P_SEARCH:
                if (act == Panel.A_LEFT) {
                    doSearch(p);
                } else if (act == Panel.A_RIGHT) {
                    back();
                }
                break;
            case P_SETTINGS:
                if (act == Panel.A_LEFT) {
                    saveSettings(p);
                } else if (act == Panel.A_RIGHT) {
                    back();
                } else if (act == Panel.A_EDIT) {
                    Panel e = newPanel(Panel.INPUT, P_EDIT, p.row(sel).label, "OK", "Hủy");
                    e.setInput(p.row(sel).value, Vi.tr("Để trống là dùng mặc định"));
                    editFor = p;
                    editRow = sel;
                    show(e);
                }
                break;
            case P_FB:
                if (act == Panel.A_LEFT) {
                    sendFeedback(p);
                } else if (act == Panel.A_RIGHT) {
                    back();
                } else if (act == Panel.A_EDIT) {
                    editFeedback(p, sel);
                }
                break;
            case P_EDIT:
                if (act == Panel.A_LEFT && editFor != null) {
                    editFor.row(editRow).value = p.inputText().trim();
                    editFor = null;
                    back();
                } else if (act == Panel.A_RIGHT) {
                    editFor = null;
                    back();
                }
                break;
            case P_NETHELP:
                showHome();
                if (act == Panel.A_LEFT) {
                    homeAlerted = false;
                    loadHome();
                }
                break;
            case P_NET:
                if (act == Panel.A_LEFT) {
                    back();
                    openNetTest();
                } else {
                    back();
                }
                break;
            default:
                // thông tin, giới thiệu: phím mềm phải hoặc phím giữa quay lại
                back();
                break;
        }
    }

    private void optionPicked(int i) {
        boolean hasUpdate = updateUrl.length() > 0;
        if (i == 0) {
            stack.removeAllElements();
            showHome();
            home.activate();
        } else if (i == 1) {
            openSearch();
        } else if (i == 2) {
            openSettings();
        } else if (i == 3) {
            showInfo();
        } else if (i == 4) {
            showAbout();
        } else if (i == 5) {
            openNetTest();
        } else if (i == 6) {
            openKeyTest();
        } else if (i == 7) {
            openFeedback(0, null);
        } else if (hasUpdate && i == 8) {
            installUpdate();
        }
    }

    private static final String[] NET_URLS = {
        "http://api.justmonika.top/",
        "http://api.justmonika.top:80/",
        "http://104.21.79.142/",
        "http://example.com/"
    };

    /** Chẩn đoán mạng: thử vài địa chỉ khác nhau, hiện kết quả từng dòng để biết lỗi do điểm truy cập, DNS hay máy chủ. */
    private void openNetTest() {
        final Panel p = newPanel(Panel.TEXT, P_NET, "Kiểm tra mạng", "Chạy lại", "Quay lại");
        p.addInfo(Vi.tr("Đang thử, chờ vài chục giây"), "...");
        for (int i = 0; i < NET_URLS.length; i++) {
            p.addInfo(NET_URLS[i], "...");
        }
        show(p);
        final String agent = ua;
        new Thread(new Runnable() {
            public void run() {
                boolean[] ok = new boolean[NET_URLS.length];
                for (int i = 0; i < NET_URLS.length; i++) {
                    String r = Net.probe(NET_URLS[i], agent);
                    ok[i] = r.startsWith("OK");
                    p.row(i + 1).value = r;
                    p.repaint();
                }
                String v;
                if (ok[0] || ok[1]) {
                    v = "Mạng thông, máy chủ Monika trả lời.";
                } else if (ok[2]) {
                    v = "Gọi bằng IP được, gọi bằng tên miền không: lỗi phân giải tên miền (DNS).";
                } else if (ok[3]) {
                    v = "Ra được mạng nhưng không tới được Monika: lỗi riêng với tên miền hoặc Cloudflare.";
                } else {
                    v = "Không địa chỉ nào qua được: app chưa có điểm truy cập dùng được. Đổi điểm truy cập của app trong Quản lý ứng dụng.";
                }
                p.row(0).label = Vi.tr("Kết luận");
                p.row(0).value = Vi.tr(v);
                p.repaint();
            }
        }).start();
    }

    /** Máy cảm ứng: chạm vào ô thì dùng ô nhập gốc để hiện bàn phím ảo. */
    private void nativeInput(Panel p) {
        nowner = p;
        nbox = new TextBox(p.id == P_SEARCH ? Vi.tr("Tìm ứng dụng") : Vi.tr("Nhập"),
            p.inputText(), Math.max(80, p.maxLen), p.numeric ? TextField.NUMERIC
            : p.id == P_EDIT ? TextField.URL : TextField.ANY);
        cNbOk = new Command(Vi.tr("OK"), Command.OK, 1);
        nbox.addCommand(cNbOk);
        nbox.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 2));
        nbox.setCommandListener(this);
        display.setCurrent(nbox);
    }

    private void installUpdate() {
        try {
            if (platformRequest(repo.url(updateUrl))) {
                exit();
            }
        } catch (ConnectionNotFoundException e) {
            alert("Thông báo", "Máy không mở được liên kết cập nhật.", home);
        }
    }

    // ---------- nhận lệnh ----------

    public void commandAction(Command c, Displayable d) {
        if (d == home) {
            if (c == home.cmdOpen) {
                home.activate();
            } else if (c == home.cmdSearch) {
                openSearch();
            } else if (c == home.cmdSettings) {
                openSettings();
            } else if (c == home.cmdInfo) {
                showInfo();
            } else if (c == home.cmdAbout) {
                showAbout();
            } else if (c == home.cmdExit) {
                exit();
            }
            return;
        }
        if (d == cbox) {
            cbox = null;
            display.setCurrent(cback);
            return;
        }
        if (d instanceof DonateCanvas) {
            DonateCanvas dc = (DonateCanvas) d;
            if (c == dc.cmdCopy) {
                dc.copy();
            } else {
                dc.close();
            }
            return;
        }
        if (d == nbox) {
            Panel o = nowner;
            nbox = null;
            nowner = null;
            if (o != null) {
                if (c == cNbOk) {
                    o.setText(((TextBox) d).getString());
                }
                display.setCurrent(o);
            } else {
                back();
            }
            return;
        }
        if (d instanceof Panel) {
            ((Panel) d).onCommand(c);
            return;
        }
        if (d instanceof ListCanvas) {
            ListCanvas lc = (ListCanvas) d;
            if (c == lc.cmdOpen) {
                lc.openSelected();
            } else if (c == lc.cmdSearch) {
                openSearch();
            } else {
                back();
            }
            return;
        }
        if (d instanceof DetailCanvas) {
            DetailCanvas dc = (DetailCanvas) d;
            if (c == dc.cmdInstall) {
                detailInstall(dc);
            } else if (c == dc.cmdFav) {
                detailFav(dc);
            } else if (c == dc.cmdSearch) {
                openSearch();
            } else if (c == dc.cmdReport) {
                detailReport(dc);
            } else {
                back();
            }
            return;
        }
        if (c.getCommandType() == Command.BACK) {
            back();
            return;
        }
    }
}
