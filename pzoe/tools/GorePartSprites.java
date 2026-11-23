// Run with java GorePartSprites.java gore_parts_atlas.png.
// The atlas layout and color palettes match GoreParts.java.


import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public final class GorePartSprites {
    private static final int W = 32, H = 20;
    private static final int[] SKIN = {0xD3B39A, 0xB38A68, 0x8A6047, 0x59403A};
    private static final int[] HAIR = {0x352C29, 0x644736, 0xB99A62, 0xADB0AA, 0x873F2C};
    private static final int[] CLOTH = {0x000000, 0x3D4143, 0xA4A29A, 0x4B6373, 0x555B43, 0x85483E, 0x9E8462, 0x846D93};
    private static final int BLOOD = 0x432022, DARK = 0x281917, BONE = 0xA38D76;
    private static final int RES = 2;
    private final BufferedImage image = new BufferedImage(512 * RES, 512 * RES, BufferedImage.TYPE_INT_ARGB);
    private final Graphics2D canvas = image.createGraphics();

    private Graphics2D cell(int index) {
        Graphics2D g = (Graphics2D) canvas.create((index % 16) * W * RES, (index / 16) * H * RES, W * RES, H * RES);
        g.scale(RES, RES);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }

    private static Color tone(int color, double scale) {
        int r = (int) Math.min(255, ((color >> 16) & 255) * scale);
        int g = (int) Math.min(255, ((color >> 8) & 255) * scale);
        int b = (int) Math.min(255, (color & 255) * scale);
        return new Color(r, g, b);
    }

    private static void shape(Graphics2D g, int color, double light, double... points) {
        var p = new Path2D.Double();
        p.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) p.lineTo(points[i], points[i + 1]);
        p.closePath();
        g.setColor(tone(color, light));
        g.fill(p);
    }

    private static void oval(Graphics2D g, int color, double light, double x, double y, double w, double h) {
        g.setColor(tone(color, light));
        g.fill(new Ellipse2D.Double(x, y, w, h));
    }

    private void head(int index, boolean narrow, int style, int skin, int hair) {
        Graphics2D g = cell(index);
        int face = SKIN[skin], trim = HAIR[hair];
        double cheek = narrow ? 18.9 : 19.8, jaw = narrow ? 16.2 : 16.8;
        if (style == 2) {
            shape(g, trim, 0.51, 10,7, 12,3, 17,2, 21,4, 23,8, 22,13, 24,17, 20,17, 18,13, 11,16, 8,14);
            shape(g, trim, 0.78, 19,6, 21,6, 22,9, 21,13, 22,15, 20,14);
        }
        shape(g, DARK, 0.92, 10,5, 12,3, 17,3, 20,5, 21,9, 20,12, 18,15, 16,16, 14,15, 11,12, 9.5,8);
        shape(g, face, 0.77, 10.6,5.6, 13,4, 17,4, 19.5,6, cheek,8.4, 20.2,9.6, cheek,10.7,
            18.2,13, jaw,14.6, 14.3,14.4, 12.1,12.2, 10.6,9.8);
        shape(g, face, 0.98, 11.4,6.1, 14,4.5, 17,4.6, 19.1,7.1, 17.7,7.3, 15,6.7, 13,8.2, 12.3,10.3, 11.4,9.8);
        shape(g, face, 0.63, 14,10, 15.2,8.9, 17.3,9, 19,8.1, 20.1,9.6, 18.2,10.8, 16.7,12.2, 14.4,12);
        oval(g, face, 0.86, 10.6,8.4, 2.7,3.1);
        shape(g, DARK, 0.92, 15,8, 17.1,7.75, 17.4,8.55, 15.6,9);
        shape(g, DARK, 0.83, 16,11.85, 18.55,11.5, 18,12.4, 16.6,12.8);
        if (style == 1 || style == 2 || style == 3) {
            shape(g, trim, 0.62, 9.75,7.7, 10.3,5, 12.4,3, 15.9,2.4, 18.9,3.25, 20.9,5.3,
                20.6,7, 18.9,6, 16.1,5.65, 13.5,6.5, 12.25,8.5, 11.5,10);
            shape(g, trim, 0.93, 11,5.7, 12.6,3.7, 15.7,3.1, 18.25,3.75, 19.45,5, 16,4.65, 13.8,5.1, 12.4,6.6);
        }
        if (style == 3) {
            shape(g, trim, 0.66, 12.8,10.9, 14.25,12, 16.2,12.4, 18.55,11.6, 18.4,13.4, 16.45,15,
                14.65,14.55, 13.1,13.65);
        }
        if (style == 4) {
            shape(g, trim, 0.71, 9.7,6.5, 10.7,3.75, 14,2.2, 18.3,2.6, 20,4.4, 20.3,6.3, 24,7.7, 22.25,8.4,
                19.4,7.55, 16.5,7.45, 14.15,6.5);
            shape(g, trim, 1.17, 11.4,4.55, 14.1,3, 17.55,3.35, 19,4.5, 16.6,4.1, 14.5,4.25, 12.5,5.1);
        }
        shape(g, BLOOD, 0.88, 13.4,12.9, 15.8,13.85, 18.3,13.5, 18,15.8, 16.95,17.05, 15.1,17.5, 13.65,16.25, 12.7,15.2);
        shape(g, BLOOD, 1.44, 13.7,14.5, 15.3,15.3, 17.25,14.95, 16.2,16.4, 14.4,16.35);
        oval(g, BONE, 0.87, 14.9,15.2, 2.5,1.4);
        g.dispose();
    }

    private void limb(int index, int type, int skin, int clothing) {
        Graphics2D g = cell(index);
        boolean leg = type >= 3;
        boolean proximalOnly = type == 2 || type == 5;
        boolean upper = type == 0 || type == 3;
        double start = upper ? 3.5 : 5.5, end = upper ? 28.2 : proximalOnly ? 24.0 : 26.0;
        double joint = upper ? 16 : 13.5;
        double top = leg ? 5.0 : 6.4, bottom = leg ? 13.1 : 12.25;
        int face = SKIN[skin], cover = clothing == 0 ? face : CLOTH[clothing];
        shape(g, DARK, 0.97, start-0.7,top+1, start+2,top-1, joint-2,top-0.5, joint+2,top+1,
            end-3,top+1.8, end+0.8,leg ? 9.6 : 9.1, end+0.5,leg ? 14.8 : 12.7, end-4,bottom+2,
            joint+2,bottom+0.4, joint-2,bottom, start+1,bottom+0.4, start-0.8,bottom-0.7);
        shape(g, cover, 0.81, start,top+1.3, start+2,top, joint-2,top+0.2, joint+2,top+1.35,
            end-2.8,top+2.35, end-2.2,bottom-0.25, joint+2,bottom, joint-2,bottom-0.6, start,bottom-0.25);
        shape(g, cover, 1.18, start+1.7,top+1, joint-3,top+0.8, joint+2,top+2, end-4.8,top+2.65,
            end-3.5,top+3.8, joint+1,top+3.3, joint-3,top+2.55, start+2.1,top+3);
        shape(g, cover, 0.66, start+4,bottom-0.1, joint-4,bottom-0.8, joint+1,top+3.4,
            joint+5,bottom-0.9, joint+1,bottom-0.05, start+4,bottom+0.15);
        if (proximalOnly) {
            shape(g, BLOOD, 1.00, end-3,top+2, end-0.1,top+1.6, end+0.1,bottom-0.7, end-1.9,bottom+0.6, end-3.6,bottom-0.3);
        } else if (leg) {
            int foot = clothing == 0 ? face : 0x413A36;
            shape(g, foot, 0.92, end-4,top+2.65, end-2,top+2.4, end-1.55,10.1, end+0.4,12,
                end+1,14.35, end-2.7,15.0, end-5.0,13.8, end-4.8,11.25);
            shape(g, foot, 1.25, end-3.7,top+3, end-2.65,top+2.85, end-2.25,10.65, end-0.5,12.25, end-1.5,13.0, end-4.3,11.1);
        } else {
            shape(g, face, 0.90, end-4.25,top+2.05, end-2.2,top+2.15, end-0.7,8.9, end+0.8,10,
                end+0.4,11.95, end-1.0,12.6, end-3.0,11.8, end-4.4,10.8);
            shape(g, face, 1.12, end-3.25,top+2.5, end-1.4,9.0, end,9.75, end-0.3,10.55, end-2.3,10.2);
        }
        if (clothing != 0) {
            shape(g, cover, 0.61, joint-2,top+0.3, joint-0.9,top+0.55, joint-0.3,bottom-0.55,
                joint-1.25,bottom-0.45);
        }
        shape(g, BLOOD, 0.89, start-0.7,top+2, start+0.65,top+0.2, start+2.4,top+1.2, start+2.65,bottom-0.9,
            start+1.8,bottom+0.7, start-0.3,bottom-0.25);
        shape(g, BLOOD, 1.44, start+0.25,top+1.75, start+1.8,top+2, start+1.8,bottom-0.3, start+0.2,bottom-1);
        oval(g, BONE, 0.90, start+0.45,top+(bottom-top)*0.48, 1.75, 2.0);
        g.dispose();
    }

    public static void main(String[] args) throws Exception {
        var art = new GorePartSprites();
        int tile = 0;
        for (int rig = 0; rig < 2; rig++) for (int style = 0; style < 5; style++)
            for (int skin = 0; skin < SKIN.length; skin++) for (int hair = 0; hair < HAIR.length; hair++)
                art.head(tile++, rig == 1, style, skin, hair);
        for (int type = 0; type < 6; type++) for (int skin = 0; skin < SKIN.length; skin++)
            for (int cloth = 0; cloth < CLOTH.length; cloth++) art.limb(tile++, type, skin, cloth);
        if (tile != 392) throw new IllegalStateException("Unexpected atlas layout " + tile);
        art.canvas.dispose();
        var atlas = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(art.image, 0, 0, 512, 512, null);
        g.dispose();
        ImageIO.write(atlas, "png", new File(args[0]));
    }
}
