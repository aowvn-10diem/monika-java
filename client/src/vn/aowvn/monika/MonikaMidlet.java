package vn.aowvn.monika;

import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.io.ConnectionNotFoundException;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
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
    static final String VERSION = "0.4.3";

    private static final int LIST_APPS = 0;
    private static final int LIST_GAMES = 1;
    private static final int LIST_CATS = 2;
    private static final int LIST_UPDATES = 3;
    private static final int LIST_FAVS = 4;

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

    // màn hình đang dùng (để nhận diện trong commandAction)
    private Form fFont;
    private Command cFontYes;
    private Command cFontNo;
    private List fOptions;
    private TextBox fSearch;
    private Command cFind;
    private Form fSettings;
    private ChoiceGroup cgFont;
    private ChoiceGroup cgOpts;
    private TextField tfServer;
    private Command cSave;
    private Form fInfo;
    private Form fAbout;

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
        boolean known = store.load();
        if (known) {
            begin();
        } else {
            showFontTest();
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

    private void alert(String title, String msg, Displayable next) {
        Alert a = new Alert(Vi.tr(title), Vi.tr(msg), null, AlertType.INFO);
        a.setTimeout(3000);
        display.setCurrent(a, next == null ? (Displayable) home : next);
    }

    // ---------- lần chạy đầu: thử font ----------

    private void showFontTest() {
        Vi.mode = Vi.MODE_ACCENT;
        fFont = new Form("Monika");
        fFont.append("Bạn đọc được dòng này không?\n");
        fFont.append("Tiếng Việt có dấu: ắ ằ ẳ ẵ ặ ơ ư đ ế ệ ỷ\n\n");
        fFont.append("Neu ban khong doc duoc dong tren, hay chon \"Khong doc duoc\".");
        cFontYes = new Command("Doc duoc", Command.OK, 1);
        cFontNo = new Command("Khong doc duoc", Command.CANCEL, 2);
        fFont.addCommand(cFontYes);
        fFont.addCommand(cFontNo);
        fFont.setCommandListener(this);
        display.setCurrent(fFont);
    }

    // ---------- khởi động và trang chủ ----------

    /** Hiện trang chủ ngay (không chờ mạng), rồi nạp dữ liệu nền. */
    private void begin() {
        home = newHome();
        if (!repo.online()) {
            hdata = Home.fromCatalog(repo.embedded());
        }
        showHome();
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

    private void onHome(Object result, String err) {
        if (err != null) {
            if (!homeAlerted && display.getCurrent() == home) {
                homeAlerted = true;
                alert("Thông báo", "Không vào được mạng (" + err + "). Chi tiết: " + Net.lastDiag + ". Mã -1 của máy thường là chưa có điểm truy cập cho app: thử vào cài đặt của app trong Quản lý ứng dụng, đặt điểm truy cập là WLAN hoặc Hỏi mỗi lần.",
                    home);
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
        if (!store.fitScreen || key.startsWith("s:")
            || (key.length() > 1 && key.charAt(0) == 'i' && Character.isDigit(key.charAt(1)))) {
            return key;
        }
        String sz = screenSize();
        return sz.length() == 0 ? key : key + ".r" + sz;
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
            subs[i] = a.sizeKb + " KB · v" + a.version + (a.isFree() ? "" : " · " + money(a.price));
            flags[i] = a.flags;
        }
        lc.setData(names, subs, flags, badges, p.page, p.total, Page.SIZE);
        loadIcons(lc);
    }

    void listOpen(ListCanvas lc, int k) {
        if (lc.kind == 1 && lc.group == 0) {
            openGroup(lc.ids[k]);
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
        ListCanvas lc = newList(kind == Home.KIND_GAME ? "Trò chơi" : "Ứng dụng", 1);
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
        int[] sel = new int[Math.max(hdata.catId.length, 2)];
        String[] nm = new String[sel.length];
        int[] cnt = new int[sel.length];
        int n = 0;
        if (lc.group == 0) {
            for (int kind = Home.KIND_GAME; kind <= Home.KIND_APP; kind++) {
                int c = hdata.countOfKind(kind);
                if (c > 0) {
                    sel[n] = kind;
                    nm[n] = kind == Home.KIND_GAME ? "Trò chơi" : "Ứng dụng";
                    cnt[n++] = c;
                }
            }
        } else {
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
        store.save();
        d.setStatus(Vi.tr("Đang mở trình tải..."));
        try {
            if (platformRequest(url)) {
                // máy yêu cầu thoát app trước khi cài
                exit();
            }
        } catch (ConnectionNotFoundException e) {
            d.setStatus("");
            alert("Thông báo", "Máy không mở được liên kết. Gõ địa chỉ này vào trình duyệt: " + url, d);
        }
    }

    void detailFav(DetailCanvas d) {
        d.setFav(store.toggleFavorite(d.info));
    }

    // ---------- tìm kiếm ----------

    private void openSearch() {
        fSearch = new TextBox(Vi.tr("Tìm ứng dụng"), "", 40, TextField.ANY);
        cFind = new Command(Vi.tr("Tìm"), Command.OK, 1);
        fSearch.addCommand(cFind);
        fSearch.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 2));
        fSearch.setCommandListener(this);
        show(fSearch);
    }

    private void doSearch() {
        String key = Vi.key(fSearch.getString()).trim();
        if (Repo.enc(key).length() == 0) {
            alert("Thông báo", "Nhập từ khóa để tìm.", fSearch);
            return;
        }
        ListCanvas lc = newList("Tìm: " + fSearch.getString(), 0);
        loadList(lc, "s:" + key, 0);
        show(lc);
    }

    // ---------- tùy chọn, cài đặt, thông tin ----------

    private void openOptions() {
        fOptions = new List(Vi.tr("Tùy chọn"), Choice.IMPLICIT);
        fOptions.append(Vi.tr("Mở mục đang chọn"), null);
        fOptions.append(Vi.tr("Cài đặt"), null);
        fOptions.append(Vi.tr("Thông tin máy"), null);
        fOptions.append(Vi.tr("Giới thiệu"), null);
        if (updateUrl.length() > 0) {
            fOptions.append(Vi.tr("Cập nhật Monika " + updateVer), null);
        }
        fOptions.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 1));
        fOptions.setCommandListener(this);
        show(fOptions);
    }

    private void openSettings() {
        fSettings = new Form(Vi.tr("Cài đặt"));
        cgFont = new ChoiceGroup(Vi.tr("Chữ tiếng Việt"), Choice.EXCLUSIVE);
        cgFont.append(Vi.tr("Có dấu"), null);
        cgFont.append(Vi.tr("Không dấu"), null);
        cgFont.setSelectedIndex(Vi.mode == Vi.MODE_PLAIN ? 1 : 0, true);
        fSettings.append(cgFont);
        cgOpts = new ChoiceGroup(Vi.tr("Khác"), Choice.MULTIPLE);
        cgOpts.append(Vi.tr("Toàn màn hình (tự vẽ phím mềm)"), null);
        cgOpts.append(Vi.tr("Chỉ hiện game hợp màn hình"), null);
        cgOpts.append(Vi.tr("Xóa dữ liệu đã lưu"), null);
        cgOpts.setSelectedIndex(0, isFull());
        cgOpts.setSelectedIndex(1, store.fitScreen);
        fSettings.append(cgOpts);
        tfServer = new TextField(Vi.tr("Máy chủ (để trống = mặc định)"), store.server, 80, TextField.URL);
        fSettings.append(tfServer);
        cSave = new Command(Vi.tr("Lưu"), Command.OK, 1);
        fSettings.addCommand(cSave);
        fSettings.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 2));
        fSettings.setCommandListener(this);
        show(fSettings);
    }

    private void saveSettings() {
        Vi.mode = cgFont.getSelectedIndex() == 1 ? Vi.MODE_PLAIN : Vi.MODE_ACCENT;
        store.fullscreen = cgOpts.isSelected(0) ? 1 : 2;
        store.fitScreen = cgOpts.isSelected(1);
        if (cgOpts.isSelected(2)) {
            store.cacheClear();
        }
        store.server = tfServer.getString().trim();
        store.save();
        home = newHome();
        hdata = repo.online() ? new Home() : Home.fromCatalog(repo.embedded());
        stack.removeAllElements();
        Alert a = new Alert(Vi.tr("Cài đặt"), Vi.tr("Đã lưu."), null, AlertType.INFO);
        a.setTimeout(1500);
        display.setCurrent(a, home);
        loadHome();
    }

    private void showInfo() {
        fInfo = new Form(Vi.tr("Thông tin máy"));
        fInfo.append(line("Nền tảng", System.getProperty("microedition.platform")));
        fInfo.append(line("Cấu hình", System.getProperty("microedition.configuration")));
        fInfo.append(line("Profile", System.getProperty("microedition.profiles")));
        fInfo.append(line("Ngôn ngữ máy", System.getProperty("microedition.locale")));
        Runtime rt = Runtime.getRuntime();
        fInfo.append(line("Bộ nhớ", (rt.freeMemory() / 1024) + " / " + (rt.totalMemory() / 1024) + " KB"));
        fInfo.append(line("Màn hình", home.getWidth() + "x" + home.getHeight()
            + (display.isColor() ? ", " + display.numColors() + " màu" : ", đơn sắc")));
        fInfo.append(line("Mã thiết bị", store.deviceId));
        fInfo.append(line("Dữ liệu", repo.source + (repo.stale ? " (mạng lỗi)" : "")));
        fInfo.append(line("Lọc màn hình", store.fitScreen ? screenSize() : "tắt"));
        fInfo.append(line("Máy chủ", repo.online() ? repo.base() : "(chưa cấu hình)"));
        fInfo.append(line("Lỗi mạng", Net.lastDiag.length() == 0 ? "chưa có" : Net.lastDiag));
        fInfo.append(line("Monika", VERSION));
        fInfo.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 1));
        fInfo.setCommandListener(this);
        show(fInfo);
    }

    private StringItem line(String label, String value) {
        return new StringItem(Vi.tr(label + ": "), (value == null ? "-" : value) + "\n");
    }

    private void showAbout() {
        fAbout = new Form(Vi.tr("Giới thiệu"));
        fAbout.append(new StringItem(null, "Monika " + VERSION + "\n"));
        fAbout.append(new StringItem(null,
            Vi.tr("Cửa hàng ứng dụng Java S60 by AOW.VN.\nDữ liệu đồng bộ với web m.justmonika.top.\n")));
        fAbout.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 1));
        fAbout.setCommandListener(this);
        show(fAbout);
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
        if (d == fFont) {
            Vi.mode = c == cFontNo ? Vi.MODE_PLAIN : Vi.MODE_ACCENT;
            store.save();
            fFont = null;
            begin();
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
            } else {
                back();
            }
            return;
        }
        if (c.getCommandType() == Command.BACK) {
            back();
            return;
        }
        if (d == fOptions && c == List.SELECT_COMMAND) {
            int i = fOptions.getSelectedIndex();
            if (i == 0) {
                stack.removeAllElements();
                showHome();
                home.activate();
            } else if (i == 1) {
                openSettings();
            } else if (i == 2) {
                showInfo();
            } else if (i == 3) {
                showAbout();
            } else {
                installUpdate();
            }
        } else if (d == fSearch && c == cFind) {
            doSearch();
        } else if (d == fSettings && c == cSave) {
            saveSettings();
        }
    }
}
