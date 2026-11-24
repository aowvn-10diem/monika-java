package vn.aowvn.monika;

import javax.microedition.lcdui.Graphics;

/** Icon vẽ bằng nét vẽ cơ bản, không dùng file ảnh (JAR nhỏ, co giãn theo màn hình). */
final class Icons {
    static final int GRID = 0;
    static final int GAMEPAD = 1;
    static final int LAYERS = 2;
    static final int REFRESH = 3;
    static final int STAR = 4;
    static final int SEARCH = 5;

    // Ngôi sao 5 cánh: 10 điểm, đơn vị 1/1000 bán kính.
    private static final int[] STAR_X = {0, 225, 951, 363, 588, 0, -588, -363, -951, -225};
    private static final int[] STAR_Y = {-1000, -309, -309, 118, 809, 382, 809, 118, -309, -309};

    private Icons() {
    }

    static void draw(Graphics g, int kind, int x, int y, int s, int color) {
        g.setColor(color);
        switch (kind) {
            case GRID:
                grid(g, x, y, s);
                break;
            case GAMEPAD:
                gamepad(g, x, y, s);
                break;
            case LAYERS:
                layers(g, x, y, s);
                break;
            case REFRESH:
                refresh(g, x, y, s);
                break;
            case STAR:
                star(g, x, y, s);
                break;
            case SEARCH:
                search(g, x, y, s);
                break;
            default:
                break;
        }
    }

    private static void thickRect(Graphics g, int x, int y, int w, int h) {
        g.drawRoundRect(x, y, w, h, 4, 4);
        g.drawRoundRect(x + 1, y + 1, w - 2, h - 2, 3, 3);
    }

    private static void grid(Graphics g, int x, int y, int s) {
        int q = s / 2 - 2;
        thickRect(g, x + 1, y + 1, q, q);
        thickRect(g, x + s - q - 1, y + 1, q, q);
        thickRect(g, x + 1, y + s - q - 1, q, q);
        thickRect(g, x + s - q - 1, y + s - q - 1, q, q);
    }

    private static void gamepad(Graphics g, int x, int y, int s) {
        int top = y + s / 4;
        int h = s / 2 + 2;
        g.drawRoundRect(x, top, s - 1, h, h, h);
        g.drawRoundRect(x + 1, top + 1, s - 3, h - 2, h, h);
        int cy = top + h / 2;
        int cx = x + s / 4 + 1;
        int a = Math.max(2, s / 8);
        g.fillRect(cx - a, cy - 1, 2 * a + 1, 3);
        g.fillRect(cx - 1, cy - a, 3, 2 * a + 1);
        int bx = x + s - s / 4 - 2;
        g.fillRect(bx - 2, cy - 4, 3, 3);
        g.fillRect(bx + 2, cy, 3, 3);
    }

    private static void layers(Graphics g, int x, int y, int s) {
        int cx = x + s / 2;
        int step = Math.max(3, s / 5);
        int half = s / 2 - 1;
        for (int k = 0; k < 3; k++) {
            int top = y + 1 + k * step;
            // dùng 2 nét dày
            for (int d = 0; d < 2; d++) {
                g.drawLine(x + 1, top + half / 2 + d, cx, top + d);
                g.drawLine(cx, top + d, x + s - 2, top + half / 2 + d);
                g.drawLine(x + 1, top + half / 2 + d, cx, top + half + d);
                g.drawLine(cx, top + half + d, x + s - 2, top + half / 2 + d);
            }
        }
    }

    private static void refresh(Graphics g, int x, int y, int s) {
        int d = s - 4;
        for (int t = 0; t < 2; t++) {
            g.drawArc(x + 2 + t, y + 2 + t, d - 2 * t, d - 2 * t, 20, 140);
            g.drawArc(x + 2 + t, y + 2 + t, d - 2 * t, d - 2 * t, 200, 140);
        }
        int cy = y + s / 2;
        g.fillTriangle(x, cy - 1, x + 6, cy - 1, x + 3, cy + 4);
        g.fillTriangle(x + s - 1, cy + 1, x + s - 7, cy + 1, x + s - 4, cy - 4);
    }

    private static void star(Graphics g, int x, int y, int s) {
        int cx = x + s / 2;
        int cy = y + s / 2 + 1;
        int r = s / 2;
        for (int t = 0; t < 2; t++) {
            for (int i = 0; i < 10; i++) {
                int j = (i + 1) % 10;
                g.drawLine(cx + STAR_X[i] * r / 1000 + t, cy + STAR_Y[i] * r / 1000,
                    cx + STAR_X[j] * r / 1000 + t, cy + STAR_Y[j] * r / 1000);
            }
        }
    }

    private static void search(Graphics g, int x, int y, int s) {
        int d = s * 2 / 3;
        g.drawArc(x, y, d, d, 0, 360);
        g.drawArc(x + 1, y + 1, d - 2, d - 2, 0, 360);
        g.drawLine(x + d - 1, y + d - 1, x + s - 1, y + s - 1);
        g.drawLine(x + d, y + d - 1, x + s - 1, y + s - 2);
    }
}
