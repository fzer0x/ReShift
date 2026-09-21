package ox.fzer0x.snakeloader.ai

import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppClassData
import ox.fzer0x.snakeloader.ui.viewmodels.Il2CppMethodData

data class ScoredMethod(
    val klass: Il2CppClassData,
    val method: Il2CppMethodData,
    val score: Int
)

class AstSemanticRanker {

    private val systemPackagePrefixes = listOf(
        "system.", "unityengine.", "mono.", "microsoft.", "newtonsoft.",
        "google.", "tmpro.", "nunit.", "mscorlib", "android."
    )

    private val domainKeywordSynonyms = mapOf(
        "budget" to listOf("budget", "money", "gold", "coin", "cash", "currency", "wallet", "balance", "amount", "revenue", "bank", "fund"),
        "geld" to listOf("money", "gold", "coin", "cash", "currency", "wallet", "balance"),
        "münzen" to listOf("coin", "coins", "money", "currency"),
        "währung" to listOf("currency", "money", "coins", "cash", "wallet"),
        "erhöhe" to listOf("add", "set", "get", "increase", "earn", "gain", "modify", "update"),
        "erhöhen" to listOf("add", "set", "get", "increase", "earn", "gain"),
        "unendlich" to listOf("infinite", "max", "add", "set", "get"),
        "leben" to listOf("health", "hp", "life", "god", "vital"),
        "schaden" to listOf("damage", "attack", "hit", "power", "strength")
    )

    private val gameLogicKeywords = setOf(
        "money", "gold", "coin", "cash", "gem", "diamond", "currency",
        "wallet", "balance", "bank", "budget", "resource", "health", "hp",
        "god", "add", "set", "get", "score", "reward", "purchase", "amount"
    )

    /**
     * Ranks and filters C# methods based on weighted AST properties, non-obfuscated names,
     * Primitive return types, and keyword token matching with German/multilingual synonym expansion.
     */
    fun rankAndFilterMethods(
        allClasses: List<Il2CppClassData>,
        userGoal: String,
        maxResults: Int = 15
    ): List<ScoredMethod> {
        val rawUserTokens = userGoal.lowercase().split(" ", "_", ".", ",", ";", "-").filter { it.length > 2 }
        val expandedSearchTokens = mutableSetOf<String>()

        for (token in rawUserTokens) {
            expandedSearchTokens.add(token)
            domainKeywordSynonyms[token]?.let { synonyms ->
                expandedSearchTokens.addAll(synonyms)
            }
        }

        val scoredList = mutableListOf<ScoredMethod>()

        for (klass in allClasses) {
            val fullClassIdentifier = "${klass.namespace}.${klass.fullName}.${klass.name}".lowercase()

            // Strictly exclude System/Unity/Framework namespaces and classes
            if (systemPackagePrefixes.any { prefix -> fullClassIdentifier.contains(prefix) }) {
                continue
            }

            for (method in klass.methods) {
                // Ignore empty or 0x0 offsets
                if (method.offset.isBlank() || method.offset == "0x0" || method.offset == "0x00") continue

                var score = 0
                val mName = method.name.lowercase()
                val cName = klass.name.lowercase()
                val retType = method.returnType.lowercase()

                // Ignore system reflection / framework methods
                if (mName.startsWith("gettype") || mName.startsWith("gethashcode") || mName.startsWith("tostring") || mName.startsWith("equals")) {
                    continue
                }

                // Rule 1: Non-obfuscated method name
                if (mName.length > 3 && !mName.matches(Regex("^[a-z][0-9]?$"))) {
                    score += 15
                }

                // Rule 2: Token & Synonym Matches against User Goal
                for (token in expandedSearchTokens) {
                    if (mName.contains(token)) score += 50
                    if (cName.contains(token)) score += 35
                }

                // Rule 3: Numerical / Primitive Return Types (Crucial for budget/currency modification)
                val isNumericReturn = retType.contains("int") || retType.contains("long") || retType.contains("float") || retType.contains("double")
                if (isNumericReturn) {
                    score += 30
                }

                // Rule 4: Property Getters/Setters for Currency/Wallet
                if (mName.startsWith("get_") || mName.startsWith("set_") || mName.startsWith("add_")) {
                    if (gameLogicKeywords.any { kw -> mName.contains(kw) || cName.contains(kw) }) {
                        score += 40
                    }
                }

                // Rule 5: Domain Keywords
                if (gameLogicKeywords.any { kw -> mName.contains(kw) || cName.contains(kw) }) {
                    score += 25
                }

                if (score > 30 || rawUserTokens.isEmpty()) {
                    scoredList.add(ScoredMethod(klass, method, score))
                }
            }
        }

        return if (scoredList.isNotEmpty()) {
            scoredList.sortedByDescending { it.score }.take(maxResults)
        } else {
            // Safe fallback: take user domain methods without framework classes
            allClasses
                .filter { k ->
                    val full = "${k.namespace}.${k.fullName}.${k.name}".lowercase()
                    !systemPackagePrefixes.any { prefix -> full.contains(prefix) }
                }
                .flatMap { k -> k.methods.map { m -> ScoredMethod(k, m, 1) } }
                .filter { it.method.offset.isNotBlank() && it.method.offset != "0x0" && it.method.offset != "0x00" }
                .take(maxResults)
        }
    }
}
