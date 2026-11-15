package vn.aowvn.monika;

/** Một mục trong catalog (id, tên, dung lượng, giá, cờ...). */
final class AppInfo {
    static final int F_NEW = 1;
    static final int F_UPDATE = 2;
    static final int F_NEEDNET = 4;

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
}
