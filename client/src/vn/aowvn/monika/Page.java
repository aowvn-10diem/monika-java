package vn.aowvn.monika;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Vector;

/**
 * Một trang danh sách từ /v1/l/&lt;khóa&gt;/&lt;trang&gt;.bin hoặc /v1/s/&lt;từ khóa&gt;/&lt;trang&gt;.bin.
 *
 * <pre>
 * int magic = 0x4D4B4C31 ("MKL1"), ubyte version = 1, ushort total, ushort page,
 * ubyte count, rồi count mục (giống MKA1: id, cat, flags, sizeKb, price, version, name, desc, path)
 * </pre>
 * Bản v2 (/v1/l2/): magic 0x4D4B4C32 ("MKL2"), version 2, mỗi mục thêm int dl, ushort tags, ubyte plat
 * giữa price và version.
 */
final class Page {
    static final int MAGIC = 0x4D4B4C31;
    static final int MAGIC2 = 0x4D4B4C32;
    static final int SIZE = 15;

    int total;
    int page;
    Vector items = new Vector();
    /** True nếu bộ lọc theo máy cho kết quả rỗng và Repo đã tải lại không lọc. */
    boolean unfiltered;
    /** True nếu đọc từ định dạng v2. */
    boolean v2;

    boolean hasNext() {
        return (page + 1) * SIZE < total;
    }

    int pages() {
        return total <= 0 ? 1 : (total + SIZE - 1) / SIZE;
    }

    static Page parse(byte[] data) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            int magic = in.readInt();
            if (magic != MAGIC && magic != MAGIC2) {
                throw new IOException("E14");
            }
            boolean two = magic == MAGIC2;
            if (in.readUnsignedByte() != (two ? 2 : 1)) {
                throw new IOException("E15");
            }
            Page p = new Page();
            p.v2 = two;
            p.total = in.readUnsignedShort();
            p.page = in.readUnsignedShort();
            int n = in.readUnsignedByte();
            for (int i = 0; i < n; i++) {
                p.items.addElement(two ? AppInfo.readV2(in) : AppInfo.read(in));
            }
            return p;
        } finally {
            try {
                in.close();
            } catch (IOException ignore) {
                // bỏ qua
            }
        }
    }

    /** Cắt trang từ danh sách có sẵn trong máy. */
    static Page local(Vector all, int page) {
        Page p = new Page();
        p.total = all.size();
        p.page = page;
        for (int i = page * SIZE; i < all.size() && i < (page + 1) * SIZE; i++) {
            p.items.addElement(all.elementAt(i));
        }
        return p;
    }
}
