package vn.aowvn.monika;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * Góp ý / báo lỗi game gửi về máy chủ Monika (máy chủ gom lại, gửi email cho ban quản trị).
 * Chống spam: câu hỏi phép tính ngắn (GET /v1/fb/c.bin) kèm token ký sẵn, trả lời gửi cùng nội dung (POST /v1/fb).
 * Gọi trên luồng riêng.
 */
final class Feedback {
    static final int MSG_MIN = 5;
    static final int MSG_MAX = 400;
    static final int CONTACT_MAX = 60;
    static final String[] KINDS = {"bug", "idea", "other"};

    private Feedback() {
    }

    /** Lấy câu hỏi: {"7 + 5 = ?", token}. */
    static String[] challenge(String base, String suffix, String ua) throws IOException {
        byte[] b = Net.get(base + "/v1/fb/c.bin", suffix, ua, 512);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        String q = in.readUTF();
        String t = in.readUTF();
        return new String[] {q, t};
    }

    /** Gửi góp ý. Trả "ok" nếu máy chủ nhận, ngược lại mã lỗi của máy chủ (bad_captcha, expired, too_fast, short, slow, full) hoặc E12/E20 nếu mạng lỗi. */
    static String send(String base, String suffix, String ua, int kind, int item, String dev, String msg,
                       String contact, String token, String answer) {
        StringBuffer sb = new StringBuffer();
        sb.append("o=t&s=app&k=").append(KINDS[kind < 0 || kind >= KINDS.length ? 2 : kind]);
        sb.append("&g=").append(item > 0 ? item : 0);
        sb.append("&v=").append(MonikaMidlet.VERSION);
        sb.append("&d=").append(enc(dev == null ? "" : dev));
        sb.append("&q=").append(enc(token));
        sb.append("&a=").append(enc(answer));
        sb.append("&m=").append(enc(msg));
        sb.append("&c=").append(enc(contact == null ? "" : contact));
        try {
            byte[] body = sb.toString().getBytes("UTF-8");
            byte[] r = Net.post(base + "/v1/fb", suffix, ua, body, 256);
            String s = new String(r, "UTF-8").trim();
            if (Net.postCode == 200 && s.startsWith("ok")) {
                return "ok";
            }
            if (s.startsWith("err:")) {
                return s.substring(4);
            }
            if (Net.postCode == 429) {
                return "slow";
            }
            if (Net.postCode == 400) {
                return "bad_captcha";
            }
            if (Net.postCode == 413) {
                return "short";
            }
            return "E13";
        } catch (IOException e) {
            String m = e.getMessage();
            return m == null ? "E12" : m;
        }
    }

    /** Thông báo cho người dùng theo mã lỗi. */
    static String describe(String code) {
        if ("ok".equals(code)) {
            return "Đã gửi, cảm ơn bạn đã góp ý!";
        }
        if ("bad_captcha".equals(code)) {
            return "Đáp án phép tính chưa đúng. Nhập lại.";
        }
        if ("expired".equals(code)) {
            return "Câu hỏi đã hết hạn hoặc đã dùng. Đã đổi câu mới, nhập đáp án rồi gửi lại.";
        }
        if ("too_fast".equals(code)) {
            return "Gửi nhanh quá, đợi vài giây rồi bấm Gửi lại.";
        }
        if ("short".equals(code)) {
            return "Nội dung quá ngắn (tối thiểu 5 ký tự).";
        }
        if ("slow".equals(code)) {
            return "Bạn gửi nhiều quá, thử lại sau ít phút.";
        }
        if ("full".equals(code)) {
            return "Hôm nay nhận quá nhiều góp ý, mời bạn thử lại ngày mai.";
        }
        if ("E20".equals(code)) {
            return "Máy chặn quyền mạng của Monika (mã E20).";
        }
        return "Chưa gửi được, kiểm tra mạng rồi thử lại (mã " + code + ").";
    }

    /** Mã lỗi nào cần phải lấy câu hỏi mới (token đã dùng, hết hạn hoặc sai). */
    static boolean needsNewChallenge(String code) {
        return "expired".equals(code) || "bad_captcha".equals(code) || "too_fast".equals(code);
    }

    /** Mã hóa %XX theo UTF-8 (chữ/số/-._~ giữ nguyên). */
    static String enc(String s) {
        if (s == null || s.length() == 0) {
            return "";
        }
        byte[] b;
        try {
            b = s.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
        StringBuffer sb = new StringBuffer(b.length * 2);
        String hex = "0123456789ABCDEF";
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xFF;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '-' || c == '.' || c == '_' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(hex.charAt(c >> 4)).append(hex.charAt(c & 15));
            }
        }
        return sb.toString();
    }
}
