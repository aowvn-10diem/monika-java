package vn.aowvn.monika;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.Vector;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * Lưu trạng thái vào RMS: id thiết bị ngẫu nhiên (không dùng IMEI), chế độ chữ,
 * toàn màn hình, máy chủ tự chọn, lọc theo màn hình, danh sách yêu thích (đủ thông tin để mở offline),
 * cache vài trang đầu từ máy chủ.
 */
final class Store {
    private static final String SETTINGS = "monika";
    private static final String FAVS = "mfav";
    private static final String CACHE = "mc2";
    private static final String OLD_CACHE = "mcache";
    private static final int CACHE_MAX = 32 * 1024;
    private static final int CACHE_ENTRIES = 12;
    private static final int FAV_MAX = 40;

    String deviceId = "";
    /** 0 = theo JAD, 1 = bật, 2 = tắt. */
    int fullscreen = 0;
    /** Máy chủ do người dùng nhập (rỗng = dùng máy chủ có sẵn trong JAR). */
    String server = "";
    /** Chỉ hiện mục hợp màn hình máy (thêm hậu tố .r&lt;W&gt;x&lt;H&gt; khi gọi danh sách). Mặc định bật. */
    boolean fitScreen = true;

    private Vector favs;

    // ---------- yêu thích ----------

    private void loadFavs() {
        if (favs != null) {
            return;
        }
        favs = new Vector();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(FAVS, true);
            if (rs.getNumRecords() == 0) {
                return;
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            int n = in.readUnsignedByte();
            for (int i = 0; i < n; i++) {
                favs.addElement(AppInfo.read(in));
            }
        } catch (Exception e) {
            // hỏng thì coi như rỗng
        } finally {
            close(rs);
        }
    }

    private void saveFavs() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeByte(favs.size());
            for (int i = 0; i < favs.size(); i++) {
                ((AppInfo) favs.elementAt(i)).write(out);
            }
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(FAVS, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, b, 0, b.length);
            }
        } catch (Exception e) {
            // RMS đầy hoặc bị chặn: bỏ qua, app vẫn chạy
        } finally {
            close(rs);
        }
    }

    boolean isFavorite(int id) {
        return indexOfFav(id) >= 0;
    }

    private int indexOfFav(int id) {
        loadFavs();
        for (int i = 0; i < favs.size(); i++) {
            if (((AppInfo) favs.elementAt(i)).id == id) {
                return i;
            }
        }
        return -1;
    }

    /** Thêm hoặc bỏ yêu thích. Trả true nếu đang là yêu thích sau khi đổi. */
    boolean toggleFavorite(AppInfo a) {
        int i = indexOfFav(a.id);
        boolean on;
        if (i >= 0) {
            favs.removeElementAt(i);
            on = false;
        } else {
            if (favs.size() >= FAV_MAX) {
                favs.removeElementAt(0);
            }
            favs.addElement(a);
            on = true;
        }
        saveFavs();
        return on;
    }

    Vector favorites() {
        loadFavs();
        Vector v = new Vector();
        for (int i = favs.size() - 1; i >= 0; i--) {
            v.addElement(favs.elementAt(i));
        }
        return v;
    }

    // ---------- cài đặt ----------

    /** Nạp cài đặt. Trả false nếu đây là lần chạy đầu. */
    boolean load() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(SETTINGS, true);
            if (rs.getNumRecords() == 0) {
                newDeviceId();
                return false;
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            int ver = in.readUnsignedByte();
            Vi.mode = in.readUnsignedByte();
            fullscreen = in.readUnsignedByte();
            deviceId = in.readUTF();
            if (ver == 1) {
                in.readUTF(); // bản 0.1: danh sách yêu thích dạng chuỗi, bỏ
            } else {
                server = in.readUTF();
                if (ver >= 3) {
                    fitScreen = in.readUnsignedByte() != 0;
                }
            }
            if (deviceId.length() == 0) {
                newDeviceId();
            }
            return Vi.mode != Vi.MODE_UNKNOWN;
        } catch (Exception e) {
            if (deviceId.length() == 0) {
                newDeviceId();
            }
            return false;
        } finally {
            close(rs);
        }
    }

    void save() {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeByte(3);
            out.writeByte(Vi.mode);
            out.writeByte(fullscreen);
            out.writeUTF(deviceId);
            out.writeUTF(server);
            out.writeByte(fitScreen ? 1 : 0);
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(SETTINGS, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(b, 0, b.length);
            } else {
                rs.setRecord(1, b, 0, b.length);
            }
        } catch (Exception e) {
            // RMS đầy hoặc bị chặn: bỏ qua, app vẫn chạy
        } finally {
            close(rs);
        }
    }

    private void newDeviceId() {
        Random r = new Random(System.currentTimeMillis() ^ (long) hashCode() * 31L);
        String alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        StringBuffer sb = new StringBuffer(8);
        for (int i = 0; i < 8; i++) {
            sb.append(alphabet.charAt((r.nextInt() & 0x7fffffff) % alphabet.length()));
        }
        deviceId = sb.toString();
    }

    // ---------- cache theo khóa ----------

    /** Bản ghi cache: UTF khóa, rồi dữ liệu thô đến hết bản ghi. */
    byte[] cacheGet(String key) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(CACHE, true);
            int last = rs.getNextRecordID();
            for (int id = 1; id < last; id++) {
                byte[] rec;
                try {
                    rec = rs.getRecord(id);
                } catch (RecordStoreException e) {
                    continue;
                }
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rec));
                if (key.equals(in.readUTF())) {
                    byte[] out = new byte[in.available()];
                    in.readFully(out);
                    return out;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            close(rs);
        }
    }

    void cachePut(String key, byte[] data) {
        if (data == null || data.length > CACHE_MAX) {
            return;
        }
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeUTF(key);
            out.write(data, 0, data.length);
            byte[] b = bo.toByteArray();
            rs = RecordStore.openRecordStore(CACHE, true);
            int last = rs.getNextRecordID();
            int found = -1;
            int first = -1;
            for (int id = 1; id < last; id++) {
                byte[] rec;
                try {
                    rec = rs.getRecord(id);
                } catch (RecordStoreException e) {
                    continue;
                }
                if (first < 0) {
                    first = id;
                }
                if (key.equals(new DataInputStream(new ByteArrayInputStream(rec)).readUTF())) {
                    found = id;
                    break;
                }
            }
            if (found > 0) {
                rs.setRecord(found, b, 0, b.length);
            } else {
                if (rs.getNumRecords() >= CACHE_ENTRIES && first > 0) {
                    rs.deleteRecord(first);
                }
                rs.addRecord(b, 0, b.length);
            }
        } catch (Exception e) {
            // bỏ qua
        } finally {
            close(rs);
        }
    }

    void cacheClear() {
        try {
            RecordStore.deleteRecordStore(CACHE);
        } catch (RecordStoreException e) {
            // chưa có cache
        }
        try {
            RecordStore.deleteRecordStore(OLD_CACHE);
        } catch (RecordStoreException e) {
            // chưa có cache bản cũ
        }
    }

    private static void close(RecordStore rs) {
        if (rs != null) {
            try {
                rs.closeRecordStore();
            } catch (RecordStoreException ignore) {
                // bỏ qua
            }
        }
    }
}
