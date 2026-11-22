package vn.aowvn.monika;

/**
 * Tiếng Việt: bỏ dấu bằng bảng tra (máy cũ nhiều máy không có font dấu).
 * Chế độ lưu trong Store: 0 chưa hỏi, 1 có dấu, 2 không dấu.
 */
final class Vi {
    static final int MODE_UNKNOWN = 0;
    static final int MODE_ACCENT = 1;
    static final int MODE_PLAIN = 2;

    static int mode = MODE_ACCENT;

    private static final String FROM =
        "àáạảãâầấậẩẫăằắặẳẵ" + "èéẹẻẽêềếệểễ" + "ìíịỉĩ"
        + "òóọỏõôồốộổỗơờớợởỡ" + "ùúụủũưừứựửữ" + "ỳýỵỷỹ" + "đ"
        + "ÀÁẠẢÃÂẦẤẬẨẪĂẰẮẶẲẴ" + "ÈÉẸẺẼÊỀẾỆỂỄ" + "ÌÍỊỈĨ"
        + "ÒÓỌỎÕÔỒỐỘỔỖƠỜỚỢỞỠ" + "ÙÚỤỦŨƯỪỨỰỬỮ" + "ỲÝỴỶỸ" + "Đ";

    // Mỗi nhóm có độ dài khớp với FROM ở trên.
    private static final String TO =
        "aaaaaaaaaaaaaaaaa" + "eeeeeeeeeee" + "iiiii"
        + "ooooooooooooooooo" + "uuuuuuuuuuu" + "yyyyy" + "d"
        + "AAAAAAAAAAAAAAAAA" + "EEEEEEEEEEE" + "IIIII"
        + "OOOOOOOOOOOOOOOOO" + "UUUUUUUUUUU" + "YYYYY" + "D";

    private Vi() {
    }

    /** Bỏ dấu một chuỗi. */
    static String strip(String s) {
        if (s == null) {
            return "";
        }
        StringBuffer sb = null;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c >= 0x80) {
                int k = FROM.indexOf(c);
                if (k >= 0 && k < TO.length()) {
                    if (sb == null) {
                        sb = new StringBuffer(n);
                        sb.append(s.substring(0, i));
                    }
                    sb.append(TO.charAt(k));
                    continue;
                }
            }
            if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    /** Chuỗi hiển thị theo chế độ người dùng đã chọn. */
    static String tr(String s) {
        return mode == MODE_PLAIN ? strip(s) : s;
    }

    /** Dạng dùng để so khớp tìm kiếm: bỏ dấu, chữ thường. */
    static String key(String s) {
        return strip(s).toLowerCase();
    }
}
