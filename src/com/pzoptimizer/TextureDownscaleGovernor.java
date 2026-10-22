package com.pzoptimizer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;
import zombie.asset.Asset;
import zombie.asset.AssetTask;
import zombie.asset.AssetTask_RunFileTask;
import zombie.asset.FileTask_LoadImageData;
import zombie.core.Core;
import zombie.core.textures.ImageData;
import zombie.core.textures.MipMapLevel;
import zombie.core.textures.TextureID;
import zombie.core.textures.TextureIDAssetManager;
import zombie.core.utils.DirectBufferAllocator;
import zombie.core.utils.WrappedBuffer;
import zombie.fileSystem.FileSystemImpl;
import zombie.fileSystem.FileTask;

/**
 * Moves TextureID.limitMaxSize off the main thread. A texture bigger than the "max texture size" option is
 * downscaled when its load finishes, in the main-thread callback, by generating mipmaps pixel by pixel (100-250 ms
 * for a large clothing or vehicle texture, a visible hitch). Here the same downscale runs on the file worker right
 * after the PNG decode, so the callback's own limitMaxSize finds the image already within the limit and returns it
 * unchanged. The output is built exactly as limitMaxSize builds it.
 *
 * TextureIDAssetManager.setTask records the size limit for each image load task (the only point where the
 * TextureID and its task are both known); FileSystemImpl.runAsync wraps that task's future before it is queued.
 */
public final class TextureDownscaleGovernor {

    private static volatile boolean active = true;
    public static final AtomicLong texturesDownscaled = new AtomicLong();

    private static final int LIMIT_FLAGS = 384;
    private static final Map<FileTask, Integer> pending = Collections.synchronizedMap(new IdentityHashMap<>());

    private static final MethodHandle SET_TASK;
    private static final MethodHandle RUN_ASYNC;
    private static final VarHandle FILE_TASK;
    private static final VarHandle ITEM_TASK;
    private static final VarHandle ITEM_FUTURE;
    private static final VarHandle PARAM_FLAGS;

    static {
        MethodHandle setTask = null, runAsync = null;
        VarHandle fileTask = null, itemTask = null, itemFuture = null, paramFlags = null;
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            setTask = MethodHandles.privateLookupIn(zombie.asset.AssetManager.class, l).findVirtual(zombie.asset.AssetManager.class,
                "setTask", MethodType.methodType(void.class, Asset.class, AssetTask.class));
            Class<?> item = Class.forName("zombie.fileSystem.FileSystemImpl$AsyncItem");
            MethodHandles.Lookup fs = MethodHandles.privateLookupIn(FileSystemImpl.class, l);
            runAsync = fs.findVirtual(FileSystemImpl.class, "runAsync", MethodType.methodType(int.class, item))
                .asType(MethodType.methodType(int.class, FileSystemImpl.class, Object.class));
            MethodHandles.Lookup il = MethodHandles.privateLookupIn(item, l);
            itemTask = il.findVarHandle(item, "task", FileTask.class);
            itemFuture = il.findVarHandle(item, "future", FutureTask.class);
            fileTask = MethodHandles.privateLookupIn(AssetTask_RunFileTask.class, l)
                .findVarHandle(AssetTask_RunFileTask.class, "fileTask", FileTask.class);
            paramFlags = MethodHandles.privateLookupIn(TextureID.TextureIDAssetParams.class, l)
                .findVarHandle(TextureID.TextureIDAssetParams.class, "flags", int.class);
        } catch (Throwable t) {
            PZOLogger.warn("[TextureDownscaleGovernor] hooks unavailable, governor disabled: " + t);
            active = false;
        }
        SET_TASK = setTask;
        RUN_ASYNC = runAsync;
        FILE_TASK = fileTask;
        ITEM_TASK = itemTask;
        ITEM_FUTURE = itemFuture;
        PARAM_FLAGS = paramFlags;
    }

    private TextureDownscaleGovernor() {}

    public static void setActive(boolean value) {
        active = value;
    }

    // Replaces this.setTask(asset, task) in TextureIDAssetManager.startLoading
    public static void setTask(TextureIDAssetManager manager, Asset asset, AssetTask task) throws Throwable {
        SET_TASK.invoke(manager, asset, task);
        if (active && asset instanceof TextureID tex && task instanceof AssetTask_RunFileTask) {
            TextureID.TextureIDAssetParams params = tex.assetParams;
            int flags = params == null ? 0 : (int) PARAM_FLAGS.get(params);
            if ((flags & LIMIT_FLAGS) != 0) {
                FileTask fileTask = (FileTask) FILE_TASK.get((AssetTask_RunFileTask) task);
                if (fileTask instanceof FileTask_LoadImageData) {
                    pending.put(fileTask, Core.getInstance().getMaxTextureSizeFromFlags(flags));
                }
            }
        }
    }

    // Replaces this.runAsync(item) in FileSystemImpl.runAsync(FileTask); item is a FileSystemImpl.AsyncItem
    public static int runAsync(FileSystemImpl fs, Object item) throws Throwable {
        if (active) {
            FileTask task = (FileTask) ITEM_TASK.get(item);
            Integer maxSize = task == null ? null : pending.remove(task);
            if (maxSize != null) {
                int max = maxSize;
                ITEM_FUTURE.set(item, new FutureTask<Object>(() -> {
                    Object result = task.call();
                    return result instanceof ImageData data ? limitMaxSize(data, max) : result;
                }));
            }
        }
        return (int) RUN_ASYNC.invokeExact(fs, item);
    }

    // Same steps as TextureID.limitMaxSize
    private static ImageData limitMaxSize(ImageData data, int maxSize) {
        if (!active || (data.getWidth() <= maxSize && data.getHeight() <= maxSize)) {
            return data;
        }
        data.preserveTransparentColor = true;
        MipMapLevel mml = data.getData();
        int level = 0;
        while (mml.width > maxSize || mml.height > maxSize) {
            mml = data.getMipMapData(++level);
        }
        WrappedBuffer wrappedBuffer = DirectBufferAllocator.allocate(mml.getBuffer().capacity());
        mml.getBuffer().rewind();
        wrappedBuffer.getBuffer().put(mml.getBuffer());
        wrappedBuffer.getBuffer().rewind();
        ImageData newData = new ImageData(data.getWidth() >> level, data.getHeight() >> level, wrappedBuffer);
        newData.alphaPaddingDone = true;
        data.dispose();
        texturesDownscaled.incrementAndGet();
        return newData;
    }
}
