package com.pzoptimizer.pzoe;

import com.pzoptimizer.pzoe.gore.GoreState;
import java.util.ArrayList;
import java.util.Locale;
import zombie.characterTextures.BloodBodyPartType;
import zombie.characterTextures.BloodClothingType;
import zombie.characters.IsoGameCharacter;
import zombie.core.ImmutableColor;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.population.BeardStyles;
import zombie.core.skinnedmodel.population.HairStyles;
import zombie.core.skinnedmodel.visual.IHumanVisual;
import zombie.core.skinnedmodel.visual.ItemVisual;
import zombie.core.skinnedmodel.visual.ItemVisuals;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.scripting.ScriptManager;

final class GoreParts {
    static final int TUMBLE_STEPS = 32;
    static final int PARTS = GoreState.HEAD | GoreState.ARMS_L | GoreState.ARMS_R | GoreState.LEGS_L | GoreState.LEGS_R;
    private static final int[] UPPER = {GoreState.ARM_L_UP, GoreState.ARM_R_UP, GoreState.LEG_L_UP, GoreState.LEG_R_UP};
    private static final int[] LOWER = {GoreState.ARM_L_LOW, GoreState.ARM_R_LOW, GoreState.LEG_L_LOW, GoreState.LEG_R_LOW};
    private static final int[] SKIN = {0xD3B39A, 0xB38A68, 0x8A6047, 0x59403A};
    private static final int[] HAIR = {0x352C29, 0x644736, 0xB99A62, 0xADB0AA, 0x873F2C};
    private static final int[] CLOTH = {0x000000, 0x3D4143, 0xA4A29A, 0x4B6373, 0x555B43, 0x85483E, 0x9E8462, 0x846D93};

    // The atlas has 16 columns of 32 x 20 cells in one 512 x 512 texture.
    private static final int HEAD_CELLS = 2 * 5 * SKIN.length * HAIR.length;
    private static final float[] SIN = new float[TUMBLE_STEPS], COS = new float[TUMBLE_STEPS];
    private static final ItemVisuals WORN = new ItemVisuals();
    private static final ArrayList<BloodBodyPartType> COVERED = new ArrayList<>(24);
    private static Texture atlas;

    static {
        for (int i = 0; i < TUMBLE_STEPS; i++) {
            double angle = i * (2.0 * Math.PI / TUMBLE_STEPS);
            SIN[i] = (float) Math.sin(angle);
            COS[i] = (float) Math.cos(angle);
        }
    }

    private GoreParts() {}

    static void warm() {
        if (atlas == null) atlas = Texture.getSharedTexture("media/textures/PZOE/gore_parts_atlas.png");
    }

    static Texture readyAtlas() {
        return atlas != null && atlas.isReady() ? atlas : null;
    }

    static int cutRoots(int cuts) {
        cuts &= PARTS;
        for (int i = 0; i < UPPER.length; i++) {
            if ((cuts & UPPER[i]) != 0) cuts &= ~LOWER[i];
        }
        return cuts;
    }

    static boolean shortened(int root, int before) {
        for (int i = 0; i < UPPER.length; i++) {
            if (root == UPPER[i]) return (before & LOWER[i]) != 0;
        }
        return false;
    }

    static boolean left(int part) {
        return (part & (GoreState.ARMS_L | GoreState.LEGS_L)) != 0;
    }

    static int snapshot(IsoGameCharacter actor, int root, boolean shortened) {
        if (!(actor instanceof IHumanVisual human) || human.isSkeleton()) return -1;
        try {
            var visual = human.getHumanVisual();
            if (visual == null) return -1;
            int skin = nearest(SKIN, visual.getSkinColor(), 0);
            human.getItemVisuals(WORN);
            if (root == GoreState.HEAD) {
                ItemVisual hat = outerLayer(BloodBodyPartType.Head);
                int hair = nearest(HAIR, visual.getHairColor(), 0);
                if (hat != null) return headTile(human.isFemale(), 4, skin, garment(hat, HAIR, 0));
                var styles = HairStyles.instance;
                String hairName = styles == null ? null : visual.getHairModel();
                String beard = BeardStyles.instance == null ? null : visual.getBeardModel();
                if (beard != null && !beard.isBlank() && !beard.equalsIgnoreCase("none")
                    && !beard.equalsIgnoreCase("shaved")) {
                    return headTile(human.isFemale(), 3, skin, nearest(HAIR, visual.getBeardColor(), 0));
                }
                int style = 0;
                if (hairName != null && !hairName.isBlank() && !hairName.equalsIgnoreCase("bald")) {
                    var hairStyle = human.isFemale() ? styles.FindFemaleStyle(hairName) : styles.FindMaleStyle(hairName);
                    if (hairStyle != null && hairStyle.isValid()) style = hairStyle.getLevel() >= 2 || hairStyle.isAttachedHair() ? 2 : 1;
                }
                return headTile(human.isFemale(), style, skin, hair);
            }
            BloodBodyPartType region = partRegion(root);
            ItemVisual garment = outerLayer(region);
            if (garment == null && !shortened && (root & (GoreState.ARM_L_UP | GoreState.ARM_R_UP
                | GoreState.LEG_L_UP | GoreState.LEG_R_UP)) != 0) {
                int lower = root << 1;
                garment = outerLayer(partRegion(lower));
            }
            return limbTile(root, shortened, skin, garment == null ? 0 : garment(garment, CLOTH, 1));
        } catch (RuntimeException ignored) {
            return -1;
        } finally {
            WORN.clear();
            COVERED.clear();
        }
    }

    static int neutralTile(int root, boolean shortened) {
        return root == GoreState.HEAD ? headTile(false, 0, 1, 0) : limbTile(root, shortened, 1, 0);
    }

    static int headTile(boolean female, int style, int skin, int hair) {
        return (((female ? 5 : 0) + style) * SKIN.length + skin) * HAIR.length + hair;
    }

    static int limbTile(int root, boolean shortened, int skin, int clothes) {
        boolean leg = (root & (GoreState.LEGS_L | GoreState.LEGS_R)) != 0;
        boolean upper = (root & (GoreState.ARM_L_UP | GoreState.ARM_R_UP | GoreState.LEG_L_UP | GoreState.LEG_R_UP)) != 0;
        int shape = (leg ? 3 : 0) + (shortened ? 2 : upper ? 0 : 1);
        return HEAD_CELLS + (shape * SKIN.length + skin) * CLOTH.length + clothes;
    }

    private static BloodBodyPartType partRegion(int part) {
        return switch (part) {
            case GoreState.ARM_L_UP -> BloodBodyPartType.UpperArm_L;
            case GoreState.ARM_R_UP -> BloodBodyPartType.UpperArm_R;
            case GoreState.ARM_L_LOW -> BloodBodyPartType.ForeArm_L;
            case GoreState.ARM_R_LOW -> BloodBodyPartType.ForeArm_R;
            case GoreState.LEG_L_UP -> BloodBodyPartType.UpperLeg_L;
            case GoreState.LEG_R_UP -> BloodBodyPartType.UpperLeg_R;
            case GoreState.LEG_L_LOW -> BloodBodyPartType.LowerLeg_L;
            case GoreState.LEG_R_LOW -> BloodBodyPartType.LowerLeg_R;
            default -> BloodBodyPartType.Head;
        };
    }

    private static ItemVisual outerLayer(BloodBodyPartType region) {
        for (int i = WORN.size() - 1, first = Math.max(0, i - 23); i >= first; i--) {
            ItemVisual visual = WORN.get(i);
            if (visual == null || visual.getItemType() == null || visual.getHole(region) >= 0.95f) continue;
            var item = ScriptManager.instance.getItem(visual.getItemType());
            if (item == null) continue;
            COVERED.clear();
            if (BloodClothingType.getCoveredParts(item.getBloodClothingType(), COVERED).contains(region)) return visual;
            if (region == BloodBodyPartType.Head && item.getBodyLocation() != null) {
                String location = item.getBodyLocation().toString().toLowerCase(Locale.ROOT);
                if (location.endsWith("hat") || location.endsWith("fullhat") || location.endsWith("helmet")) return visual;
            }
        }
        return null;
    }

    private static int garment(ItemVisual visual, int[] palette, int first) {
        var item = visual.getClothingItem();
        if (item == null || !item.isReady()) return first;
        ImmutableColor tint = visual.getTint(item);
        if (palette == CLOTH && tint != null && tint.r > 0.94f && tint.g > 0.94f && tint.b > 0.94f) {
            String texture = visual.getTextureChoice(item);
            if (texture != null && texture.toLowerCase(Locale.ROOT).contains("denim")) return 3;
        }
        return nearest(palette, tint, first);
    }

    private static int nearest(int[] palette, ImmutableColor color, int first) {
        if (color == null) return first;
        float r = color.r * 255f, g = color.g * 255f, b = color.b * 255f;
        int best = first;
        float bestError = Float.MAX_VALUE;
        for (int i = first; i < palette.length; i++) {
            int value = palette[i];
            float dr = r - (value >>> 16), dg = g - ((value >>> 8) & 255), db = b - (value & 255);
            float error = dr * dr + dg * dg * 1.4f + db * db;
            if (error >= bestError) continue;
            best = i;
            bestError = error;
        }
        return best;
    }

    static int angle(int start, float age) {
        return (start + (int) (age * 23f)) & (TUMBLE_STEPS - 1);
    }

    static void draw(Texture texture, int tile, int angle, boolean flip, float x, float y, float scale, ColorInfo light, float alpha) {
        float w, h, u0, v0, u1, v1;
        if (tile >= 0) {
            int px = (tile & 15) * 32, py = (tile >>> 4) * 20;
            u0 = (px + 0.5f) / 512f;
            v0 = (py + 0.5f) / 512f;
            u1 = (px + 31.5f) / 512f;
            v1 = (py + 19.5f) / 512f;
            w = 15.5f * scale;
            h = 9.5f * scale;
        } else {
            u0 = texture.getXStart();
            v0 = texture.getYStart();
            u1 = texture.getXEnd();
            v1 = texture.getYEnd();
            w = texture.getWidth() * scale * 0.275f;
            h = texture.getHeight() * scale * 0.275f;
        }
        if (flip) {
            float u = u0;
            u0 = u1;
            u1 = u;
        }
        quad(texture, angle, x, y, w, h, u0, v0, u1, v1, light.r, light.g, light.b, alpha);
    }

    static void drawCaptured(Texture texture, int crop, int angle, float x, float y, ColorInfo light, float alpha) {
        float left = (texture.getXStart() + texture.getXEnd()) * 0.5f;
        float top = (texture.getYStart() + texture.getYEnd()) * 0.5f;
        float radius = crop * 0.5f;
        float u = (texture.getXEnd() - texture.getXStart()) * radius / texture.getWidth();
        float v = (texture.getYEnd() - texture.getYStart()) * radius / texture.getHeight();
        quad(texture, angle, x, y, radius, radius, left - u, top - v, left + u, top + v,
            light.r, light.g, light.b, alpha);
    }

    private static void quad(Texture texture, int angle, float x, float y, float w, float h,
                             float u0, float v0, float u1, float v1, float r, float g, float b, float alpha) {
        float ax = COS[angle] * w, ay = SIN[angle] * w;
        float bx = -SIN[angle] * h, by = COS[angle] * h;
        SpriteRenderer.instance.render(texture, x - ax - bx, y - ay - by, x + ax - bx, y + ay - by,
            x + ax + bx, y + ay + by, x - ax + bx, y - ay + by,
            u0, v0, u1, v0, u1, v1, u0, v1, r, g, b, alpha);
    }
}
