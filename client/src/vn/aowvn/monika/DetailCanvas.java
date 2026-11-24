package vn.aowvn.monika;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Trang một ứng dụng/trò chơi, giống trang bài trên web: tên, nút lớn "Tải về", nút Yêu thích,
 * rồi mô tả và thông tin tệp (cuộn bằng phím lên/xuống).
 */
final class DetailCanvas extends BaseCanvas {
    private final MonikaMidlet app;
    private final boolean full;
    final Command cmdInstall;
    final Command cmdFav;
    final Command cmdSearch;
    final Command cmdReport;
    final Command cmdBack;

    final AppInfo info;
    private final String body;
    private final boolean canInstall;
    private boolean fav;
    private String status = "";
    private int scroll;

    // bố cục
    private int hh;
    private int btnY;
    private int btnH;
    private int favY;
    private int favH;
    private int repY;
    private int repH;
    private int textY;
    private int barH;
    private String[] lines = new String[0];
    private int lineW;

    DetailCanvas(MonikaMidlet app, boolean full, AppInfo a, String catName, boolean fav, boolean canInstall,
                 String note) {
        this.app = app;
        this.full = full;
        this.info = a;
        this.fav = fav;
        this.canInstall = canInstall;
        StringBuffer sb = new StringBuffer();
        sb.append(a.desc).append("\n\n");
        sb.append(Vi.tr("Phiên bản: ")).append(a.version).append('\n');
        sb.append(Vi.tr("Dung lượng: ")).append(a.sizeKb).append(" KB\n");
        sb.append(Vi.tr("Giá: ")).append(Vi.tr(a.isFree() ? "Miễn phí" : MonikaMidlet.money(a.price))).append('\n');
        String dl = AppInfo.dlText(a.dl);
        if (dl.length() > 0 && !a.has(AppInfo.F_POST | AppInfo.F_MEDIA)) {
            sb.append(Vi.tr("Lượt tải: ")).append(dl).append('\n');
        }
        String[] tg = a.tagBadges(6);
        if (tg.length > 0) {
            sb.append(Vi.tr("Thẻ: "));
            for (int i = 0; i < tg.length; i++) {
                sb.append(i > 0 ? ", " : "").append(tg[i]);
            }
            sb.append('\n');
        }
        String[] sc = a.screenBadges();
        if (sc.length > 0) {
            sb.append(Vi.tr("Màn hình: "));
            for (int i = 0; i < sc.length; i++) {
                sb.append(i > 0 ? ", " : "").append(sc[i]);
            }
            sb.append('\n');
        }
        if (catName != null && catName.length() > 0) {
            sb.append(Vi.tr("Thể loại: ")).append(catName).append('\n');
        }
        if (a.has(AppInfo.F_NEEDNET)) {
            sb.append(Vi.tr("Cần kết nối mạng khi chạy.")).append('\n');
        }
        if (note != null && note.length() > 0) {
            sb.append('\n').append(note);
        }
        body = sb.toString();
        cmdInstall = new Command(Vi.tr(a.has(AppInfo.F_POST) ? "Mở bài" : a.has(AppInfo.F_MEDIA) ? "Mở trang tải" : "Tải về"), Command.OK, 1);
        cmdFav = new Command(Vi.tr("Yêu thích"), Command.ITEM, 2);
        cmdSearch = new Command(Vi.tr("Tìm ứng dụng"), Command.SCREEN, 3);
        cmdReport = new Command(Vi.tr("Báo lỗi / góp ý"), Command.ITEM, 4);
        cmdBack = new Command(Vi.tr("Quay lại"), Command.BACK, 1);
        setFullScreenMode(full);
        if (!full) {
            if (canInstall) {
                addCommand(cmdInstall);
            }
            addCommand(cmdFav);
            addCommand(cmdSearch);
            addCommand(cmdReport);
            addCommand(cmdBack);
        }
    }

    void setFav(boolean f) {
        fav = f;
        repaint();
    }

    void setStatus(String s) {
        status = s;
        repaint();
    }

    private void layout(int w, int h) {
        Font fb = Theme.bold();
        Font f = Theme.body();
        Font fs = Theme.small();
        barH = full ? fs.getHeight() + 10 : 0;
        hh = fb.getHeight() + 8;
        btnY = hh + 6;
        btnH = Math.max(f.getHeight() + 10, 26);
        favY = btnY + btnH + 4;
        favH = Math.max(f.getHeight() + 4, 20);
        repY = favY + favH + 3;
        repH = Math.max(fs.getHeight() + 4, 16);
        textY = repY + repH + 5;
        lineW = w - 16;
        lines = wrap(f, body, lineW);
    }

    private int maxScroll(int h) {
        int total = lines.length * Theme.body().getHeight();
        int view = h - textY - barH;
        return Math.max(0, total - view);
    }

    protected void paint(Graphics g) {
        paintTo(g, getWidth(), getHeight());
    }

    void paintTo(Graphics g, int w, int h) {
        layout(w, h);
        Font fb = Theme.bold();
        Font f = Theme.body();
        Font fs = Theme.small();
        Theme.vgradient(g, 0, 0, w, h, Theme.BG_TOP, Theme.BG_BOT);
        // tiêu đề
        for (int x = 0; x < w; x++) {
            int t = x * 256 / Math.max(1, w - 1);
            g.setColor(t < 160 ? Theme.mix(0x2B0F4F, 0x5A1670, t * 256 / 160)
                : Theme.mix(0x5A1670, 0xB8307A, (t - 160) * 256 / 96));
            g.drawLine(x, 0, x, hh - 1);
        }
        g.setColor(Theme.ORANGE);
        g.fillRect(0, hh - 2, w, 2);
        g.setFont(fb);
        g.setColor(Theme.TEXT);
        g.drawString(fit(fb, Vi.tr(info.name), w - 12), 6, 3, Graphics.TOP | Graphics.LEFT);

        // nút tải về
        if (canInstall) {
            Theme.vgradient(g, 6, btnY, w - 12, btnH, Theme.SEL_A, Theme.SEL_B);
            g.setColor(Theme.mix(Theme.SEL_A, 0xFFFFFF, 80));
            g.drawLine(6, btnY, w - 7, btnY);
            g.setColor(Theme.TEXT);
        } else {
            g.setColor(Theme.LINE);
            g.fillRect(6, btnY, w - 12, btnH);
            g.setColor(Theme.DIM);
        }
        g.setFont(fb);
        String bt = canInstall ? Vi.tr(info.has(AppInfo.F_POST) ? "Mở bài viết" : info.has(AppInfo.F_MEDIA) ? "Mở trang tải" : "Tải về (JAR)") : Vi.tr("Chưa có file cài");
        g.drawString(bt, w / 2, btnY + (btnH - fb.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
        if (canInstall) {
            g.setFont(fs);
            g.drawString("1", 12, btnY + (btnH - fs.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
        }
        // nút yêu thích
        g.setColor(fav ? Theme.PINK_D : Theme.LINE);
        g.fillRect(6, favY, w - 12, favH);
        g.setFont(f);
        g.setColor(Theme.TEXT);
        g.drawString(Vi.tr(fav ? "Bỏ yêu thích" : "Yêu thích"), w / 2, favY + (favH - f.getHeight()) / 2,
            Graphics.TOP | Graphics.HCENTER);
        g.setFont(fs);
        g.drawString(numNav ? "3" : "2", 12, favY + (favH - fs.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);

        // nút báo lỗi / góp ý (nhỏ hơn, phím 9)
        g.setColor(Theme.LINE);
        g.fillRect(6, repY, w - 12, repH);
        g.setFont(fs);
        g.setColor(Theme.TEXT);
        g.drawString(Vi.tr("Báo lỗi / góp ý game này"), w / 2, repY + (repH - fs.getHeight()) / 2,
            Graphics.TOP | Graphics.HCENTER);
        g.setColor(Theme.DIM);
        g.drawString("9", 12, repY + (repH - fs.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);

        // phần chữ cuộn
        int viewH = h - textY - barH;
        g.setClip(0, textY, w, viewH);
        int sc = Math.min(scroll, maxScroll(h));
        int y = textY - sc;
        int lh = f.getHeight();
        g.setFont(f);
        for (int i = 0; i < lines.length; i++) {
            if (y + lh >= textY && y < textY + viewH) {
                boolean head = lines[i].startsWith(Vi.tr("Phiên bản: ")) || lines[i].startsWith(Vi.tr("Dung lượng: "))
                    || lines[i].startsWith(Vi.tr("Giá: ")) || lines[i].startsWith(Vi.tr("Thể loại: "))
                    || lines[i].startsWith(Vi.tr("Lượt tải: ")) || lines[i].startsWith(Vi.tr("Thẻ: "));
                g.setColor(head ? Theme.DIM : Theme.TEXT);
                g.drawString(lines[i], 8, y, Graphics.TOP | Graphics.LEFT);
            }
            y += lh;
        }
        g.setClip(0, 0, w, h);
        if (maxScroll(h) > 0) {
            // thanh cuộn mảnh bên phải
            int th = Math.max(8, viewH * viewH / (viewH + maxScroll(h)));
            int ty = textY + (viewH - th) * sc / maxScroll(h);
            g.setColor(Theme.ORANGE);
            g.fillRect(w - 3, ty, 2, th);
        }
        if (status.length() > 0) {
            int sh = fs.getHeight() + 6;
            g.setColor(Theme.BAR_BG);
            g.fillRect(0, h - barH - sh, w, sh);
            g.setFont(fs);
            g.setColor(Theme.TEXT);
            g.drawString(fit(fs, status, w - 8), 4, h - barH - sh + 3, Graphics.TOP | Graphics.LEFT);
        }
        if (full) {
            g.setColor(Theme.BAR_BG);
            g.fillRect(0, h - barH, w, barH);
            g.setColor(Theme.LINE);
            g.drawLine(0, h - barH, w - 1, h - barH);
            g.setFont(fb);
            g.setColor(Theme.TEXT);
            int ty = h - barH + (barH - fb.getHeight()) / 2;
            g.drawString(canInstall ? Vi.tr(info.has(AppInfo.F_POST) ? "Mở bài" : info.has(AppInfo.F_MEDIA) ? "Mở trang tải" : "Tải về") : Vi.tr("Yêu thích"), 4, ty,
                Graphics.TOP | Graphics.LEFT);
            g.drawString(Vi.tr("Quay lại"), w - 4, ty, Graphics.TOP | Graphics.RIGHT);
        }
    }

    private void install() {
        if (canInstall) {
            app.detailInstall(this);
        }
    }

    protected void showNotify() {
        app.detailShown();
    }

    protected void hideNotify() {
        app.detailHidden();
    }

    protected void keyPressed(int code) {
        int d = digit(code);
        if (d == 1) {
            install();
            return;
        }
        // phím yêu thích: 3 khi 2/4/6/8 dùng để di chuyển, 2 khi tắt chế độ đó
        if (d == (numNav ? 3 : 2)) {
            app.detailFav(this);
            return;
        }
        if (d == 9) {
            app.detailReport(this);
            return;
        }
        if (isStar(code)) {
            app.listSearch();
            return;
        }
        if (full) {
            int soft = softKey(code);
            if (soft == SOFT_LEFT) {
                if (canInstall) {
                    install();
                } else {
                    app.detailFav(this);
                }
                return;
            }
            if (soft == SOFT_RIGHT) {
                app.listBack();
                return;
            }
        }
        int ga = dir(code);
        if (isFire(code) || ga == Canvas.FIRE) {
            if (canInstall) {
                install();
            } else {
                app.detailFav(this);
            }
            return;
        }
        int step = Theme.body().getHeight() * 2;
        if (ga == Canvas.DOWN) {
            scroll = Math.min(maxScroll(getHeight()), scroll + step);
            repaint();
        } else if (ga == Canvas.UP) {
            scroll = Math.max(0, scroll - step);
            repaint();
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
            if (x < w / 3) {
                if (canInstall) {
                    install();
                } else {
                    app.detailFav(this);
                }
            } else if (x > w - w / 3) {
                app.listBack();
            }
            return;
        }
        if (y >= btnY && y < btnY + btnH) {
            install();
        } else if (y >= favY && y < favY + favH) {
            app.detailFav(this);
        } else if (y >= repY && y < repY + repH) {
            app.detailReport(this);
        } else if (y >= textY) {
            int step = Theme.body().getHeight() * 3;
            if (y < textY + (h - textY - barH) / 2) {
                scroll = Math.max(0, scroll - step);
            } else {
                scroll = Math.min(maxScroll(h), scroll + step);
            }
            repaint();
        }
    }
}
