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
 * Draws the app icon: the product mark's shell prompt on the accent blue, in a sombrero and a moustache.
 * Run from this folder with `java MakeIcons.java`, then `iconutil -c icns icon.iconset`.
 * Writes icon.iconset/ (macOS), icon.png (Linux) and icon.ico (Windows).
 */
public class MakeIcons {
    static final Color ACCENT = new Color(0x18, 0x5F, 0xA5);
    static final Color STRAW = new Color(0xF4, 0xC7, 0x52);
    static final Color STRAW_DARK = new Color(0xE3, 0xAC, 0x3A);
    static final Color BAND = new Color(0xD2, 0x46, 0x3C);
    static final Color MOUSTACHE = new Color(0x2A, 0x1C, 0x14);

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

        // The artwork is drawn on the macOS grid (an 824 tile centred in 1024) and scaled to this tile.
        g.translate(size / 2.0, size / 2.0);
        g.scale(tile / 824, tile / 824);
        g.translate(-512, -512);
        prompt(g);
        // The prompt wears the hat, tipped a little, and a moustache over its cursor.
        place(g, 512, 388, 0.68, -8, 512, 418, () -> sombrero(g));
        place(g, 614, 668, 0.48, 0, 512, 690, () -> mustache(g));
        g.dispose();
        return image;
    }

    /** The prompt from CartaIcons.Snippet, on its 24-unit grid centred at (12, 12). */
    static void prompt(Graphics2D g) {
        double scale = 24, cx = 512, cy = 620;
        Path2D prompt = new Path2D.Double();
        prompt.moveTo(cx + (5 - 12) * scale, cy + (7 - 12) * scale);
        prompt.lineTo(cx + (10 - 12) * scale, cy);
        prompt.lineTo(cx + (5 - 12) * scale, cy + (17 - 12) * scale);
        prompt.moveTo(cx + (12.5 - 12) * scale, cy + (17 - 12) * scale);
        prompt.lineTo(cx + (19 - 12) * scale, cy + (17 - 12) * scale);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) (2.1 * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(prompt);
    }

    /** Draws at (x, y), scaled and turned by [degrees] around the drawing's own centre (cx, cy). */
    static void place(Graphics2D g, double x, double y, double scale, double degrees, double cx, double cy, Runnable draw) {
        AffineTransform saved = g.getTransform();
        g.translate(x, y);
        g.rotate(Math.toRadians(degrees));
        g.scale(scale, scale);
        g.translate(-cx, -cy);
        draw.run();
        g.setTransform(saved);
    }

    static void sombrero(Graphics2D g) {
        Path2D brim = new Path2D.Double();
        brim.moveTo(200, 430);
        brim.curveTo(250, 482, 380, 505, 512, 505);
        brim.curveTo(644, 505, 774, 482, 824, 430);
        brim.curveTo(800, 545, 650, 575, 512, 575);
        brim.curveTo(374, 575, 224, 545, 200, 430);
        brim.closePath();
        g.setColor(STRAW_DARK);
        g.fill(brim);

        Path2D crown = new Path2D.Double();
        crown.moveTo(392, 512);
        crown.curveTo(392, 360, 430, 262, 512, 262);
        crown.curveTo(594, 262, 632, 360, 632, 512);
        crown.curveTo(560, 524, 464, 524, 392, 512);
        crown.closePath();
        g.setColor(STRAW);
        g.fill(crown);

        // The band, with a zigzag stitched into it.
        Shape clip = g.getClip();
        g.clip(crown);
        g.setColor(BAND);
        g.fill(new Rectangle2D.Double(380, 432, 264, 50));
        Path2D zigzag = new Path2D.Double();
        zigzag.moveTo(380, 470);
        for (int i = 0; i < 12; i++) zigzag.lineTo(392 + i * 22, i % 2 == 0 ? 444 : 470);
        g.setColor(STRAW);
        g.setStroke(new BasicStroke(9, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(zigzag);
        g.setClip(clip);
    }

    /** A handlebar moustache: two lobes that meet under the nose and curl up at the tips. */
    static void mustache(Graphics2D g) {
        Path2D right = new Path2D.Double();
        right.moveTo(512, 668);
        right.curveTo(560, 628, 630, 628, 680, 660);
        right.curveTo(712, 680, 740, 676, 752, 640);
        right.curveTo(766, 668, 752, 712, 710, 728);
        right.curveTo(640, 752, 560, 742, 512, 712);
        right.closePath();
        Path2D both = new Path2D.Double(right);
        both.append(right.createTransformedShape(new AffineTransform(-1, 0, 0, 1, 1024, 0)), false);
        g.setColor(MOUSTACHE);
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
