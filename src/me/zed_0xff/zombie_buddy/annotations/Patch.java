package me.zed_0xff.zombie_buddy.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Patch {
    String className() default "";
    String methodName() default "";
    boolean isAdvice() default true;
    boolean warmUp() default false;
    boolean strictMatch() default false;
    boolean debug() default false;

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface OnEnter {
        boolean skipOn() default false;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface OnExit {
        Class<? extends Throwable> onThrowable() default Throwable.class;
        Class<? extends Throwable> suppress() default Throwable.class;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface NameMap {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Return {
        boolean readOnly() default true;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Thrown {
        boolean readOnly() default true;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface This {
        boolean readOnly() default true;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Argument {
        int value() default 0;
        boolean readOnly() default true;
        boolean optional() default false;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface AllArguments {
        boolean readOnly() default true;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface RuntimeType {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface SuperMethod {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface SuperCall {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Local {
        String value() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface Field {
        String[] value() default {};
        String logicalName() default "";
        Class<?> declaringType() default void.class;
        boolean readOnly() default false;
        boolean optional() default false;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface MethodHandle {
        String[] name() default {};
        String className() default "";
        boolean optional() default false;
        Class<?> returnType() default void.class;
        Class<?>[] paramTypes() default {};
        Class<?> owner() default void.class;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    public @interface VarHandle {
        String[] name() default {};
        String className() default "";
        Class<?> owner() default void.class;
        boolean optional() default false;
        Class<?> type() default void.class;
    }
}
