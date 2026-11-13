package ox.fzer0x.snakeloader

data class ScriptRepository(
    val name: String,
    val owner: String,
    val description: String,
    val url: String,
    val stars: Int,
    val defaultPath: String = ""
)

data class ScriptFile(
    val name: String,
    val path: String,
    val downloadUrl: String,
    val size: Long,
    val repository: ScriptRepository,
    val isDirectory: Boolean = false,
    val description: String? = null
)

data class ModuleMetadata(
    val version: String? = null,
    val author: String? = null,
    val targetPackages: List<String> = emptyList(),
    val minSnakeVersion: Int? = null,
    val noEdit: Boolean = false,
    val category: String? = null,
    val apkPath: String? = null
)

data class DownloadedScript(
    val id: String,
    val name: String,
    val content: String,
    val repository: String,
    val path: String,
    val isEnabled: Boolean = false,
    val downloadedAt: Long = System.currentTimeMillis(),
    val description: String? = null,
    val metadata: ModuleMetadata = ModuleMetadata()
)

fun String.isGoodDescription(): Boolean {
    val clean = this.trim().removePrefix(":").trim()
    if (clean.isBlank() || clean.length < 5) return false
    
    val codeKeywords = listOf(
        "function ", "function(", "var ", "let ", "const ", "=>", "return ", "if (", "for (", "while (",
        "console.log", "rpc.exports", "require(", "import ", "from '", "ptr(", "0x"
    )
    
    if (codeKeywords.any { clean.contains(it, ignoreCase = true) }) return false
    
    val symbolCount = clean.count { it in "{}=><+-*/%&|^!" }
    if (symbolCount > 10 || symbolCount > clean.length / 3) return false
    
    val badStarts = listOf("@", "Author:", "Version:", "Target:", "File:", "Name:")
    if (badStarts.any { clean.startsWith(it, ignoreCase = true) }) return false

    return true
}
