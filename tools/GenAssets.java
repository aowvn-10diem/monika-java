import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/**
 * Sinh tài nguyên cho client (chạy bằng "java tools/GenAssets.java <thư mục res>").
 *  - icon.png: icon MIDlet 32x32
 *  - catalog_sample.bin: nhị phân từ catalog/sample.tsv (định dạng MKA1, xem Catalog.java)
 */
public class GenAssets {
    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        File tsv = new File(args.length > 1 ? args[1] : "catalog/sample.tsv");
        out.mkdirs();
        icon(new File(out, "icon.png"));
        catalog(tsv, new File(out, "catalog_sample.bin"));
    }

    static void icon(File f) throws Exception {
        int s = 32;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setPaint(new GradientPaint(0, 0, new Color(0xFF8A2A), s, s, new Color(0xE0357F)));
        g.fill(new RoundRectangle2D.Float(1, 1, s - 2, s - 2, 9, 9));
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        Path2D p = new Path2D.Float();
        p.moveTo(8, 24);
        p.lineTo(8, 9);
        p.lineTo(16, 18);
        p.lineTo(24, 9);
        p.lineTo(24, 24);
        g.draw(p);
        g.dispose();
        ImageIO.write(img, "png", f);
    }

    static void catalog(File tsv, File outFile) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(bo);
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(new FileInputStream(tsv), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] c = line.split("\t", -1);
                if (c.length != 9) {
                    throw new IllegalArgumentException("Dòng sai số cột (" + c.length + "): " + line);
                }
                rows.add(c);
            }
        }
        o.writeInt(0x4D4B4131);
        o.writeByte(1);
        o.writeShort(rows.size());
        for (String[] c : rows) {
            o.writeInt(Integer.parseInt(c[0]));
            o.writeByte(Integer.parseInt(c[1]));
            o.writeByte(Integer.parseInt(c[2]));
            o.writeShort(Integer.parseInt(c[3]));
            o.writeInt(Integer.parseInt(c[4]));
            o.writeUTF(c[5]);
            o.writeUTF(c[6]);
            o.writeUTF(c[7]);
            o.writeUTF(c[8]);
        }
        Files.write(outFile.toPath(), bo.toByteArray());
    }
}
