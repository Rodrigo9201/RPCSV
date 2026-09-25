package org.vita3k.emulator;

/**
 * Stub de compilação apenas: em runtime a implementação real vem de
 * classes2.dex (engine embarcada). Referenciado por EngineActivity.
 */
public class NativeLib {
    public static native boolean init(String storage_path);
    public static native boolean isInitialized();
    public static native boolean prepareFrontend();
}