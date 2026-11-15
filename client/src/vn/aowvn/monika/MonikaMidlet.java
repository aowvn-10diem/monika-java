package vn.aowvn.monika;

import java.io.IOException;
import java.util.Vector;
import javax.microedition.io.ConnectionNotFoundException;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Gauge;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;
import javax.microedition.midlet.MIDletStateChangeException;

/**
 * Monika: cửa hàng ứng dụng Java S60 by AOW.VN.
 * MIDP 2.0 / CLDC 1.1. Màn hình chính vẽ bằng Canvas, các màn hình còn lại dùng
 * LCDUI cấp cao (List, Form, TextBox) để máy tự lo phím mềm và cảm ứng.
 *
 * Thuộc tính JAD tùy chọn:
 *   Monika-Base-URL   gốc máy chủ catalog, ví dụ http://m.aowvn.us (rỗng = chỉ dùng danh mục mẫu)
 *   Monika-Net-Suffix hậu tố kết nối, ví dụ ;deviceside=true cho BlackBerry
 *   Monika-Fullscreen 1 = toàn màn hình, tự vẽ phím mềm
 */
public final class MonikaMidlet extends MIDlet implements CommandListener {
    static final String VERSION = "0.1.0";

    private static final int LIST_APPS = 0;
    private static final int LIST_GAMES = 1;
    private static final int LIST_CATS = 2;
    private static final int LIST_UPDATES = 3;
    private static final int LIST_FAVS = 4;

    private Display display;
    private final Store store = new Store();
    private Catalog catalog;
    private HomeCanvas home;
    private boolean started;

    private String baseUrl = "";
    private String netSuffix = "";
    private boolean jadFullscreen;
    private String ua = "Monika";

    private long loadMs;
    private String loadSource = "";
    private boolean loadDone;

    private final Vector stack = new Vector();

    // màn hình đang dùng (để nhận diện trong commandAction)
    private Form fFont;
    private Command cFontYes;
    private Command cFontNo;
    private Form fLoad;
    private Command cSkip;
    private List fOptions;
    private List fList;
    private Vector listItems = new Vector();
    private List fCats;
    private Form fDetail;
    private AppInfo cur;
    private Command cInstall;
    private Command cFav;
    private TextBox fSearch;
    private Command cFind;
    private Form fSettings;
    private ChoiceGroup cgFont;
    private ChoiceGroup cgOpts;
    private Command cSave;
    private Form fInfo;
    private Form fAbout;

    // ---------- vòng đời MIDlet ----------

    protected void startApp() throws MIDletStateChangeException {
        if (started) {
            return;
        }
        started = true;
        display = Display.getDisplay(this);
        baseUrl = prop("Monika-Base-URL");
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        netSuffix = prop("Monika-Net-Suffix");
        jadFullscreen = "1".equals(prop("Monika-Fullscreen"));
        boolean known = store.load();
        if (known) {
            beginLoad();
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

    private void back() {
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
        stack.removeAllElements();
        if (home == null) {
            home = newHome();
        }
        if (catalog != null) {
            home.setBadge(catalog.updateCount());
        }
        display.setCurrent(home);
    }

    private HomeCanvas newHome() {
        boolean full = store.fullscreen == 1 || (store.fullscreen == 0 && jadFullscreen);
        HomeCanvas h = new HomeCanvas(this, full);
        h.setCommandListener(this);
        ua = Net.userAgent(h.getWidth(), h.getHeight());
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

    // ---------- nạp catalog ----------

    private void beginLoad() {
        if (home == null) {
            home = newHome();
        }
        if (baseUrl.length() == 0) {
            loadEmbedded("danh mục mẫu trong máy");
            showHome();
            return;
        }
        fLoad = new Form(Vi.tr("Đang tải danh mục"));
        fLoad.append(new Gauge(null, false, Gauge.INDEFINITE, Gauge.CONTINUOUS_RUNNING));
        fLoad.append(Vi.tr("Đang kết nối...\n"));
        cSkip = new Command(Vi.tr("Dùng bản có sẵn"), Command.CANCEL, 1);
        fLoad.addCommand(cSkip);
        fLoad.setCommandListener(this);
        loadDone = false;
        display.setCurrent(fLoad);
        new Thread(new Loader()).start();
    }

    private void loadEmbedded(String source) {
        try {
            catalog = Catalog.loadEmbedded();
        } catch (IOException e) {
            catalog = new Catalog();
        }
        loadSource = source;
        loadMs = 0;
    }

    private synchronized void finishLoad(Catalog c, String source, long ms, String warn) {
        if (loadDone) {
            return;
        }
        loadDone = true;
        catalog = c;
        loadSource = source;
        loadMs = ms;
        if (home != null) {
            home.setBadge(catalog.updateCount());
        }
        if (warn != null) {
            alert("Thông báo", warn, home);
        } else {
            showHome();
        }
    }

    /** Luồng nạp catalog từ mạng; lỗi thì rơi về cache rồi danh mục mẫu. */
    private final class Loader implements Runnable {
        public void run() {
            long t0 = System.currentTimeMillis();
            try {
                byte[] b = Net.get(baseUrl + "/v1/c/vi/all/0.bin", netSuffix, ua, 64 * 1024);
                Catalog c = Catalog.parse(b);
                store.cachePut(b);
                finishLoad(c, "mạng", System.currentTimeMillis() - t0, null);
                return;
            } catch (IOException e) {
                String code = e.getMessage() == null ? "E12" : e.getMessage();
                String msg = "Không vào được mạng (" + code + "). ";
                byte[] cached = store.cacheGet();
                if (cached != null) {
                    try {
                        finishLoad(Catalog.parse(cached), "cache", 0, msg + "Đang dùng danh mục đã lưu.");
                        return;
                    } catch (IOException ignore) {
                        // cache hỏng, rơi xuống bản mẫu
                    }
                }
                try {
                    finishLoad(Catalog.loadEmbedded(), "danh mục mẫu trong máy", 0,
                        msg + "Đang dùng danh mục mẫu.");
                } catch (IOException e2) {
                    finishLoad(new Catalog(), "trống", 0, msg);
                }
            }
        }
    }

    // ---------- hành động từ màn hình chính ----------

    void homeAction(int act, int sel) {
        if (catalog == null) {
            return;
        }
        if (act == HomeCanvas.ACT_EXIT) {
            exit();
        } else if (act == HomeCanvas.ACT_SEARCH) {
            openSearch();
        } else if (act == HomeCanvas.ACT_OPTIONS) {
            openOptions();
        } else if (act == HomeCanvas.ACT_OPEN) {
            switch (sel) {
                case LIST_APPS:
                    showList("Ứng dụng", catalog.apps());
                    break;
                case LIST_GAMES:
                    showList("Trò chơi", catalog.games());
                    break;
                case LIST_CATS:
                    openCategories();
                    break;
                case LIST_UPDATES:
                    showList("Cập nhật", catalog.updates());
                    break;
                default:
                    showList("Yêu thích", catalog.favorites(store));
                    break;
            }
        }
    }

    // ---------- danh sách ----------

    private void showList(String title, Vector items) {
        if (items.size() == 0) {
            alert("Thông báo", "Chưa có mục nào.", display.getCurrent());
            return;
        }
        listItems = items;
        fList = new List(Vi.tr(title) + " (" + items.size() + ")", Choice.IMPLICIT);
        for (int i = 0; i < items.size(); i++) {
            AppInfo a = (AppInfo) items.elementAt(i);
            StringBuffer sb = new StringBuffer();
            if (store.isFavorite(a.id)) {
                sb.append("* ");
            }
            sb.append(a.name);
            if (a.has(AppInfo.F_UPDATE)) {
                sb.append(" (cập nhật)");
            } else if (a.has(AppInfo.F_NEW)) {
                sb.append(" (mới)");
            }
            fList.append(Vi.tr(sb.toString()), null);
        }
        fList.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 1));
        fList.setCommandListener(this);
        show(fList);
    }

    private void openCategories() {
        fCats = new List(Vi.tr("Danh mục"), Choice.IMPLICIT);
        for (int i = 0; i < Catalog.CATEGORIES.length; i++) {
            fCats.append(Vi.tr(Catalog.CATEGORIES[i]) + " (" + catalog.byCategory(i).size() + ")", null);
        }
        fCats.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 1));
        fCats.setCommandListener(this);
        show(fCats);
    }

    // ---------- chi tiết ----------

    private static String money(int vnd) {
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

    private void showDetail(AppInfo a) {
        cur = a;
        fDetail = new Form(Vi.tr(a.name));
        fDetail.append(new StringItem(null, Vi.tr(a.desc) + "\n"));
        fDetail.append(new StringItem(Vi.tr("Phiên bản: "), a.version + "\n"));
        fDetail.append(new StringItem(Vi.tr("Dung lượng: "), a.sizeKb + " KB\n"));
        fDetail.append(new StringItem(Vi.tr("Giá: "),
            Vi.tr(a.isFree() ? "Miễn phí" : money(a.price)) + "\n"));
        if (a.has(AppInfo.F_NEEDNET)) {
            fDetail.append(new StringItem(null, Vi.tr("Cần kết nối mạng khi chạy.\n")));
        }
        cInstall = null;
        if (a.path.length() > 0 && a.isFree()) {
            cInstall = new Command(Vi.tr("Cài đặt"), Command.OK, 1);
            fDetail.addCommand(cInstall);
        } else if (a.path.length() == 0) {
            fDetail.append(new StringItem(null, Vi.tr("Mục mẫu: chưa có file cài đặt.\n")));
        } else {
            fDetail.append(new StringItem(null, Vi.tr("App trả phí: tính năng mua sẽ có ở bản sau.\n")));
        }
        cFav = new Command(Vi.tr(store.isFavorite(a.id) ? "Bỏ yêu thích" : "Yêu thích"),
            Command.ITEM, 2);
        fDetail.addCommand(cFav);
        fDetail.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 1));
        fDetail.setCommandListener(this);
        show(fDetail);
    }

    private void install(AppInfo a) {
        String url = a.path;
        if (!url.startsWith("http")) {
            if (baseUrl.length() == 0) {
                alert("Thông báo", "Chưa cấu hình máy chủ (bản thử), chưa cài được.", fDetail);
                return;
            }
            url = baseUrl + a.path;
        }
        store.save();
        try {
            if (platformRequest(url)) {
                // máy yêu cầu thoát app trước khi cài
                exit();
            }
        } catch (ConnectionNotFoundException e) {
            alert("Thông báo", "Máy không mở được liên kết. Gõ địa chỉ này vào trình duyệt: " + url,
                fDetail);
        }
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
        Vector r = catalog.search(fSearch.getString());
        if (r.size() == 0) {
            alert("Thông báo", "Không tìm thấy.", fSearch);
        } else {
            showList("Kết quả", r);
        }
    }

    // ---------- tùy chọn, cài đặt, thông tin ----------

    private void openOptions() {
        fOptions = new List(Vi.tr("Tùy chọn"), Choice.IMPLICIT);
        fOptions.append(Vi.tr("Cài đặt"), null);
        fOptions.append(Vi.tr("Thông tin máy"), null);
        fOptions.append(Vi.tr("Giới thiệu"), null);
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
        cgOpts.append(Vi.tr("Xóa danh mục đã lưu"), null);
        cgOpts.setSelectedIndex(0, home != null && homeIsFull());
        fSettings.append(cgOpts);
        cSave = new Command(Vi.tr("Lưu"), Command.OK, 1);
        fSettings.addCommand(cSave);
        fSettings.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 2));
        fSettings.setCommandListener(this);
        show(fSettings);
    }

    private boolean homeIsFull() {
        return store.fullscreen == 1 || (store.fullscreen == 0 && jadFullscreen);
    }

    private void saveSettings() {
        Vi.mode = cgFont.getSelectedIndex() == 1 ? Vi.MODE_PLAIN : Vi.MODE_ACCENT;
        store.fullscreen = cgOpts.isSelected(0) ? 1 : 2;
        if (cgOpts.isSelected(1)) {
            store.cacheClear();
        }
        store.save();
        home = newHome();
        if (catalog != null) {
            home.setBadge(catalog.updateCount());
        }
        stack.removeAllElements();
        Alert a = new Alert(Vi.tr("Cài đặt"), Vi.tr("Đã lưu."), null, AlertType.INFO);
        a.setTimeout(1500);
        display.setCurrent(a, home);
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
        fInfo.append(line("Danh mục", loadSource + (loadMs > 0 ? " (" + loadMs + " ms)" : "")));
        fInfo.append(line("Số mục", catalog == null ? "0" : Integer.toString(catalog.size())));
        fInfo.append(line("Máy chủ", baseUrl.length() == 0 ? "(chưa cấu hình)" : baseUrl));
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
            Vi.tr("Cửa hàng ứng dụng Java S60 by AOW.VN.\nBản dựng đầu tiên, dùng danh mục mẫu.\n")));
        fAbout.addCommand(new Command(Vi.tr("Quay lại"), Command.BACK, 1));
        fAbout.setCommandListener(this);
        show(fAbout);
    }

    // ---------- nhận lệnh ----------

    public void commandAction(Command c, Displayable d) {
        if (d == home) {
            if (c == home.cmdSearch) {
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
            beginLoad();
            return;
        }
        if (d == fLoad) {
            if (c == cSkip) {
                loadEmbedded("danh mục mẫu trong máy");
                synchronized (this) {
                    loadDone = true;
                }
                showHome();
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
                openSettings();
            } else if (i == 1) {
                showInfo();
            } else {
                showAbout();
            }
        } else if (d == fList && c == List.SELECT_COMMAND) {
            int i = fList.getSelectedIndex();
            if (i >= 0 && i < listItems.size()) {
                showDetail((AppInfo) listItems.elementAt(i));
            }
        } else if (d == fCats && c == List.SELECT_COMMAND) {
            int i = fCats.getSelectedIndex();
            if (i >= 0) {
                showList(Catalog.CATEGORIES[i], catalog.byCategory(i));
            }
        } else if (d == fDetail) {
            if (c == cInstall) {
                install(cur);
            } else if (c == cFav) {
                store.toggleFavorite(cur.id);
                showDetailRefresh();
            }
        } else if (d == fSearch && c == cFind) {
            doSearch();
        } else if (d == fSettings && c == cSave) {
            saveSettings();
        }
    }

    /** Dựng lại trang chi tiết sau khi đổi yêu thích, không đẩy thêm vào ngăn xếp. */
    private void showDetailRefresh() {
        int n = stack.size();
        if (n > 0) {
            Displayable prev = (Displayable) stack.elementAt(n - 1);
            stack.removeElementAt(n - 1);
            display.setCurrent(prev);
        }
        showDetail(cur);
    }
}
