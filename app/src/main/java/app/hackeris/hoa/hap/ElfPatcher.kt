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
}
