package com.pzoptimizer;

import com.pzoptimizer.shaded.asm.ClassWriter;
import com.pzoptimizer.shaded.asm.Label;
import com.pzoptimizer.shaded.asm.MethodVisitor;
import com.pzoptimizer.shaded.asm.Opcodes;

/**
 * Turns one Kahlua function (Prototype) into a class whose run(thread, callFrame) does what luaMainloop would do for
 * that function's instructions, without fetching and decoding each one.
 *
 * It works in Kahlua's own frame model: registers stay on the coroutine stack (callFrame.get/set), callFrame.pc is
 * kept as the interpreter keeps it, and every helper called is the interpreter's own (tableget, tableSet,
 * primitiveMath, KahluaUtil). Two rules keep it exact:
 * - Control transfers stay in the interpreter, apart from calls between compiled functions on the main coroutine (see
 *   directLuaCall, tailCall). CLOSURE, VARARG, SETLIST, SETGLOBAL, calls to uncompiled Lua functions, and RETURN and
 *   TAILCALL when the interpreter entered the function hand back: run() sets pc to that instruction and returns, luaMainloop executes
 *   it, and its next
 *   pass enters run() again at the new pc. Every instruction is an entry point, since all state is in the frame. So
 *   calls, returns, coroutines, pcall and error unwinding are vanilla.
 * - An instruction only runs here in the cases it can do exactly (numbers with numbers, strings with strings, plain
 *   reads). Anything else (metamethods, numeric strings, mixed operands) hands that one instruction back, which is
 *   safe because looking at the operands changes nothing.
 * Before each instruction pc is set as the interpreter would have it mid-instruction, so an error thrown from here is
 * caught and reported by luaMainloop with the right line.
 *
 * The class is defined as a hidden nestmate of KahluaThread (see LuaJit), which is what lets it call KahluaThread's
 * private helpers directly.
 */
final class LuaJitCompiler {

    static final String HOST = "se/krka/kahlua/vm/KahluaThread";
    private static final String FRAME = "se/krka/kahlua/vm/LuaCallFrame";
    private static final String LCLOSURE = "se/krka/kahlua/vm/LuaClosure";
    private static final String PROTO = "se/krka/kahlua/vm/Prototype";
    private static final String UPVAL = "se/krka/kahlua/vm/UpValue";
    private static final String TABLE = "se/krka/kahlua/vm/KahluaTable";
    private static final String UTIL = "se/krka/kahlua/vm/KahluaUtil";
    private static final String PLATFORM = "se/krka/kahlua/vm/Platform";
    private static final String OBJ = "java/lang/Object";
    private static final String DBL = "java/lang/Double";
    static final String FN = "com/pzoptimizer/LuaJit$Fn";
    private static final String JFN = "se/krka/kahlua/vm/JavaFunction";

    // Locals of run()
    private static final int THIS = 0, T = 1, CF = 2, DIRECT = 3, K = 4, CL = 5, X = 6, Y = 7, Z = 8, EX = 9, N = 10,
        D1 = 11, D2 = 13, D3 = 15;
    private static final String COROUTINE = "se/krka/kahlua/vm/Coroutine";

    // Kahlua 5.1 opcodes
    private static final int MOVE = 0, LOADK = 1, LOADBOOL = 2, LOADNIL = 3, GETUPVAL = 4, GETGLOBAL = 5, GETTABLE = 6,
        SETGLOBAL = 7, SETUPVAL = 8, SETTABLE = 9, NEWTABLE = 10, SELF = 11, ADD = 12, POW = 17, UNM = 18, NOT = 19,
        LEN = 20, CONCAT = 21, JMP = 22, EQ = 23, LT = 24, LE = 25, TEST = 26, TESTSET = 27, CALL = 28, TAILCALL = 29,
        RETURN = 30, FORLOOP = 31, FORPREP = 32, TFORLOOP = 33, SETLIST = 34, CLOSE = 35, CLOSURE = 36, VARARG = 37;

    static final class TooBig extends RuntimeException {
        TooBig(String m) { super(m, null, false, false); }
    }

    private final int[] code;
    private final int n;
    private MethodVisitor mv;
    private Label[] at;
    private Label end;
    int compiledOps, handedBackOps;

    private LuaJitCompiler(int[] code) {
        this.code = code;
        this.n = code.length;
    }

    /** Class bytes for this function, or null if there's nothing worth compiling. */
    static byte[] compile(int[] code, String debugName) {
        if (code == null || code.length == 0) return null;
        // Big one-off functions (data tables, file bodies) aren't hot, and the method would get too large
        if (code.length > 4000) throw new TooBig("too many instructions: " + code.length);
        LuaJitCompiler c = new LuaJitCompiler(code);
        byte[] bytes;
        try {
            bytes = c.generate(debugName);
        } catch (com.pzoptimizer.shaded.asm.MethodTooLargeException e) {
            throw new TooBig("over the JVM's 64 KB method limit: " + code.length + " instructions");
        }
        return c.compiledOps == 0 ? null : bytes;
    }

    private byte[] generate(String debugName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            // Everything merged at a label is an Object or one of the fixed locals, so no class loading is needed
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return OBJ;
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, "se/krka/kahlua/vm/PzoLuaFn", null, OBJ,
            new String[] {FN});
        if (debugName != null) cw.visitSource(debugName.length() > 200 ? debugName.substring(0, 200) : debugName, null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, OBJ, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "run", "(L" + HOST + ";L" + FRAME + ";Z)I", null, null);
        mv.visitCode();
        at = new Label[n];
        for (int i = 0; i < n; i++) at[i] = new Label();
        end = new Label();

        // closure and constants once; then straight to the instruction at pc
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "closure", "L" + LCLOSURE + ";");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ASTORE, CL);
        mv.visitFieldInsn(Opcodes.GETFIELD, LCLOSURE, "prototype", "L" + PROTO + ";");
        mv.visitFieldInsn(Opcodes.GETFIELD, PROTO, "constants", "[L" + OBJ + ";");
        mv.visitVarInsn(Opcodes.ASTORE, K);
        // Typed placeholders so every later label sees the same local types
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, X);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, Y);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, Z);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ASTORE, EX);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ISTORE, N);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitVarInsn(Opcodes.DSTORE, D1);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitVarInsn(Opcodes.DSTORE, D2);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitVarInsn(Opcodes.DSTORE, D3);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "pc", "I");
        mv.visitTableSwitchInsn(0, n - 1, end, at);

        for (int i = 0; i < n; i++) {
            mv.visitLabel(at[i]);
            emit(i, code[i]);
        }
        mv.visitLabel(end);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private void emit(int i, int op) {
        int opcode = op & 63;
        int a = op >>> 6 & 0xFF;
        int c = op >>> 14 & 511;
        int b = op >>> 23 & 511;
        int bx = op >>> 14;
        int sbx = bx - 131071;
        switch (opcode) {
            case MOVE -> {
                setPc(i + 1);
                set(a, () -> get(b));
            }
            case LOADK -> {
                setPc(i + 1);
                set(a, () -> constant(bx));
            }
            case LOADBOOL -> {
                setPc(i + 1);
                set(a, () -> mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/Boolean", b == 0 ? "FALSE" : "TRUE", "Ljava/lang/Boolean;"));
                if (c != 0) jumpTo(i, i + 2);
            }
            case LOADNIL -> {
                setPc(i + 1);
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                push(a);
                push(b);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "stackClear", "(II)V", false);
            }
            case GETUPVAL -> {
                setPc(i + 1);
                set(a, () -> {
                    upvalue(b);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, UPVAL, "getValue", "()L" + OBJ + ";", false);
                });
            }
            case GETGLOBAL -> {
                setPc(i + 1);
                set(a, () -> {
                    mv.visitVarInsn(Opcodes.ALOAD, T);
                    mv.visitVarInsn(Opcodes.ALOAD, CL);
                    mv.visitFieldInsn(Opcodes.GETFIELD, LCLOSURE, "env", "L" + TABLE + ";");
                    constant(bx);
                    tableget();
                });
            }
            case GETTABLE -> {
                setPc(i + 1);
                get(b);
                mv.visitVarInsn(Opcodes.ASTORE, X);
                rk(c);
                mv.visitVarInsn(Opcodes.ASTORE, Y);
                set(a, () -> {
                    mv.visitVarInsn(Opcodes.ALOAD, T);
                    mv.visitVarInsn(Opcodes.ALOAD, X);
                    mv.visitVarInsn(Opcodes.ALOAD, Y);
                    tableget();
                });
            }
            case SETUPVAL -> {
                setPc(i + 1);
                upvalue(b);
                get(a);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, UPVAL, "setValue", "(L" + OBJ + ";)V", false);
            }
            case SETTABLE -> {
                setPc(i + 1);
                mv.visitVarInsn(Opcodes.ALOAD, T);
                get(a);
                rk(b);
                rk(c);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOST, "tableSet", "(L" + OBJ + ";L" + OBJ + ";L" + OBJ + ";)V", false);
            }
            case NEWTABLE -> {
                setPc(i + 1);
                set(a, () -> {
                    mv.visitVarInsn(Opcodes.ALOAD, T);
                    mv.visitFieldInsn(Opcodes.GETFIELD, HOST, "platform", "L" + PLATFORM + ";");
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PLATFORM, "newTable", "()L" + TABLE + ";", true);
                });
            }
            case SELF -> {
                setPc(i + 1);
                rk(c);
                mv.visitVarInsn(Opcodes.ASTORE, Y);
                get(b);
                mv.visitVarInsn(Opcodes.ASTORE, X);
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, HOST, "lastCallFrame", "L" + FRAME + ";");
                mv.visitVarInsn(Opcodes.ALOAD, T);
                mv.visitVarInsn(Opcodes.ALOAD, X);
                mv.visitVarInsn(Opcodes.ALOAD, Y);
                tableget();
                mv.visitVarInsn(Opcodes.ASTORE, Z);
                set(a, () -> mv.visitVarInsn(Opcodes.ALOAD, Z));
                set(a + 1, () -> mv.visitVarInsn(Opcodes.ALOAD, X));
            }
            case 12, 13, 14, 15, 16, POW -> arith(i, opcode, a, b, c);
            case UNM -> {
                setPc(i + 1);
                get(b);
                mv.visitVarInsn(Opcodes.ASTORE, X);
                mv.visitVarInsn(Opcodes.ALOAD, X);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, DBL);
                Label handBack = new Label();
                mv.visitJumpInsn(Opcodes.IFEQ, handBack);
                set(a, () -> {
                    mv.visitVarInsn(Opcodes.ALOAD, X);
                    fromDouble();
                    mv.visitInsn(Opcodes.DNEG);
                    toDouble();
                });
                jumpTo(i, i + 1);
                mv.visitLabel(handBack);
                handBack(i);
            }
            case NOT -> {
                setPc(i + 1);
                set(a, () -> {
                    get(b);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "boolEval", "(L" + OBJ + ";)Z", false);
                    mv.visitInsn(Opcodes.ICONST_1);
                    mv.visitInsn(Opcodes.IXOR);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "toBoolean", "(Z)Ljava/lang/Boolean;", false);
                });
            }
            case LEN -> {
                setPc(i + 1);
                get(b);
                mv.visitVarInsn(Opcodes.ASTORE, X);
                Label notTable = new Label(), handBack = new Label();
                mv.visitVarInsn(Opcodes.ALOAD, X);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, TABLE);
                mv.visitJumpInsn(Opcodes.IFEQ, notTable);
                set(a, () -> {
                    mv.visitVarInsn(Opcodes.ALOAD, X);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, TABLE);
                    mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, TABLE, "len", "()I", true);
                    mv.visitInsn(Opcodes.I2L);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "toDouble", "(J)Ljava/lang/Double;", false);
                });
                jumpTo(i, i + 1);
                mv.visitLabel(notTable);
                mv.visitVarInsn(Opcodes.ALOAD, X);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/String");
                mv.visitJumpInsn(Opcodes.IFEQ, handBack);
                set(a, () -> {
                    mv.visitVarInsn(Opcodes.ALOAD, X);
                    mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
                    mv.visitInsn(Opcodes.I2L);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "toDouble", "(J)Ljava/lang/Double;", false);
                });
                jumpTo(i, i + 1);
                mv.visitLabel(handBack);
                handBack(i);
            }
            case JMP -> {
                setPc(i + 1);
                jumpTo(i, i + 1 + sbx);
            }
            case EQ, LT, LE -> compare(i, opcode, a, b, c);
            case TEST -> {
                setPc(i + 1);
                get(a);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "boolEval", "(L" + OBJ + ";)Z", false);
                // skips the next instruction when boolEval(value) == (c == 0)
                branch(i, c == 0 ? Opcodes.IFNE : Opcodes.IFEQ, i + 2);
                jumpTo(i, i + 1);
            }
            case TESTSET -> {
                setPc(i + 1);
                get(b);
                mv.visitVarInsn(Opcodes.ASTORE, X);
                mv.visitVarInsn(Opcodes.ALOAD, X);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "boolEval", "(L" + OBJ + ";)Z", false);
                // boolEval(value) != (c == 0): copy and go on, else skip the next instruction
                branch(i, c == 0 ? Opcodes.IFNE : Opcodes.IFEQ, i + 2);
                set(a, () -> mv.visitVarInsn(Opcodes.ALOAD, X));
                jumpTo(i, i + 1);
            }
            case FORLOOP -> forLoop(i, a, i + 1 + sbx);
            case FORPREP -> {
                setPc(i + 1);
                get(a);
                fromDouble();
                mv.visitVarInsn(Opcodes.DSTORE, D1);
                get(a + 2);
                fromDouble();
                mv.visitVarInsn(Opcodes.DSTORE, D3);
                set(a, () -> {
                    mv.visitVarInsn(Opcodes.DLOAD, D1);
                    mv.visitVarInsn(Opcodes.DLOAD, D3);
                    mv.visitInsn(Opcodes.DSUB);
                    toDouble();
                });
                jumpTo(i, i + 1 + sbx);
            }
            case CONCAT -> {
                // Every operand a string or number: the interpreter joins their rawTostring in order. Anything else
                // (__concat, nil, tables) goes back to it.
                if (c <= b) {
                    handBack(i);
                    handedBackOps++;
                    return;
                }
                setPc(i + 1);
                Label handBack = new Label();
                for (int k = b; k <= c; k++) {
                    get(k);
                    rawTostring();
                    mv.visitJumpInsn(Opcodes.IFNULL, handBack);
                }
                mv.visitVarInsn(Opcodes.ALOAD, T);
                mv.visitFieldInsn(Opcodes.GETFIELD, HOST, "concatBuffer", "Ljava/lang/StringBuilder;");
                mv.visitInsn(Opcodes.DUP);
                mv.visitInsn(Opcodes.ICONST_0);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "setLength", "(I)V", false);
                for (int k = b; k <= c; k++) {
                    get(k);
                    rawTostring();
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                }
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                mv.visitVarInsn(Opcodes.ASTORE, X);
                set(a, () -> mv.visitVarInsn(Opcodes.ALOAD, X));
                jumpTo(i, i + 1);
                mv.visitLabel(handBack);
                handBack(i);
            }
            case TFORLOOP -> {
                // As the interpreter: copy iterator, state and control up, call the iterator (call(2) nests exactly as
                // it does there), then either keep the first result or skip the jump back
                setPc(i + 1);
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                push(a + 6);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "setTop", "(I)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                push(a);
                push(a + 3);
                push(3);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "stackCopy", "(III)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, T);
                push(2);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOST, "call", "(I)I", false);
                mv.visitInsn(Opcodes.POP);
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                push(a + 3 + c);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "clearFromIndex", "(I)V", false);
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "setPrototypeStacksize", "()V", false);
                get(a + 3);
                mv.visitVarInsn(Opcodes.ASTORE, X);
                mv.visitVarInsn(Opcodes.ALOAD, X);
                mv.visitJumpInsn(Opcodes.IFNULL, at.length > i + 2 ? at[i + 2] : end);
                set(a + 2, () -> mv.visitVarInsn(Opcodes.ALOAD, X));
                jumpTo(i, i + 1);
            }
            case CALL -> {
                // Calls into Java (the game's API, the standard library) are made here exactly as the interpreter's
                // CALL makes them. Lua functions, callable tables, nil and the coroutine library (resume/yield switch
                // coroutines, which only the interpreter loop can follow) are handed back before anything changes.
                setPc(i + 1);
                Label handBack = new Label(), luaCall = new Label();
                get(a);
                mv.visitVarInsn(Opcodes.ASTORE, X);
                mv.visitVarInsn(Opcodes.ALOAD, X);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, JFN);
                mv.visitJumpInsn(Opcodes.IFEQ, luaCall);
                mv.visitVarInsn(Opcodes.ALOAD, X);
                mv.visitTypeInsn(Opcodes.INSTANCEOF, "se/krka/kahlua/stdlib/CoroutineLib");
                mv.visitJumpInsn(Opcodes.IFNE, handBack);
                // nArguments: b - 1, or "up to top" when b == 0
                if (b != 0) {
                    mv.visitVarInsn(Opcodes.ALOAD, CF);
                    push(a + b);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "setTop", "(I)V", false);
                }
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                push(c != 0 ? 1 : 0);
                mv.visitFieldInsn(Opcodes.PUTFIELD, FRAME, "restoreTop", "Z");
                mv.visitVarInsn(Opcodes.ALOAD, T);
                mv.visitVarInsn(Opcodes.ALOAD, X);
                mv.visitTypeInsn(Opcodes.CHECKCAST, JFN);
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "localBase", "I");
                push(a + 1);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "localBase", "I");
                push(a);
                mv.visitInsn(Opcodes.IADD);
                if (b != 0) {
                    push(b - 1);
                } else {
                    mv.visitVarInsn(Opcodes.ALOAD, CF);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "getTop", "()I", false);
                    push(a + 1);
                    mv.visitInsn(Opcodes.ISUB);
                }
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOST, "callJava", "(L" + JFN + ";III)I", false);
                mv.visitInsn(Opcodes.POP);
                if (c != 0) {
                    mv.visitVarInsn(Opcodes.ALOAD, CF);
                    mv.visitVarInsn(Opcodes.ALOAD, CL);
                    mv.visitFieldInsn(Opcodes.GETFIELD, LCLOSURE, "prototype", "L" + PROTO + ";");
                    mv.visitFieldInsn(Opcodes.GETFIELD, PROTO, "maxStacksize", "I");
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "setTop", "(I)V", false);
                }
                jumpTo(i, i + 1);
                mv.visitLabel(luaCall);
                directLuaCall(i, a, b, c, handBack);
                mv.visitLabel(handBack);
                handBack(i);
            }
            case RETURN -> {
                // Only a directly called function returns here (its caller pushed it with fromLua false); from the
                // interpreter RETURN is handed back
                Label handBack = new Label();
                mv.visitVarInsn(Opcodes.ILOAD, DIRECT);
                mv.visitJumpInsn(Opcodes.IFEQ, handBack);
                setPc(i + 1);
                coroutine();
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "localBase", "I");
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COROUTINE, "closeUpvalues", "(I)V", false);
                if (b != 0) {
                    push(b - 1);
                } else {
                    mv.visitVarInsn(Opcodes.ALOAD, CF);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "getTop", "()I", false);
                    push(a);
                    mv.visitInsn(Opcodes.ISUB);
                }
                mv.visitVarInsn(Opcodes.ISTORE, N);
                coroutine();
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "localBase", "I");
                push(a);
                mv.visitInsn(Opcodes.IADD);
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "returnBase", "I");
                mv.visitVarInsn(Opcodes.ILOAD, N);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COROUTINE, "stackCopy", "(III)V", false);
                coroutine();
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "returnBase", "I");
                mv.visitVarInsn(Opcodes.ILOAD, N);
                mv.visitInsn(Opcodes.IADD);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COROUTINE, "setTop", "(I)V", false);
                coroutine();
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COROUTINE, "popCallFrame", "()V", false);
                mv.visitInsn(Opcodes.ICONST_1);
                mv.visitInsn(Opcodes.IRETURN);
                mv.visitLabel(handBack);
                handBack(i);
            }
            case TAILCALL -> tailCall(i, a, b);
            case CLOSE -> {
                setPc(i + 1);
                mv.visitVarInsn(Opcodes.ALOAD, CF);
                push(a);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "closeUpvalues", "(I)V", false);
            }
            default -> {
                // SETGLOBAL, SETLIST, CLOSURE, VARARG and anything unknown
                handBack(i);
                handedBackOps++;
                return;
            }
        }
        compiledOps++;
    }

    /**
     * CALL to a compiled Lua function: push its frame as the interpreter does (but with fromLua false) and run its
     * compiled code straight away. If it hands back, the interpreter finishes the callee in a nested luaMainloop, which
     * returns at the callee's RETURN because of fromLua false. Only on the main coroutine (a yield can't cross these Java
     * frames) and below MAX_DIRECT_DEPTH; everything else goes to handBack untouched.
     */
    private void directLuaCall(int i, int a, int b, int c, Label handBack) {
        // Read on every call so the setting applies to functions already compiled
        mv.visitFieldInsn(Opcodes.GETSTATIC, "com/pzoptimizer/LuaJit", "directCalls", "Z");
        mv.visitJumpInsn(Opcodes.IFEQ, handBack);
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, LCLOSURE);
        mv.visitJumpInsn(Opcodes.IFEQ, handBack);
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.CHECKCAST, LCLOSURE);
        mv.visitFieldInsn(Opcodes.GETFIELD, LCLOSURE, "prototype", "L" + PROTO + ";");
        mv.visitFieldInsn(Opcodes.GETFIELD, PROTO, "pzoJit", "L" + OBJ + ";");
        mv.visitVarInsn(Opcodes.ASTORE, Z);
        mv.visitVarInsn(Opcodes.ALOAD, Z);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, FN);
        mv.visitJumpInsn(Opcodes.IFEQ, handBack);
        mv.visitVarInsn(Opcodes.ALOAD, T);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOST, "pzoJitDepth", "I");
        push(LuaJit.MAX_DIRECT_DEPTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, handBack);
        coroutine();
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "coroutine", "L" + COROUTINE + ";");
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, handBack);
        coroutine();
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COROUTINE, "getParent", "()L" + COROUTINE + ";", false);
        mv.visitJumpInsn(Opcodes.IFNONNULL, handBack);

        // As the interpreter's CALL up to the frame push
        if (b != 0) {
            mv.visitVarInsn(Opcodes.ALOAD, CF);
            push(a + b);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "setTop", "(I)V", false);
        }
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        push(c != 0 ? 1 : 0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, FRAME, "restoreTop", "Z");
        coroutine();
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.CHECKCAST, LCLOSURE);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "localBase", "I");
        push(a + 1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "localBase", "I");
        push(a);
        mv.visitInsn(Opcodes.IADD);
        if (b != 0) {
            push(b - 1);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, CF);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "getTop", "()I", false);
            push(a + 1);
            mv.visitInsn(Opcodes.ISUB);
        }
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "canYield", "Z");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COROUTINE, "pushNewCallFrame",
            "(L" + LCLOSURE + ";L" + JFN + ";IIIZZ)L" + FRAME + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, Y);
        mv.visitVarInsn(Opcodes.ALOAD, Y);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "init", "()V", false);
        depth(1);

        Label runStart = new Label(), runEnd = new Label(), runFailed = new Label();
        Label nestStart = new Label(), nestEnd = new Label(), nestFailed = new Label(), done = new Label();
        mv.visitTryCatchBlock(runStart, runEnd, runFailed, "java/lang/Throwable");
        mv.visitTryCatchBlock(nestStart, nestEnd, nestFailed, "java/lang/Throwable");
        mv.visitLabel(runStart);
        mv.visitVarInsn(Opcodes.ALOAD, Z);
        mv.visitTypeInsn(Opcodes.CHECKCAST, FN);
        mv.visitVarInsn(Opcodes.ALOAD, T);
        mv.visitVarInsn(Opcodes.ALOAD, Y);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FN, "run", "(L" + HOST + ";L" + FRAME + ";Z)I", true);
        mv.visitLabel(runEnd);
        mv.visitJumpInsn(Opcodes.IFNE, done);
        // The callee handed back: the interpreter finishes it
        mv.visitLabel(nestStart);
        mv.visitVarInsn(Opcodes.ALOAD, T);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOST, "luaMainloop", "()V", false);
        mv.visitLabel(nestEnd);
        mv.visitLabel(done);
        depth(-1);
        if (c != 0) {
            mv.visitVarInsn(Opcodes.ALOAD, CF);
            mv.visitVarInsn(Opcodes.ALOAD, CL);
            mv.visitFieldInsn(Opcodes.GETFIELD, LCLOSURE, "prototype", "L" + PROTO + ";");
            mv.visitFieldInsn(Opcodes.GETFIELD, PROTO, "maxStacksize", "I");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "setTop", "(I)V", false);
        }
        jumpTo(i, i + 1);

        // Thrown by the callee's compiled code: nothing caught it for that frame yet
        mv.visitLabel(runFailed);
        mv.visitVarInsn(Opcodes.ASTORE, EX);
        depth(-1);
        mv.visitVarInsn(Opcodes.ALOAD, T);
        mv.visitVarInsn(Opcodes.ALOAD, EX);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Throwable");
        mv.visitVarInsn(Opcodes.ALOAD, Y);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/pzoptimizer/LuaJit", "calleeFailed",
            "(L" + HOST + ";Ljava/lang/Throwable;L" + FRAME + ";)Ljava/lang/Throwable;", false);
        mv.visitInsn(Opcodes.ATHROW);
        // Thrown out of the nested luaMainloop: its catch already handled the callee's frames
        mv.visitLabel(nestFailed);
        mv.visitVarInsn(Opcodes.ASTORE, EX);
        depth(-1);
        mv.visitVarInsn(Opcodes.ALOAD, EX);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Throwable");
        mv.visitInsn(Opcodes.ATHROW);
    }

    /**
     * TAILCALL in a directly called function (from the interpreter it's handed back, like RETURN). Same steps as the
     * interpreter's TAILCALL: arguments moved down to returnBase, then a Java function is called and this frame popped
     * (its fromLua is false, so the interpreter would return here too), or the frame is reused for a Lua function. That
     * one runs straight away if it's compiled; if not, or when the Java stack is deep, the frame is left at pc 0 and
     * returning 0 makes directLuaCall's nested luaMainloop run it. Callable tables and the coroutine library hand back.
     */
    private void tailCall(int i, int a, int b) {
        Label handBack = new Label(), luaTail = new Label(), interp = new Label();
        mv.visitVarInsn(Opcodes.ILOAD, DIRECT);
        mv.visitJumpInsn(Opcodes.IFEQ, handBack);
        setPc(i + 1);
        get(a);
        mv.visitVarInsn(Opcodes.ASTORE, X);
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, JFN);
        mv.visitJumpInsn(Opcodes.IFEQ, luaTail);
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "se/krka/kahlua/stdlib/CoroutineLib");
        mv.visitJumpInsn(Opcodes.IFNE, handBack);
        tailArgs(a, b);
        mv.visitVarInsn(Opcodes.ALOAD, T);
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.CHECKCAST, JFN);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "returnBase", "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "returnBase", "I");
        mv.visitVarInsn(Opcodes.ILOAD, N);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOST, "callJava", "(L" + JFN + ";III)I", false);
        mv.visitInsn(Opcodes.POP);
        coroutine();
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COROUTINE, "popCallFrame", "()V", false);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(luaTail);
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, LCLOSURE);
        mv.visitJumpInsn(Opcodes.IFEQ, handBack);
        tailArgs(a, b);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "returnBase", "I");
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitFieldInsn(Opcodes.PUTFIELD, FRAME, "localBase", "I");
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitVarInsn(Opcodes.ILOAD, N);
        mv.visitFieldInsn(Opcodes.PUTFIELD, FRAME, "nArguments", "I");
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.CHECKCAST, LCLOSURE);
        mv.visitFieldInsn(Opcodes.PUTFIELD, FRAME, "closure", "L" + LCLOSURE + ";");
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "init", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.CHECKCAST, LCLOSURE);
        mv.visitFieldInsn(Opcodes.GETFIELD, LCLOSURE, "prototype", "L" + PROTO + ";");
        mv.visitFieldInsn(Opcodes.GETFIELD, PROTO, "pzoJit", "L" + OBJ + ";");
        mv.visitVarInsn(Opcodes.ASTORE, Z);
        mv.visitVarInsn(Opcodes.ALOAD, Z);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, FN);
        mv.visitJumpInsn(Opcodes.IFEQ, interp);
        mv.visitVarInsn(Opcodes.ALOAD, T);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOST, "pzoJitDepth", "I");
        push(LuaJit.MAX_DIRECT_DEPTH);
        mv.visitJumpInsn(Opcodes.IF_ICMPGE, interp);
        depth(1);
        Label runStart = new Label(), runEnd = new Label(), runFailed = new Label();
        mv.visitTryCatchBlock(runStart, runEnd, runFailed, "java/lang/Throwable");
        mv.visitLabel(runStart);
        mv.visitVarInsn(Opcodes.ALOAD, Z);
        mv.visitTypeInsn(Opcodes.CHECKCAST, FN);
        mv.visitVarInsn(Opcodes.ALOAD, T);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FN, "run", "(L" + HOST + ";L" + FRAME + ";Z)I", true);
        mv.visitLabel(runEnd);
        mv.visitVarInsn(Opcodes.ISTORE, N);
        depth(-1);
        mv.visitVarInsn(Opcodes.ILOAD, N);
        mv.visitInsn(Opcodes.IRETURN);
        // The frame is the same one directLuaCall pushed, so its handler reports the error for it as usual
        mv.visitLabel(runFailed);
        mv.visitVarInsn(Opcodes.ASTORE, EX);
        depth(-1);
        mv.visitVarInsn(Opcodes.ALOAD, EX);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Throwable");
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(interp);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);

        mv.visitLabel(handBack);
        handBack(i);
    }

    // The interpreter's TAILCALL up to the call: close upvalues, N = argument count, arguments (with the function) moved
    // down to returnBase
    private void tailArgs(int a, int b) {
        coroutine();
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "localBase", "I");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COROUTINE, "closeUpvalues", "(I)V", false);
        if (b != 0) {
            push(b - 1);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, CF);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "getTop", "()I", false);
            push(a + 1);
            mv.visitInsn(Opcodes.ISUB);
        }
        mv.visitVarInsn(Opcodes.ISTORE, N);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitFieldInsn(Opcodes.PUTFIELD, FRAME, "restoreTop", "Z");
        coroutine();
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "localBase", "I");
        push(a);
        mv.visitInsn(Opcodes.IADD);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "returnBase", "I");
        mv.visitVarInsn(Opcodes.ILOAD, N);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COROUTINE, "stackCopy", "(III)V", false);
        coroutine();
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        mv.visitFieldInsn(Opcodes.GETFIELD, FRAME, "returnBase", "I");
        mv.visitVarInsn(Opcodes.ILOAD, N);
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IADD);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, COROUTINE, "setTop", "(I)V", false);
    }

    private void depth(int delta) {
        mv.visitVarInsn(Opcodes.ALOAD, T);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOST, "pzoJitDepth", "I");
        push(delta);
        mv.visitInsn(Opcodes.IADD);
        mv.visitFieldInsn(Opcodes.PUTFIELD, HOST, "pzoJitDepth", "I");
    }

    private void coroutine() {
        mv.visitVarInsn(Opcodes.ALOAD, T);
        mv.visitFieldInsn(Opcodes.GETFIELD, HOST, "currentCoroutine", "L" + COROUTINE + ";");
    }

    // ADD..POW: both operands Double goes through primitiveMath like the interpreter; anything else is handed back,
    // where rawTonumber/metamethods take over
    private void arith(int i, int opcode, int a, int b, int c) {
        setPc(i + 1);
        rk(b);
        mv.visitVarInsn(Opcodes.ASTORE, X);
        rk(c);
        mv.visitVarInsn(Opcodes.ASTORE, Y);
        Label handBack = new Label();
        bothDoubles(handBack);
        set(a, () -> {
            mv.visitVarInsn(Opcodes.ALOAD, T);
            mv.visitVarInsn(Opcodes.ALOAD, X);
            mv.visitTypeInsn(Opcodes.CHECKCAST, DBL);
            mv.visitVarInsn(Opcodes.ALOAD, Y);
            mv.visitTypeInsn(Opcodes.CHECKCAST, DBL);
            push(opcode);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOST, "primitiveMath", "(L" + DBL + ";L" + DBL + ";I)L" + DBL + ";", false);
        });
        jumpTo(i, i + 1);
        mv.visitLabel(handBack);
        handBack(i);
    }

    // EQ/LT/LE: the interpreter skips the next instruction when result == (a == 0). Double/Double and String/String
    // are done here, and so is EQ on the very same object; everything else (metamethods, luaEquals) is handed back.
    private void compare(int i, int opcode, int a, int b, int c) {
        setPc(i + 1);
        rk(b);
        mv.visitVarInsn(Opcodes.ASTORE, X);
        rk(c);
        mv.visitVarInsn(Opcodes.ASTORE, Y);
        int skipIfResult = a == 0 ? Opcodes.IFNE : Opcodes.IFEQ;
        Label notDoubles = new Label(), notStrings = new Label(), handBack = new Label();

        bothDoubles(notDoubles);
        mv.visitVarInsn(Opcodes.ALOAD, X);
        fromDouble();
        mv.visitVarInsn(Opcodes.ALOAD, Y);
        fromDouble();
        Label t = new Label(), done = new Label();
        if (opcode == EQ) {
            mv.visitInsn(Opcodes.DCMPL);
            mv.visitJumpInsn(Opcodes.IFEQ, t);
        } else if (opcode == LT) {
            mv.visitInsn(Opcodes.DCMPG);
            mv.visitJumpInsn(Opcodes.IFLT, t);
        } else {
            mv.visitInsn(Opcodes.DCMPG);
            mv.visitJumpInsn(Opcodes.IFLE, t);
        }
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitJumpInsn(Opcodes.GOTO, done);
        mv.visitLabel(t);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitLabel(done);
        branch(i, skipIfResult, i + 2);
        jumpTo(i, i + 1);

        mv.visitLabel(notDoubles);
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/String");
        mv.visitJumpInsn(Opcodes.IFEQ, notStrings);
        mv.visitVarInsn(Opcodes.ALOAD, Y);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, "java/lang/String");
        mv.visitJumpInsn(Opcodes.IFEQ, notStrings);
        if (opcode == EQ) {
            mv.visitVarInsn(Opcodes.ALOAD, X);
            mv.visitVarInsn(Opcodes.ALOAD, Y);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OBJ, "equals", "(L" + OBJ + ";)Z", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, X);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
            mv.visitVarInsn(Opcodes.ALOAD, Y);
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "compareTo", "(Ljava/lang/String;)I", false);
            Label st = new Label(), sdone = new Label();
            mv.visitJumpInsn(opcode == LT ? Opcodes.IFLT : Opcodes.IFLE, st);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitJumpInsn(Opcodes.GOTO, sdone);
            mv.visitLabel(st);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitLabel(sdone);
        }
        branch(i, skipIfResult, i + 2);
        jumpTo(i, i + 1);

        mv.visitLabel(notStrings);
        if (opcode == EQ) {
            // The same object is equal without looking at metatables
            mv.visitVarInsn(Opcodes.ALOAD, X);
            mv.visitVarInsn(Opcodes.ALOAD, Y);
            mv.visitJumpInsn(Opcodes.IF_ACMPNE, handBack);
            if (a == 0) {
                jumpTo(i, i + 2);
            } else {
                jumpTo(i, i + 1);
            }
        }
        mv.visitLabel(handBack);
        handBack(i);
    }

    // FORLOOP exactly as the interpreter: iter += step, store it, then either jump back (and copy it to a + 3) or
    // clear from a and fall through
    private void forLoop(int i, int a, int target) {
        setPc(i + 1);
        get(a);
        fromDouble();
        mv.visitVarInsn(Opcodes.DSTORE, D1);
        get(a + 1);
        fromDouble();
        mv.visitVarInsn(Opcodes.DSTORE, D2);
        get(a + 2);
        fromDouble();
        mv.visitVarInsn(Opcodes.DSTORE, D3);
        mv.visitVarInsn(Opcodes.DLOAD, D1);
        mv.visitVarInsn(Opcodes.DLOAD, D3);
        mv.visitInsn(Opcodes.DADD);
        mv.visitInsn(Opcodes.DUP2);
        mv.visitVarInsn(Opcodes.DSTORE, D1);
        toDouble();
        mv.visitVarInsn(Opcodes.ASTORE, X);
        set(a, () -> mv.visitVarInsn(Opcodes.ALOAD, X));

        Label negative = new Label(), exit = new Label(), cont = new Label();
        // step > 0.0 (NaN is not)
        mv.visitVarInsn(Opcodes.DLOAD, D3);
        mv.visitInsn(Opcodes.DCONST_0);
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFLE, negative);
        // exit when !(iter <= end)
        mv.visitVarInsn(Opcodes.DLOAD, D1);
        mv.visitVarInsn(Opcodes.DLOAD, D2);
        mv.visitInsn(Opcodes.DCMPG);
        mv.visitJumpInsn(Opcodes.IFGT, exit);
        mv.visitJumpInsn(Opcodes.GOTO, cont);
        mv.visitLabel(negative);
        // exit when !(iter >= end)
        mv.visitVarInsn(Opcodes.DLOAD, D1);
        mv.visitVarInsn(Opcodes.DLOAD, D2);
        mv.visitInsn(Opcodes.DCMPL);
        mv.visitJumpInsn(Opcodes.IFLT, exit);
        mv.visitLabel(cont);
        set(a + 3, () -> mv.visitVarInsn(Opcodes.ALOAD, X));
        jumpTo(i, target);
        mv.visitLabel(exit);
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        push(a);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "clearFromIndex", "(I)V", false);
        jumpTo(i, i + 1);
    }

    // Jumps to both operands being Double, else to otherwise
    private void bothDoubles(Label otherwise) {
        mv.visitVarInsn(Opcodes.ALOAD, X);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, DBL);
        mv.visitJumpInsn(Opcodes.IFEQ, otherwise);
        mv.visitVarInsn(Opcodes.ALOAD, Y);
        mv.visitTypeInsn(Opcodes.INSTANCEOF, DBL);
        mv.visitJumpInsn(Opcodes.IFEQ, otherwise);
    }

    // Conditional jump to instruction target (with the reset check on backward jumps)
    private void branch(int from, int ifOpcode, int target) {
        if (target <= from || target >= n) {
            Label no = new Label();
            mv.visitJumpInsn(invert(ifOpcode), no);
            jumpTo(from, target);
            mv.visitLabel(no);
        } else {
            mv.visitJumpInsn(ifOpcode, at[target]);
        }
    }

    private static int invert(int ifOpcode) {
        return switch (ifOpcode) {
            case Opcodes.IFEQ -> Opcodes.IFNE;
            case Opcodes.IFNE -> Opcodes.IFEQ;
            default -> throw new IllegalArgumentException("op " + ifOpcode);
        };
    }

    // GOTO instruction target. Backward jumps check KahluaThread.reset, which the interpreter checks every
    // instruction; a target outside the function hands back to the interpreter.
    private void jumpTo(int from, int target) {
        if (target < 0 || target >= n) {
            mv.visitVarInsn(Opcodes.ALOAD, CF);
            push(Math.max(0, Math.min(target, n)));
            mv.visitFieldInsn(Opcodes.PUTFIELD, FRAME, "pc", "I");
            mv.visitJumpInsn(Opcodes.GOTO, end);
            return;
        }
        if (target <= from) {
            Label ok = new Label();
            mv.visitVarInsn(Opcodes.ALOAD, T);
            mv.visitFieldInsn(Opcodes.GETFIELD, HOST, "reset", "Z");
            mv.visitJumpInsn(Opcodes.IFEQ, ok);
            mv.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
            mv.visitInsn(Opcodes.DUP);
            mv.visitLdcInsn("lua was reset");
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V", false);
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitLabel(ok);
        }
        mv.visitJumpInsn(Opcodes.GOTO, at[target]);
    }

    // pc = i and return: luaMainloop fetches and executes instruction i itself
    private void handBack(int i) {
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        push(i);
        mv.visitFieldInsn(Opcodes.PUTFIELD, FRAME, "pc", "I");
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
    }

    private void setPc(int pc) {
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        push(pc);
        mv.visitFieldInsn(Opcodes.PUTFIELD, FRAME, "pc", "I");
    }

    private void get(int reg) {
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        push(reg);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "get", "(I)L" + OBJ + ";", false);
    }

    private void set(int reg, Runnable value) {
        mv.visitVarInsn(Opcodes.ALOAD, CF);
        push(reg);
        value.run();
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, FRAME, "set", "(IL" + OBJ + ";)V", false);
    }

    // Register or constant, as getRegisterOrConstant
    private void rk(int x) {
        if (x >= 256) constant(x - 256);
        else get(x);
    }

    private void constant(int index) {
        mv.visitVarInsn(Opcodes.ALOAD, K);
        push(index);
        mv.visitInsn(Opcodes.AALOAD);
    }

    private void upvalue(int index) {
        mv.visitVarInsn(Opcodes.ALOAD, CL);
        mv.visitFieldInsn(Opcodes.GETFIELD, LCLOSURE, "upvalues", "[L" + UPVAL + ";");
        push(index);
        mv.visitInsn(Opcodes.AALOAD);
    }

    private void tableget() {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, HOST, "tableget", "(L" + OBJ + ";L" + OBJ + ";)L" + OBJ + ";", false);
    }

    private void rawTostring() {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "rawTostring", "(L" + OBJ + ";)Ljava/lang/String;", false);
    }

    private void fromDouble() {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "fromDouble", "(L" + OBJ + ";)D", false);
    }

    private void toDouble() {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, UTIL, "toDouble", "(D)Ljava/lang/Double;", false);
    }

    private void push(int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(Opcodes.ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(Opcodes.BIPUSH, v);
        else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(Opcodes.SIPUSH, v);
        else mv.visitLdcInsn(v);
    }
}
