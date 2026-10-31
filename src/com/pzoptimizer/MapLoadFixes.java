package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicLong;
import zombie.iso.zones.VehicleZone;
import zombie.iso.zones.Zone;

/**
 * Part of "Faster file and map loading" (JVM_FastLoad).
 *
 * IsoMetaGrid.checkVehiclesZones drops vehicle zones that repeat an earlier zone's x, y, w and h by comparing every
 * zone with every zone before it; with map mods that is millions of comparisons during each save load. This keeps the
 * first zone of each rectangle in a hash map instead: the same zones are removed, the survivors keep their order, and
 * each duplicate is logged with the same message (against the first zone with that rectangle, as the stock loop does).
 */
public final class MapLoadFixes {

    public static final AtomicLong duplicateZonesRemoved = new AtomicLong();

    private MapLoadFixes() {}

    private record Rect(int x, int y, int w, int h) {}

    /** Start of IsoMetaGrid.checkVehiclesZones; true when done here. */
    public static boolean checkVehiclesZones(zombie.iso.IsoMetaGrid grid) {
        if (!ScriptCommentStrip.active) return false;
        ArrayList<VehicleZone> zones = grid.vehiclesZones;
        HashMap<Rect, Zone> first = new HashMap<>(zones.size() * 2);
        int write = 0;
        for (int i = 0, n = zones.size(); i < n; i++) {
            VehicleZone a = zones.get(i);
            Rect r = new Rect(a.getX(), a.getY(), a.w, a.h);
            Zone b = first.putIfAbsent(r, a);
            if (b == null) {
                zones.set(write++, a);
            } else {
                duplicateZonesRemoved.incrementAndGet();
                zombie.debug.DebugType.Vehicle.debugln("checkVehiclesZones: ERROR! Zone '" + a.name + "':'" + a.type + "' (" + a.x + ", " + a.y
                    + ") duplicate with Zone '" + b.name + "':'" + b.type + "' (" + b.x + ", " + b.y + ")");
            }
        }
        while (zones.size() > write) zones.remove(zones.size() - 1);
        return true;
    }

    // Last lookup of LotHeader.getZombieIntensityForChunk's map search; published whole, so any thread may read it
    private record IntensityMemo(zombie.iso.LotHeader header, zombie.iso.MapFiles files, int c300x, int c300y, int maps,
                                 zombie.iso.LotHeader found, boolean hit) {}

    private static volatile IntensityMemo intensityMemo;
    public static final AtomicLong intensityLookupsSaved = new AtomicLong();

    /**
     * Replaces LotHeader.getZombieIntensityForChunk while active (Integer.MIN_VALUE = run the original). The map search
     * depends only on the cell and which 300-tile cell the chunk falls in, so the 1,024 chunks of a cell that MapCollisionData
     * and IsoMetaCell ask about in a row share one search; the answer is then read the same way as stock.
     */
    public static int zombieIntensity(zombie.iso.LotHeader lotHeader, int chunkX, int chunkY) {
        if (!ScriptCommentStrip.active) return Integer.MIN_VALUE;
        if (chunkX < 0 || chunkY < 0 || chunkX >= 32 || chunkY >= 32) return -1;
        if (lotHeader == null) return -1;
        int cell300X = zombie.core.math.PZMath.fastfloor((lotHeader.cellX * 256 + chunkX * 8) / 300.0F);
        int cell300Y = zombie.core.math.PZMath.fastfloor((lotHeader.cellY * 256 + chunkY * 8) / 300.0F);
        java.util.ArrayList<zombie.iso.MapFiles> all = zombie.iso.IsoLot.MapFiles;
        zombie.iso.MapFiles own = lotHeader.mapFiles;
        IntensityMemo m = intensityMemo;
        if (m != null && m.header == lotHeader && m.files == own && m.c300x == cell300X && m.c300y == cell300Y && m.maps == all.size()) {
            intensityLookupsSaved.incrementAndGet();
            return m.hit ? m.found.getZombieIntensity(chunkX + chunkY * 32) & 0xFF : -1;
        }
        zombie.iso.LotHeader found = null;
        boolean hit = false;
        for (int j = own.priority; j < all.size(); j++) {
            zombie.iso.MapFiles mapFiles = all.get(j);
            if (mapFiles.bgHasCell300.getValue(cell300X - mapFiles.minCell300X, cell300Y - mapFiles.minCell300Y)) {
                found = mapFiles.getLotHeader(lotHeader.cellX, lotHeader.cellY);
                hit = true;
                break;
            }
        }
        intensityMemo = new IntensityMemo(lotHeader, own, cell300X, cell300Y, all.size(), found, hit);
        return hit ? found.getZombieIntensity(chunkX + chunkY * 32) & 0xFF : -1;
    }

    static boolean patchLotHeader(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "getZombieIntensityForChunk", "(Lzombie/iso/LotHeader;II)I");
        if (m == null) return false;
        // int r = zombieIntensity(h, x, y); if (r != Integer.MIN_VALUE) return r;
        com.pzoptimizer.shaded.asm.tree.LabelNode orig = new com.pzoptimizer.shaded.asm.tree.LabelNode();
        com.pzoptimizer.shaded.asm.tree.InsnList pre = new com.pzoptimizer.shaded.asm.tree.InsnList();
        pre.add(new com.pzoptimizer.shaded.asm.tree.VarInsnNode(com.pzoptimizer.shaded.asm.Opcodes.ALOAD, 0));
        pre.add(new com.pzoptimizer.shaded.asm.tree.VarInsnNode(com.pzoptimizer.shaded.asm.Opcodes.ILOAD, 1));
        pre.add(new com.pzoptimizer.shaded.asm.tree.VarInsnNode(com.pzoptimizer.shaded.asm.Opcodes.ILOAD, 2));
        pre.add(new com.pzoptimizer.shaded.asm.tree.MethodInsnNode(com.pzoptimizer.shaded.asm.Opcodes.INVOKESTATIC,
            "com/pzoptimizer/MapLoadFixes", "zombieIntensity", "(Lzombie/iso/LotHeader;II)I", false));
        pre.add(new com.pzoptimizer.shaded.asm.tree.InsnNode(com.pzoptimizer.shaded.asm.Opcodes.DUP));
        pre.add(new com.pzoptimizer.shaded.asm.tree.LdcInsnNode(Integer.MIN_VALUE));
        pre.add(new com.pzoptimizer.shaded.asm.tree.JumpInsnNode(com.pzoptimizer.shaded.asm.Opcodes.IF_ICMPEQ, orig));
        pre.add(new com.pzoptimizer.shaded.asm.tree.InsnNode(com.pzoptimizer.shaded.asm.Opcodes.IRETURN));
        pre.add(orig);
        pre.add(new com.pzoptimizer.shaded.asm.tree.InsnNode(com.pzoptimizer.shaded.asm.Opcodes.POP));
        m.instructions.insert(pre);
        return true;
    }

    static boolean patchMetaGrid(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "checkVehiclesZones", "()V");
        if (m == null) return false;
        AsmSupport.prependSkip(m, "com/pzoptimizer/MapLoadFixes", "checkVehiclesZones", "(Lzombie/iso/IsoMetaGrid;)Z");
        return true;
    }
}
