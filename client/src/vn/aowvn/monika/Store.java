package vn.aowvn.monika;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/**
 * Lưu trạng thái vào RMS: id thiết bị ngẫu nhiên (không dùng IMEI), chế độ chữ,
 * toàn màn hình, danh sách yêu thích, cache catalog.
 */
final class Store {
    private static final String SETTINGS = "monika";
    private static final String CACHE = "mcache";
    private static final int CACHE_MAX = 48 * 1024;

    String deviceId = "";
    /** 0 = theo JAD, 1 = bật, 2 = tắt. */
    int fullscreen = 0;
    /** Danh sách id yêu thích, dạng ",1,5,9,". */
    String favs = ",";

    boolean isFavorite(int id) {
        return favs.indexOf("," + id + ",") >= 0;
    }

    void toggleFavorite(int id) {
        String k = "," + id + ",";
        int i = favs.indexOf(k);
        if (i >= 0) {
            favs = favs.substring(0, i + 1) + favs.substring(i + k.length());
        } else {
            favs = favs + id + ",";
        }
        save();
    }

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
            in.readUnsignedByte(); // phiên bản bản ghi
            Vi.mode = in.readUnsignedByte();
            fullscreen = in.readUnsignedByte();
            deviceId = in.readUTF();
            favs = in.readUTF();
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
            out.writeByte(1);
            out.writeByte(Vi.mode);
            out.writeByte(fullscreen);
            out.writeUTF(deviceId);
            out.writeUTF(favs);
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

    byte[] cacheGet() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(CACHE, true);
            if (rs.getNumRecords() == 0) {
                return null;
            }
            return rs.getRecord(1);
        } catch (Exception e) {
            return null;
        } finally {
            close(rs);
        }
    }

    void cachePut(byte[] data) {
        if (data == null || data.length > CACHE_MAX) {
            return;
        }
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(CACHE, true);
            if (rs.getNumRecords() == 0) {
                rs.addRecord(data, 0, data.length);
            } else {
                rs.setRecord(1, data, 0, data.length);
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
