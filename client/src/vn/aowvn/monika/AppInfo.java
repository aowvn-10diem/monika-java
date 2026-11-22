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

    static final int CAT_GAME = 1;

    int id;
    int cat;
    int flags;
    int sizeKb;
    int price;
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
