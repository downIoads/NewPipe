package org.schabi.newpipe.translation;

/**
 * Thin Java binding around the native llama.cpp translator.
 * All methods are static — only one model is loaded at a time per process.
 */
final class LlamaTranslator {

    private static final boolean LIB_AVAILABLE = loadLib();

    private static boolean loadLib() {
        try {
            System.loadLibrary("newpipe-translator");
            return true;
        } catch (final Throwable t) {
            android.util.Log.e("LlamaTranslator",
                    "Translation native lib unavailable: " + t.getMessage());
            return false;
        }
    }

    private LlamaTranslator() { }

    static boolean isLibAvailable() {
        return LIB_AVAILABLE;
    }

    static native void nativeBackendInit();

    static native boolean nativeLoad(String path, int nCtx, int nThreads);

    static native void nativeRelease();

    static native void nativeCancel();

    static native boolean nativeIsLoaded();

    static native String nativeTranslate(String text, String targetLanguage, int maxTokens);
}
