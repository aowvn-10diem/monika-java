package vn.aowvn.monika;

import java.util.Timer;
import java.util.TimerTask;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Màn hình chính theo mockup: headImg Monika, 5 mục menu (Ứng dụng, Trò chơi,
 * Danh mục, Cập nhật kèm huy hiệu, Yêu thích) và thanh phím mềm
 * Tùy chọn / Tìm ứng dụng / Thoát.
 *
 * Hai chế độ:
 * - Thường (mặc định): giữ thanh trạng thái của máy; menu Tùy chọn và Thoát là Command gốc.
 * - Toàn màn hình: tự vẽ cả ba nhãn phím mềm và tự nhận mã phím mềm.
 */
final class HomeCanvas extends BaseCanvas {
    static final int ITEMS = 5;
    /** 5 mục menu cộng nút Tìm ứng dụng. */
    static final int FOCUS = ITEMS + 1;
    static final int ACT_OPEN = 0;
    static final int ACT_OPTIONS = 1;
    static final int ACT_SEARCH = 2;
    static final int ACT_EXIT = 3;
    static final int ACT_PROMO = 4;
    /** Vị trí focus của banner quảng cáo (sau mục Tìm ứng dụng). */
    static final int PROMO = ITEMS + 1;

    private static final String[] LABELS = {
        "Ứng dụng", "Trò chơi", "Danh mục", "Cập nhật", "Yêu thích"
    };
    private static final int[] ICONS = {
        Icons.GRID, Icons.GAMEPAD, Icons.LAYERS, Icons.REFRESH, Icons.STAR
    };

    private final MonikaMidlet app;
    final Command cmdOpen;
    final Command cmdSearch;
    final Command cmdSettings;
    final Command cmdInfo;
    final Command cmdAbout;
    final Command cmdExit;

    private boolean full;
    private int sel;
    private int top;
    private int badge;

    // bố cục tính lại mỗi lần vẽ
    private int hdr;
    private int rowH;
    private int barH;
    private int visible;
    private int promoH;

    // banner quảng cáo lấy từ API (slot chung với web)
    private Image[] promos = new Image[0];
    private String[] promoLinks = new String[0];
    private int promoIdx;
    private Timer timer;

    private Image headImg;
    private int headW;
    private int headH;

    HomeCanvas(MonikaMidlet app, boolean full) {
        this.app = app;
        cmdOpen = new Command(Vi.tr("Mở"), Command.OK, 1);
        cmdSearch = new Command(Vi.tr("Tìm ứng dụng"), Command.SCREEN, 1);
        cmdSettings = new Command(Vi.tr("Cài đặt"), Command.SCREEN, 2);
        cmdInfo = new Command(Vi.tr("Thông tin máy"), Command.SCREEN, 3);
        cmdAbout = new Command(Vi.tr("Giới thiệu"), Command.SCREEN, 4);
        cmdExit = new Command(Vi.tr("Thoát"), Command.EXIT, 1);
        setFull(full);
    }

    /** Bật/tắt toàn màn hình và gắn lại Command cho phù hợp. */
    void setFull(boolean f) {
        full = f;
        removeCommand(cmdOpen);
        removeCommand(cmdSearch);
        removeCommand(cmdSettings);
        removeCommand(cmdInfo);
        removeCommand(cmdAbout);
        removeCommand(cmdExit);
        setFullScreenMode(f);
        if (!f) {
            addCommand(cmdOpen);
            addCommand(cmdSearch);
            addCommand(cmdSettings);
            addCommand(cmdInfo);
            addCommand(cmdAbout);
            addCommand(cmdExit);
        }
        headImg = null;
        repaint();
    }

    /** Mục đang chọn: 0 đến 4 là menu, ITEMS là nút Tìm ứng dụng. */
    int selected() {
        return sel;
    }

    /** Bấm mục đang chọn (phím giữa hoặc lệnh Mở). */
    void activate() {
        if (sel == PROMO) {
            app.homeAction(ACT_PROMO, sel);
        } else if (sel >= ITEMS) {
            app.homeAction(ACT_SEARCH, sel);
        } else {
            app.homeAction(ACT_OPEN, sel);
        }
    }

    void setBadge(int n) {
        badge = n;
        repaint();
    }

    protected void sizeChanged(int w, int h) {
        headImg = null;
    }

    // ---------- bố cục ----------

    private void layout(int w, int h) {
        promoH = promos.length > 0 && h >= 240 ? Math.min(promos[promoIdx].getHeight(), h / 5) : 0;
        int hh = h - promoH;
        Font f = Theme.body();
        Font fs = Theme.small();
        barH = fs.getHeight() + 10;
        rowH = Math.max(f.getHeight() + 8, 20);
        hdr = hh - barH - ITEMS * rowH;
        if (hdr < 36) {
            rowH = f.getHeight() + 4;
            hdr = hh - barH - ITEMS * rowH;
        }
        int cap = hh * 42 / 100;
        if (hdr > cap) {
            // máy cao: chia phần dư cho hàng menu
            rowH = Math.min((hh - barH - cap) / ITEMS, Math.max(f.getHeight() + 18, hh / 12));
            hdr = hh - barH - ITEMS * rowH;
        }
        if (hdr < 26) {
            hdr = Math.min(26, hh / 3);
        }
        visible = (hh - hdr - barH) / rowH;
        if (visible > ITEMS) {
            visible = ITEMS;
        }
        if (visible < 1) {
            visible = 1;
        }
        int r = Math.min(sel, ITEMS - 1);
        if (r < top) {
            top = r;
        }
        if (r >= top + visible) {
            top = r - visible + 1;
        }
    }

    // ---------- vẽ ----------

    protected void paint(Graphics g) {
        paintTo(g, getWidth(), getHeight());
    }

    /** Vẽ với kích thước tùy ý (dùng được để chụp thử giao diện không cần máy). */
    void paintTo(Graphics g, int w, int h) {
        layout(w, h);

        Theme.vgradient(g, 0, 0, w, h, Theme.BG_TOP, Theme.BG_BOT);
        paintHead(g, w);
        if (promoH > 0) {
            paintPromo(g, w, hdr);
        }
        int y = hdr + promoH;
        for (int i = top; i < top + visible && i < ITEMS; i++) {
            paintRow(g, i, y, w);
            y += rowH;
        }
        paintBar(g, w, h);
    }

    private void paintPromo(Graphics g, int w, int y) {
        Image im = promos[promoIdx];
        g.setClip(0, y, w, promoH);
        g.drawImage(im, (w - im.getWidth()) / 2, y, Graphics.TOP | Graphics.LEFT);
        g.setClip(0, 0, w, getHeight());
        if (sel == PROMO) {
            g.setColor(Theme.ORANGE);
            g.drawRect(0, y, w - 1, promoH - 1);
            g.drawRect(1, y + 1, w - 3, promoH - 3);
        }
    }

    /** Đặt banner (ảnh đã tải) và link tương ứng; mảng rỗng = ẩn slot. */
    void setPromos(Image[] imgs, String[] links) {
        promos = imgs;
        promoLinks = links;
        promoIdx = 0;
        if (sel == PROMO && imgs.length == 0) {
            sel = 0;
        }
        startTimer();
        repaint();
    }

    String promoLink() {
        return promos.length > 0 ? promoLinks[promoIdx] : "";
    }

    private int focusCount() {
        return promoH > 0 ? PROMO + 1 : FOCUS;
    }

    private void startTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        if (promos.length > 1 && isShown()) {
            timer = new Timer();
            timer.schedule(new TimerTask() {
                public void run() {
                    promoIdx = (promoIdx + 1) % promos.length;
                    repaint();
                }
            }, 6000L, 6000L);
        }
    }

    protected void showNotify() {
        startTimer();
    }

    protected void hideNotify() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void paintHead(Graphics g, int w) {
        if (headImg == null || headW != w || headH != hdr) {
            headImg = buildHead(w, hdr);
            headW = w;
            headH = hdr;
        }
        g.drawImage(headImg, 0, 0, Graphics.TOP | Graphics.LEFT);

        boolean big = hdr >= 56;
        Font lf = Theme.logo(big);
        Font fs = Theme.small();
        int x = 8;
        int y = 4;
        // logo chữ: M cam, phần còn lại trắng
        g.setFont(lf);
        g.setColor(Theme.ORANGE);
        g.drawString("M", x, y, Graphics.TOP | Graphics.LEFT);
        int mw = lf.stringWidth("M");
        g.setColor(Theme.TEXT);
        g.drawString("onika", x + mw, y, Graphics.TOP | Graphics.LEFT);
        y += lf.getHeight();

        if (hdr >= lf.getHeight() + fs.getHeight() + 8) {
            g.setFont(fs);
            g.setColor(Theme.TEXT);
            String sub = Vi.tr("Cửa hàng ứng dụng Java S60");
            if (fs.stringWidth(sub) > w - 2 * x) {
                sub = Vi.tr("Cửa hàng Java S60");
            }
            g.drawString(sub, x, y, Graphics.TOP | Graphics.LEFT);
            y += fs.getHeight();
        }
        if (hdr >= lf.getHeight() + 2 * fs.getHeight() + 8) {
            g.setFont(fs);
            g.setColor(Theme.DIM);
            g.drawString("by ", x, y, Graphics.TOP | Graphics.LEFT);
            int bx = x + fs.stringWidth("by ");
            g.setFont(Theme.bold());
            g.setColor(Theme.ORANGE);
            g.drawString("AOW", bx, y, Graphics.TOP | Graphics.LEFT);
            bx += Theme.bold().stringWidth("AOW") + 2;
            g.setFont(fs);
            g.setColor(Theme.TEXT);
            g.fillRoundRect(bx, y + 1, fs.stringWidth(".VN") + 6, fs.getHeight() - 2, 8, 8);
            g.setColor(Theme.BG_TOP);
            g.drawString(".VN", bx + 3, y, Graphics.TOP | Graphics.LEFT);
        }
    }

    /** Nền headImg: gradient tím sang hồng, thêm sóng cam hồng góc phải. */
    private static Image buildHead(int w, int h) {
        Image img = Image.createImage(w, h);
        Graphics g = img.getGraphics();
        for (int x = 0; x < w; x++) {
            int t = x * 256 / Math.max(1, w - 1);
            int c = t < 160 ? Theme.mix(0x2B0F4F, 0x5A1670, t * 256 / 160)
                : Theme.mix(0x5A1670, 0xB8307A, (t - 160) * 256 / 96);
            g.setColor(c);
            g.drawLine(x, 0, x, h - 1);
        }
        int x0 = w * 35 / 100;
        int span = w - x0;
        for (int x = x0; x < w; x++) {
            int t = (x - x0) * 256 / Math.max(1, span - 1);
            int s = t * t * (768 - 2 * t) >> 16; // smoothstep 0..256
            int wave = h * 70 / 100 * s >> 8;
            int y = h - wave;
            if (y < h) {
                int col = Theme.mix(0xE0457B, 0xFF8A2A, t);
                g.setColor(col);
                g.drawLine(x, y, x, h - 1);
                g.setColor(Theme.mix(col, 0xFFFFFF, 90));
                g.drawLine(x, y, x, y);
            }
        }
        // ngăn cách dưới headImg
        g.setColor(Theme.LINE);
        g.drawLine(0, h - 1, w - 1, h - 1);
        return img;
    }

    private void paintRow(Graphics g, int i, int y, int w) {
        boolean on = i == sel;
        Font f = on ? Theme.bold() : Theme.body();
        if (on) {
            Theme.vgradient(g, 1, y, w - 2, rowH, Theme.SEL_A, Theme.SEL_B);
            g.setColor(Theme.mix(Theme.SEL_A, 0xFFFFFF, 80));
            g.drawLine(1, y, w - 2, y);
        } else {
            g.setColor(Theme.LINE);
            g.drawLine(8, y + rowH - 1, w - 9, y + rowH - 1);
        }
        int is = Math.min(rowH - 6, 24);
        int ix = 8;
        int iy = y + (rowH - is) / 2;
        Icons.draw(g, ICONS[i], ix, iy, is, on ? Theme.TEXT : Theme.DIM);

        g.setFont(f);
        g.setColor(Theme.TEXT);
        g.drawString(Vi.tr(LABELS[i]), ix + is + 10, y + (rowH - f.getHeight()) / 2,
            Graphics.TOP | Graphics.LEFT);

        int cx = w - 12;
        int cy = y + rowH / 2;
        for (int t = 0; t < 2; t++) {
            g.drawLine(cx - 3 + t, cy - 5, cx + 1 + t, cy);
            g.drawLine(cx + 1 + t, cy, cx - 3 + t, cy + 5);
        }
        if (i == 3 && badge > 0) {
            Font bf = Theme.small();
            String s = Integer.toString(badge);
            int bw = Math.max(bf.stringWidth(s) + 8, bf.getHeight());
            int bh = bf.getHeight();
            int bx = cx - 10 - bw;
            int by = cy - bh / 2;
            g.setColor(Theme.PINK);
            g.fillRoundRect(bx, by, bw, bh, bh, bh);
            g.setColor(Theme.TEXT);
            g.setFont(bf);
            g.drawString(s, bx + bw / 2, by, Graphics.TOP | Graphics.HCENTER);
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
        String left = Vi.tr("Tùy chọn");
        String right = Vi.tr("Thoát");
        // chỗ còn lại cho nút tìm kiếm ở giữa
        int avail = w - 8;
        if (full) {
            avail = w - bf.stringWidth(left) - bf.stringWidth(right) - 20;
        }
        String mid = Vi.tr("Tìm ứng dụng");
        int is = fs.getHeight() - 2;
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
        if (sel == ITEMS) {
            g.setColor(Theme.SEL_B);
            g.fillRoundRect(px, y + 2, pw - 1, barH - 5, 10, 10);
            g.setColor(Theme.ORANGE);
        } else {
            g.setColor(Theme.LINE);
        }
        g.drawRoundRect(px, y + 2, pw - 1, barH - 5, 10, 10);
        Icons.draw(g, Icons.SEARCH, px + 7, y + (barH - is) / 2, is, Theme.TEXT);
        if (mid != null) {
            g.setFont(fs);
            g.setColor(Theme.TEXT);
            g.drawString(mid, px + 7 + is + 5, y + (barH - fs.getHeight()) / 2,
                Graphics.TOP | Graphics.LEFT);
        }

        if (full) {
            g.setFont(bf);
            g.setColor(Theme.TEXT);
            int ty = y + (barH - bf.getHeight()) / 2;
            g.drawString(left, 4, ty, Graphics.TOP | Graphics.LEFT);
            g.drawString(right, w - 4, ty, Graphics.TOP | Graphics.RIGHT);
        }
    }

    // ---------- phím ----------

    protected void keyPressed(int code) {
        if (code >= Canvas.KEY_NUM1 && code <= Canvas.KEY_NUM5) {
            sel = code - Canvas.KEY_NUM1;
            repaint();
            app.homeAction(ACT_OPEN, sel);
            return;
        }
        if (code == Canvas.KEY_NUM0 && promoH > 0) {
            app.homeAction(ACT_PROMO, PROMO);
            return;
        }
        if (code == Canvas.KEY_STAR || code == 's' || code == 'S') {
            app.homeAction(ACT_SEARCH, sel);
            return;
        }
        if (full) {
            int soft = softKey(code);
            if (soft == SOFT_LEFT) {
                app.homeAction(ACT_OPTIONS, sel);
                return;
            }
            if (soft == SOFT_RIGHT) {
                app.homeAction(ACT_EXIT, sel);
                return;
            }
        }
        if (isFire(code)) {
            activate();
            return;
        }
        int ga = gameAction(code);
        if (ga == Canvas.UP) {
            sel = (sel + focusCount() - 1) % focusCount();
            repaint();
        } else if (ga == Canvas.DOWN) {
            sel = (sel + 1) % focusCount();
            repaint();
        }
    }

    protected void keyRepeated(int code) {
        int ga = gameAction(code);
        if (ga == Canvas.UP || ga == Canvas.DOWN) {
            keyPressed(code);
        }
    }

    // ---------- cảm ứng ----------

    protected void pointerPressed(int x, int y) {
        int w = getWidth();
        int h = getHeight();
        layout(w, h);
        if (y >= h - barH) {
            if (full && x < w / 4) {
                app.homeAction(ACT_OPTIONS, sel);
            } else if (full && x > w - w / 4) {
                app.homeAction(ACT_EXIT, sel);
            } else {
                app.homeAction(ACT_SEARCH, sel);
            }
            return;
        }
        if (promoH > 0 && y >= hdr && y < hdr + promoH) {
            sel = PROMO;
            repaint();
            app.homeAction(ACT_PROMO, PROMO);
            return;
        }
        if (y >= hdr + promoH) {
            int i = top + (y - hdr - promoH) / rowH;
            if (i >= 0 && i < ITEMS) {
                sel = i;
                repaint();
                app.homeAction(ACT_OPEN, i);
            }
        }
    }
}
