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
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;

public class MultiDexIO {

    public static final int DEFAULT_MAX_THREADS = 4;

    private MultiDexIO() {
    }

    // Read

    public static DexFile readDexFile(boolean multiDex, File file, DexFileNamer namer, Opcodes opcodes,
                                      DexIO.Logger logger) throws IOException {
        MultiDexContainer<DexBackedDexFile> container = readMultiDexContainer(multiDex, file, namer, opcodes, logger);
        return new MultiDexContainerBackedDexFile<>(container);
    }

    public static MultiDexContainer<DexBackedDexFile> readMultiDexContainer(boolean multiDex, File file,
                                                                            DexFileNamer namer, Opcodes opcodes, DexIO.Logger logger) throws IOException {
        MultiDexContainer<DexBackedDexFile> container = readMultiDexContainer(file, namer, opcodes, logger);
        int entries = container.getDexEntryNames().size();
        if (entries == 0) throw new EmptyMultiDexContainerException(file.toString());
        if (!multiDex && entries > 1) throw new MultiDexDetectedException(file.toString());
        return container;
    }

    public static MultiDexContainer<DexBackedDexFile> readMultiDexContainer(File file, DexFileNamer namer,
                                                                            Opcodes opcodes, DexIO.Logger logger) throws IOException {
        MultiDexContainer<DexBackedDexFile> container = readMultiDexContainer(file, namer, opcodes);
        if (logger != null) {
            for (String name : container.getDexEntryNames()) {
                //noinspection ConstantConditions
                logger.log(file, name, container.getEntry(name).getDexFile().getClasses().size());
            }
        }
        return container;
    }

    public static MultiDexContainer<DexBackedDexFile> readMultiDexContainer(File file, DexFileNamer namer,
                                                                            Opcodes opcodes) throws IOException {
        if (file.isDirectory()) return new DirectoryDexContainer(file, namer, opcodes);
        if (!file.isFile()) throw new FileNotFoundException(file.toString());
        if (ZipFileDexContainer.isZipFile(file))
            return new ZipFileDexContainer(file, namer, opcodes);
        return new SingletonDexContainer<>(RawDexIO.readRawDexFile(file, opcodes));
    }

    // Write

    public static int writeDexFile(boolean multiDex, Map<String, MemoryDataStore> output, DexFileNamer namer, DexFile dexFile,
                                   int maxDexPoolSize, DexIO.Logger logger) throws IOException {
        return writeDexFile(multiDex, 1, output, namer, dexFile, maxDexPoolSize, logger);
    }

    public static int writeDexFile(boolean multiDex, int threadCount, Map<String, MemoryDataStore> output, DexFileNamer namer, DexFile dexFile,
                                   int maxDexPoolSize, DexIO.Logger logger) throws IOException {
        return writeDexFile(multiDex, threadCount, output, namer, dexFile, 0, false, maxDexPoolSize);
    }

    public static int writeDexFile(boolean multiDex, Map<String, MemoryDataStore> output, DexFileNamer namer, DexFile dexFile,
                                   int minMainDexClassCount, boolean minimalMainDex, int maxDexPoolSize,
                                   DexIO.Logger logger) throws IOException {
        return writeDexFile(multiDex, 1, output, namer, dexFile, minMainDexClassCount, minimalMainDex, maxDexPoolSize
        );
    }

    public static int writeDexFile(boolean multiDex, int threadCount, Map<String, MemoryDataStore> output, DexFileNamer namer, DexFile dexFile,
                                   int minMainDexClassCount, boolean minimalMainDex, int maxDexPoolSize) throws IOException {
        if (!multiDex) throw new UnsupportedOperationException(
                "Non-multidex is no longer supported, please use the official multidexlib2 for that."
        );
        return writeMultiDexDirectory(threadCount, output, namer, dexFile, minMainDexClassCount,
                minimalMainDex, maxDexPoolSize);
    }

    public static int writeMultiDexDirectory(int threadCount, Map<String, MemoryDataStore> output, DexFileNamer namer,
                                             DexFile dexFile, int minMainDexClassCount, boolean minimalMainDex, int maxDexPoolSize)
            throws IOException {
        DexFileNameIterator nameIterator = new DexFileNameIterator(namer);
        if (threadCount <= 0) {
            threadCount = Runtime.getRuntime().availableProcessors();
            if (threadCount > DEFAULT_MAX_THREADS) threadCount = DEFAULT_MAX_THREADS;
        }
        if (threadCount > 1 && minMainDexClassCount == 0 && !minimalMainDex) {
            DexIO.writeMultiDexDirectoryMultiThread(threadCount, output, nameIterator, dexFile, maxDexPoolSize
            );
        } else {
            DexIO.writeMultiDexDirectorySingleThread(output, nameIterator, dexFile, minMainDexClassCount,
                    minimalMainDex, maxDexPoolSize);
        }
        return nameIterator.getCount();
    }

}
