package app.hackeris.hoa.hap

object ElfPatcher {
    @JvmStatic
    external fun patchSo(path: String): Boolean
}
