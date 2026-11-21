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

    /** Phím giữa của cần điều hướng: mã -5 trên S60 và S40, Enter, hoặc game action FIRE. */
    protected boolean isFire(int code) {
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
}
