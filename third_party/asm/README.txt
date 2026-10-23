ASM 9.8 (https://asm.ow2.io), from Maven Central org.ow2.asm:{asm,asm-tree,asm-commons}:9.8.
ShadeAsm.java relocates org.objectweb.asm to com.pzoptimizer.shaded.asm at build time so the agent's copy
cannot clash with another ASM on the game's class path. Licence: LICENSE.txt (BSD 3-clause).
