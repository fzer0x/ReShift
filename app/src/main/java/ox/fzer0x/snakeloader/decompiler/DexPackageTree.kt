package ox.fzer0x.snakeloader.decompiler

/**
 * Hierarchical tree node representing Android Java package structures (e.g. `com.example.app`).
 */
data class DexPackageNode(
    val name: String,
    val fullPackagePath: String,
    val subpackages: List<DexPackageNode> = emptyList(),
    val classes: List<DexClassData> = emptyList(),
    val totalClassCount: Int = 0
)

object DexPackageTreeBuilder {

    /**
     * Reconstructs a nested package tree hierarchy from a flat list of [DexClassData].
     */
    fun buildPackageTree(classes: List<DexClassData>): DexPackageNode {
        val root = MutablePackageNode("root", "")
        for (cls in classes) {
            val pkgParts = if (cls.packageName.isBlank()) emptyList() else cls.packageName.split('.')
            var current = root
            var currentPath = ""
            for (part in pkgParts) {
                currentPath = if (currentPath.isEmpty()) part else "$currentPath.$part"
                current = current.subpackages.getOrPut(part) { MutablePackageNode(part, currentPath) }
            }
            current.classes.add(cls)
        }
        return root.toImmutable()
    }

    private class MutablePackageNode(val name: String, val fullPath: String) {
        val subpackages = mutableMapOf<String, MutablePackageNode>()
        val classes = mutableListOf<DexClassData>()

        fun toImmutable(): DexPackageNode {
            val immSub = subpackages.values.map { it.toImmutable() }.sortedBy { it.name }
            val immClasses = classes.sortedBy { it.name }
            val count = immClasses.size + immSub.sumOf { it.totalClassCount }
            return DexPackageNode(
                name = name,
                fullPackagePath = fullPath,
                subpackages = immSub,
                classes = immClasses,
                totalClassCount = count
            )
        }
    }
}
