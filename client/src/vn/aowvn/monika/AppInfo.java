package vn.aowvn.monika;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Một mục trong catalog (id, tên, dung lượng, giá, cờ...). */
final class AppInfo {
    static final int F_NEW = 1;
    static final int F_UPDATE = 2;
    static final int F_NEEDNET = 4;
    static final int F_HOT = 8;
    /** Có icon: lấy ở /v1/ic/&lt;id&gt;-32.png. */
    static final int F_ICON = 16;
    /** Có ảnh minh họa: lấy ở /v1/sc/&lt;id&gt;-&lt;w&gt;x&lt;h&gt;.jpg (cắt giữa đúng cỡ). */
    static final int F_SHOT = 32;
    /** Bài viết (truyện, thủ thuật): nút Mở chuyển tới trang bài, không có JAR. */
    static final int F_POST = 64;
    /** Giao diện / âm thanh (hình nền, theme, nhạc chuông): nút mở trang chọn bản tải. */
    static final int F_MEDIA = 128;

    static final int CAT_GAME = 1;

    /** Bit thẻ (khớp taxonomy): 1 gốc, 2 Việt hóa, 4 mod, 8 3D, 16 cảm ứng, 32 game Việt, 64 nhiều người, 128 cần mạng. */
    static final int T_VH = 2;
    static final int T_MOD = 4;
    private static final int[] TAG_BITS = {2, 4, 8, 16, 32, 64};
    private static final String[] TAG_NAMES = {"VH", "MOD", "3D", "TOUCH", "VN", "MP"};

    int id;
    int cat;
    int flags;
    int sizeKb;
    int price;
    /** Lượt tải (v2); 0 nếu máy chủ cũ hoặc mục lưu từ bản trước. */
    int dl;
    /** Bit thẻ (v2). */
    int tags;
    /** Bit nền tảng (v2). */
    int plat;
    String version = "";
    String name = "";
    String desc = "";
    /** Đường dẫn tương đối tới JAD (rỗng = chưa có file cài). */
    String path = "";

    boolean isFree() {
        return price <= 0;
    }

    boolean has(int flag) {
        return (flags & flag) != 0;
    }

    /** Đọc một mục theo định dạng MKA1/MKL1. */
    static AppInfo read(DataInputStream in) throws IOException {
        AppInfo a = new AppInfo();
        a.id = in.readInt();
        a.cat = in.readUnsignedByte();
        a.flags = in.readUnsignedByte();
        a.sizeKb = in.readUnsignedShort();
        a.price = in.readInt();
        a.version = in.readUTF();
        a.name = in.readUTF();
        a.desc = in.readUTF();
        a.path = in.readUTF();
        return a;
    }

    /** Đọc một mục theo định dạng MKL2 (thêm dl, tags, plat giữa price và version). */
    static AppInfo readV2(DataInputStream in) throws IOException {
        AppInfo a = new AppInfo();
        a.id = in.readInt();
        a.cat = in.readUnsignedByte();
        a.flags = in.readUnsignedByte();
        a.sizeKb = in.readUnsignedShort();
        a.price = in.readInt();
        a.dl = in.readInt();
        a.tags = in.readUnsignedShort();
        a.plat = in.readUnsignedByte();
        a.version = in.readUTF();
        a.name = in.readUTF();
        a.desc = in.readUTF();
        a.path = in.readUTF();
        return a;
    }

    /** Số lượt tải để hiện: luôn hiện (0 cũng hiện), dưới 1000 số thật, từ 1000 dạng 1,2k. */
    static String dlText(int dl) {
        if (dl < 0) {
            return "";
        }
        if (dl < 1000) {
            return Integer.toString(dl);
        }
        if (dl < 1000000) {
            return scaled(dl, 1000, "k");
        }
        return scaled(dl / 1000, 1000, "M");
    }

    private static String scaled(int v, int unit, String suffix) {
        int whole = v / unit;
        int tenth = (v % unit) * 10 / unit;
        if (whole >= 100 || tenth == 0) {
            return whole + suffix;
        }
        return whole + "," + tenth + suffix;
    }

    /** Hai cỡ màn hình tiêu biểu mà game có bản đúng cỡ (Worker gửi trong bit 64/128 của plat): 240x320 trước, 320x240 sau. */
    String[] screenBadges() {
        boolean a = (plat & 64) != 0;
        boolean b = (plat & 128) != 0;
        if (a && b) {
            return new String[] {"240x320", "320x240"};
        }
        return a ? new String[] {"240x320"} : b ? new String[] {"320x240"} : new String[0];
    }

    /** Nhãn thẻ ngắn theo thứ tự ưu tiên (VH, MOD trước), tối đa max nhãn; bỏ GỐC và NET (đã có cờ riêng). */
    String[] tagBadges(int max) {
        String[] r = new String[Math.min(max, TAG_BITS.length)];
        int n = 0;
        for (int i = 0; i < TAG_BITS.length && n < r.length; i++) {
            if ((tags & TAG_BITS[i]) != 0) {
                r[n++] = TAG_NAMES[i];
            }
        }
        String[] out = new String[n];
        System.arraycopy(r, 0, out, 0, n);
        return out;
    }

    void write(DataOutputStream out) throws IOException {
        out.writeInt(id);
        out.writeByte(cat);
        out.writeByte(flags);
        out.writeShort(sizeKb);
        out.writeInt(price);
        out.writeUTF(version);
        out.writeUTF(name);
        out.writeUTF(desc);
        out.writeUTF(path);
    }
}
