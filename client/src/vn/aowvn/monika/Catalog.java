package vn.aowvn.monika;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Vector;

/**
 * Catalog nhị phân tự định nghĩa (đọc bằng DataInputStream, không cần parser).
 *
 * <pre>
 * int    magic  = 0x4D4B4131 ("MKA1")
 * byte   version (hiện = 1)
 * ushort count
 * mỗi mục: int id, ubyte cat, ubyte flags, ushort sizeKb, int price,
 *          UTF version, UTF name, UTF desc, UTF path
 * </pre>
 */
final class Catalog {
    static final int MAGIC = 0x4D4B4131;
    static final int FORMAT = 1;

    static final String[] CATEGORIES = {
        "Tiện ích", "Trò chơi", "Mạng & liên lạc", "Học tập & đọc"
    };

    private Vector items = new Vector();

    int size() {
        return items.size();
    }

    AppInfo get(int i) {
        return (AppInfo) items.elementAt(i);
    }

    AppInfo byId(int id) {
        for (int i = 0; i < items.size(); i++) {
            AppInfo a = get(i);
            if (a.id == id) {
                return a;
            }
        }
        return null;
    }

    /** Danh sách "Ứng dụng": mọi mục không phải trò chơi. */
    Vector apps() {
        Vector v = new Vector();
        for (int i = 0; i < items.size(); i++) {
            if (get(i).cat != AppInfo.CAT_GAME) {
                v.addElement(get(i));
            }
        }
        return v;
    }

    Vector games() {
        return byCategory(AppInfo.CAT_GAME);
    }

    Vector byCategory(int cat) {
        Vector v = new Vector();
        for (int i = 0; i < items.size(); i++) {
            if (get(i).cat == cat) {
                v.addElement(get(i));
            }
        }
        return v;
    }

    Vector updates() {
        Vector v = new Vector();
        for (int i = 0; i < items.size(); i++) {
            if (get(i).has(AppInfo.F_UPDATE)) {
                v.addElement(get(i));
            }
        }
        return v;
    }

    int updateCount() {
        int n = 0;
        for (int i = 0; i < items.size(); i++) {
            if (get(i).has(AppInfo.F_UPDATE)) {
                n++;
            }
        }
        return n;
    }

    Vector favorites(Store store) {
        Vector v = new Vector();
        for (int i = 0; i < items.size(); i++) {
            if (store.isFavorite(get(i).id)) {
                v.addElement(get(i));
            }
        }
        return v;
    }

    /** Tìm theo từ khóa đã bỏ dấu, khớp trong tên hoặc mô tả. */
    Vector search(String query) {
        Vector v = new Vector();
        String q = Vi.key(query).trim();
        if (q.length() == 0) {
            return v;
        }
        for (int i = 0; i < items.size(); i++) {
            AppInfo a = get(i);
            if (Vi.key(a.name).indexOf(q) >= 0 || Vi.key(a.desc).indexOf(q) >= 0) {
                v.addElement(a);
            }
        }
        return v;
    }

    /** Đọc catalog từ mảng byte. Ném IOException nếu sai magic/phiên bản. */
    static Catalog parse(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            if (in.readInt() != MAGIC) {
                throw new IOException("E14");
            }
            if (in.readUnsignedByte() != FORMAT) {
                throw new IOException("E15");
            }
            int n = in.readUnsignedShort();
            Catalog c = new Catalog();
            for (int i = 0; i < n; i++) {
                AppInfo a = AppInfo.read(in);
                c.items.addElement(a);
            }
            return c;
        } finally {
            try {
                in.close();
            } catch (IOException ignore) {
                // bỏ qua
            }
        }
    }

    /** Catalog mẫu đóng gói trong JAR (/catalog_sample.bin). */
    static Catalog loadEmbedded() throws IOException {
        InputStream is = Catalog.class.getResourceAsStream("/catalog_sample.bin");
        if (is == null) {
            throw new IOException("E16");
        }
        try {
            return parse(Net.readAll(is, 64 * 1024));
        } finally {
            try {
                is.close();
            } catch (IOException ignore) {
                // bỏ qua
            }
        }
    }
}
