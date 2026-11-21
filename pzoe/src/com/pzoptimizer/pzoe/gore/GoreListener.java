package com.pzoptimizer.pzoe.gore;

/** What GoreCore tells the render side (GoreFx) about. Called on the main thread; never on a dedicated server. */
public interface GoreListener {
    void hit(zombie.characters.IsoGameCharacter victim, zombie.characters.IsoGameCharacter attacker, int part, int kind, int weapon);

    /** taken = limbs that came off with the kill, burst 1 = head blown apart, 2 = head crushed. */
    void death(zombie.characters.IsoGameCharacter victim, zombie.characters.IsoGameCharacter killer, GoreState g, int taken, int burst,
        int weapon, boolean gory);

    void severed(zombie.characters.IsoGameCharacter victim, int limbs, boolean living);

    void vehicleHit(zombie.characters.IsoZombie victim, float speed);

    void corpse(zombie.iso.objects.IsoDeadBody body, GoreState g);

    void fed(Object body, GoreState g);

    /** True takes over drawing this giblet. */
    boolean giblet(zombie.iso.objects.IsoZombieGiblets gib);

    /** 1 normally; less during hit-stop or the kill cam. */
    float timeScale();
}
