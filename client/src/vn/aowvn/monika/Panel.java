package vn.aowvn.monika;

import java.util.Vector;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Màn hình vẽ tay dùng chung cho menu Tùy chọn, tìm kiếm, cài đặt, thông tin, giới thiệu và thông báo:
 * cùng bảng màu, header, thanh phím mềm với danh sách và trang chủ, không dùng Form/List/Alert gốc.
 *
 * Bốn kiểu: MENU (danh sách chọn), TEXT (đọc, cuộn), FORM (hàng bật/tắt, chọn, nhập), INPUT (ô gõ chữ).
 * Phím mềm trái/phải gọi {@link MonikaMidlet#panelAction}; ở chế độ không toàn màn hình dùng Command gốc
 * chỉ để nhận phím mềm, nội dung vẫn tự vẽ.
 */
final class Panel extends BaseCanvas {
    static final int MENU = 0;
    static final int TEXT = 1;
    static final int FORM = 2;
    static final int INPUT = 3;

    static final int K_ACT = 0;
    static final int K_TOGGLE = 1;
    static final int K_CHOICE = 2;
    static final int K_TEXT = 3;
    static final int K_INFO = 4;
    static final int K_PARA = 5;

    /** Hành động gửi cho MonikaMidlet. */
    static final int A_LEFT = 0;
    static final int A_RIGHT = 1;
    static final int A_PICK = 2;
    static final int A_EDIT = 3;
    static final int A_SEARCH = 4;
    static final int A_NATIVE = 5;

    private static final int MAXLEN = 60;
    private static final String[] MT = {
        " 0", ".,'-?!1@/:_", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9"
    };

    /** Một hàng của MENU, TEXT, FORM. */
    static final class Row {
        int kind;
        String label;
        String value;
        boolean on;
        String[] opts;
        int idx;
    }

    final MonikaMidlet app;
    final int kind;
    final int id;
    /** Màn hình quay về khi bấm OK trên thông báo (chỉ dùng cho TEXT kiểu thông báo). */
    javax.microedition.lcdui.Displayable next;

    private final String title;
    private final String left;
    private final String right;
    private final boolean full;
    private final Command cmdLeft;
    private final Command cmdRight;
    private final Vector rows = new Vector();

    private int sel = -1;
    private int scroll;
    private int contentH;

    // bố cục tính lại mỗi lần vẽ
    private int hh;
    private int barH;

    // INPUT
    private StringBuffer text = new StringBuffer();
    private String hint = "";
    private boolean qwerty;
    private int mtKey = -1;
    private int mtIdx;
    private long mtTime;

    // cảm ứng
    private int pdownY = -1;
    private int plastY;
    private boolean pdrag;

    Panel(MonikaMidlet app, int kind, int id, String title, String left, String right, boolean full) {
        this.app = app;
        this.kind = kind;
        this.id = id;
        this.title = title;
        this.left = left;
        this.right = right;
        this.full = full;
        setFullScreenMode(full);
        if (!full) {
            cmdLeft = left == null ? null : new Command(left, Command.OK, 1);
            cmdRight = right == null ? null : new Command(right, Command.BACK, 1);
            if (cmdLeft != null) {
                addCommand(cmdLeft);
            }
            if (cmdRight != null) {
                addCommand(cmdRight);
            }
        } else {
            cmdLeft = null;
            cmdRight = null;
        }
    }

    // ---------- nội dung ----------

    private Row add(int k, String label, String value) {
        Row r = new Row();
        r.kind = k;
        r.label = label;
        r.value = value;
        rows.addElement(r);
        if (sel < 0 && isSelectable(r)) {
            sel = rows.size() - 1;
        }
        return r;
    }

    void addItem(String label) {
        add(K_ACT, label, null);
    }

    void addToggle(String label, boolean on) {
        add(K_TOGGLE, label, null).on = on;
    }

    void addChoice(String label, String[] opts, int idx) {
        Row r = add(K_CHOICE, label, null);
        r.opts = opts;
        r.idx = idx;
    }

    void addText(String label, String value) {
        add(K_TEXT, label, value);
    }

    void addInfo(String label, String value) {
        add(K_INFO, label, value);
    }

    void addPara(String s) {
        add(K_PARA, null, s);
    }

    Row row(int i) {
        return (Row) rows.elementAt(i);
    }

    int rowCount() {
        return rows.size();
    }

    int selected() {
        return sel < 0 ? 0 : sel;
    }

    private static boolean isSelectable(Row r) {
        return r.kind != K_INFO && r.kind != K_PARA;
    }

    void setInput(String initial, String hintText) {
        text = new StringBuffer(initial == null ? "" : initial);
        hint = hintText == null ? "" : hintText;
        repaint();
    }

    void setText(String t) {
        text = new StringBuffer(t == null ? "" : t);
        mtKey = -1;
        repaint();
    }

    String inputText() {
        return text.toString();
    }

    void onCommand(Command c) {
        if (c == cmdLeft) {
            app.panelAction(this, kind == MENU ? A_PICK : A_LEFT, selected());
        } else if (c == cmdRight) {
            app.panelAction(this, A_RIGHT, selected());
        }
    }

    // ---------- bố cục ----------

    private void layout(int w, int h) {
        Font fb = Theme.bold();
        hh = fb.getHeight() + 8;
        barH = full ? fb.getHeight() + 8 : 0;
    }

    private int rowH(Row r, int w) {
        Font f = Theme.body();
        Font fs = Theme.small();
        if (r.kind == K_INFO) {
            return fs.getHeight() + wrap(f, r.value == null ? "-" : r.value, w - 16).length * f.getHeight() + 8;
        }
        if (r.kind == K_PARA) {
            return wrap(f, r.value == null ? "" : r.value, w - 16).length * f.getHeight() + 8;
        }
        return f.getHeight() + 14;
    }

    private int rowTop(int idx, int w) {
        int y = 0;
        for (int i = 0; i < idx; i++) {
            y += rowH(row(i), w);
        }
        return y;
    }

    private int totalH(int w) {
        return rowTop(rows.size(), w);
    }

    private void clamp(int w, int h) {
        layout(w, h);
        int view = h - hh - barH;
        int total = totalH(w);
        contentH = total;
        int max = total - view;
        if (max < 0) {
            max = 0;
        }
        if (scroll > max) {
            scroll = max;
        }
        if (scroll < 0) {
            scroll = 0;
        }
    }

    private void ensureVisible(int w, int h) {
        if (sel < 0) {
            return;
        }
        layout(w, h);
        int view = h - hh - barH;
        int top = rowTop(sel, w);
        int bot = top + rowH(row(sel), w);
        if (top < scroll) {
            scroll = top;
        } else if (bot > scroll + view) {
            scroll = bot - view;
        }
        clamp(w, h);
    }

    // ---------- vẽ ----------

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        layout(w, h);
        g.setClip(0, 0, w, h);
        Theme.vgradient(g, 0, 0, w, h, Theme.BG_TOP, Theme.BG_BOT);
        paintHeader(g, w);
        g.setClip(0, hh, w, h - hh - barH);
        if (kind == INPUT) {
            paintInput(g, w, h);
        } else {
            clamp(w, h);
            int y = hh - scroll;
            for (int i = 0; i < rows.size(); i++) {
                Row r = row(i);
                int rh = rowH(r, w);
                if (y + rh > hh && y < h - barH) {
                    paintRow(g, r, i == sel, y, rh, w);
                }
                y += rh;
            }
            paintScrollbar(g, w, h);
        }
        g.setClip(0, 0, w, h);
        if (full) {
            paintBar(g, w, h);
        }
    }

    private void paintHeader(Graphics g, int w) {
        Font fb = Theme.bold();
        for (int x = 0; x < w; x++) {
            int t = x * 256 / Math.max(1, w - 1);
            g.setColor(t < 160 ? Theme.mix(0x2B0F4F, 0x5A1670, t * 256 / 160)
                : Theme.mix(0x5A1670, 0xB8307A, (t - 160) * 256 / 96));
            g.drawLine(x, 0, x, hh - 1);
        }
        g.setColor(Theme.ORANGE);
        g.fillRect(0, hh - 2, w, 2);
        g.setFont(fb);
        g.setColor(Theme.ORANGE);
        g.drawString("M", 6, 3, Graphics.TOP | Graphics.LEFT);
        int mw = fb.stringWidth("M ");
        g.setColor(Theme.TEXT);
        g.drawString(fit(fb, title, w - 6 - mw - 4), 6 + mw, 3, Graphics.TOP | Graphics.LEFT);
    }

    private void paintRow(Graphics g, Row r, boolean on, int y, int rh, int w) {
        Font f = on ? Theme.bold() : Theme.body();
        Font fs = Theme.small();
        if (r.kind == K_INFO) {
            g.setFont(fs);
            g.setColor(Theme.DIM);
            g.drawString(fit(fs, r.label, w - 16), 8, y + 2, Graphics.TOP | Graphics.LEFT);
            Font b = Theme.body();
            String[] ls = wrap(b, r.value == null ? "-" : r.value, w - 16);
            g.setFont(b);
            g.setColor(Theme.TEXT);
            int ty = y + 2 + fs.getHeight();
            for (int i = 0; i < ls.length; i++) {
                g.drawString(ls[i], 8, ty, Graphics.TOP | Graphics.LEFT);
                ty += b.getHeight();
            }
            g.setColor(Theme.LINE);
            g.drawLine(8, y + rh - 1, w - 9, y + rh - 1);
            return;
        }
        if (r.kind == K_PARA) {
            Font b = Theme.body();
            String[] ls = wrap(b, r.value == null ? "" : r.value, w - 16);
            g.setFont(b);
            g.setColor(Theme.TEXT);
            int ty = y + 4;
            for (int i = 0; i < ls.length; i++) {
                g.drawString(ls[i], 8, ty, Graphics.TOP | Graphics.LEFT);
                ty += b.getHeight();
            }
            return;
        }
        if (on) {
            Theme.vgradient(g, 1, y, w - 2, rh, Theme.SEL_A, Theme.SEL_B);
            g.setColor(Theme.mix(Theme.SEL_A, 0xFFFFFF, 80));
            g.drawLine(1, y, w - 2, y);
        } else {
            g.setColor(Theme.LINE);
            g.drawLine(8, y + rh - 1, w - 9, y + rh - 1);
        }
        g.setFont(f);
        g.setColor(Theme.TEXT);
        int ty = y + (rh - f.getHeight()) / 2;
        int rightEdge = w - 10;
        if (r.kind == K_ACT) {
            drawChevron(g, w - 12, y + rh / 2);
            g.drawString(fit(f, r.label, w - 36), 10, ty, Graphics.TOP | Graphics.LEFT);
        } else if (r.kind == K_TOGGLE) {
            int s = Math.min(rh - 8, 16);
            int bx = rightEdge - s;
            int by = y + (rh - s) / 2;
            g.setColor(Theme.TEXT);
            g.drawRect(bx, by, s, s);
            if (r.on) {
                g.fillRect(bx + 3, by + 3, s - 5, s - 5);
            }
            g.setFont(f);
            g.drawString(fit(f, r.label, bx - 20), 10, ty, Graphics.TOP | Graphics.LEFT);
        } else {
            String v = r.kind == K_CHOICE ? r.opts[r.idx] : (r.value == null || r.value.length() == 0
                ? Vi.tr("(trống)") : r.value);
            Font vf = Theme.small();
            int vw = Math.min(vf.stringWidth(v), (w - 20) / 2);
            g.setFont(vf);
            g.setColor(on ? Theme.TEXT : Theme.DIM);
            g.drawString(fit(vf, v, vw), rightEdge, y + (rh - vf.getHeight()) / 2,
                Graphics.TOP | Graphics.RIGHT);
            g.setFont(f);
            g.setColor(Theme.TEXT);
            g.drawString(fit(f, r.label, w - 28 - vw), 10, ty, Graphics.TOP | Graphics.LEFT);
        }
    }

    private static void drawChevron(Graphics g, int cx, int cy) {
        g.setColor(Theme.TEXT);
        for (int t = 0; t < 2; t++) {
            g.drawLine(cx - 3 + t, cy - 5, cx + 1 + t, cy);
            g.drawLine(cx + 1 + t, cy, cx - 3 + t, cy + 5);
        }
    }

    private void paintScrollbar(Graphics g, int w, int h) {
        int view = h - hh - barH;
        if (contentH <= view || view <= 0) {
            return;
        }
        int bh = Math.max(10, view * view / contentH);
        int by = hh + (view - bh) * scroll / (contentH - view);
        g.setColor(Theme.ORANGE);
        g.fillRect(w - 3, by, 3, bh);
    }

    private void paintInput(Graphics g, int w, int h) {
        Font f = Theme.body();
        Font fs = Theme.small();
        int y = hh + 10;
        if (hint.length() > 0) {
            g.setFont(fs);
            g.setColor(Theme.DIM);
            g.drawString(fit(fs, hint, w - 16), 8, y, Graphics.TOP | Graphics.LEFT);
            y += fs.getHeight() + 6;
        }
        int fh = f.getHeight() + 12;
        g.setColor(Theme.BAR_BG);
        g.fillRoundRect(6, y, w - 12, fh, 8, 8);
        g.setColor(Theme.ORANGE);
        g.drawRoundRect(6, y, w - 12, fh, 8, 8);
        g.setFont(f);
        g.setColor(Theme.TEXT);
        String s = text.toString();
        int avail = w - 12 - 14;
        int from = 0;
        while (from < s.length() && f.substringWidth(s, from, s.length() - from) > avail) {
            from++;
        }
        String shown = s.substring(from);
        g.drawString(shown, 13, y + 6, Graphics.TOP | Graphics.LEFT);
        int cx = 13 + f.stringWidth(shown);
        g.setColor(Theme.ORANGE);
        g.fillRect(cx + 1, y + 5, 2, f.getHeight() + 2);
        y += fh + 8;
        g.setFont(fs);
        g.setColor(Theme.DIM);
        String mode = qwerty ? Vi.tr("Gõ bằng bàn phím") : Vi.tr("Bấm phím số như nhắn tin, # đổi chữ/số");
        String[] ls = wrap(fs, mode, w - 16);
        for (int i = 0; i < ls.length; i++) {
            g.drawString(ls[i], 8, y, Graphics.TOP | Graphics.LEFT);
            y += fs.getHeight();
        }
        y += 4;
        String tap = Vi.tr("Chạm vào ô để dùng bàn phím máy");
        ls = wrap(fs, tap, w - 16);
        for (int i = 0; i < ls.length; i++) {
            g.drawString(ls[i], 8, y, Graphics.TOP | Graphics.LEFT);
            y += fs.getHeight();
        }
    }

    private void paintBar(Graphics g, int w, int h) {
        int y = h - barH;
        g.setColor(Theme.BAR_BG);
        g.fillRect(0, y, w, barH);
        g.setColor(Theme.LINE);
        g.drawLine(0, y, w - 1, y);
        Font bf = Theme.bold();
        g.setFont(bf);
        g.setColor(Theme.TEXT);
        int ty = y + (barH - bf.getHeight()) / 2;
        if (left != null) {
            g.drawString(left, 4, ty, Graphics.TOP | Graphics.LEFT);
        }
        if (right != null) {
            g.drawString(right, w - 4, ty, Graphics.TOP | Graphics.RIGHT);
        }
    }

    // ---------- phím ----------

    private void move(int dir) {
        int w = getWidth();
        int h = getHeight();
        int n = rows.size();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            if (isSelectable(row(i))) {
                any = true;
            }
        }
        if (!any) {
            scroll += dir * (Theme.body().getHeight() * 2);
            clamp(w, h);
        } else {
            int i = sel;
            for (int t = 0; t < n; t++) {
                i = (i + dir + n) % n;
                if (isSelectable(row(i))) {
                    sel = i;
                    break;
                }
            }
            ensureVisible(w, h);
        }
        repaint();
    }

    private void activate() {
        if (sel < 0) {
            return;
        }
        Row r = row(sel);
        if (r.kind == K_TOGGLE) {
            r.on = !r.on;
            repaint();
        } else if (r.kind == K_CHOICE) {
            r.idx = (r.idx + 1) % r.opts.length;
            repaint();
        } else if (r.kind == K_TEXT) {
            app.panelAction(this, A_EDIT, sel);
        } else if (r.kind == K_ACT) {
            app.panelAction(this, A_PICK, sel);
        }
    }

    protected void keyPressed(int code) {
        if (kind == INPUT) {
            inputKey(code);
            return;
        }
        if (code == Canvas.KEY_STAR) {
            app.panelAction(this, A_SEARCH, selected());
            return;
        }
        if (kind == MENU && code >= Canvas.KEY_NUM1 && code <= Canvas.KEY_NUM9) {
            int i = code - Canvas.KEY_NUM1;
            if (i < rows.size()) {
                sel = i;
                repaint();
                app.panelAction(this, A_PICK, i);
            }
            return;
        }
        int soft = softKey(code);
        if (soft == SOFT_LEFT) {
            if (left != null) {
                if (kind == MENU) {
                    app.panelAction(this, A_PICK, selected());
                } else {
                    app.panelAction(this, A_LEFT, selected());
                }
            }
            return;
        }
        if (soft == SOFT_RIGHT || code == -8 || code == 8) {
            app.panelAction(this, A_RIGHT, selected());
            return;
        }
        if (isFire(code)) {
            if (kind == TEXT) {
                app.panelAction(this, left != null ? A_LEFT : A_RIGHT, 0);
            } else {
                activate();
            }
            return;
        }
        int ga = gameAction(code);
        if (ga == Canvas.UP) {
            move(-1);
        } else if (ga == Canvas.DOWN) {
            move(1);
        } else if ((ga == Canvas.LEFT || ga == Canvas.RIGHT) && sel >= 0 && row(sel).kind == K_CHOICE) {
            Row r = row(sel);
            r.idx = (r.idx + (ga == Canvas.RIGHT ? 1 : r.opts.length - 1)) % r.opts.length;
            repaint();
        }
    }

    protected void keyRepeated(int code) {
        int ga = gameAction(code);
        if (kind != INPUT && (ga == Canvas.UP || ga == Canvas.DOWN)) {
            move(ga == Canvas.UP ? -1 : 1);
        } else if (kind == INPUT && (code == -8 || code == 8)) {
            inputKey(code);
        }
    }

    // ---------- nhập chữ ----------

    private void inputKey(int code) {
        int soft = softKey(code);
        if (soft == SOFT_LEFT) {
            app.panelAction(this, A_LEFT, 0);
            return;
        }
        if (soft == SOFT_RIGHT) {
            app.panelAction(this, A_RIGHT, 0);
            return;
        }
        if (code == -8 || code == 8 || code == 127) {
            int n = text.length();
            if (n > 0) {
                text.setLength(n - 1);
            } else if (code != 127) {
                app.panelAction(this, A_RIGHT, 0);
                return;
            }
            mtKey = -1;
            repaint();
            return;
        }
        if (code == 10 || code == 13 || isFire(code)) {
            app.panelAction(this, A_LEFT, 0);
            return;
        }
        if (code == Canvas.KEY_POUND) {
            qwerty = !qwerty;
            mtKey = -1;
            repaint();
            return;
        }
        boolean letter = (code >= 'a' && code <= 'z') || (code >= 'A' && code <= 'Z');
        if (letter) {
            qwerty = true;
        }
        if (!qwerty && code >= '0' && code <= '9') {
            multitap(code - '0');
            return;
        }
        if (code >= 32 && code != 127 && text.length() < MAXLEN) {
            text.append((char) code);
            mtKey = -1;
            repaint();
        }
    }

    private void multitap(int d) {
        String set = MT[d];
        long now = System.currentTimeMillis();
        if (d == mtKey && now - mtTime < 1000 && text.length() > 0) {
            mtIdx = (mtIdx + 1) % set.length();
            text.setCharAt(text.length() - 1, set.charAt(mtIdx));
        } else if (text.length() < MAXLEN) {
            mtIdx = 0;
            text.append(set.charAt(0));
        }
        mtKey = d;
        mtTime = now;
        repaint();
    }

    // ---------- cảm ứng ----------

    protected void pointerPressed(int x, int y) {
        pdownY = y;
        plastY = y;
        pdrag = false;
    }

    protected void pointerDragged(int x, int y) {
        if (kind == INPUT || pdownY < 0) {
            return;
        }
        if (Math.abs(y - pdownY) > 6) {
            pdrag = true;
        }
        if (pdrag) {
            scroll -= y - plastY;
            clamp(getWidth(), getHeight());
            repaint();
        }
        plastY = y;
    }

    protected void pointerReleased(int x, int y) {
        int w = getWidth();
        int h = getHeight();
        layout(w, h);
        boolean tap = pdownY >= 0 && !pdrag;
        pdownY = -1;
        if (!tap) {
            return;
        }
        if (full && y >= h - barH) {
            if (x < w / 2) {
                if (left != null) {
                    if (kind == MENU) {
                        app.panelAction(this, A_PICK, selected());
                    } else {
                        app.panelAction(this, A_LEFT, selected());
                    }
                }
            } else if (right != null) {
                app.panelAction(this, A_RIGHT, selected());
            }
            return;
        }
        if (kind == INPUT) {
            if (y >= hh) {
                app.panelAction(this, A_NATIVE, 0);
            }
            return;
        }
        if (y < hh) {
            return;
        }
        int pos = y - hh + scroll;
        int acc = 0;
        for (int i = 0; i < rows.size(); i++) {
            int rh = rowH(row(i), w);
            if (pos >= acc && pos < acc + rh) {
                if (isSelectable(row(i))) {
                    sel = i;
                    repaint();
                    activate();
                }
                return;
            }
            acc += rh;
        }
    }
}
