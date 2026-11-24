package vn.aowvn.monika;

import java.util.Timer;
import java.util.TimerTask;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Danh sách có phân trang vẽ theo ngôn ngữ giao diện web: thanh tiêu đề tím, tab Mới / Hot / Hôm nay / Tuần /
 * Tải nhiều, huy hiệu MỚI / HOT, thẻ (VH, MOD...), số lượt tải, hạng 1-2-3, "Trang sau" ở cuối.
 *
 * Màn rộng từ 170 px (trừ danh mục): lưới 2 cột (3 cột từ 420 px), mỗi ô có ảnh minh họa của game, icon, tên,
 * dung lượng; chiều cao ô chia sao cho mỗi màn hình hiện đúng số hàng nguyên (E72 320x240: một hàng = 2 game
 * mỗi lần cuộn). Ảnh tải nền theo ô đang hiện + 1 hàng dự phòng, ô ra xa thì giải phóng bộ nhớ.
 * Màn hẹp hơn: danh sách dòng như bản 0.2 (dòng đánh số 1-9 bấm nhanh, dòng đang chọn tô cam).
 */
final class ListCanvas extends BaseCanvas {
    static final int LOADING = 0;
    static final int READY = 1;
    static final int ERROR = 2;

    private final MonikaMidlet app;
    private final boolean full;
    final Command cmdOpen;
    final Command cmdSearch;
    final Command cmdBack;

    /** Để midlet nhận biết yêu cầu nào còn hợp lệ. */
    final Object token = new Object();
    /** Khóa danh sách hiện tại (app, game, new, c3, s:abc...). */
    String key = "";
    /** Khóa gửi máy chủ (key + hậu tố lọc màn hình); đổi sang key nếu bộ lọc cho kết quả rỗng. */
    String reqKey = "";
    /** Danh mục: 0 = cấp 1 (nhóm), 1 = thể loại game, 2 = thể loại app. */
    int group;
    /** 0 = danh sách app, 1 = danh mục, 2 = yêu thích (cục bộ, không phân trang). */
    int kind;
    String title = "";
    String[] tabLabels;
    /** Nhãn tab ngắn dùng khi nhãn đầy đủ không vừa bề ngang (máy hẹp); null = không có. */
    String[] tabShort;
    String[] tabKeys;
    int tab;
    int state = LOADING;
    String err = "";
    int page;
    int pages = 1;
    /** Yêu cầu đang chờ (do midlet đặt), dùng để bỏ kết quả cũ. */
    Object pending;
    /** Dữ liệu gắn với dòng: danh sách AppInfo hoặc id danh mục. */
    java.util.Vector items = new java.util.Vector();
    int[] ids = new int[0];
    /** True nếu danh sách chỉ để mở thẳng mục đầu tiên (banner trỏ tới một app). */
    boolean openOnly;

    private String[] names = new String[0];
    private String[] subs = new String[0];
    private int[] flags = new int[0];
    private int[] badges = new int[0];
    /** Icon game (32x32 từ máy chủ), thu nhỏ theo cỡ dòng khi vẽ. */
    private Image[] icons = new Image[0];
    private Image[] scaled = new Image[0];
    private int[] scaledPx = new int[0];
    private boolean showIcons;
    /** True nếu đây là trang đầu của bảng xếp hạng (top1/top7/dl/hot): 3 mục đầu có huy hiệu hạng. */
    private boolean ranked;
    private int iconPx;

    // lưới ảnh
    private Image[] shots = new Image[0];
    private boolean[] reqShot = new boolean[0];
    private boolean[] reqIcon = new boolean[0];
    private boolean noShots;
    private int gen;
    private boolean grid;
    private boolean needLoad;
    private boolean snap;
    private int scrollY;
    private int cols = 2;
    private int gap = 4;
    private int cellW;
    private int cellH;
    private int thW;
    private int thH;
    private int pagH;
    private int nameLines = 1;
    private int areaH;
    private int paintW;
    private int vl0;
    private int vl1;
    private int stickyCol;
    private int pressX;
    private int pressY = -1;
    private int pressScroll;
    private boolean moved;
    private boolean hasPrev;
    private boolean hasNext;
    private int sel;
    private int top;
    private int tick;
    private Timer timer;

    // bố cục
    private int hh;
    private int tabH;
    private int y0;
    private int barH;
    private int rowH;
    private int visible;
    private boolean two;

    ListCanvas(MonikaMidlet app, boolean full, String title, int kind) {
        this.app = app;
        this.full = full;
        this.title = title;
        this.kind = kind;
        cmdOpen = new Command(Vi.tr("Mở"), Command.OK, 1);
        cmdSearch = new Command(Vi.tr("Tìm ứng dụng"), Command.SCREEN, 1);
        cmdBack = new Command(Vi.tr("Quay lại"), Command.BACK, 1);
        setFullScreenMode(full);
        if (!full) {
            addCommand(cmdOpen);
            addCommand(cmdSearch);
            addCommand(cmdBack);
        }
    }

    // ---------- dữ liệu ----------

    void setLoading() {
        state = LOADING;
        repaint();
    }

    void setError(String code) {
        state = ERROR;
        err = code;
        repaint();
    }

    void setData(String[] names, String[] subs, int[] flags, int[] badges, int page, int total, int perPage) {
        this.names = names;
        this.subs = subs;
        this.flags = flags;
        this.badges = badges;
        icons = new Image[names.length];
        scaled = new Image[names.length];
        scaledPx = new int[names.length];
        shots = new Image[names.length];
        reqShot = new boolean[names.length];
        reqIcon = new boolean[names.length];
        gen++;
        scrollY = 0;
        stickyCol = 0;
        needLoad = true;
        snap = true;
        showIcons = false;
        if (kind != 1) {
            for (int i = 0; i < flags.length; i++) {
                if ((flags[i] & AppInfo.F_ICON) != 0) {
                    showIcons = true;
                    break;
                }
            }
        }
        this.page = page;
        ranked = kind == 0 && page == 0 && rankKey(key);
        this.pages = perPage <= 0 || total <= 0 ? 1 : (total + perPage - 1) / perPage;
        hasPrev = page > 0;
        hasNext = perPage > 0 && (page + 1) * perPage < total;
        state = READY;
        sel = hasPrev ? 1 : 0;
        if (sel >= rowCount()) {
            sel = 0;
        }
        top = 0;
        repaint();
    }

    /** Khóa xếp hạng: lần đổi cách xếp cuối cùng (base hoặc mod) là top1, top7, dl hoặc hot. */
    static boolean rankKey(String key) {
        if (key == null || key.startsWith("s:")) {
            return false;
        }
        boolean r = false;
        int pos = 0;
        while (pos <= key.length()) {
            int dot = key.indexOf('.', pos);
            String part = dot < 0 ? key.substring(pos) : key.substring(pos, dot);
            if ("top1".equals(part) || "top7".equals(part) || "dl".equals(part) || "hot".equals(part)) {
                r = true;
            } else if ("new".equals(part) || "upd".equals(part)) {
                r = false;
            }
            if (dot < 0) {
                break;
            }
            pos = dot + 1;
        }
        return r;
    }

    /** Nhận icon đã tải của mục k (thread an toàn: chỉ gán tham chiếu rồi vẽ lại). */
    void setIcon(int k, Image img) {
        if (k >= 0 && k < icons.length) {
            icons[k] = img;
            scaled[k] = null;
            repaint();
        }
    }

    /** Nhận ảnh minh họa của mục k (gen khác = dữ liệu cũ, bỏ; ô đã bị giải phóng cũng bỏ). */
    void setShot(int k, int g, Image img) {
        if (g == gen && k >= 0 && k < shots.length && reqShot[k] && !noShots) {
            // máy chủ đã thu vừa khung; ảnh từ nguồn ngoài có thể lớn hơn khung: thu nhỏ giữ tỉ lệ
            int iw = img.getWidth();
            int ih = img.getHeight();
            if ((iw > thW || ih > thH) && thW > 0 && thH > 0) {
                int dw = iw * thH > ih * thW ? thW : iw * thH / ih;
                int dh = iw * thH > ih * thW ? ih * thW / iw : thH;
                try {
                    img = scaleTo(img, Math.max(1, dw), Math.max(1, dh));
                } catch (OutOfMemoryError e) {
                    // giữ ảnh gốc, ô sẽ cắt phần thừa
                }
            }
            shots[k] = img;
            repaint();
        }
    }

    /** Hết bộ nhớ khi giải mã ảnh: thôi dùng ảnh minh họa cho danh sách này. */
    void dropShots() {
        noShots = true;
        for (int i = 0; i < shots.length; i++) {
            shots[i] = null;
        }
        repaint();
    }

    /** Lưới chỉ dùng cho danh sách app (không phải danh mục) trên màn rộng từ 170 px. */
    boolean useGrid() {
        return kind != 1 && getWidth() >= 170;
    }

    /** Báo cần xin ảnh cho các ô đang hiện ở lần vẽ tới. */
    void wantLoad() {
        needLoad = true;
        repaint();
    }

    int itemCount() {
        return names.length;
    }

    private int rowCount() {
        return (hasPrev ? 1 : 0) + names.length + (hasNext ? 1 : 0);
    }

    /** Chỉ số mục (không tính dòng Trang trước/sau) của dòng i, hoặc -1. */
    private int itemOf(int row) {
        int k = row - (hasPrev ? 1 : 0);
        return k >= 0 && k < names.length ? k : -1;
    }

    protected void showNotify() {
        if (timer == null) {
            timer = new Timer();
            timer.schedule(new TimerTask() {
                public void run() {
                    if (state == LOADING) {
                        tick++;
                        repaint();
                    }
                }
            }, 400L, 400L);
        }
    }

    protected void hideNotify() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    protected void sizeChanged(int w, int h) {
        repaint();
    }

    // ---------- bố cục ----------

    private void layout(int w, int h) {
        Font fb = Theme.bold();
        Font f = Theme.body();
        Font fs = Theme.small();
        hh = fb.getHeight() + 8;
        tabH = tabLabels == null ? 0 : fs.getHeight() + 8;
        barH = full ? fs.getHeight() + 10 : 0;
        y0 = hh + tabH;
        grid = kind != 1 && w >= 170;
        if (grid) {
            layoutGrid(w, h - y0 - barH);
            return;
        }
        two = h >= 200;
        rowH = two ? f.getHeight() + fs.getHeight() + 8 : f.getHeight() + 6;
        visible = Math.max(1, (h - y0 - barH) / rowH);
        iconPx = showIcons && w >= 150 ? Math.min(32, rowH - 4) : 0;
        if (iconPx < 14) {
            iconPx = 0;
        }
        int n = rowCount();
        if (sel >= n) {
            sel = Math.max(0, n - 1);
        }
        if (sel < top) {
            top = sel;
        }
        if (sel >= top + visible) {
            top = sel - visible + 1;
        }
    }

    /** Chia lưới: số hàng nguyên mỗi màn hình, ảnh 4:3 (tối đa 10:9) trong ô, tên 1-2 dòng + dòng dung lượng. */
    private void layoutGrid(int w, int ah) {
        areaH = ah;
        paintW = w;
        cols = w >= 420 ? 3 : 2;
        cellW = (w - gap * (cols + 1)) / cols;
        int tw = cellW - 4;
        Font fb = Theme.smallBold();
        Font fs = Theme.small();
        int t1 = fb.getHeight() + fs.getHeight() + 3;
        int t2 = 2 * fb.getHeight() + fs.getHeight() + 3;
        int th0 = tw * 3 / 4;
        int r1 = Math.max(1, ah / (th0 + t1 + 8 + gap));
        int r2 = ah / (th0 + t2 + 8 + gap);
        int rows;
        if (r2 >= 1 && r2 == r1) {
            nameLines = 2;
            rows = r2;
        } else {
            nameLines = 1;
            rows = r1;
        }
        int textH = nameLines == 2 ? t2 : t1;
        cellH = Math.max(ah / rows - gap, textH + 8 + 24);
        thW = tw;
        thH = Math.min(cellH - textH - 8, tw * 9 / 10);
        pagH = Theme.body().getHeight() + 14;
        int n = rowCount();
        if (sel >= n) {
            sel = Math.max(0, n - 1);
        }
        if (snap) {
            snap = false;
            ensureVisible();
        }
        clampScroll();
    }

    private int off() {
        return hasPrev ? 1 : 0;
    }

    private int lineCount() {
        return off() + (names.length + cols - 1) / cols + (hasNext ? 1 : 0);
    }

    private boolean pagerLine(int l) {
        return (hasPrev && l == 0) || (hasNext && l == lineCount() - 1);
    }

    private int lineH(int l) {
        return pagerLine(l) ? pagH : cellH;
    }

    private int lineTop(int l) {
        int y = gap;
        for (int i = 0; i < l; i++) {
            y += lineH(i) + gap;
        }
        return y;
    }

    private int lineOfRow(int row) {
        int k = itemOf(row);
        if (k >= 0) {
            return off() + k / cols;
        }
        return hasPrev && row == 0 ? 0 : lineCount() - 1;
    }

    private int rowAt(int line, int col) {
        if (hasPrev && line == 0) {
            return 0;
        }
        if (hasNext && line == lineCount() - 1) {
            return rowCount() - 1;
        }
        int k = (line - off()) * cols + Math.min(col, cols - 1);
        if (k >= names.length) {
            k = names.length - 1;
        }
        return k + off();
    }

    private void clampScroll() {
        int max = Math.max(0, lineTop(lineCount()) - areaH);
        if (scrollY > max) {
            scrollY = max;
        }
        if (scrollY < 0) {
            scrollY = 0;
        }
    }

    /** Cuộn sao cho dòng của ô đang chọn nằm trọn trong màn hình. */
    private void ensureVisible() {
        if (rowCount() == 0) {
            return;
        }
        int l = lineOfRow(sel);
        int t = lineTop(l) - gap;
        int b = lineTop(l) + lineH(l) + gap;
        if (t < scrollY) {
            scrollY = t;
        }
        if (b > scrollY + areaH) {
            scrollY = b - areaH;
        }
    }

    private void moveV(int d) {
        int n = rowCount();
        if (n == 0) {
            return;
        }
        int lines = lineCount();
        int t = (lineOfRow(sel) + d + lines) % lines;
        sel = rowAt(t, stickyCol);
    }

    /** Sang ô bên cạnh; false nếu đã ở mép (để phím trái/phải đổi tab hoặc trang). */
    private boolean moveH(int d) {
        int k = itemOf(sel);
        if (k < 0) {
            return false;
        }
        int c = k % cols;
        if (d < 0 ? c == 0 : (c == cols - 1 || k + 1 >= names.length)) {
            return false;
        }
        sel += d;
        stickyCol = (k + d) % cols;
        return true;
    }

    private int hit(int x, int y) {
        int ly = y - y0 + scrollY;
        for (int l = 0; l < lineCount(); l++) {
            int t = lineTop(l);
            if (ly >= t && ly < t + lineH(l)) {
                int col = Math.min(cols - 1, Math.max(0, (x - gap / 2) / (cellW + gap)));
                int row = rowAt(l, col);
                return pagerLine(l) || itemOf(row) >= 0 ? row : -1;
            }
        }
        return -1;
    }

    /** Xin ảnh cho ô đang hiện (ưu tiên) và 1 hàng liền kề, giải phóng ảnh các ô đã xa. */
    private void requestVisible() {
        int n = names.length;
        if (n == 0 || vl1 < vl0) {
            return;
        }
        int k0 = Math.max(0, (vl0 - off()) * cols);
        int k1 = Math.min(n - 1, (vl1 - off() + 1) * cols - 1);
        for (int pass = 0; pass < 2; pass++) {
            int a = pass == 0 ? k0 : Math.max(0, k0 - cols);
            int b = pass == 0 ? k1 : Math.min(n - 1, k1 + cols);
            for (int k = a; k <= b; k++) {
                if (pass == 1 && k >= k0 && k <= k1) {
                    continue;
                }
                boolean shot = !noShots && !reqShot[k] && (flags[k] & AppInfo.F_SHOT) != 0 && thW >= 16 && thH >= 12;
                boolean icon = !reqIcon[k] && icons[k] == null && (flags[k] & AppInfo.F_ICON) != 0;
                if (shot || icon) {
                    reqShot[k] = reqShot[k] || shot;
                    reqIcon[k] = reqIcon[k] || icon;
                    app.listLoad(this, k, gen, thW, thH, pass == 0, icon, shot);
                }
            }
        }
        int lo = k0 - 3 * cols;
        int hi = k1 + 3 * cols;
        for (int k = 0; k < n; k++) {
            if ((k < lo || k > hi) && (shots[k] != null || reqShot[k])) {
                shots[k] = null;
                reqShot[k] = false;
            }
        }
    }

    // ---------- vẽ ----------

    protected void paint(Graphics g) {
        paintTo(g, getWidth(), getHeight());
    }

    void paintTo(Graphics g, int w, int h) {
        layout(w, h);
        Theme.vgradient(g, 0, 0, w, h, Theme.BG_TOP, Theme.BG_BOT);
        paintHeader(g, w);
        if (tabH > 0) {
            paintTabs(g, w, hh);
        }
        g.setClip(0, y0, w, h - y0 - barH);
        if (state == LOADING) {
            paintMessage(g, w, h, Vi.tr("Đang tải") + dots(), null);
        } else if (state == ERROR) {
            paintMessage(g, w, h, Vi.tr("Không vào được mạng (" + err + ")"),
                Vi.tr("Bấm giữa để thử lại"));
        } else if (rowCount() == 0) {
            paintMessage(g, w, h, Vi.tr("Chưa có mục nào."), null);
        } else if (grid) {
            paintGrid(g, w);
        } else {
            int y = y0;
            for (int i = top; i < top + visible && i < rowCount(); i++) {
                paintRow(g, i, y, w);
                y += rowH;
            }
        }
        g.setClip(0, 0, w, h);
        if (grid && needLoad && state == READY) {
            needLoad = false;
            requestVisible();
        }
        if (full) {
            paintBar(g, w, h);
        }
    }

    private String dots() {
        String d = "...";
        return d.substring(0, tick % 4 > 3 ? 3 : tick % 4);
    }

    private void paintHeader(Graphics g, int w) {
        Font fb = Theme.bold();
        Font fs = Theme.small();
        for (int x = 0; x < w; x++) {
            int t = x * 256 / Math.max(1, w - 1);
            g.setColor(t < 160 ? Theme.mix(0x2B0F4F, 0x5A1670, t * 256 / 160)
                : Theme.mix(0x5A1670, 0xB8307A, (t - 160) * 256 / 96));
            g.drawLine(x, 0, x, hh - 1);
        }
        g.setColor(Theme.ORANGE);
        g.fillRect(0, hh - 2, w, 2);
        String right = pages > 1 && state == READY ? (page + 1) + "/" + pages : "";
        int rw = right.length() > 0 ? fs.stringWidth(right) + 8 : 0;
        g.setFont(fb);
        g.setColor(Theme.ORANGE);
        g.drawString("M", 6, 3, Graphics.TOP | Graphics.LEFT);
        int mw = fb.stringWidth("M ");
        g.setColor(Theme.TEXT);
        g.drawString(fit(fb, Vi.tr(title), w - 6 - mw - rw - 2), 6 + mw, 3, Graphics.TOP | Graphics.LEFT);
        if (rw > 0) {
            g.setFont(fs);
            g.setColor(Theme.TEXT);
            g.drawString(right, w - 6, 3 + (fb.getHeight() - fs.getHeight()) / 2,
                Graphics.TOP | Graphics.RIGHT);
        }
    }

    /** 0 = nhãn đầy đủ vừa, 1 = nhãn ngắn vừa, 2 = chỉ hiện tab đang chọn giữa hai mũi tên (máy rất hẹp). */
    private int tabMode(int w) {
        Font fs = Theme.small();
        int n = tabLabels.length;
        int tw = w / n - 4;
        boolean longOk = true;
        boolean shortOk = tabShort != null && tabShort.length == n;
        for (int i = 0; i < n; i++) {
            if (fs.stringWidth(Vi.tr(tabLabels[i])) > tw) {
                longOk = false;
            }
            if (shortOk && fs.stringWidth(Vi.tr(tabShort[i])) > tw) {
                shortOk = false;
            }
        }
        return longOk ? 0 : shortOk ? 1 : 2;
    }

    private void paintTabs(Graphics g, int w, int y) {
        Font fs = Theme.small();
        int n = tabLabels.length;
        int mode = tabMode(w);
        g.setColor(Theme.BAR_BG);
        g.fillRect(0, y, w, tabH);
        if (mode == 2) {
            String lab = fit(fs, Vi.tr(tabLabels[tab]), w - 40);
            int lw = fs.stringWidth(lab) + 14;
            Theme.vgradient(g, (w - lw) / 2, y, lw, tabH, Theme.SEL_A, Theme.SEL_B);
            g.setFont(fs);
            g.setColor(Theme.TEXT);
            g.drawString(lab, w / 2, y + (tabH - fs.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
            int cy = y + tabH / 2;
            g.setColor(Theme.ORANGE);
            g.fillTriangle(8, cy, 14, cy - 4, 14, cy + 4);
            g.fillTriangle(w - 9, cy, w - 15, cy - 4, w - 15, cy + 4);
        } else {
            int tw = w / n;
            for (int i = 0; i < n; i++) {
                int x = i * tw;
                boolean on = i == tab;
                if (on) {
                    Theme.vgradient(g, x, y, tw, tabH, Theme.SEL_A, Theme.SEL_B);
                }
                g.setFont(fs);
                g.setColor(on ? Theme.TEXT : Theme.DIM);
                g.drawString(fit(fs, Vi.tr(mode == 1 ? tabShort[i] : tabLabels[i]), tw - 4), x + tw / 2,
                    y + (tabH - fs.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
            }
        }
        g.setColor(Theme.LINE);
        g.drawLine(0, y + tabH - 1, w - 1, y + tabH - 1);
    }

    private void paintMessage(Graphics g, int w, int h, String line1, String line2) {
        Font f = Theme.body();
        Font fs = Theme.small();
        int y = y0 + (h - y0 - barH) / 3;
        String[] ls = wrap(f, line1, w - 24);
        g.setFont(f);
        g.setColor(Theme.TEXT);
        for (int i = 0; i < ls.length; i++) {
            g.drawString(ls[i], w / 2, y, Graphics.TOP | Graphics.HCENTER);
            y += f.getHeight();
        }
        if (line2 != null) {
            y += 4;
            g.setFont(fs);
            g.setColor(Theme.DIM);
            g.drawString(line2, w / 2, y, Graphics.TOP | Graphics.HCENTER);
        }
    }

    private void paintGrid(Graphics g, int w) {
        int n = lineCount();
        vl0 = n;
        vl1 = -1;
        for (int l = 0; l < n; l++) {
            int y = y0 + lineTop(l) - scrollY;
            if (y + lineH(l) <= y0) {
                continue;
            }
            if (y >= y0 + areaH) {
                break;
            }
            if (l < vl0) {
                vl0 = l;
            }
            vl1 = l;
            if (pagerLine(l)) {
                paintPager(g, l, y, w);
                continue;
            }
            for (int c = 0; c < cols; c++) {
                int k = (l - off()) * cols + c;
                if (k >= names.length) {
                    break;
                }
                paintCell(g, k, gap + c * (cellW + gap), y);
            }
        }
    }

    private void paintPager(Graphics g, int l, int y, int w) {
        int row = hasPrev && l == 0 ? 0 : rowCount() - 1;
        boolean on = row == sel;
        g.setColor(on ? Theme.mix(Theme.SEL_A, Theme.SEL_B, 128) : 0x250E48);
        g.fillRoundRect(gap, y, w - 2 * gap, pagH, 8, 8);
        g.setColor(on ? Theme.mix(Theme.SEL_A, 0xFFFFFF, 90) : Theme.LINE);
        g.drawRoundRect(gap, y, w - 2 * gap - 1, pagH - 1, 8, 8);
        Font f = on ? Theme.bold() : Theme.body();
        g.setFont(f);
        g.setColor(on ? Theme.TEXT : Theme.ORANGE);
        g.drawString(hasPrev && l == 0 ? Vi.tr("« Trang trước") : Vi.tr("Trang sau »"), w / 2,
            y + (pagH - f.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
    }

    private void paintCell(Graphics g, int k, int x, int y) {
        boolean on = k + off() == sel;
        Font fb = Theme.smallBold();
        Font fs = Theme.small();
        g.setColor(on ? Theme.mix(Theme.SEL_A, Theme.SEL_B, 128) : 0x250E48);
        g.fillRoundRect(x, y, cellW, cellH, 8, 8);
        g.setColor(on ? Theme.mix(Theme.SEL_A, 0xFFFFFF, 90) : Theme.LINE);
        g.drawRoundRect(x, y, cellW - 1, cellH - 1, 8, 8);
        if (on) {
            g.drawRoundRect(x + 1, y + 1, cellW - 3, cellH - 3, 6, 6);
        }
        int tx = x + 2;
        int ty = y + 2;
        // ảnh minh họa (cắt giữa), chưa có thì nền gradient + icon lớn hoặc chữ cái đầu
        Image s = shots[k];
        g.clipRect(tx, ty, thW, thH);
        if (s != null) {
            Theme.vgradient(g, tx, ty, thW, thH, Theme.BAR_BG, Theme.BAR_BG);
            g.drawImage(s, tx + (thW - s.getWidth()) / 2, ty + (thH - s.getHeight()) / 2,
                Graphics.TOP | Graphics.LEFT);
        } else {
            Theme.vgradient(g, tx, ty, thW, thH, 0x4C1466, 0xA84A0C);
            int big = Math.min(48, thH - 12);
            Image ic = big >= 20 ? iconAt(k, big) : null;
            if (ic != null) {
                g.drawImage(ic, tx + (thW - big) / 2, ty + (thH - big) / 2, Graphics.TOP | Graphics.LEFT);
            } else {
                Font lf = Theme.logo(thH >= 50);
                g.setFont(lf);
                g.setColor(Theme.mix(Theme.TEXT, 0x4C1466, 96));
                g.drawString(initial(k), tx + thW / 2, ty + (thH - lf.getHeight()) / 2,
                    Graphics.TOP | Graphics.HCENTER);
            }
        }
        if (s != null) {
            int ov = Math.max(16, Math.min(28, thH / 4));
            Image ic = iconAt(k, ov);
            if (ic != null) {
                int ix = tx + 4;
                int iy = ty + thH - ov - 4;
                g.setColor(Theme.TEXT);
                g.drawRect(ix - 1, iy - 1, ov + 1, ov + 1);
                g.drawImage(ic, ix, iy, Graphics.TOP | Graphics.LEFT);
            }
        }
        g.setClip(0, y0, paintW, areaH);
        // số bấm nhanh (trái trên) và huy hiệu (phải trên)
        int bh = fs.getHeight();
        if (ranked && k < 3) {
            medal(g, tx + 3 + (bh + 4) / 2, ty + 3 + bh / 2, bh + 4, k + 1);
        } else if (k < 9 && (ranked || !numNav)) {
            g.setColor(Theme.BAR_BG);
            g.fillRoundRect(tx + 3, ty + 3, bh, bh, 6, 6);
            g.setFont(fs);
            g.setColor(Theme.ORANGE);
            g.drawString(Integer.toString(k + 1), tx + 3 + bh / 2, ty + 3, Graphics.TOP | Graphics.HCENTER);
        }
        int bcy = ty + 3 + bh / 2;
        int bright = tx + thW - 3;
        boolean flagged = true;
        if ((flags[k] & AppInfo.F_HOT) != 0) {
            pill(g, fs, "HOT", bright, bcy, Theme.SEL_B);
        } else if ((flags[k] & AppInfo.F_NEW) != 0) {
            pill(g, fs, Vi.tr("MỚI"), bright, bcy, Theme.PINK_D);
        } else if ((flags[k] & AppInfo.F_UPDATE) != 0) {
            pill(g, fs, Vi.tr("CẬP NHẬT"), bright, bcy, Theme.PINK_D);
        } else {
            flagged = false;
        }
        AppInfo ai = k < items.size() ? (AppInfo) items.elementAt(k) : null;
        if (ai != null) {
            // huy hiệu thẻ (tối đa 2: VH, MOD trước): hàng dưới cờ HOT/MỚI, hoặc hàng trên nếu không có cờ
            String[] tg = ai.tagBadges(2);
            int ry = flagged ? bcy + bh + 2 : bcy;
            int rr = bright;
            int lim = flagged ? tx + 3 : tx + 3 + bh + 6;
            if (ry + bh / 2 <= ty + thH - 3) {
                for (int i = tg.length - 1; i >= 0; i--) {
                    int bw = Math.max(fs.stringWidth(tg[i]) + 8, bh);
                    if (rr - bw < lim) {
                        break;
                    }
                    pill(g, fs, tg[i], rr, ry, tagColor(tg[i]));
                    rr -= bw + 2;
                }
            }
            String dt = AppInfo.dlText(ai.dl);
            if (dt.length() > 0 && thH >= bh + 8) {
                dlPill(g, fs, dt, tx + thW - 3, ty + thH - 3);
            }
        }
        // tên (1-2 dòng) và dung lượng
        int maxW = cellW - 8;
        int yy = ty + thH + 3;
        String[] ls = wrap(fb, Vi.tr(names[k]), maxW);
        g.setFont(fb);
        g.setColor(Theme.TEXT);
        int shown = Math.min(ls.length, nameLines);
        for (int i = 0; i < shown; i++) {
            String line = ls[i];
            if (i == shown - 1 && ls.length > shown) {
                line = line + " " + ls[i + 1];
            }
            g.drawString(fit(fb, line, maxW), x + 4, yy, Graphics.TOP | Graphics.LEFT);
            yy += fb.getHeight();
        }
        g.setFont(fs);
        g.setColor(on ? Theme.TEXT : Theme.DIM);
        g.drawString(fit(fs, Vi.tr(subs[k] == null ? "" : subs[k]), maxW), x + 4, yy, Graphics.TOP | Graphics.LEFT);
    }

    private String initial(int k) {
        String n = names[k];
        int c = 0;
        while (n != null && c < n.length() - 1 && !(Character.isDigit(n.charAt(c)) || Character.isUpperCase(n.charAt(c)) || Character.isLowerCase(n.charAt(c)))) {
            c++;
        }
        return n == null || n.length() == 0 ? "M" : n.substring(c, c + 1).toUpperCase();
    }

    /** Icon của mục k thu/phóng về px x px (nhớ lại để khỏi tính lại mỗi lần vẽ); null nếu chưa tải. */
    private Image iconAt(int k, int px) {
        Image im = icons[k];
        if (im == null) {
            return null;
        }
        if (im.getWidth() == px && im.getHeight() == px) {
            return im;
        }
        if (scaled[k] == null || scaledPx[k] != px) {
            scaled[k] = scale(im, px);
            scaledPx[k] = px;
        }
        return scaled[k];
    }

    private void paintRow(Graphics g, int i, int y, int w) {
        boolean on = i == sel;
        Font f = on ? Theme.bold() : Theme.body();
        Font fs = Theme.small();
        if (on) {
            Theme.vgradient(g, 0, y, w, rowH, Theme.SEL_A, Theme.SEL_B);
            g.setColor(Theme.mix(Theme.SEL_A, 0xFFFFFF, 80));
            g.drawLine(0, y, w - 1, y);
        } else {
            g.setColor(Theme.LINE);
            g.drawLine(6, y + rowH - 1, w - 7, y + rowH - 1);
        }
        int k = itemOf(i);
        int cy = y + rowH / 2;
        g.setFont(f);
        if (k < 0) {
            g.setColor(Theme.TEXT);
            String s = hasPrev && i == 0 ? Vi.tr("« Trang trước") : Vi.tr("Trang sau »");
            if (!on) {
                g.setColor(Theme.ORANGE);
            }
            g.drawString(s, w / 2, cy - f.getHeight() / 2, Graphics.TOP | Graphics.HCENTER);
            return;
        }
        // số thứ tự bấm nhanh
        int x = 6;
        int md = ranked ? Math.min(f.getHeight(), rowH - 4) : 0;
        if (ranked && k < 3) {
            medal(g, x + md / 2, cy, md, k + 1);
        } else if (k < 9 && (ranked || !numNav)) {
            g.setColor(on ? Theme.TEXT : Theme.ORANGE);
            String n = Integer.toString(k + 1);
            g.drawString(n, x, cy - f.getHeight() / 2, Graphics.TOP | Graphics.LEFT);
        }
        x += (ranked ? md : numNav ? 0 : f.stringWidth("9")) + 6;
        if (iconPx > 0) {
            paintIcon(g, k, x, cy - iconPx / 2, f);
            x += iconPx + 6;
        }
        // chevron bên phải
        int cx = w - 10;
        g.setColor(Theme.TEXT);
        for (int t = 0; t < 2; t++) {
            g.drawLine(cx - 3 + t, cy - 5, cx + 1 + t, cy);
            g.drawLine(cx + 1 + t, cy, cx - 3 + t, cy + 5);
        }
        int right = cx - 8;
        if (badges[k] > 0) {
            right -= pill(g, fs, Integer.toString(badges[k]), right, cy, Theme.PINK_D) + 4;
        } else if (w >= 200) {
            if ((flags[k] & AppInfo.F_UPDATE) != 0) {
                right -= pill(g, fs, Vi.tr("CẬP NHẬT"), right, cy, Theme.PINK_D) + 4;
            } else if ((flags[k] & AppInfo.F_NEW) != 0) {
                right -= pill(g, fs, Vi.tr("MỚI"), right, cy, Theme.PINK_D) + 4;
            } else if ((flags[k] & AppInfo.F_HOT) != 0) {
                right -= pill(g, fs, "HOT", right, cy, Theme.SEL_B) + 4;
            }
        }
        g.setFont(f);
        g.setColor(Theme.TEXT);
        if (two && subs[k] != null && subs[k].length() > 0) {
            g.drawString(fit(f, Vi.tr(names[k]), right - x), x, y + 3, Graphics.TOP | Graphics.LEFT);
            g.setFont(fs);
            g.setColor(on ? Theme.TEXT : Theme.DIM);
            g.drawString(fit(fs, Vi.tr(subs[k]), right - x), x, y + 3 + f.getHeight(),
                Graphics.TOP | Graphics.LEFT);
        } else {
            g.drawString(fit(f, Vi.tr(names[k]), right - x), x, cy - f.getHeight() / 2,
                Graphics.TOP | Graphics.LEFT);
        }
    }

    /** Icon game; chưa có thì vẽ ô chữ cái đầu cho dòng không bị lệch. */
    private void paintIcon(Graphics g, int k, int x, int y, Font f) {
        Image im = icons[k];
        if (im != null) {
            if (im.getWidth() != iconPx || im.getHeight() != iconPx) {
                if (scaled[k] == null || scaledPx[k] != iconPx) {
                    scaled[k] = scale(im, iconPx);
                    scaledPx[k] = iconPx;
                }
                im = scaled[k];
            }
            g.drawImage(im, x, y, Graphics.TOP | Graphics.LEFT);
            return;
        }
        g.setColor(Theme.BAR_BG);
        g.fillRoundRect(x, y, iconPx, iconPx, 8, 8);
        String n = names[k];
        if (n != null && n.length() > 0) {
            int c = 0;
            while (c < n.length() - 1 && !(Character.isDigit(n.charAt(c)) || Character.isUpperCase(n.charAt(c)) || Character.isLowerCase(n.charAt(c)))) {
                c++;
            }
            g.setFont(f);
            g.setColor(Theme.ORANGE);
            g.drawString(n.substring(c, c + 1).toUpperCase(), x + iconPx / 2, y + (iconPx - f.getHeight()) / 2,
                Graphics.TOP | Graphics.HCENTER);
        }
    }

    /** Thu về px x px bằng trung bình diện tích (alpha nhân trước); phóng to thì lấy mẫu gần nhất. */
    static Image scale(Image src, int px) {
        return scaleTo(src, px, px);
    }

    static Image scaleTo(Image src, int dw, int dh) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int[] in = new int[sw * sh];
        src.getRGB(in, 0, sw, 0, 0, sw, sh);
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int y0 = y * sh / dh;
            int y1 = Math.max(y0 + 1, (y + 1) * sh / dh);
            for (int x = 0; x < dw; x++) {
                int x0 = x * sw / dw;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / dw);
                int r = 0;
                int g = 0;
                int b = 0;
                int a = 0;
                int n = 0;
                for (int yy = y0; yy < y1 && yy < sh; yy++) {
                    for (int xx = x0; xx < x1 && xx < sw; xx++) {
                        int p = in[yy * sw + xx];
                        int al = (p >>> 24) & 255;
                        r += ((p >> 16) & 255) * al;
                        g += ((p >> 8) & 255) * al;
                        b += (p & 255) * al;
                        a += al;
                        n++;
                    }
                }
                if (a > 0 && n > 0) {
                    out[y * dw + x] = ((a / n) << 24) | ((r / a) << 16) | ((g / a) << 8) | (b / a);
                }
            }
        }
        return Image.createRGBImage(out, dw, dh, true);
    }

    private void paintBar(Graphics g, int w, int h) {
        int y = h - barH;
        g.setColor(Theme.BAR_BG);
        g.fillRect(0, y, w, barH);
        g.setColor(Theme.LINE);
        g.drawLine(0, y, w - 1, y);
        Font fs = Theme.small();
        Font bf = Theme.bold();
        String left = Vi.tr("Mở");
        String right = Vi.tr("Quay lại");
        g.setFont(bf);
        g.setColor(Theme.TEXT);
        int ty = y + (barH - bf.getHeight()) / 2;
        g.drawString(left, 4, ty, Graphics.TOP | Graphics.LEFT);
        g.drawString(right, w - 4, ty, Graphics.TOP | Graphics.RIGHT);
        int avail = w - bf.stringWidth(left) - bf.stringWidth(right) - 20;
        int is = fs.getHeight() - 2;
        String mid = Vi.tr("Tìm ứng dụng");
        int pw = is + 6 + fs.stringWidth(mid) + 14;
        if (pw > avail) {
            mid = Vi.tr("Tìm");
            pw = is + 6 + fs.stringWidth(mid) + 14;
        }
        if (pw > avail) {
            mid = null;
            pw = is + 14;
        }
        int px = (w - pw) / 2;
        g.setColor(Theme.LINE);
        g.drawRoundRect(px, y + 2, pw - 1, barH - 5, 10, 10);
        Icons.draw(g, Icons.SEARCH, px + 7, y + (barH - is) / 2, is, Theme.TEXT);
        if (mid != null) {
            g.setFont(fs);
            g.setColor(Theme.TEXT);
            g.drawString(mid, px + 7 + is + 5, y + (barH - fs.getHeight()) / 2,
                Graphics.TOP | Graphics.LEFT);
        }
    }

    // ---------- thao tác ----------

    private void activate(int row) {
        if (state == ERROR) {
            app.listRetry(this);
            return;
        }
        if (state != READY || row < 0 || row >= rowCount()) {
            return;
        }
        int k = itemOf(row);
        if (k >= 0) {
            app.listOpen(this, k);
        } else if (hasPrev && row == 0) {
            app.listPage(this, -1);
        } else {
            app.listPage(this, 1);
        }
    }

    /** Mở dòng đang chọn (lệnh Mở ở chế độ thường). */
    void openSelected() {
        activate(sel);
    }

    private void switchTab(int d) {
        if (tabLabels == null) {
            return;
        }
        int n = tabLabels.length;
        app.listTab(this, (tab + n + d) % n);
    }

    protected void keyPressed(int code) {
        layout(getWidth(), getHeight());
        int d = digit(code);
        if (!numNav && d >= 1) {
            int k = d - 1;
            if (state == READY && k < names.length) {
                sel = k + (hasPrev ? 1 : 0);
                snap = true;
                repaint();
                app.listOpen(this, k);
            }
            return;
        }
        if (isStar(code)) {
            app.listSearch();
            return;
        }
        if (isPound(code)) {
            if (hasNext) {
                app.listPage(this, 1);
            }
            return;
        }
        if (full) {
            int soft = softKey(code);
            if (soft == SOFT_LEFT) {
                activate(sel);
                return;
            }
            if (soft == SOFT_RIGHT) {
                app.listBack();
                return;
            }
        }
        int ga = dir(code);
        if (isFire(code) || ga == Canvas.FIRE) {
            activate(sel);
            return;
        }
        if (grid && state == READY && rowCount() > 0) {
            boolean did = false;
            if (ga == Canvas.UP || ga == Canvas.DOWN) {
                moveV(ga == Canvas.UP ? -1 : 1);
                did = true;
            } else if (ga == Canvas.LEFT || ga == Canvas.RIGHT) {
                did = moveH(ga == Canvas.LEFT ? -1 : 1);
            }
            if (did) {
                snap = true;
                needLoad = true;
                repaint();
                return;
            }
        }
        int n = rowCount();
        if (ga == Canvas.UP && n > 0) {
            sel = (sel + n - 1) % n;
            repaint();
        } else if (ga == Canvas.DOWN && n > 0) {
            sel = (sel + 1) % n;
            repaint();
        } else if (ga == Canvas.LEFT) {
            if (tabLabels != null) {
                switchTab(-1);
            } else if (hasPrev) {
                app.listPage(this, -1);
            }
        } else if (ga == Canvas.RIGHT) {
            if (tabLabels != null) {
                switchTab(1);
            } else if (hasNext) {
                app.listPage(this, 1);
            }
        }
    }

    protected void keyRepeated(int code) {
        int ga = dir(code);
        if (ga == Canvas.UP || ga == Canvas.DOWN) {
            keyPressed(code);
        }
    }

    protected void pointerPressed(int x, int y) {
        int w = getWidth();
        int h = getHeight();
        layout(w, h);
        if (full && y >= h - barH) {
            if (x < w / 4) {
                activate(sel);
            } else if (x > w - w / 4) {
                app.listBack();
            } else {
                app.listSearch();
            }
            return;
        }
        if (tabH > 0 && y >= hh && y < y0) {
            int n = tabLabels.length;
            int t = tabMode(w) == 2 ? (x < w / 2 ? (tab + n - 1) % n : (tab + 1) % n) : Math.min(n - 1, x * n / w);
            if (t != tab) {
                app.listTab(this, t);
            }
            return;
        }
        if (y >= y0 && y < h - barH) {
            if (grid && state == READY) {
                // lưới: chạm nhả ra mới mở, kéo thì cuộn
                pressX = x;
                pressY = y;
                pressScroll = scrollY;
                moved = false;
                return;
            }
            int i = top + (y - y0) / rowH;
            if (i >= 0 && i < rowCount() && state == READY) {
                sel = i;
                repaint();
                activate(i);
            } else if (state == ERROR) {
                activate(0);
            }
        }
    }

    protected void pointerDragged(int x, int y) {
        if (!grid || pressY < 0) {
            return;
        }
        int dy = y - pressY;
        if (!moved && (dy > 8 || dy < -8)) {
            moved = true;
        }
        if (moved) {
            scrollY = pressScroll - dy;
            clampScroll();
            needLoad = true;
            repaint();
        }
    }

    protected void pointerReleased(int x, int y) {
        if (!grid || pressY < 0) {
            return;
        }
        int py = pressY;
        pressY = -1;
        if (!moved && state == READY) {
            int row = hit(x, py);
            if (row >= 0 && row < rowCount()) {
                sel = row;
                if (itemOf(row) >= 0) {
                    stickyCol = itemOf(row) % cols;
                }
                repaint();
                activate(row);
            }
        }
    }
}
