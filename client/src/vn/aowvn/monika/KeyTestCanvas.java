package vn.aowvn.monika;

import java.util.Vector;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Màn Kiểm tra phím: mỗi phím bấm hiện mã phím, tên máy báo, game action của máy và cách Monika hiểu phím đó.
 * Dùng để đối chiếu bàn phím lạ (QWERTY như Nokia E72, T9 đời cũ) khi phím không chạy như mong đợi.
 * Thoát bằng phím mềm phải, phím xóa C hoặc chạm vào thanh dưới.
 */
final class KeyTestCanvas extends BaseCanvas {
    private static final int KEEP = 12;

    private final MonikaMidlet app;
    private final Vector log = new Vector();

    KeyTestCanvas(MonikaMidlet app) {
        this.app = app;
        setFullScreenMode(true);
    }

    /** Cách Monika hiểu một mã phím (cùng quy tắc với các màn hình khác). */
    String describe(int code) {
        int soft = softKey(code);
        if (soft == SOFT_LEFT) {
            return "phím mềm trái";
        }
        if (soft == SOFT_RIGHT) {
            return "phím mềm phải";
        }
        if (code == -8 || code == 8) {
            return "phím xóa";
        }
        int ga = dir(code);
        if (ga == Canvas.UP) {
            return "lên";
        }
        if (ga == Canvas.DOWN) {
            return "xuống";
        }
        if (ga == Canvas.LEFT) {
            return "trái";
        }
        if (ga == Canvas.RIGHT) {
            return "phải";
        }
        if (isFire(code) || ga == Canvas.FIRE) {
            return "OK";
        }
        if (isStar(code)) {
            return "* tìm kiếm";
        }
        if (isPound(code)) {
            return "# trang sau";
        }
        int d = digit(code);
        if (d >= 0) {
            return "số " + d;
        }
        return "bỏ qua";
    }

    protected void keyPressed(int code) {
        if (softKey(code) == SOFT_RIGHT || code == -8 || code == 8) {
            app.keyTestExit();
            return;
        }
        String name;
        try {
            name = getKeyName(code);
        } catch (IllegalArgumentException e) {
            name = "?";
        }
        log.addElement(code + " [" + name + "] ga=" + gameAction(code) + " > " + describe(code));
        if (log.size() > KEEP) {
            log.removeElementAt(0);
        }
        repaint();
    }

    protected void pointerPressed(int x, int y) {
        if (y >= getHeight() - Theme.small().getHeight() - 10) {
            app.keyTestExit();
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        Font f = Theme.small();
        Font fb = Theme.bold();
        Theme.vgradient(g, 0, 0, w, h, Theme.BG_TOP, Theme.BG_BOT);
        int y = 4;
        g.setFont(fb);
        g.setColor(Theme.ORANGE);
        g.drawString(Vi.tr("Kiểm tra phím"), 6, y, Graphics.TOP | Graphics.LEFT);
        y += fb.getHeight() + 2;
        g.setFont(f);
        g.setColor(Theme.DIM);
        String[] intro = wrap(f, Vi.tr("Bấm thử từng phím: mã, tên, game action (ga) và cách Monika hiểu."), w - 12);
        for (int i = 0; i < intro.length; i++) {
            g.drawString(intro[i], 6, y, Graphics.TOP | Graphics.LEFT);
            y += f.getHeight();
        }
        y += 4;
        int barH = f.getHeight() + 10;
        int room = (h - barH - y) / f.getHeight();
        int from = Math.max(0, log.size() - room);
        g.setColor(Theme.TEXT);
        for (int i = from; i < log.size(); i++) {
            g.setColor(i == log.size() - 1 ? Theme.ORANGE : Theme.TEXT);
            g.drawString(fit(f, (String) log.elementAt(i), w - 12), 6, y, Graphics.TOP | Graphics.LEFT);
            y += f.getHeight();
        }
        g.setColor(Theme.BAR_BG);
        g.fillRect(0, h - barH, w, barH);
        g.setColor(Theme.LINE);
        g.drawLine(0, h - barH, w - 1, h - barH);
        g.setFont(f);
        g.setColor(Theme.TEXT);
        g.drawString(Vi.tr("Thoát: phím mềm phải hoặc phím C"), w / 2, h - barH + 5, Graphics.TOP | Graphics.HCENTER);
    }
}
