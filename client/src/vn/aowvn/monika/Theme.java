package vn.aowvn.monika;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Màu và font theo mockup: nền tím đậm, điểm nhấn cam và hồng. */
final class Theme {
    static final int BG_TOP = 0x1B0A33;
    static final int BG_BOT = 0x2E1158;
    static final int BAR_BG = 0x140729;
    static final int SEL_A = 0xFF8A2A;
    static final int SEL_B = 0xC24B00;
    static final int TEXT = 0xFFFFFF;
    static final int DIM = 0xB9A9D6;
    static final int LINE = 0x42306A;
    static final int PINK = 0xFF3D8B;
    /** Hồng đậm cho nền huy hiệu có chữ trắng (đủ tương phản). */
    static final int PINK_D = 0xDC1A70;
    static final int ORANGE = 0xFF7A1A;

    private Theme() {
    }

    static Font small() {
        return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    }

    static Font smallBold() {
        return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
    }

    static Font body() {
        return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_MEDIUM);
    }

    static Font bold() {
        return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    }

    static Font logo(boolean big) {
        return Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD,
            big ? Font.SIZE_LARGE : Font.SIZE_MEDIUM);
    }

    /** Trộn hai màu RGB, t từ 0 đến 256. */
    static int mix(int a, int b, int t) {
        int r = (((a >> 16) & 255) * (256 - t) + ((b >> 16) & 255) * t) >> 8;
        int g = (((a >> 8) & 255) * (256 - t) + ((b >> 8) & 255) * t) >> 8;
        int bl = ((a & 255) * (256 - t) + (b & 255) * t) >> 8;
        return (r << 16) | (g << 8) | bl;
    }

    /** Tô nền gradient dọc từ y đến y+h. */
    static void vgradient(Graphics g, int x, int y, int w, int h, int top, int bot) {
        if (h <= 0) {
            return;
        }
        for (int i = 0; i < h; i++) {
            g.setColor(mix(top, bot, h <= 1 ? 0 : i * 256 / (h - 1)));
            g.drawLine(x, y + i, x + w - 1, y + i);
        }
    }
}
