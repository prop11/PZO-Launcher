package com.pzoptimizer.pzoe.gore;

/** What GoreCore tells the render side (GoreFx) about. Called on the main thread; never on a dedicated server. */
public interface GoreListener {
    void hit(zombie.characters.IsoGameCharacter victim, zombie.characters.IsoGameCharacter attacker, int part, int kind, int weapon,
        boolean openingHit, GoreImpact contact);

    /** taken = limbs that came off with the kill, burst 1 = head blown apart, 2 = head crushed. */
    void death(zombie.characters.IsoGameCharacter victim, zombie.characters.IsoGameCharacter killer, GoreState g, int taken, int cutBefore, int burst,
        int weapon, int part, boolean gory);

    void severed(zombie.characters.IsoGameCharacter victim, int limbs, int cutBefore);

    void vehicleHit(zombie.characters.IsoZombie victim, float speed);

    void corpse(zombie.iso.objects.IsoDeadBody body, GoreState g);

    void fed(Object body, GoreState g);

    /** True replaces the stock combat giblet with a pooled effect or drops it when over budget. */
    boolean combatGiblet(float x, float y, float z, float dx, float dy);

    /** True takes over drawing this giblet. */
    boolean giblet(zombie.iso.objects.IsoZombieGiblets gib);

    /** 1 normally; less during hit-stop or the kill cam. */
    float timeScale();
}
