import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the app icon, "El Bigote": a white shell prompt on the app's accent blue, in a sombrero, with a moustache for a cursor.
 * Run from this folder with `java MakeIcons.java`, then `iconutil -c icns icon.iconset`.
 * Writes icon.iconset/ (macOS), icon.png (Linux) and icon.ico (Windows).
 */
public class MakeIcons {
    static final Color ACCENT = new Color(0x18, 0x5F, 0xA5);

    public static void main(String[] args) throws IOException {
        File iconset = new File("icon.iconset");
        iconset.mkdirs();
        for (int size : new int[]{16, 32, 128, 256, 512}) {
            ImageIO.write(draw(size, true), "png", new File(iconset, "icon_" + size + "x" + size + ".png"));
            ImageIO.write(draw(size * 2, true), "png", new File(iconset, "icon_" + size + "x" + size + "@2x.png"));
        }
        ImageIO.write(draw(512, false), "png", new File("icon.png"));
        writeIco(new File("icon.ico"), new int[]{16, 24, 32, 48, 64, 128, 256});
    }

    /**
     * One icon at [size] pixels. The macOS one follows Apple's grid: an 824 tile on a 1024
     * canvas with a soft shadow; the others fill the canvas, as those platforms expect.
     */
    static BufferedImage draw(int size, boolean mac) {
        double unit = size / 1024.0;
        double inset = (mac ? 100 : 24) * unit;
        double tile = size - inset * 2;
        RoundRectangle2D shape = new RoundRectangle2D.Double(inset, inset, tile, tile, tile * 0.45, tile * 0.45);

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(image);
        if (mac && size >= 64) {
            g.drawImage(shadow(shape, size, unit), 0, 0, null);
        }
        g.setColor(ACCENT);
        g.fill(shape);

        // The artwork is drawn on a 464-unit tile centred at (256, 256), the grid of docs/logo/proposte.html.
        g.translate(size / 2.0, size / 2.0);
        g.scale(tile / 464, tile / 464);
        g.translate(-256, -256);
        g.setColor(Color.WHITE);
        // The prompt wears the hat, tipped a little, and its cursor is a moustache.
        place(g, 256, 168, 1.15, -7, () -> sombrero(g));
        place(g, 166, 330, 1, 0, () -> chevron(g));
        place(g, 330, 368, 1.02, 0, () -> mustache(g));
        g.dispose();
        return image;
    }

    /** Draws around (0, 0), moved to (x, y), turned by [degrees] and scaled. */
    static void place(Graphics2D g, double x, double y, double scale, double degrees, Runnable draw) {
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        g.rotate(Math.toRadians(degrees));
        g.scale(scale, scale);
        draw.run();
        g.setTransform(saved);
    }

    /** A flat sombrero centred on its brim, with the band cut out in the tile colour. */
    static void sombrero(Graphics2D g) {
        Path2D brim = new Path2D.Double();
        brim.moveTo(-120, -16);
        brim.curveTo(-102, 4, -60, 12, 0, 12);
        brim.curveTo(60, 12, 102, 4, 120, -16);
        brim.curveTo(114, 10, 72, 28, 0, 28);
        brim.curveTo(-72, 28, -114, 10, -120, -16);
        brim.closePath();
        g.fill(brim);

        Path2D crown = new Path2D.Double();
        crown.moveTo(-36, 8);
        crown.curveTo(-38, -30, -26, -76, 0, -78);
        crown.curveTo(26, -76, 38, -30, 36, 8);
        crown.curveTo(20, 12, -20, 12, -36, 8);
        crown.closePath();
        g.fill(crown);

        Shape clip = g.getClip();
        g.clip(crown);
        g.setColor(ACCENT);
        g.fill(new Rectangle2D.Double(-40, -18, 80, 14));
        g.setColor(Color.WHITE);
        g.setClip(clip);
    }

    /** The prompt's chevron. */
    static void chevron(Graphics2D g) {
        Path2D chevron = new Path2D.Double();
        chevron.moveTo(-30, -56);
        chevron.lineTo(30, 0);
        chevron.lineTo(-30, 56);
        g.setStroke(new BasicStroke(40, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(chevron);
    }

    /** A handlebar moustache: two lobes that meet under the nose and curl up at the tips. */
    static void mustache(Graphics2D g) {
        Path2D right = new Path2D.Double();
        right.moveTo(0, -10);
        right.curveTo(14, -30, 46, -30, 62, -12);
        right.curveTo(72, -2, 84, 0, 96, -18);
        right.curveTo(102, 6, 82, 30, 52, 28);
        right.curveTo(32, 26, 14, 18, 0, 22);
        right.closePath();
        Path2D both = new Path2D.Double(right);
        both.append(right.createTransformedShape(AffineTransform.getScaleInstance(-1, 1)), false);
        g.fill(both);
    }

    static BufferedImage shadow(RoundRectangle2D shape, int size, double unit) {
        BufferedImage mask = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(mask);
        g.translate(0, 12 * unit);
        g.setColor(new Color(0, 0, 0, 80));
        g.fill(shape);
        g.dispose();
        int radius = Math.max(1, (int) Math.round(18 * unit));
        float[] weights = gaussian(radius);
        BufferedImage once = new ConvolveOp(new Kernel(weights.length, 1, weights), ConvolveOp.EDGE_NO_OP, null).filter(mask, null);
        return new ConvolveOp(new Kernel(1, weights.length, weights), ConvolveOp.EDGE_NO_OP, null).filter(once, null);
    }

    static float[] gaussian(int radius) {
        float[] weights = new float[radius * 2 + 1];
        double sigma = radius / 2.0, sum = 0;
        for (int i = -radius; i <= radius; i++) sum += weights[i + radius] = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
        for (int i = 0; i < weights.length; i++) weights[i] /= (float) sum;
        return weights;
    }

    static Graphics2D smooth(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    /** An .ico whose entries are PNGs, which Windows has read since Vista. */
    static void writeIco(File file, int[] sizes) throws IOException {
        List<byte[]> pngs = new ArrayList<>();
        for (int size : sizes) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(draw(size, false), "png", bytes);
            pngs.add(bytes.toByteArray());
        }
        ByteBuffer header = ByteBuffer.allocate(6 + 16 * sizes.length).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = header.capacity();
        for (int i = 0; i < sizes.length; i++) {
            header.put((byte) (sizes[i] >= 256 ? 0 : sizes[i])).put((byte) (sizes[i] >= 256 ? 0 : sizes[i]));
            header.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            header.putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header.array());
            for (byte[] png : pngs) out.write(png);
        }
    }
}
