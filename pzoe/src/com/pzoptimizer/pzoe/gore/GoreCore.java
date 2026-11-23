package com.pzoptimizer.pzoe.gore;

import com.pzoptimizer.AsmSupport;
import com.pzoptimizer.PZOLogger;
import com.pzoptimizer.pzoe.GoreMap;
import com.pzoptimizer.shaded.asm.Opcodes;
import com.pzoptimizer.shaded.asm.tree.AbstractInsnNode;
import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.FieldInsnNode;
import com.pzoptimizer.shaded.asm.tree.InsnList;
import com.pzoptimizer.shaded.asm.tree.InsnNode;
import com.pzoptimizer.shaded.asm.tree.JumpInsnNode;
import com.pzoptimizer.shaded.asm.tree.LabelNode;
import com.pzoptimizer.shaded.asm.tree.MethodInsnNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import com.pzoptimizer.shaded.asm.tree.TryCatchBlockNode;
import com.pzoptimizer.shaded.asm.tree.TypeInsnNode;
import com.pzoptimizer.shaded.asm.tree.VarInsnNode;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import zombie.characterTextures.BloodBodyPartType;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoGridSquare;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoDeadBody;
import zombie.scripting.objects.WeaponCategory;

/**
 * PZOE Gore's hooks into the game and its rules. No GL in here: this class loads on dedicated servers, where only the
 * gameplay side runs (limbs that change what a zombie can do, decided by the server and sent to clients).
 *
 * Two layers, like PZOE Weather. Looks (client cards): wounds where a hit landed, limbs and heads coming off on kills,
 * sprays, gibs, pools. They never change gameplay and are rolled from a seed per zombie and hit, so clients that see
 * the same hits draw the same thing. Gameplay (sandbox page PZOEGore, server authority, mild-on): living zombies can
 * lose legs (they crawl), arms (they grab less) or the jaw (no bite). With gameplay off, limbs only come off on kills.
 *
 * Ground Blood: the game's floor splats stay as they are (saved, sent, read by Lua), but while GoreMap draws them
 * (levels 0-3) the chunk bake skips their sprites and a new splat no longer re-bakes its chunk level.
 */
public final class GoreCore {

    public static final String COMBAT = "zombie/CombatManager";
    public static final String CHUNK = "zombie/iso/IsoChunk", SQUARE = "zombie/iso/IsoGridSquare",
        RENDER_CELL = "zombie/iso/fboRenderChunk/FBORenderCell", CHARACTER = "zombie/characters/IsoGameCharacter",
        ZOMBIE = "zombie/characters/IsoZombie", DEAD_BODY = "zombie/iso/objects/IsoDeadBody",
        BODY_DAMAGE = "zombie/characters/BodyDamage/BodyDamage", EAT_STATE = "zombie/ai/states/ZombieEatBodyState",
        GIBLETS = "zombie/iso/objects/IsoZombieGiblets", GAME_TIME = "zombie/GameTime";
    private static final String GIBLET_ARGS = "(Lzombie/iso/objects/IsoZombieGiblets$GibletType;Lzombie/iso/IsoCell;FFFFF)V";
    private static final String SELF = "com/pzoptimizer/pzoe/gore/GoreCore";
    public static final int LEVELS = 4;

    /** The render side (GoreFx in PZOE's main package) on clients; null on servers. */
    public static volatile GoreListener fx;

    // Set by GoreMap once a frame: the map draws floor blood, so vanilla's draw stands down
    public static volatile boolean mapDraws;
    // Looks, from the client cards
    public static volatile boolean hitLook, deathLook, vehicleLook, woundsLook, runoffLook, dismemberLook, giblets;
    public static volatile float sprayAmount = 1f;

    private static final ConcurrentLinkedQueue<int[]> cleaned = new ConcurrentLinkedQueue<>();
    private static final AtomicLong skippedBakes = new AtomicLong(), cleanings = new AtomicLong();
    private static long reportedSkips, lastReportNs, wounds, severed, kills;
    private static final long START = System.nanoTime();
    // Server: limb losses to send to clients, "id:mask,id:mask"
    private static final StringBuilder outbox = new StringBuilder();

    private GoreCore() {}

    public static float clock() {
        return (System.nanoTime() - START) / 1e9f;
    }

    static boolean ours(int level) {
        return mapDraws && level >= 0 && level < LEVELS;
    }

    // Sandbox (gameplay). Read when used; the game caches the options itself.
    private static boolean sandboxBool(String name, boolean dflt) {
        try {
            zombie.SandboxOptions.SandboxOption o = zombie.SandboxOptions.instance.getOptionByName(name);
            if (o instanceof zombie.SandboxOptions.BooleanSandboxOption b) return b.getValue();
        } catch (Throwable ignored) {
        }
        return dflt;
    }

    private static int sandboxInt(String name, int dflt) {
        try {
            zombie.SandboxOptions.SandboxOption o = zombie.SandboxOptions.instance.getOptionByName(name);
            if (o instanceof zombie.SandboxOptions.EnumSandboxOption e) return e.getValue();
        } catch (Throwable ignored) {
        }
        return dflt;
    }

    /** Gameplay chance scale: mild 0.5, normal 1, strong 2. */
    static float strength() {
        int s = sandboxInt("PZOEGore.Strength", 1);
        return s <= 1 ? 0.5f : s == 2 ? 1f : 2f;
    }

    static boolean gameplayHere() {
        return !zombie.network.GameClient.client && sandboxBool("PZOEGore.LimbLossEnabled", false);
    }

    /** The game's Blood Level: None turns all gore off, the others scale amounts like vanilla's splats. */
    public static float bloodScale() {
        int v = zombie.SandboxOptions.instance.bloodLevel.getValue();
        return v <= 1 ? 0f : v == 2 ? 0.5f : v == 3 ? 1f : v == 4 ? 2f : 3f;
    }

    // Weapon classes
    public static final int W_NONE = 0, W_BLUNT = 1, W_SMALL_BLADE = 2, W_AXE = 3, W_LONG_BLADE = 4, W_SPEAR = 5, W_GUN = 6,
        W_SHOTGUN = 7, W_VEHICLE = 8;

    public static int weaponClass(HandWeapon w) {
        if (w == null) return W_NONE;
        try {
            if (w.isRanged()) return w.getProjectileCount() > 1 ? W_SHOTGUN : W_GUN;
            if (w.isOfWeaponCategory(WeaponCategory.AXE)) return W_AXE;
            if (w.isOfWeaponCategory(WeaponCategory.LONG_BLADE)) return W_LONG_BLADE;
            if (w.isOfWeaponCategory(WeaponCategory.SMALL_BLADE)) return W_SMALL_BLADE;
            if (w.isOfWeaponCategory(WeaponCategory.SPEAR)) return W_SPEAR;
            if (w.isOfWeaponCategory(WeaponCategory.UNARMED)) return W_NONE;
        } catch (Throwable ignored) {
        }
        return W_BLUNT;
    }

    private static HandWeapon weaponOf(IsoGameCharacter attacker) {
        return attacker != null && attacker.getPrimaryHandItem() instanceof HandWeapon w ? w : null;
    }

    // Same numbers on every client for the same zombie and hit
    static java.util.Random seeded(IsoGameCharacter c, int salt) {
        String uid = c.getUID();
        long s = (uid == null ? System.identityHashCode(c) : uid.hashCode()) * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L;
        return new java.util.Random(s);
    }

    static boolean localPlayer(IsoGameCharacter c) {
        return c instanceof IsoPlayer p && p.isLocalPlayer();
    }

    // Body parts as BloodBodyPartType indices
    public static final int P_HAND_L = 0, P_HAND_R = 1, P_FOREARM_L = 2, P_FOREARM_R = 3, P_UPPERARM_L = 4, P_UPPERARM_R = 5,
        P_TORSO_UP = 6, P_TORSO_LOW = 7, P_HEAD = 8, P_NECK = 9, P_GROIN = 10, P_THIGH_L = 11, P_THIGH_R = 12, P_SHIN_L = 13,
        P_SHIN_R = 14, P_FOOT_L = 15, P_FOOT_R = 16, P_BACK = 17;

    private static final ThreadLocal<IsoMovingObject> resolvingVictim = new ThreadLocal<>();

    public static IsoMovingObject beginResolvedHit(IsoMovingObject target) {
        if (!hitLook) return null;
        IsoMovingObject previous = resolvingVictim.get();
        resolvingVictim.set(target instanceof IsoZombie && !zombie.network.GameServer.server ? target : null);
        return previous;
    }

    public static void endResolvedHit(IsoMovingObject previous) {
        if (hitLook || previous != null) resolvingVictim.set(previous);
    }

    public static void resolvedHit(float damage, IsoGameCharacter victim, IsoGameCharacter attacker, HandWeapon weapon,
        int part, zombie.CombatManager.HitData hit, boolean ranged) {
        try {
            if (!hitLook || !(victim instanceof IsoZombie z) || victim != resolvingVictim.get() || attacker == null
                || !(damage > 0f) || hit.isIgnoreDamage() || bloodScale() <= 0f) return;
            if (!ranged && attacker instanceof zombie.characters.IsoLivingCharacter living
                && living.isDoShove() && !attacker.isAimAtFloor()) return;
            int p = ranged ? rangedPart(part) : BloodBodyPartType.FromString(
                zombie.characters.BodyDamage.BodyPartType.FromIndex(part).name()).index();
            if (!ranged && hit.hitHead() > 0 && !isHead(p)) p = P_HEAD;
            if (p < 0 || p >= BloodBodyPartType.MAX.index()) return;
            GoreState g = GoreState.make(victim);
            float now = clock();
            g.lastSeen = g.bloodAt = g.resolvedAt = now;
            int wc = weaponClass(weapon);
            g.lastPart = p;
            g.lastWeapon = wc;
            int kind = woundKind(wc, false, false);
            java.util.Random random = seeded(victim, ++g.hits);
            int side = impactSide(victim, attacker);
            float distanceSquared = distanceSquared(victim, attacker);
            boolean buckshot = wc == W_SHOTGUN && distanceSquared <= 16f && Math.abs(victim.getZ() - attacker.getZ()) < 0.5f;
            float along = p == P_NECK ? 0.28f : 0.52f;
            float size = switch (kind) {
                case GoreState.BULLET -> buckshot ? 1.02f : 0.59f;
                case GoreState.CUT -> 0.78f;
                case GoreState.BLUNT -> 0.61f;
                default -> 0.58f;
            };
            size += Math.min(0.30f, damage * 0.11f);
            float around = side * 0.25f;
            GoreImpact contact = ranged ? GoreImpact.capture(victim, attacker, part, p, wc != W_SHOTGUN) : null;
            long point = contact == null ? 0L : contact.bindPoint;
            float severity = woundsLook ? g.impact(p, buckshot ? GoreState.BUCKSHOT : kind, along, around, size, point) : 0f;
            beginBloodRun(g, p, kind, side, size, point);
            if (woundsLook) wounds++;
            if (woundsLook && kind == GoreState.BULLET && !buckshot && !isHead(p) && random.nextFloat() < 0.35f) {
                g.impact(p, GoreState.EXIT, along, (around + 0.5f) % 1f, size * 1.25f);
            }
            if (isHead(p) && dismemberLook) {
                if (buckshot) g.shapeHead(GoreState.HEAD_BUCKSHOT, side);
                else if ((wc == W_AXE || wc == W_LONG_BLADE) && severity >= 1.15f) g.shapeHead(GoreState.HEAD_SPLIT, side);
                else if (kind == GoreState.BLUNT && severity >= 1.05f) g.shapeHead(GoreState.HEAD_DENT, side);
            }
            boolean opening = ++g.combatHits == 1;
            GoreListener l = fx;
            if (l != null) l.hit(victim, attacker, p, kind, wc, opening, contact);
            if (gameplayHere() && !z.isDead()) livingLimbs(z, g, p, wc, random);
        } catch (Throwable t) {
            warnOnce("resolved hit", t);
        }
    }

    private static void beginBloodRun(GoreState g, int p, int kind, int side, float size, long point) {
        if (!runoffLook) return;
        int power = switch (kind) {
            case GoreState.BULLET, GoreState.CUT, GoreState.STAB -> size > 0.85f ? 4 : 3;
            case GoreState.BITE -> 2;
            case GoreState.BLUNT -> size >= 0.90f ? 2 : 0;
            default -> 0;
        };
        if (power != 0) g.bleed(p, side, power, point, (float) zombie.GameTime.getInstance().getWorldAgeHours());
    }

    static boolean isHead(int part) {
        return part == P_HEAD || part == P_NECK;
    }

    private static float distanceSquared(IsoGameCharacter victim, IsoGameCharacter attacker) {
        float dx = attacker.getX() - victim.getX(), dy = attacker.getY() - victim.getY();
        return dx * dx + dy * dy;
    }

    private static int impactSide(IsoGameCharacter victim, IsoGameCharacter attacker) {
        var forward = victim.getForwardDirection();
        float dx = attacker.getX() - victim.getX(), dy = attacker.getY() - victim.getY();
        float dot = forward.x * dx + forward.y * dy, cross = forward.x * dy - forward.y * dx;
        return Math.abs(dot) >= Math.abs(cross) ? (dot >= 0f ? 0 : 2) : (cross >= 0f ? 1 : 3);
    }

    private static int rangedPart(int ragdollPart) {
        return switch (ragdollPart) {
            case 0 -> P_TORSO_LOW;
            case 1 -> P_TORSO_UP;
            case 2 -> P_HEAD;
            case 3 -> P_THIGH_L;
            case 4 -> P_SHIN_L;
            case 5 -> P_THIGH_R;
            case 6 -> P_SHIN_R;
            case 7 -> P_UPPERARM_L;
            case 8 -> P_FOREARM_L;
            case 9 -> P_UPPERARM_R;
            case 10 -> P_FOREARM_R;
            default -> -1;
        };
    }

    private static int woundKind(int weapon, boolean scratched, boolean bitten) {
        return bitten && weapon == W_NONE ? GoreState.BITE : switch (weapon) {
            case W_GUN, W_SHOTGUN -> GoreState.BULLET;
            case W_AXE, W_LONG_BLADE, W_SMALL_BLADE -> GoreState.CUT;
            case W_SPEAR -> GoreState.STAB;
            case W_NONE -> scratched ? GoreState.SCRATCH : GoreState.BLUNT;
            default -> GoreState.BLUNT;
        };
    }

    /** Start of IsoGameCharacter.addBlood(part, scratched, bitten, allLayers): a hit on that part. */
    public static void onBlood(IsoGameCharacter chr, BloodBodyPartType part, boolean scratched, boolean bitten) {
        try {
            boolean play = chr instanceof IsoZombie && gameplayHere();
            if (!hitLook && !play) return;
            if (chr == null || part == null || part == BloodBodyPartType.MAX || bloodScale() <= 0f) return;
            // Local players' wounds come from their own body damage (GoreFx polls it)
            if (localPlayer(chr) || (chr instanceof IsoZombie && resolvingVictim.get() == chr)) return;
            boolean server = zombie.network.GameServer.server;
            if (server && !play) return;
            GoreState g = GoreState.make(chr);
            // One blow can call addBlood for several parts at once (a head hit also bloodies chest and arms): the
            // first part is the one that was hit
            float now = clock();
            if (now - g.bloodAt < 0.08f) return;
            g.bloodAt = now;
            g.hits++;
            g.lastSeen = now;
            IsoGameCharacter att = chr.getAttackedBy();
            int wc = weaponClass(weaponOf(att));
            int p = part.index();
            java.util.Random r = seeded(chr, g.hits);
            int kind = woundKind(wc, scratched, bitten);
            g.lastPart = p;
            g.lastWeapon = wc;
            // Only a blow from someone close by is a hit; the game also adds blood when it dresses a fresh zombie (a
            // weapon stuck in it) or one climbs through broken glass: those leave the wound but throw no blood
            boolean ranged = wc == W_GUN || wc == W_SHOTGUN;
            boolean blow = att != null && att != chr && Math.abs(att.getX() - chr.getX()) + Math.abs(att.getY() - chr.getY()) < (ranged ? 80f : 4f);
            if (!server && hitLook) {
                float sz = switch (kind) {
                    case GoreState.BULLET -> 0.35f + 0.15f * r.nextFloat();
                    case GoreState.CUT -> 0.6f + 0.5f * r.nextFloat();
                    case GoreState.BLUNT -> 0.7f + 0.4f * r.nextFloat();
                    default -> 0.5f + 0.3f * r.nextFloat();
                };
                float along = blow ? (p == P_NECK ? 0.28f : 0.52f) : 0.2f + 0.6f * r.nextFloat();
                float around = blow ? impactSide(chr, att) * 0.25f : r.nextFloat();
                if (woundsLook) {
                    if (blow) {
                        g.impact(p, kind, along, around, sz);
                        beginBloodRun(g, p, kind, Math.round(around * 4f), sz, 0L);
                    } else g.wound(p, kind, along, around, sz);
                    wounds++;
                }
                // A good share of bullets go through
                if (woundsLook && kind == GoreState.BULLET && r.nextFloat() < (wc == W_SHOTGUN ? 0.2f : 0.45f)) {
                    g.wound(p, GoreState.EXIT, along, (around + 0.5f) % 1f, sz * 1.8f);
                }
                GoreListener l = fx;
                if (blow) {
                    boolean openingHit = ++g.combatHits == 1;
                    if (l != null) l.hit(chr, att, p, kind, wc, openingHit, null);
                }
            }
            if (play && blow && !chr.isDead()) livingLimbs((IsoZombie) chr, g, p, wc, r);
        } catch (Throwable t) {
            warnOnce("hit", t);
        }
    }

    private static int limbForPart(int p) {
        return switch (p) {
            case P_HAND_L, P_FOREARM_L -> GoreState.ARM_L_LOW;
            case P_UPPERARM_L -> GoreState.ARM_L_UP;
            case P_HAND_R, P_FOREARM_R -> GoreState.ARM_R_LOW;
            case P_UPPERARM_R -> GoreState.ARM_R_UP;
            case P_THIGH_L -> GoreState.LEG_L_UP;
            case P_SHIN_L, P_FOOT_L -> GoreState.LEG_L_LOW;
            case P_THIGH_R -> GoreState.LEG_R_UP;
            case P_SHIN_R, P_FOOT_R -> GoreState.LEG_R_LOW;
            case P_HEAD, P_NECK -> GoreState.HEAD;
            default -> 0;
        };
    }

    // Gameplay: what a hit can take off a zombie that keeps going
    private static void livingLimbs(IsoZombie z, GoreState g, int p, int wc, java.util.Random r) {
        float k = strength();
        int limb = limbForPart(p);
        boolean blade = wc == W_AXE || wc == W_LONG_BLADE;
        boolean arm = (limb & (GoreState.ARMS_L | GoreState.ARMS_R)) != 0, leg = (limb & (GoreState.LEGS_L | GoreState.LEGS_R)) != 0;
        int take = 0;
        if (arm && sandboxBool("PZOEGore.ArmLoss", false)) {
            float c = blade ? 0.12f : wc == W_SHOTGUN ? 0.06f : 0f;
            if (r.nextFloat() < c * k) take = limb;
        } else if (leg && sandboxBool("PZOEGore.Crawlers", false)) {
            float c = blade ? 0.1f : wc == W_SHOTGUN ? 0.07f : wc == W_GUN ? 0.02f : 0f;
            if (r.nextFloat() < c * k) take = limb;
        } else if ((p == P_HEAD) && sandboxBool("PZOEGore.JawLoss", false)) {
            float c = wc == W_BLUNT || wc == W_AXE ? 0.06f : wc == W_SHOTGUN ? 0.08f : 0f;
            if (r.nextFloat() < c * k) take = GoreState.JAW;
        }
        int cutBefore = g.cut;
        if (take == 0 || !g.sever(take)) return;
        severed++;
        if ((take & (GoreState.LEGS_L | GoreState.LEGS_R)) != 0) {
            z.setBecomeCrawler(true);
            if (!z.isCrawling()) z.knockDown(false);
        }
        if (take == GoreState.JAW) g.wound(P_HEAD, GoreState.CUT, 0.15f, 0.5f, 1.1f);
        if (zombie.network.GameServer.server) {
            synchronized (outbox) {
                if (outbox.length() > 0) outbox.append(',');
                outbox.append(z.getOnlineID()).append(':').append(g.cut);
            }
        } else {
            GoreListener l = fx;
            if (l != null && (hitLook || dismemberLook)) l.severed(z, take, cutBefore);
        }
    }

    /** Start of IsoGameCharacter.doDeathSplatterAndSounds(weapon, wielder, isGory): kill looks. */
    public static void onDeath(IsoGameCharacter chr, HandWeapon weapon, IsoGameCharacter wielder, boolean gory) {
        try {
            if (!deathLook || chr == null || zombie.network.GameServer.server || bloodScale() <= 0f) return;
            if (chr instanceof IsoZombie z && (z.isReanimatedForGrappleOnly() || z.isOnKillDone() || z.isOnDeathDone())) return;
            GoreState g = GoreState.make(chr);
            if (g.deathEffects) return;
            g.deathEffects = true;
            kills++;
            g.lastSeen = clock();
            int wc = weapon != null ? weaponClass(weapon) : clock() - g.vehicleHitAt < 2f ? W_VEHICLE : W_NONE;
            int p = g.lastPart >= 0 ? g.lastPart : P_TORSO_UP;
            java.util.Random r = seeded(chr, 1000 + g.hits);
            int burst = 0;
            int taken = 0, cutBefore = g.cut;
            if (dismemberLook && gory) {
                boolean crit = chr.isCloseKilled() || (wielder != null && wielder.isCriticalHit());
                // A zombie finished off on the ground is hit in the head (that's what the game's ground attacks aim at)
                boolean grounded = chr.isOnFloor() || (chr instanceof IsoZombie zz && zz.getHitHeadWhileOnFloor() > 0);
                boolean resolved = g.lastWeapon == wc && clock() - g.resolvedAt < 0.35f;
                if (!resolved && grounded && wc != W_GUN && wc != W_SHOTGUN) p = P_HEAD;
                boolean head = isHead(p);
                int side = wielder == null ? g.headSide : impactSide(chr, wielder);
                int limb = limbForPart(p);
                float f = r.nextFloat();
                switch (wc) {
                    case W_BLUNT, W_NONE -> {
                        // Clubs, hammers, bats and boots smash the head in
                        float c = head ? (crit || g.headStyle == GoreState.HEAD_DENT ? 1f : 0.85f) : 0f;
                        if (wc == W_NONE) c = head && grounded && wielder instanceof IsoPlayer ? 0.75f : 0f;
                        if (f < c) {
                            g.shapeHead(GoreState.HEAD_CRUSHED, side);
                            g.impact(P_HEAD, GoreState.BLUNT, 0.52f, side * 0.25f, 1.4f);
                            burst = 2;
                        }
                    }
                    case W_AXE, W_LONG_BLADE -> {
                        if (head) {
                            if (f < (wc == W_LONG_BLADE ? 0.6f : 0.5f) + (crit ? 0.25f : 0f)) {
                                taken = GoreState.HEAD;
                            } else if (wc == W_AXE && f < 0.9f) {
                                // An axe that doesn't take the head splits it
                                g.shapeHead(GoreState.HEAD_SPLIT, side);
                                g.impact(P_HEAD, GoreState.CUT, 0.52f, side * 0.25f, 1.6f);
                                burst = 2;
                            }
                        } else if (limb != 0 && f < (crit ? 0.8f : 0.5f)) {
                            taken = limb;
                        } else if ((p == P_TORSO_UP || p == P_TORSO_LOW || p == P_GROIN) && crit && f < (wc == W_LONG_BLADE ? 0.3f : 0.15f)) {
                            g.slice = 1 + r.nextInt(3);
                            g.version++;
                        }
                    }
                    case W_SHOTGUN -> {
                        if (head && wielder != null && distanceSquared(chr, wielder) <= 16f
                            && Math.abs(chr.getZ() - wielder.getZ()) < 0.5f) {
                            taken = GoreState.HEAD;
                            g.impact(P_HEAD, GoreState.BUCKSHOT, 0.52f, side * 0.25f, 1.34f);
                            burst = 1;
                        } else if (limb != 0 && limb != GoreState.HEAD && f < 0.3f) taken = limb;
                    }
                    case W_VEHICLE -> {
                        if (f < 0.35f) {
                            int[] any = {GoreState.ARM_L_LOW, GoreState.ARM_R_LOW, GoreState.LEG_L_LOW, GoreState.LEG_R_LOW, GoreState.ARM_L_UP,
                                GoreState.LEG_R_UP};
                            taken = any[r.nextInt(any.length)];
                        }
                    }
                    default -> {
                    }
                }
                if (taken != 0) {
                    if (g.sever(taken)) severed++;
                    else taken = 0;
                }
                if (burst == 2) severed++;
                if (chr instanceof IsoZombie z && (taken != 0 || burst != 0 || g.slice != 0)) z.atlasTex = null;
            }
            GoreListener l = fx;
            if (l != null) l.death(chr, wielder, g, taken, cutBefore, burst, wc, p, gory);
        } catch (Throwable t) {
            warnOnce("death", t);
        }
    }

    /** Start of IsoZombie.addBloodFromVehicleImpact(speed). */
    public static void onVehicleHit(IsoZombie z, float speed) {
        try {
            if (!vehicleLook || z == null || zombie.network.GameServer.server) return;
            GoreState g = GoreState.make(z);
            g.vehicleHitAt = clock();
            g.lastSeen = g.vehicleHitAt;
            GoreListener l = fx;
            if (l != null) l.vehicleHit(z, speed);
        } catch (Throwable t) {
            warnOnce("vehicle", t);
        }
    }

    /** End of IsoDeadBody(died, wasCorpseAlready, addToWorld): the record moves to the corpse and into its mod data. */
    public static void onCorpse(IsoDeadBody body, IsoGameCharacter died) {
        try {
            if (body == null || died == null) return;
            GoreState g = GoreState.of(died);
            if (g == null) return;
            GoreState.forget(died);
            GoreState.put(body, g);
            if (body.getAtlasTexture() != null) GoreAtlas.refreshTexture(body);
            g.lastSeen = clock();
            if (g.cut != 0 || g.slice != 0 || g.count > 0 || g.headStyle != 0 || g.crush || g.flowPart >= 0) body.getModData().rawset("PZOE_Gore", g.encode());
            GoreListener l = fx;
            if (l != null) l.corpse(body, g);
        } catch (Throwable t) {
            warnOnce("corpse", t);
        }
    }

    /** A saved corpse's record, read back from its mod data the first time it's drawn. */
    public static GoreState corpseRecord(IsoDeadBody body) {
        GoreState g = GoreState.of(body);
        if (g != null || body == null || !body.hasModData()) return g;
        Object s = body.getModData().rawget("PZOE_Gore");
        if (!(s instanceof String str)) return null;
        g = GoreState.decode(str);
        if (g != null) GoreState.put(body, g);
        return g;
    }

    /** Start of IsoZombie.resetForReuse: a pooled zombie starts clean. */
    public static void onReuse(IsoZombie z) {
        GoreState.forget(z);
    }

    /** Start of IsoZombie.cantBite: no jaw, no bite (gameplay). */
    public static boolean jawless(IsoZombie z) {
        GoreState g = GoreState.of(z);
        return g != null && g.lost(GoreState.JAW) && gameplayHere() && sandboxBool("PZOEGore.JawLoss", false);
    }

    /** Start of BodyDamage.AddRandomDamageFromZombie: a zombie with no arms (and no bite) can't hurt you. */
    public static boolean attackBlocked(IsoZombie z) {
        GoreState g = GoreState.of(z);
        if (g == null || (g.cut & (GoreState.ARMS_L | GoreState.ARMS_R)) == 0 || !gameplayHere()
            || !sandboxBool("PZOEGore.ArmLoss", false)) return false;
        boolean noL = g.lost(GoreState.ARMS_L), noR = g.lost(GoreState.ARMS_R);
        if (noL && noR) return g.lost(GoreState.JAW) || z.cantBite() || zombie.core.random.Rand.Next(3) != 0;
        return (noL || noR) && zombie.core.random.Rand.Next(4) == 0;
    }

    /** Start of ZombieEatBodyState.execute: the body being eaten gets torn open, a little at a time. */
    public static void eating(IsoGameCharacter owner) {
        try {
            if (!woundsLook || !(owner instanceof IsoZombie z) || zombie.network.GameServer.server || bloodScale() <= 0f) return;
            Object target = z.getEatBodyTarget();
            if (target == null) return;
            GoreState g = target instanceof IsoDeadBody b ? corpseRecord(b) : GoreState.of(target);
            if (g == null) g = GoreState.make(target);
            float now = clock();
            g.lastSeen = now;
            if (now - g.fedAt < 9f) return;
            g.fedAt = now;
            java.util.Random r = new java.util.Random(System.identityHashCode(target) * 31L + g.count * 7L + g.next);
            int[] parts = {P_TORSO_UP, P_TORSO_LOW, P_NECK, P_UPPERARM_L, P_UPPERARM_R, P_THIGH_L, P_THIGH_R};
            g.wound(parts[r.nextInt(parts.length)], GoreState.BITE, 0.2f + 0.6f * r.nextFloat(), r.nextFloat(), 0.9f + 0.6f * r.nextFloat());
            if (target instanceof IsoDeadBody b) b.getModData().rawset("PZOE_Gore", g.encode());
            GoreListener l = fx;
            if (l != null) l.fed(target, g);
        } catch (Throwable t) {
            warnOnce("eat", t);
        }
    }

    public static void spawnCombatGiblet(zombie.iso.objects.IsoZombieGiblets.GibletType type, zombie.iso.IsoCell cell,
        float x, float y, float z, float dx, float dy) {
        GoreListener l = fx;
        if (!giblets || l == null || !l.combatGiblet(x, y, z, dx, dy)) {
            new zombie.iso.objects.IsoZombieGiblets(type, cell, x, y, z, dx, dy);
        }
    }

    public static void combatWallBlood(IsoGameCharacter target, int distance, float alpha) {
        if (mapDraws && !zombie.network.GameClient.client && !zombie.network.GameServer.server) {
            distance = Math.min(1, distance);
        }
        target.splatBlood(distance, alpha);
    }

    /** End of the IsoZombieGiblets constructor: true makes the game's sprite invisible (GoreFx draws its own). */
    public static boolean giblet(zombie.iso.objects.IsoZombieGiblets gib) {
        GoreListener l = fx;
        return giblets && l != null && l.giblet(gib);
    }

    /** Return of GameTime.getSlomoMultiplier: hit-stop and kill cam (single player only, GoreFx decides). */
    public static float slomo(float v) {
        GoreListener l = fx;
        return l == null ? v : v * l.timeScale();
    }

    // MP: server side, the Lua server script picks these up every tick and sends them on
    public static String takeOutbox() {
        synchronized (outbox) {
            if (outbox.length() == 0) return null;
            String s = outbox.toString();
            outbox.setLength(0);
            return s;
        }
    }

    /** Client side: "id:mask,..." from the server. */
    public static void applyRemote(String s) {
        if (!dismemberLook || s == null || s.isEmpty()) return;
        for (String e : s.split(",")) {
            int c = e.indexOf(':');
            if (c < 0) continue;
            try {
                short id = Short.parseShort(e.substring(0, c));
                int mask = Integer.parseInt(e.substring(c + 1));
                IsoZombie z = zombie.network.GameClient.IDToZombieMap.get(id);
                if (z == null) continue;
                GoreState g = GoreState.make(z);
                int cutBefore = g.cut;
                int add = mask & ~cutBefore;
                if (add == 0 || !g.sever(add)) continue;
                g.lastSeen = clock();
                GoreListener l = fx;
                if (l != null) l.severed(z, add, cutBefore);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static boolean warned;

    private static void warnOnce(String what, Throwable t) {
        if (warned) return;
        warned = true;
        PZOLogger.warn("[PZOE gore] " + what + " hook failed: " + t);
    }

    // Ground Blood hooks

    /** Replaces IsoChunk.addBloodSplat's invalidateRenderChunkLevel(level, flags). */
    public static void splatInvalidate(zombie.iso.IsoChunk chunk, int level, long flags) {
        if (level >= 0 && level < LEVELS) GoreMap.splatAdded(chunk);
        if (ours(level)) {
            // Vanilla consumes these during its blood draw. The map does not use the evicted splats.
            var fading = chunk.floorBloodSplatsFade;
            int last = fading.size() - 1;
            if (last >= 0 && ours((int) Math.floor(fading.get(last).z))) fading.remove(last);
            skippedBakes.incrementAndGet();
            return;
        }
        chunk.invalidateRenderChunkLevel(level, flags);
    }

    public static void bloodRemoved(IsoGridSquare sq, boolean onlyWall) {
        if (sq == null || sq.getZ() < 0 || sq.getZ() >= LEVELS || zombie.network.GameServer.server) return;
        GoreMap.cleanSurface(sq, onlyWall);
        if (!GoreMap.trackingGroundBlood()) return;
        cleaned.add(new int[] {sq.x, sq.y, sq.z, onlyWall ? 1 : 0});
        cleanings.incrementAndGet();
    }

    public static boolean exactFloorCleanup(IsoGridSquare sq, boolean onlyWall) {
        if (onlyWall || !exactFloorBlood(sq)) return onlyWall;
        var chunk = sq.getChunk();
        if (chunk == null) return true;
        int lx = sq.getX() - chunk.wx * 8, ly = sq.getY() - chunk.wy * 8, z = sq.getZ();
        var blood = chunk.floorBloodSplats;
        for (int i = blood.size() - 1; i >= 0; i--) {
            var splat = blood.get(i);
            if ((int) Math.floor(splat.x) == lx && (int) Math.floor(splat.y) == ly && (int) Math.floor(splat.z) == z) blood.remove(i);
        }
        var fading = chunk.floorBloodSplatsFade;
        for (int i = fading.size() - 1; i >= 0; i--) {
            var splat = fading.get(i);
            if ((int) Math.floor(splat.x) == lx && (int) Math.floor(splat.y) == ly && (int) Math.floor(splat.z) == z) fading.remove(i);
        }
        return true;
    }

    public static boolean exactFloorBlood(IsoGridSquare sq) {
        return GoreMap.trackingGroundBlood() && sq.getZ() >= 0 && sq.getZ() < LEVELS
            && !zombie.network.GameClient.client && !zombie.network.GameServer.server;
    }

    private static boolean localFloorBlood(IsoGridSquare sq) {
        if (GoreMap.hasSurface(sq, false)) return true;
        var ch = sq.getChunk();
        if (ch == null) return false;
        int x = sq.getX() - ch.wx * 8, y = sq.getY() - ch.wy * 8, z = sq.getZ();
        var blood = ch.floorBloodSplats;
        if (blood.isEmpty()) return false;
        boolean outside = sq.isOutside();
        for (int i = blood.size() - 1; i >= 0; i--) {
            var splat = blood.get(i);
            if ((int) Math.floor(splat.x) == x && (int) Math.floor(splat.y) == y && (int) Math.floor(splat.z) == z
                && GoreMap.visibleVanilla(splat, outside)) return true;
        }
        return false;
    }

    public static int bloodQuery(IsoGridSquare sq, int mode) {
        if (!GoreMap.trackingGroundBlood()) return -1;
        if (mode != 2 && exactFloorBlood(sq)) {
            return (mode == 0 && sq.haveBloodWall()) || localFloorBlood(sq) ? 1 : 0;
        }
        if (mode != 1 && GoreMap.hasSurface(sq, true)) return 1;
        return mode != 2 && GoreMap.hasSurface(sq, false) ? 1 : -1;
    }

    public static boolean takeWallSplat(IsoGridSquare sq, String sprite, IsoFlagType face, float alpha) {
        if (!ours(sq.getZ()) || zombie.network.GameClient.client || zombie.network.GameServer.server
            || Thread.currentThread() != zombie.GameWindow.gameThread || sprite == null || !sprite.startsWith("overlay_blood_wall_")) return false;
        boolean north = face == IsoFlagType.cutN;
        if ((!north && face != IsoFlagType.cutW) || sq.getWall(north) == null) return false;
        return GoreMap.takeVanillaWall(sq, north, sprite.hashCode(), alpha);
    }

    public static int[] pollCleaned() {
        return cleaned.poll();
    }

    public static void dropCleaned() {
        cleaned.clear();
    }

    /** Start of FBORenderCell.renderOneLevel_Blood(chunk, level, minX, minY, maxX, maxY): true skips the sprites. */
    public static boolean skipFloorBlood(int level) {
        return ours(level);
    }

    /** "[PZOE gore]" line every 30 s while anything of Gore runs; extra is the render side's numbers. */
    public static void report(long now, String extra) {
        if (now - lastReportNs < 30_000_000_000L) return;
        lastReportNs = now;
        long s = skippedBakes.get();
        if (mapDraws || wounds > 0 || kills > 0) {
            PZOLogger.info("[PZOE gore] last 30 s: " + (s - reportedSkips) + " splat re-bakes skipped; total " + wounds + " wounds, "
                + kills + " kills, " + severed + " limbs off, " + GoreState.size() + " records; " + extra);
        }
        reportedSkips = s;
    }

    public static String stateText() {
        return "goreMap=" + (mapDraws ? 1 : 0) + ";goreSkipped=" + skippedBakes.get() + ";goreCleaned=" + cleanings.get()
            + ";goreWounds=" + wounds + ";goreKills=" + kills + ";goreSevered=" + severed + ";goreRecords=" + GoreState.size();
    }

    // Bytecode

    private static int count(MethodNode m, String owner, String name, String desc) {
        int n = 0;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc)) n++;
        }
        return n;
    }

    private static MethodInsnNode call(String name, String desc) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, name, desc, false);
    }

    private static void beforeReturns(MethodNode m, int opcode, java.util.function.Supplier<InsnList> make) {
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == opcode) m.instructions.insertBefore(insn, make.get());
        }
    }

    public static boolean patchChunk(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "addBloodSplat", "(FFFI)V");
        if (m == null || count(m, CHUNK, "invalidateRenderChunkLevel", "(IJ)V") != 1) return false;
        return AsmSupport.redirectCalls(m, CHUNK, "invalidateRenderChunkLevel", "(IJ)V", SELF, "splatInvalidate",
            "(Lzombie/iso/IsoChunk;IJ)V") == 1;
    }

    public static boolean patchSquare(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "removeBlood", "(ZZ)V");
        if (m == null) return false;
        String square = "L" + SQUARE + ";";
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pre.add(call("bloodRemoved", "(" + square + "Z)V"));
        m.instructions.insert(pre);
        for (AbstractInsnNode in : m.instructions.toArray()) {
            if (!(in instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ILOAD || load.var != 2
                || !(in.getNext() instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFNE) continue;
            boolean vanillaFloor = false;
            for (AbstractInsnNode step = jump.getNext(); step != null && step != jump.label; step = step.getNext()) {
                if (step instanceof FieldInsnNode field && field.owner.equals(CHUNK) && field.name.equals("floorBloodSplats")) {
                    vanillaFloor = true;
                    break;
                }
            }
            if (!vanillaFloor) continue;
            InsnList exact = new InsnList();
            exact.add(new VarInsnNode(Opcodes.ALOAD, 0));
            exact.add(new VarInsnNode(Opcodes.ILOAD, 2));
            exact.add(call("exactFloorCleanup", "(" + square + "Z)Z"));
            m.instructions.insertBefore(in, exact);
            m.instructions.remove(in);
            break;
        }

        String flag = "Lzombie/iso/SpriteDetails/IsoFlagType;";
        MethodNode wall = AsmSupport.findMethod(node, "DoSplat", "(Ljava/lang/String;Z" + flag + "FFF)V");
        if (wall != null) {
            LabelNode vanilla = new LabelNode();
            InsnList capture = new InsnList();
            capture.add(new VarInsnNode(Opcodes.ALOAD, 0));
            capture.add(new VarInsnNode(Opcodes.ALOAD, 1));
            capture.add(new VarInsnNode(Opcodes.ALOAD, 3));
            capture.add(new VarInsnNode(Opcodes.FLOAD, 6));
            capture.add(call("takeWallSplat", "(" + square + "Ljava/lang/String;" + flag + "F)Z"));
            capture.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
            capture.add(new InsnNode(Opcodes.RETURN));
            capture.add(vanilla);
            wall.instructions.insert(capture);
        }
        patchBloodChecks(node, square);
        return true;
    }

    private static void patchBloodChecks(ClassNode node, String square) {
        String[] queries = {"haveBlood", "haveBloodFloor", "haveBloodWall"};
        for (int i = 0; i < queries.length; i++) {
            MethodNode m = AsmSupport.findMethod(node, queries[i], "()Z");
            if (m == null) continue;
            LabelNode vanilla = new LabelNode();
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(new InsnNode(Opcodes.ICONST_0 + i));
            hook.add(call("bloodQuery", "(" + square + "I)I"));
            hook.add(new InsnNode(Opcodes.DUP));
            hook.add(new JumpInsnNode(Opcodes.IFLT, vanilla));
            hook.add(new InsnNode(Opcodes.IRETURN));
            hook.add(vanilla);
            hook.add(new InsnNode(Opcodes.POP));
            m.instructions.insert(hook);
        }
    }

    public static boolean patchCell(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "renderOneLevel_Blood", "(Lzombie/iso/IsoChunk;IIIII)V");
        if (m == null) return false;
        LabelNode go = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pre.add(call("skipFloorBlood", "(I)Z"));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, go));
        pre.add(new InsnNode(Opcodes.RETURN));
        pre.add(go);
        m.instructions.insert(pre);
        return true;
    }

    /** IsoGameCharacter: addBlood (hits) and doDeathSplatterAndSounds (kills). */
    public static boolean patchCharacter(ClassNode node) {
        MethodNode a = AsmSupport.findMethod(node, "addBlood", "(Lzombie/characterTextures/BloodBodyPartType;ZZZ)V");
        MethodNode d = AsmSupport.findMethod(node, "doDeathSplatterAndSounds",
            "(Lzombie/inventory/types/HandWeapon;Lzombie/characters/IsoGameCharacter;Z)V");
        if (a == null || d == null) return false;
        InsnList pa = new InsnList();
        pa.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pa.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pa.add(new VarInsnNode(Opcodes.ILOAD, 2));
        pa.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pa.add(call("onBlood", "(Lzombie/characters/IsoGameCharacter;Lzombie/characterTextures/BloodBodyPartType;ZZ)V"));
        a.instructions.insert(pa);
        InsnList pd = new InsnList();
        pd.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pd.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pd.add(new VarInsnNode(Opcodes.ALOAD, 2));
        pd.add(new VarInsnNode(Opcodes.ILOAD, 3));
        pd.add(call("onDeath", "(Lzombie/characters/IsoGameCharacter;Lzombie/inventory/types/HandWeapon;Lzombie/characters/IsoGameCharacter;Z)V"));
        d.instructions.insert(pd);
        return true;
    }

    /** IsoZombie: vehicle hits, pool reuse, and no bite without a jaw. */
    public static boolean patchZombie(ClassNode node) {
        MethodNode v = AsmSupport.findMethod(node, "addBloodFromVehicleImpact", "(F)V");
        MethodNode r = AsmSupport.findMethod(node, "resetForReuse", "()V");
        MethodNode b = AsmSupport.findMethod(node, "cantBite", "()Z");
        if (v == null || r == null || b == null) return false;
        InsnList pv = new InsnList();
        pv.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pv.add(new VarInsnNode(Opcodes.FLOAD, 1));
        pv.add(call("onVehicleHit", "(Lzombie/characters/IsoZombie;F)V"));
        v.instructions.insert(pv);
        InsnList pr = new InsnList();
        pr.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pr.add(call("onReuse", "(Lzombie/characters/IsoZombie;)V"));
        r.instructions.insert(pr);
        LabelNode go = new LabelNode();
        InsnList pb = new InsnList();
        pb.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pb.add(call("jawless", "(Lzombie/characters/IsoZombie;)Z"));
        pb.add(new JumpInsnNode(Opcodes.IFEQ, go));
        pb.add(new InsnNode(Opcodes.ICONST_1));
        pb.add(new InsnNode(Opcodes.IRETURN));
        pb.add(go);
        b.instructions.insert(pb);
        return true;
    }

    public static boolean patchDeadBody(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "<init>", "(Lzombie/characters/IsoGameCharacter;ZZ)V");
        if (m == null) return false;
        beforeReturns(m, Opcodes.RETURN, () -> {
            InsnList l = new InsnList();
            l.add(new VarInsnNode(Opcodes.ALOAD, 0));
            l.add(new VarInsnNode(Opcodes.ALOAD, 1));
            l.add(call("onCorpse", "(Lzombie/iso/objects/IsoDeadBody;Lzombie/characters/IsoGameCharacter;)V"));
            return l;
        });
        return true;
    }

    public static boolean patchBodyDamage(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "AddRandomDamageFromZombie", "(Lzombie/characters/IsoZombie;Ljava/lang/String;I)Z");
        if (m == null) return false;
        LabelNode go = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(call("attackBlocked", "(Lzombie/characters/IsoZombie;)Z"));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, go));
        pre.add(new InsnNode(Opcodes.ICONST_0));
        pre.add(new InsnNode(Opcodes.IRETURN));
        pre.add(go);
        m.instructions.insert(pre);
        return true;
    }

    public static boolean patchEat(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "execute", "(Lzombie/characters/IsoGameCharacter;)V");
        if (m == null) return false;
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pre.add(call("eating", "(Lzombie/characters/IsoGameCharacter;)V"));
        m.instructions.insert(pre);
        return true;
    }

    public static boolean patchCombat(ClassNode node) {
        boolean splash = patchCombatSplash(node);
        boolean location = patchCombatLocation(node);
        return splash || location;
    }

    private static boolean patchCombatLocation(ClassNode node) {
        String moving = "Lzombie/iso/IsoMovingObject;", character = "L" + CHARACTER + ";";
        String weapon = "Lzombie/inventory/types/HandWeapon;", hitData = "L" + COMBAT + "$HitData;";
        MethodNode m = AsmSupport.findMethod(node, "getHitDamage",
            "(" + character + weapon + moving + hitData + "I)L" + COMBAT + "$HitDamageData;");
        if (m == null || count(m, COMBAT, "applyMeleeHitLocationDamage", "(" + character + weapon + "IIZF)F") != 1
            || count(m, COMBAT, "applyRangeHitLocationDamage", "(" + character + weapon + "IF)F") != 1) return false;

        MethodInsnNode[] locations = new MethodInsnNode[2];
        int[] parts = new int[2], victims = new int[2];
        for (AbstractInsnNode in : m.instructions.toArray()) {
            if (!(in instanceof MethodInsnNode mi) || !COMBAT.equals(mi.owner)) continue;
            int index = switch (mi.name) {
                case "applyMeleeHitLocationDamage" -> 0;
                case "applyRangeHitLocationDamage" -> 1;
                default -> -1;
            };
            if (index < 0) continue;
            AbstractInsnNode part = mi.getPrevious();
            while (part != null && part.getOpcode() != Opcodes.ILOAD) part = part.getPrevious();
            if (!(part instanceof VarInsnNode p) || !(part.getPrevious() instanceof VarInsnNode w)
                || w.getOpcode() != Opcodes.ALOAD || w.var != 2 || !(w.getPrevious() instanceof VarInsnNode v)
                || v.getOpcode() != Opcodes.ALOAD) return false;
            locations[index] = mi;
            parts[index] = p.var;
            victims[index] = v.var;
        }
        if (locations[0] == null || locations[1] == null) return false;

        for (int i = 0; i < locations.length; i++) {
            InsnList report = new InsnList();
            report.add(new InsnNode(Opcodes.DUP));
            report.add(new VarInsnNode(Opcodes.ALOAD, victims[i]));
            report.add(new VarInsnNode(Opcodes.ALOAD, 1));
            report.add(new VarInsnNode(Opcodes.ALOAD, 2));
            report.add(new VarInsnNode(Opcodes.ILOAD, parts[i]));
            report.add(new VarInsnNode(Opcodes.ALOAD, 4));
            report.add(new InsnNode(i == 0 ? Opcodes.ICONST_0 : Opcodes.ICONST_1));
            report.add(call("resolvedHit", "(F" + character + character + weapon + "I" + hitData + "Z)V"));
            m.instructions.insert(locations[i], report);
        }

        // Keep clothing splashes within getHitDamage from being reported as a second wound.
        int previous = m.maxLocals++;
        LabelNode start = new LabelNode(), end = new LabelNode(), cleanup = new LabelNode();
        InsnList enter = new InsnList();
        enter.add(new VarInsnNode(Opcodes.ALOAD, 3));
        enter.add(call("beginResolvedHit", "(" + moving + ")" + moving));
        enter.add(new VarInsnNode(Opcodes.ASTORE, previous));
        enter.add(start);
        m.instructions.insert(enter);
        beforeReturns(m, Opcodes.ARETURN, () -> endResolvedHit(previous, moving));
        m.instructions.add(end);
        m.instructions.add(cleanup);
        m.instructions.add(endResolvedHit(previous, moving));
        m.instructions.add(new InsnNode(Opcodes.ATHROW));
        m.tryCatchBlocks.add(new TryCatchBlockNode(start, end, cleanup, null));
        return true;
    }

    private static InsnList endResolvedHit(int previous, String moving) {
        InsnList restore = new InsnList();
        restore.add(new VarInsnNode(Opcodes.ALOAD, previous));
        restore.add(call("endResolvedHit", "(" + moving + ")V"));
        return restore;
    }

    private static boolean patchCombatSplash(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "splash",
            "(Lzombie/iso/IsoMovingObject;Lzombie/inventory/types/HandWeapon;Lzombie/characters/IsoGameCharacter;)V");
        if (m == null || count(m, GIBLETS, "<init>", GIBLET_ARGS) != 1
            || count(m, CHARACTER, "splatBlood", "(IF)V") != 1) return false;

        AbstractInsnNode allocation = null;
        MethodInsnNode constructor = null;
        for (AbstractInsnNode p = m.instructions.getFirst(); p != null; p = p.getNext()) {
            if (p instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && GIBLETS.equals(t.desc)) {
                if (allocation != null) return false;
                allocation = p;
            }
            if (p instanceof MethodInsnNode mi && GIBLETS.equals(mi.owner) && "<init>".equals(mi.name)) constructor = mi;
        }
        if (allocation == null || constructor == null) return false;
        AbstractInsnNode dup = allocation.getNext(), pop = constructor.getNext();
        if (dup == null || dup.getOpcode() != Opcodes.DUP || pop == null || pop.getOpcode() != Opcodes.POP) return false;

        m.instructions.remove(dup);
        m.instructions.remove(allocation);
        m.instructions.remove(pop);
        m.instructions.set(constructor, call("spawnCombatGiblet", GIBLET_ARGS));
        return AsmSupport.redirectCalls(m, CHARACTER, "splatBlood", "(IF)V", SELF, "combatWallBlood",
            "(Lzombie/characters/IsoGameCharacter;IF)V") == 1;
    }

    public static boolean patchGiblets(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "<init>", GIBLET_ARGS);
        if (m == null) return false;
        beforeReturns(m, Opcodes.RETURN, () -> {
            InsnList l = new InsnList();
            LabelNode skip = new LabelNode();
            l.add(new VarInsnNode(Opcodes.ALOAD, 0));
            l.add(call("giblet", "(Lzombie/iso/objects/IsoZombieGiblets;)Z"));
            l.add(new JumpInsnNode(Opcodes.IFEQ, skip));
            l.add(new VarInsnNode(Opcodes.ALOAD, 0));
            l.add(new InsnNode(Opcodes.ICONST_1));
            l.add(new FieldInsnNode(Opcodes.PUTFIELD, GIBLETS, "invis", "Z"));
            l.add(skip);
            return l;
        });
        return true;
    }

    public static boolean patchGameTime(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "getSlomoMultiplier", "()F");
        if (m == null) return false;
        beforeReturns(m, Opcodes.FRETURN, () -> {
            InsnList l = new InsnList();
            l.add(call("slomo", "(F)F"));
            return l;
        });
        return true;
    }
}
