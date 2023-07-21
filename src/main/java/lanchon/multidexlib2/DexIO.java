/*
 * multidexlib2 - Copyright 2015-2022 Rodrigo Balerdi
 * (GNU General Public License version 3 or later)
 *
 * multidexlib2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 */

package lanchon.multidexlib2;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.writer.DexWriter;
import com.android.tools.smali.dexlib2.writer.io.DexDataStore;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class DexIO {

    @SuppressWarnings("unused")
    public static final int DEFAULT_MAX_DEX_POOL_SIZE = DexWriter.MAX_POOL_SIZE;
    private static final int PER_THREAD_BATCH_SIZE = 100;

    private DexIO() {
    }

    // Single-Threaded Write

    static void writeRawDexSingleThread(DexDataStore dataStore, DexFile dexFile, int maxDexPoolSize,
                                        DexIO.Logger logger, File file) throws IOException {
        Set<? extends ClassDef> classes = dexFile.getClasses();
        Iterator<? extends ClassDef> classIterator = classes.iterator();
        DexPool dexPool = new DexPool(dexFile.getOpcodes());
        int classCount = 0;
        while (classIterator.hasNext()) {
            ClassDef classDef = classIterator.next();
            dexPool.internClass(classDef);
            if (dexPool.hasOverflowed(maxDexPoolSize)) {
                handleDexPoolOverflow(classDef, classCount, classes.size());
                throw new AssertionError("unexpected type count");
            }
            classCount++;
        }
        if (logger != null)
            logger.log(file, SingletonDexContainer.UNDEFINED_ENTRY_NAME, classCount);
        dexPool.writeTo(dataStore);
    }

    static void writeMultiDexDirectorySingleThread(Map<String, MemoryDataStore> output, DexFileNameIterator nameIterator,
                                                   DexFile dexFile, int minMainDexClassCount, boolean minimalMainDex, int maxDexPoolSize)
            throws IOException {
        Set<? extends ClassDef> classes = dexFile.getClasses();
        Object lock = new Object();
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (lock) {       // avoid multiple synchronizations in single-threaded mode
            writeMultiDexDirectoryCommon(output, nameIterator, Iterators.peekingIterator(classes.iterator()),
                    minMainDexClassCount, minimalMainDex, dexFile.getOpcodes(), maxDexPoolSize, lock);
        }
    }

    // Multi-Threaded Write

    static void writeMultiDexDirectoryMultiThread(int threadCount, final Map<String, MemoryDataStore> output,
                                                  final DexFileNameIterator nameIterator, final DexFile dexFile, final int maxDexPoolSize) throws IOException {
        Iterator<? extends ClassDef> classIterator = dexFile.getClasses().iterator();
        final Object lock = new Object();
        List<Callable<Void>> callables = new ArrayList<>(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final BatchedIterator<ClassDef> batchedIterator =
                    new BatchedIterator<>(classIterator, lock, PER_THREAD_BATCH_SIZE);
            if (i != 0 && !batchedIterator.hasNext()) break;
            callables.add(() -> {
                writeMultiDexDirectoryCommon(output, nameIterator, batchedIterator, 0, false,
                        dexFile.getOpcodes(), maxDexPoolSize, lock);
                return null;
            });
        }
        ExecutorService service = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<Void>> futures = service.invokeAll(callables);
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof IOException) throw (IOException) c;
                    if (c instanceof RuntimeException) throw (RuntimeException) c;
                    if (c instanceof Error) throw (Error) c;
                    throw new UndeclaredThrowableException(c);
                }
            }
        } catch (InterruptedException e) {
            InterruptedIOException ioe = new InterruptedIOException();
            ioe.initCause(e);
            throw ioe;
        } finally {
            service.shutdown();
        }
    }

    private static void writeMultiDexDirectoryCommon(Map<String, MemoryDataStore> output, DexFileNameIterator nameIterator,
                                                     PeekingIterator<? extends ClassDef> classIterator, int minMainDexClassCount, boolean minimalMainDex,
                                                     Opcodes opcodes, int maxDexPoolSize, Object lock) throws IOException {
        do {
            DexPool dexPool = new DexPool(opcodes);
            int fileClassCount = 0;
            while (classIterator.hasNext()) {
                if (minimalMainDex && fileClassCount >= minMainDexClassCount) break;
                ClassDef classDef = classIterator.peek();
                dexPool.mark();
                dexPool.internClass(classDef);
                if (dexPool.hasOverflowed(maxDexPoolSize)) {
                    handleDexPoolOverflow(classDef, fileClassCount, minMainDexClassCount);
                    dexPool.reset();
                    break;
                }
                classIterator.next();
                fileClassCount++;
            }
            String dexName;
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized (lock) {
                dexName = nameIterator.next();
                if (classIterator instanceof BatchedIterator) {
                    ((BatchedIterator<?>) classIterator).preloadBatch();
                }
            }
            MemoryDataStore memoryDataStore = new MemoryDataStore();
            dexPool.writeTo(memoryDataStore);
            output.put(dexName, memoryDataStore);
            minMainDexClassCount = 0;
            minimalMainDex = false;
        } while (classIterator.hasNext());
    }

    // Common Code

    private static void handleDexPoolOverflow(ClassDef classDef, int classCount, int minClassCount) {
        if (classCount < minClassCount) throw new DexPoolOverflowException(
                "Dex pool overflowed while writing type " + (classCount + 1) + " of " + minClassCount);
        if (classCount == 0) throw new DexPoolOverflowException(
                "Type too big for dex pool: " + classDef.getType());
    }

    public interface Logger {
        void log(File file, String entryName, int typeCount);
    }

}
