package vn.aowvn.monika;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Thông báo kêu gọi ủng hộ: tiêu đề, lời nhắn, mã QR VietQR vẽ bằng ô vuông (không cần tệp ảnh), số tài khoản,
 * nút Sao chép (máy J2ME không có clipboard nên mở ô chữ để tự chọn và chép) và nút Đóng thông báo ở cuối trang.
 * Nội dung dài hơn màn hình: cuộn bằng lên/xuống; Đóng chỉ bấm được khi đã kéo xuống cuối.
 */
final class DonateCanvas extends BaseCanvas {
    static final String ACCOUNT = "9021870093495";
    static final String BANK = "Timo";

    static final String TITLE = "Giữ Monika luôn miễn phí";
    static final String HINT = "Kéo xuống dưới cùng có nút Đóng thông báo";
    static final String TEXT1 = "Monika cố gắng cung cấp ứng dụng miễn phí nhất có thể, nhưng chúng mình cũng phải duy trì "
        + "hệ thống ổn định. Đóng góp của bạn sẽ giúp Monika ngày càng tốt hơn <3";
    static final String TEXT2 = "Donate bằng cách dùng smartphone của bạn, quét mã QR sau, hoặc donate tới địa chỉ "
        + ACCOUNT + ", ngân hàng " + BANK;

    /** Ma trận QR (37x37, mức sửa lỗi M) của mã VietQR tĩnh tới số tài khoản trên; mỗi hàng là 37 bit, hex. */
    private static final int N = 37;
    private static final String[] ROWS = {
        "1fda8dd17f",
        "105feb3541",
        "1741b5485d",
        "175d73925d",
        "1742ada45d",
        "104e428341",
        "1fd555557f",
        "001c6dac00",
        "16e5f1094b",
        "08a2f082cd",
        "065ea804a2",
        "07ae2ab944",
        "12ff0da34d",
        "1c38252f68",
        "06732e4904",
        "05bfdde86b",
        "08c45d2751",
        "08a0538950",
        "1ef7bad0a1",
        "16a08ab796",
        "1546442aa0",
        "018ae05b88",
        "1b4c49c482",
        "042bd6c96c",
        "056105c27b",
        "00bb95a442",
        "0d52bb4980",
        "142d981852",
        "0048fd29fd",
        "001b5a4914",
        "1fdd189758",
        "1054827511",
        "1746c449f4",
        "175c654244",
        "175e5b849e",
        "1044d14561",
        "1fdfe6125d"
    };

    private static final long[] BITS = new long[N];

    static {
        for (int i = 0; i < N; i++) {
            BITS[i] = Long.parseLong(ROWS[i], 16);
        }
    }

    private final MonikaMidlet app;
    private final boolean full;
    final Command cmdCopy;
    final Command cmdClose;

    private int scroll;
    private int contentH;
    private int viewH;
    private int barH;
    private int closeY;
    private int copyY;
    private int btnH;
    private String[] hint = new String[0];
    private String[] t1 = new String[0];
    private String[] t2 = new String[0];
    private int laidW = -1;

    DonateCanvas(MonikaMidlet app, boolean full) {
        this.app = app;
        this.full = full;
        cmdCopy = new Command(Vi.tr("Sao chép STK"), Command.OK, 1);
        cmdClose = new Command(Vi.tr("Đóng thông báo"), Command.BACK, 1);
        setFullScreenMode(full);
        if (!full) {
            addCommand(cmdCopy);
            addCommand(cmdClose);
        }
    }

    private boolean dark(int x, int y) {
        if (x < 0 || y < 0 || x >= N || y >= N) {
            return false;
        }
        return ((BITS[y] >> (N - 1 - x)) & 1L) != 0L;
    }

    private int qrScale(int w, int h) {
        int s = Math.min((w - 16) / (N + 4), (h * 6 / 10) / (N + 4));
        return Math.max(2, s);
    }

    private void layout(int w, int h) {
        Font f = w <= 176 ? Theme.small() : Theme.body();
        Font fb = Theme.bold();
        Font fs = Theme.small();
        barH = full ? fs.getHeight() + 10 : 0;
        viewH = h - barH;
        btnH = Math.max(f.getHeight() + 10, 26);
        if (laidW != w) {
            laidW = w;
            hint = wrap(fs, Vi.tr(HINT), w - 16);
            t1 = wrap(f, Vi.tr(TEXT1), w - 16);
            t2 = wrap(f, Vi.tr(TEXT2), w - 16);
        }
        int y = 6 + fb.getHeight() + 4 + hint.length * fs.getHeight() + 8;
        y += t1.length * f.getHeight() + 8 + t2.length * f.getHeight() + 8;
        y += (N + 4) * qrScale(w, h) + 8;
        y += fb.getHeight() + 2 + fs.getHeight() + 10;
        copyY = y;
        y += btnH + 8;
        closeY = y;
        y += btnH + 12;
        contentH = y;
        int max = maxScroll();
        if (scroll > max) {
            scroll = max;
        }
    }

    private int maxScroll() {
        return Math.max(0, contentH - viewH);
    }

    private boolean atEnd() {
        return scroll >= maxScroll() - 8;
    }

    protected void paint(Graphics g) {
        paintTo(g, getWidth(), getHeight());
    }

    void paintTo(Graphics g, int w, int h) {
        layout(w, h);
        Font f = w <= 176 ? Theme.small() : Theme.body();
        Font fb = Theme.bold();
        Font fs = Theme.small();
        Theme.vgradient(g, 0, 0, w, h, Theme.BG_TOP, Theme.BG_BOT);
        g.setClip(0, 0, w, viewH);
        int y = 6 - scroll;
        g.setFont(fb);
        g.setColor(Theme.ORANGE);
        g.drawString(fit(fb, Vi.tr(TITLE), w - 8), w / 2, y, Graphics.TOP | Graphics.HCENTER);
        y += fb.getHeight() + 4;
        g.setFont(fs);
        g.setColor(Theme.DIM);
        for (int i = 0; i < hint.length; i++) {
            g.drawString(hint[i], w / 2, y, Graphics.TOP | Graphics.HCENTER);
            y += fs.getHeight();
        }
        y += 8;
        g.setFont(f);
        g.setColor(Theme.TEXT);
        for (int i = 0; i < t1.length; i++) {
            g.drawString(t1[i], 8, y, Graphics.TOP | Graphics.LEFT);
            y += f.getHeight();
        }
        y += 8;
        for (int i = 0; i < t2.length; i++) {
            g.drawString(t2[i], 8, y, Graphics.TOP | Graphics.LEFT);
            y += f.getHeight();
        }
        y += 8;
        // QR: nền trắng có viền trống 2 ô, vẽ từng ô vuông
        int s = qrScale(w, h);
        int qs = (N + 4) * s;
        int qx = (w - qs) / 2;
        g.setColor(0xFFFFFF);
        g.fillRect(qx, y, qs, qs);
        g.setColor(0x000000);
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                if (dark(c, r)) {
                    g.fillRect(qx + (c + 2) * s, y + (r + 2) * s, s, s);
                }
            }
        }
        y += qs + 8;
        g.setFont(fb);
        g.setColor(Theme.TEXT);
        g.drawString(ACCOUNT, w / 2, y, Graphics.TOP | Graphics.HCENTER);
        y += fb.getHeight() + 2;
        g.setFont(fs);
        g.setColor(Theme.DIM);
        g.drawString(Vi.tr("Ngân hàng ") + BANK, w / 2, y, Graphics.TOP | Graphics.HCENTER);
        button(g, w, copyY - scroll, Vi.tr("Sao chép số tài khoản"), false);
        button(g, w, closeY - scroll, Vi.tr("Đóng thông báo"), true);
        g.setClip(0, 0, w, h);
        if (maxScroll() > 0) {
            int th = Math.max(8, viewH * viewH / contentH);
            int ty = (viewH - th) * scroll / maxScroll();
            g.setColor(Theme.ORANGE);
            g.fillRect(w - 3, ty, 2, th);
        }
        if (full) {
            g.setColor(Theme.BAR_BG);
            g.fillRect(0, h - barH, w, barH);
            g.setColor(Theme.LINE);
            g.drawLine(0, h - barH, w - 1, h - barH);
            g.setFont(fb);
            g.setColor(Theme.TEXT);
            int ty = h - barH + (barH - fb.getHeight()) / 2;
            g.drawString(Vi.tr("Sao chép"), 4, ty, Graphics.TOP | Graphics.LEFT);
            g.setColor(atEnd() ? Theme.TEXT : Theme.DIM);
            g.drawString(atEnd() ? Vi.tr("Đóng") : Vi.tr("Kéo xuống"), w - 4, ty, Graphics.TOP | Graphics.RIGHT);
        }
    }

    private void button(Graphics g, int w, int y, String label, boolean close) {
        if (y + btnH < 0 || y > viewH) {
            return;
        }
        Font fb = Theme.bold();
        if (close) {
            g.setColor(atEnd() ? Theme.PINK_D : Theme.LINE);
            g.fillRoundRect(8, y, w - 16, btnH, 10, 10);
        } else {
            Theme.vgradient(g, 8, y, w - 16, btnH, Theme.SEL_A, Theme.SEL_B);
        }
        g.setFont(fb);
        g.setColor(Theme.TEXT);
        g.drawString(fit(fb, label, w - 24), w / 2, y + (btnH - fb.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
    }

    void copy() {
        app.donateCopy(this);
    }

    void close() {
        if (atEnd()) {
            app.donateClose(this);
        } else {
            scrollBy(viewH * 3 / 4);
        }
    }

    private void scrollBy(int d) {
        scroll = Math.max(0, Math.min(maxScroll(), scroll + d));
        repaint();
    }

    protected void keyPressed(int code) {
        if (full) {
            int soft = softKey(code);
            if (soft == SOFT_LEFT) {
                copy();
                return;
            }
            if (soft == SOFT_RIGHT) {
                close();
                return;
            }
        }
        int ga = dir(code);
        int step = Theme.body().getHeight() * 2;
        if (isFire(code) || ga == Canvas.FIRE) {
            close();
        } else if (ga == Canvas.DOWN || ga == Canvas.RIGHT) {
            scrollBy(step);
        } else if (ga == Canvas.UP || ga == Canvas.LEFT) {
            scrollBy(-step);
        }
    }

    protected void keyRepeated(int code) {
        int ga = dir(code);
        if (ga == Canvas.UP || ga == Canvas.DOWN) {
            keyPressed(code);
        }
    }

    private int pdownY = -1;
    private int plastY;
    private boolean pdrag;

    protected void pointerPressed(int x, int y) {
        pdownY = y;
        plastY = y;
        pdrag = false;
    }

    protected void pointerDragged(int x, int y) {
        if (pdownY < 0) {
            return;
        }
        if (Math.abs(y - pdownY) > 6) {
            pdrag = true;
        }
        if (pdrag) {
            scrollBy(plastY - y);
        }
        plastY = y;
    }

    protected void pointerReleased(int x, int y) {
        boolean tap = pdownY >= 0 && !pdrag;
        pdownY = -1;
        if (!tap) {
            return;
        }
        int h = getHeight();
        layout(getWidth(), h);
        if (full && y >= h - barH) {
            if (x < getWidth() / 2) {
                copy();
            } else {
                close();
            }
            return;
        }
        int cy = y + scroll;
        if (cy >= copyY && cy < copyY + btnH) {
            copy();
        } else if (cy >= closeY && cy < closeY + btnH && atEnd()) {
            close();
        }
    }
}
