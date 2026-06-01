package app.hackeris.hoa.hap

object ElfPatcher {
    @JvmStatic
    external fun patchSo(path: String): Boolean

    /**
     * Patch GOT entries for "sigaction" in a loaded .so to point to
     * libb.so's bridge, bypassing libsigchain.so interception.
     * Returns the number of GOT entries patched.
     */
    @JvmStatic
    external fun patchGot(path: String): Int

    /**
     * Load an .so via native dlopen(RTLD_GLOBAL), bypassing the
     * Android classloader namespace so DT_NEEDED dependencies within
     * the HAP can resolve each other's symbols.
     * Returns 0 on success, -1 on failure.
     */
    @JvmStatic
    external fun nativeLoad(path: String): Int

    /**
     * Patch DT_RUNPATH (and DT_RPATH) of an ELF .so to "$ORIGIN"
     * so that DT_NEEDED dependencies resolve from the same directory.
     * Returns true if a RUNPATH/RPATH was found and patched.
     */
    @JvmStatic
    external fun patchRunpath(path: String): Boolean
}
