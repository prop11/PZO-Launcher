package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.tree.ClassNode;
import com.pzoptimizer.shaded.asm.tree.MethodNode;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Settings screen "Skip the room ID self-check outside debug mode" (JVM_RoomIdCheckSkip).
 *
 * BuildingRoomsEditor.checkBuildingAndRoomIDs walks every building and room of every map cell and only logs when an
 * ID differs from the one its position implies; nothing is repaired or changed. It runs about six times per world load
 * (BuildingRoomsEditor.init, applyChanges, three times from Basements), with a list search per room, and maps with
 * basements or custom buildings print hundreds of "buildingDef.ID=... expected=..." error lines each time. Outside
 * -debug the walk is skipped; a -debug game keeps it for map makers.
 */
public final class RoomIdCheckSkip {

    public static volatile boolean active = true;
    public static final AtomicLong skipped = new AtomicLong();

    private RoomIdCheckSkip() {}

    /** Start of BuildingRoomsEditor.checkBuildingAndRoomIDs(); true skips it. */
    public static boolean skip(zombie.buildingRooms.BuildingRoomsEditor editor) {
        if (!active || zombie.core.Core.debug) return false;
        skipped.incrementAndGet();
        return true;
    }

    static boolean patchEditor(ClassNode node) {
        MethodNode m = AsmSupport.findMethod(node, "checkBuildingAndRoomIDs", "()V");
        if (m == null) return false;
        AsmSupport.prependSkip(m, "com/pzoptimizer/RoomIdCheckSkip", "skip", "(Lzombie/buildingRooms/BuildingRoomsEditor;)Z");
        return true;
    }
}
