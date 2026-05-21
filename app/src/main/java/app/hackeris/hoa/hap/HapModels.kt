package app.hackeris.hoa.hap

data class HapBundle(
    val hapFile: String,
    val moduleConfig: ModuleConfig,
    val bytecodeEntries: Map<String, ByteArray>,
    val resourceIndex: ByteArray?,
    val rawResources: Map<String, ByteArray>,
    val nativeLibs: Map<String, ByteArray>,
    val packInfo: PackInfo?
)

data class ModuleConfig(
    val bundleName: String,
    val label: String,
    val vendor: String,
    val versionCode: Int,
    val versionName: String,
    val apiVersion: Int,
    val compileSdkVersion: String,
    val targetApiVersion: Int,
    val minApiVersion: Int,
    val virtualMachine: String,
    val name: String,
    val type: String,
    val mainElement: String,
    val deviceTypes: List<String>,
    val pages: List<String>,
    val abilities: List<AbilityConfig>,
    val requestPermissions: List<String>,
    val rawJson: String,
    val iconId: Int = 0
)

data class AbilityConfig(
    val name: String,
    val srcEntry: String,
    val type: String,
    val launchType: String,
    val exported: Boolean,
    val label: String,
    val icon: String,
    val skills: List<SkillConfig>,
    val iconId: Int = 0
)

data class SkillConfig(
    val entities: List<String>,
    val actions: List<String>
)

data class PackInfo(
    val bundleName: String,
    val versionCode: Int,
    val versionName: String
)

class HapParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
