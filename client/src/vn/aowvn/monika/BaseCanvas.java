package vn.aowvn.monika;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Phần dùng chung của mọi màn hình vẽ tay: nhận diện phím mềm, phím giữa, cắt chữ. */
abstract class BaseCanvas extends Canvas {
    static final int SOFT_NONE = 0;
    static final int SOFT_LEFT = 1;
    static final int SOFT_RIGHT = 2;

    protected int gameAction(int code) {
        try {
            return getGameAction(code);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /** true: phím số 2/4/6/8 đi lên/trái/phải/xuống, 5 là OK (mặc định); false: phím 1..9 mở nhanh mục 1..9. */
    static boolean numNav = true;

    static boolean isLetter(int code) {
        return (code >= 'a' && code <= 'z') || (code >= 'A' && code <= 'Z');
    }

    /**
     * Số bấm 0 đến 9, hoặc -1 nếu không phải phím số. Bàn phím QWERTY (Nokia E72...) in số ở góc phím
     * nên R T Y / F G H / V B N / M cũng tính là 1 2 3 / 4 5 6 / 7 8 9 / 0. Chỉ gọi ở màn không nhập chữ.
     */
    protected int digit(int code) {
        if (code >= '0' && code <= '9') {
            return code - '0';
        }
        int c = code >= 'A' && code <= 'Z' ? code + 32 : code;
        switch (c) {
            case 'r': return 1;
            case 't': return 2;
            case 'y': return 3;
            case 'f': return 4;
            case 'g': return 5;
            case 'h': return 6;
            case 'v': return 7;
            case 'b': return 8;
            case 'n': return 9;
            case 'm': return 0;
            default: return -1;
        }
    }

    /** Phím * (hoặc phím U có in * trên bàn phím QWERTY). */
    protected boolean isStar(int code) {
        return code == Canvas.KEY_STAR || code == 'u' || code == 'U';
    }

    /** Phím # (hoặc phím J có in # trên bàn phím QWERTY). */
    protected boolean isPound(int code) {
        return code == Canvas.KEY_POUND || code == 'j' || code == 'J';
    }

    /**
     * Hướng bấm: Canvas.UP, DOWN, LEFT, RIGHT, FIRE hoặc 0. Cần điều hướng dùng game action của máy;
     * khi bật phím số thì 2/4/6/8/5 (kể cả T F H B G trên QWERTY) cũng là lên/trái/phải/xuống/OK.
     * Chữ cái còn lại và phím số bỏ qua hẳn game action (một số máy gán lung tung cho chữ).
     */
    protected int dir(int code) {
        int d = digit(code);
        if (d >= 0) {
            if (numNav) {
                switch (d) {
                    case 2: return Canvas.UP;
                    case 4: return Canvas.LEFT;
                    case 6: return Canvas.RIGHT;
                    case 8: return Canvas.DOWN;
                    case 5: return Canvas.FIRE;
                    default: break;
                }
            }
            return 0;
        }
        if (isLetter(code)) {
            return 0;
        }
        int ga = gameAction(code);
        return ga == Canvas.UP || ga == Canvas.DOWN || ga == Canvas.LEFT || ga == Canvas.RIGHT
            || ga == Canvas.FIRE ? ga : 0;
    }

    /** Phím giữa của cần điều hướng: mã -5 trên S60 và S40, Enter, hoặc game action FIRE. Chữ cái không bao giờ là phím giữa. */
    protected boolean isFire(int code) {
        if (isLetter(code) || (code >= '0' && code <= '9')) {
            return false;
        }
        if (code == -5 || code == 10 || code == 13) {
            return true;
        }
        if (gameAction(code) == Canvas.FIRE) {
            return true;
        }
        try {
            String n = getKeyName(code);
            if (n != null) {
                n = n.toLowerCase();
                return n.indexOf("select") >= 0 || n.indexOf("fire") >= 0 || n.indexOf("enter") >= 0
                    || n.equals("ok");
            }
        } catch (IllegalArgumentException e) {
            // mã phím không có tên
        }
        return false;
    }

    /** SOFT_LEFT, SOFT_RIGHT hoặc SOFT_NONE. */
    protected int softKey(int code) {
        if (isLetter(code)) {
            return SOFT_NONE;
        }
        try {
            String n = getKeyName(code);
            if (n != null) {
                n = n.toLowerCase();
                if (n.indexOf("soft") >= 0) {
                    if (n.indexOf('1') >= 0 || n.indexOf("left") >= 0) {
                        return SOFT_LEFT;
                    }
                    if (n.indexOf('2') >= 0 || n.indexOf("right") >= 0) {
                        return SOFT_RIGHT;
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            // mã phím không có tên
        }
        if (code == -6 || code == -21 || code == 21) {
            return SOFT_LEFT;
        }
        if (code == -7 || code == -22 || code == 22) {
            return SOFT_RIGHT;
        }
        return SOFT_NONE;
    }

    /** Cắt chuỗi cho vừa bề rộng, thêm "..." nếu phải cắt. */
    static String fit(Font f, String s, int maxW) {
        if (maxW <= 0) {
            return "";
        }
        if (f.stringWidth(s) <= maxW) {
            return s;
        }
        int dots = f.stringWidth("...");
        int n = s.length();
        while (n > 0 && f.substringWidth(s, 0, n) + dots > maxW) {
            n--;
        }
        return s.substring(0, n) + "...";
    }

    /** Ngắt dòng theo từ, giữ ký tự xuống dòng. */
    static String[] wrap(Font f, String s, int maxW) {
        java.util.Vector out = new java.util.Vector();
        int start = 0;
        int n = s.length();
        while (start <= n) {
            int nl = s.indexOf('\n', start);
            String para = nl < 0 ? s.substring(start) : s.substring(start, nl);
            wrapPara(f, para, maxW, out);
            if (nl < 0) {
                break;
            }
            start = nl + 1;
        }
        String[] r = new String[out.size()];
        out.copyInto(r);
        return r;
    }

    private static void wrapPara(Font f, String p, int maxW, java.util.Vector out) {
        if (p.length() == 0) {
            out.addElement("");
            return;
        }
        int pos = 0;
        int n = p.length();
        while (pos < n) {
            int end = pos;
            int lastSpace = -1;
            while (end < n && f.substringWidth(p, pos, end - pos + 1) <= maxW) {
                if (p.charAt(end) == ' ') {
                    lastSpace = end;
                }
                end++;
            }
            if (end >= n) {
                out.addElement(p.substring(pos));
                return;
            }
            if (end == pos) {
                end = pos + 1; // một ký tự rộng hơn cả dòng
            } else if (lastSpace > pos) {
                end = lastSpace;
            }
            out.addElement(p.substring(pos, end));
            pos = end;
            while (pos < n && p.charAt(pos) == ' ') {
                pos++;
            }
        }
    }

    /** Vẽ viên thuốc chữ (huy hiệu). Trả bề rộng đã dùng. */
    static int pill(Graphics g, Font f, String s, int right, int cy, int bg) {
        int bh = f.getHeight();
        int bw = Math.max(f.stringWidth(s) + 8, bh);
        int bx = right - bw;
        g.setColor(bg);
        g.fillRoundRect(bx, cy - bh / 2, bw, bh, bh, bh);
        g.setColor(Theme.TEXT);
        g.setFont(f);
        g.drawString(s, bx + bw / 2, cy - bh / 2, Graphics.TOP | Graphics.HCENTER);
        return bw;
    }

    /** Huy hiệu hạng 1-2-3: vòng tròn vàng, bạc, đồng có số (vẽ bằng Canvas, không dùng ảnh). */
    static void medal(Graphics g, int cx, int cy, int d, int rank) {
        int c = rank == 1 ? 0xFFC83D : rank == 2 ? 0xC9D1DB : 0xD08A4B;
        int dark = rank == 1 ? 0x9A6A00 : rank == 2 ? 0x6B7684 : 0x7A4515;
        int r = d / 2;
        g.setColor(c);
        g.fillArc(cx - r, cy - r, d, d, 0, 360);
        g.setColor(dark);
        g.drawArc(cx - r, cy - r, d, d, 0, 360);
        Font f = Theme.smallBold();
        g.setFont(f);
        g.setColor(0x2B1A00);
        g.drawString(Integer.toString(rank), cx, cy - f.getHeight() / 2, Graphics.TOP | Graphics.HCENTER);
    }

    /** Viên thuốc số lượt tải: mũi tên xuống (vẽ bằng nét) và chữ, canh phải/đáy theo (right, bottom). */
    static void dlPill(Graphics g, Font f, String s, int right, int bottom) {
        int bh = f.getHeight();
        int aw = bh / 2 + 2;
        int bw = aw + f.stringWidth(s) + 10;
        int bx = right - bw;
        int by = bottom - bh;
        g.setColor(Theme.BAR_BG);
        g.fillRoundRect(bx, by, bw, bh, bh, bh);
        g.setColor(Theme.LINE);
        g.drawRoundRect(bx, by, bw - 1, bh - 1, bh, bh);
        int ax = bx + 4 + aw / 2;
        int y1 = by + bh / 4;
        int y2 = by + bh - bh / 4 - 1;
        g.setColor(Theme.ORANGE);
        g.drawLine(ax, y1, ax, y2);
        g.drawLine(ax - 2, y2 - 2, ax, y2);
        g.drawLine(ax + 2, y2 - 2, ax, y2);
        g.setColor(Theme.TEXT);
        g.setFont(f);
        g.drawString(s, bx + 4 + aw + 2, by, Graphics.TOP | Graphics.LEFT);
    }

    /** Màu nền huy hiệu thẻ: VH xanh lá, MOD tím, còn lại xanh dương. */
    static int tagColor(String label) {
        // cỡ màn hình (240x320, 320x240): xanh ngọc để tách khỏi thẻ VH/MOD
        return label.indexOf('x') > 0 ? 0x0E7FA8 : "VH".equals(label) ? 0x178A4C : "MOD".equals(label) ? 0x7B3FE4 : 0x1F6FB5;
    }
}
