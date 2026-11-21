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
 * Link banner: rỗng, "l:&lt;khóa danh sách&gt;" hoặc "g:&lt;id app&gt;".
 */
final class Home {
    static final int MAGIC = 0x4D4B4831;

    int newCount;
    String latest = "";
    String latestJar = "";
    int[] catId = new int[0];
    String[] catName = new String[0];
    int[] catCount = new int[0];
    int[] banId = new int[0];
    String[] banAlt = new String[0];
    String[] banLink = new String[0];

    static Home parse(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            if (in.readInt() != MAGIC) {
                throw new IOException("E14");
            }
            if (in.readUnsignedByte() != 1) {
                throw new IOException("E15");
            }
            Home h = new Home();
            h.newCount = in.readUnsignedShort();
            h.latest = in.readUTF();
            h.latestJar = in.readUTF();
            int n = in.readUnsignedByte();
            h.catId = new int[n];
            h.catName = new String[n];
            h.catCount = new int[n];
            for (int i = 0; i < n; i++) {
                h.catId[i] = in.readUnsignedByte();
                h.catName[i] = in.readUTF();
                h.catCount[i] = in.readUnsignedShort();
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
        h.catName = new String[n];
        h.catCount = new int[n];
        for (int i = 0; i < n; i++) {
            h.catId[i] = i;
            h.catName[i] = Catalog.CATEGORIES[i];
            h.catCount[i] = c.byCategory(i).size();
        }
        h.newCount = c.updateCount();
        return h;
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
