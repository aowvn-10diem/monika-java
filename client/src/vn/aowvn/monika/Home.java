package vn.aowvn.monika;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Dữ liệu trang chủ từ /v1/home.bin: số mục mới, bản app mới nhất, danh mục, banner.
 *
 * <pre>
 * int magic = 0x4D4B4831 ("MKH1"), ubyte version = 1, ushort newCount,
 * UTF latestVersion, UTF latestJarUrl,
 * ubyte nCats, mỗi cat: ubyte id, UTF name, ushort count,
 * ubyte nBanners, mỗi banner: ubyte id, UTF alt, UTF link
 * </pre>
 * Bản v2 (/v1/home2.bin): magic 0x4D4B4832 ("MKH2"), version 2, mỗi cat thêm ubyte kind (1 game, 2 app)
 * ngay sau id. Bản v1 không có kind: đoán theo tên ("Trò chơi", "game" là game, còn lại là app).
 * Link banner: rỗng, "l:&lt;khóa danh sách&gt;" hoặc "g:&lt;id app&gt;".
 */
final class Home {
    static final int MAGIC = 0x4D4B4831;
    static final int MAGIC2 = 0x4D4B4832;
    /** home3.bin: cùng bố cục v2 nhưng kind 1..5 (3 bài viết, 4 giao diện, 5 âm thanh). */
    static final int MAGIC3 = 0x4D4B4833;
    static final int KIND_GAME = 1;
    static final int KIND_APP = 2;
    static final int KIND_READ = 3;
    static final int KIND_SKIN = 4;
    static final int KIND_SOUND = 5;
    /** Thứ tự nhóm trong menu Danh mục. */
    static final int[] KIND_ORDER = {KIND_GAME, KIND_APP, KIND_SKIN, KIND_SOUND, KIND_READ};

    int newCount;
    String latest = "";
    String latestJar = "";
    boolean v2;
    int[] catId = new int[0];
    int[] catKind = new int[0];
    String[] catName = new String[0];
    int[] catCount = new int[0];
    int[] banId = new int[0];
    String[] banAlt = new String[0];
    String[] banLink = new String[0];

    static Home parse(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            int magic = in.readInt();
            if (magic != MAGIC && magic != MAGIC2 && magic != MAGIC3) {
                throw new IOException("E14");
            }
            boolean two = magic != MAGIC;
            if (in.readUnsignedByte() != (magic == MAGIC3 ? 3 : two ? 2 : 1)) {
                throw new IOException("E15");
            }
            Home h = new Home();
            h.v2 = two;
            h.newCount = in.readUnsignedShort();
            h.latest = in.readUTF();
            h.latestJar = in.readUTF();
            int n = in.readUnsignedByte();
            h.catId = new int[n];
            h.catKind = new int[n];
            h.catName = new String[n];
            h.catCount = new int[n];
            for (int i = 0; i < n; i++) {
                h.catId[i] = in.readUnsignedByte();
                if (two) {
                    int k = in.readUnsignedByte();
                    h.catKind[i] = magic == MAGIC3 ? (k >= 1 && k <= 5 ? k : KIND_APP) : (k == KIND_GAME ? KIND_GAME : KIND_APP);
                }
                h.catName[i] = in.readUTF();
                h.catCount[i] = in.readUnsignedShort();
                if (!two) {
                    h.catKind[i] = guessKind(h.catName[i]);
                }
            }
            n = in.readUnsignedByte();
            h.banId = new int[n];
            h.banAlt = new String[n];
            h.banLink = new String[n];
            for (int i = 0; i < n; i++) {
                h.banId[i] = in.readUnsignedByte();
                h.banAlt[i] = in.readUTF();
                h.banLink[i] = in.readUTF();
            }
            return h;
        } finally {
            try {
                in.close();
            } catch (IOException ignore) {
                // bỏ qua
            }
        }
    }

    /** Trang chủ dựng từ danh mục mẫu trong JAR (khi chưa có máy chủ). */
    static Home fromCatalog(Catalog c) {
        Home h = new Home();
        int n = Catalog.CATEGORIES.length;
        h.catId = new int[n];
        h.catKind = new int[n];
        h.catName = new String[n];
        h.catCount = new int[n];
        for (int i = 0; i < n; i++) {
            h.catId[i] = i;
            h.catKind[i] = guessKind(Catalog.CATEGORIES[i]);
            h.catName[i] = Catalog.CATEGORIES[i];
            h.catCount[i] = c.byCategory(i).size();
        }
        h.newCount = c.updateCount();
        return h;
    }

    private static int guessKind(String name) {
        String k = Vi.key(name);
        return k.indexOf("choi") >= 0 || k.indexOf("game") >= 0 ? KIND_GAME : KIND_APP;
    }

    /** Tổng số mục các danh mục thuộc nhóm kind. */
    int countOfKind(int kind) {
        int n = 0;
        for (int i = 0; i < catId.length; i++) {
            if (catKind[i] == kind) {
                n += catCount[i];
            }
        }
        return n;
    }

    /** Nhóm của danh mục (1 game, 2 app, 3 bài viết, 4 giao diện, 5 âm thanh); không biết thì 0. */
    int kindOfCat(int id) {
        for (int i = 0; i < catId.length; i++) {
            if (catId[i] == id) {
                return catKind[i];
            }
        }
        return 0;
    }

    static String kindName(int kind) {
        switch (kind) {
            case KIND_GAME:
                return "Trò chơi";
            case KIND_APP:
                return "Ứng dụng";
            case KIND_SKIN:
                return "Hình nền & Theme";
            case KIND_SOUND:
                return "Nhạc & Video";
            default:
                return "Truyện & Mẹo";
        }
    }

    String nameOfCat(int id) {
        for (int i = 0; i < catId.length; i++) {
            if (catId[i] == id) {
                return catName[i];
            }
        }
        return "";
    }
}
