package vn.aowvn.monika;

import java.util.Timer;
import java.util.TimerTask;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Danh sách có phân trang vẽ theo ngôn ngữ giao diện web: thanh tiêu đề tím, tab Mới / Hot / Tải nhiều,
 * dòng đánh số 1-9 bấm nhanh, dòng đang chọn tô cam, huy hiệu MỚI / HOT, "Trang sau" ở cuối.
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
    /** 0 = danh sách app, 1 = danh mục, 2 = yêu thích (cục bộ, không phân trang). */
    int kind;
    String title = "";
    String[] tabLabels;
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
        this.page = page;
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
        two = h >= 200;
        rowH = two ? f.getHeight() + fs.getHeight() + 8 : f.getHeight() + 6;
        visible = Math.max(1, (h - y0 - barH) / rowH);
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
        } else {
            int y = y0;
            for (int i = top; i < top + visible && i < rowCount(); i++) {
                paintRow(g, i, y, w);
                y += rowH;
            }
        }
        g.setClip(0, 0, w, h);
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

    private void paintTabs(Graphics g, int w, int y) {
        Font fs = Theme.small();
        int n = tabLabels.length;
        int tw = w / n;
        g.setColor(Theme.BAR_BG);
        g.fillRect(0, y, w, tabH);
        for (int i = 0; i < n; i++) {
            int x = i * tw;
            boolean on = i == tab;
            if (on) {
                Theme.vgradient(g, x, y, tw, tabH, Theme.SEL_A, Theme.SEL_B);
            }
            g.setFont(fs);
            g.setColor(on ? Theme.TEXT : Theme.DIM);
            g.drawString(fit(fs, Vi.tr(tabLabels[i]), tw - 4), x + tw / 2,
                y + (tabH - fs.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
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
        if (k < 9) {
            g.setColor(on ? Theme.TEXT : Theme.ORANGE);
            String n = Integer.toString(k + 1);
            g.drawString(n, x, cy - f.getHeight() / 2, Graphics.TOP | Graphics.LEFT);
        }
        x += f.stringWidth("9") + 6;
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
        if (code >= Canvas.KEY_NUM1 && code <= Canvas.KEY_NUM9) {
            int k = code - Canvas.KEY_NUM1;
            if (state == READY && k < names.length) {
                sel = k + (hasPrev ? 1 : 0);
                repaint();
                app.listOpen(this, k);
            }
            return;
        }
        if (code == Canvas.KEY_STAR || code == 's' || code == 'S') {
            app.listSearch();
            return;
        }
        if (code == Canvas.KEY_POUND) {
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
        if (isFire(code)) {
            activate(sel);
            return;
        }
        int ga = gameAction(code);
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
        int ga = gameAction(code);
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
            int t = Math.min(n - 1, x * n / w);
            if (t != tab) {
                app.listTab(this, t);
            }
            return;
        }
        if (y >= y0 && y < h - barH) {
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
}
