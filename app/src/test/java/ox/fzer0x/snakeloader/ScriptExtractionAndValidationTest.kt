package ox.fzer0x.snakeloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ox.fzer0x.snakeloader.ai.Il2CppAiAssistant
import ox.fzer0x.snakeloader.ai.JsSyntaxValidator

class ScriptExtractionAndValidationTest {

    private val aiAssistant = Il2CppAiAssistant()

    @Test
    fun testExtractJsCodeWithMarkdownFences() {
        val markdownResponse = """
            Here is the requested Frida script for your game:

            ```javascript
            'use strict';
            const TAG = "TEST_GAME";
            console.log("Hooking initialized");
            ```

            This script hooks libil2cpp.so cleanly.
        """.trimIndent()

        val extracted = aiAssistant.extractJsCode(markdownResponse)

        val expected = """
            'use strict';
            const TAG = "TEST_GAME";
            console.log("Hooking initialized");
        """.trimIndent()

        assertEquals(expected, extracted)
    }

    @Test
    fun testExtractJsCodePreservesCommentsTemplateLiteralsAndRegex() {
        val codeWithSpecialJsSyntax = """
            ```javascript
            // Single line comment with { unmatched brace }
            /* Multi line comment
               with 'quotes' and "strings" */
            const regex = /\{[a-z0-9_]+\}/g;
            const msg = `Template literal ${'$'}{1 + 2} with { braces }`;
            console.log(msg);
            ```
        """.trimIndent()

        val extracted = aiAssistant.extractJsCode(codeWithSpecialJsSyntax)

        assertTrue(extracted.contains("// Single line comment with { unmatched brace }"))
        assertTrue(extracted.contains("const regex = /\\{[a-z0-9_]+\\}/g;"))
        assertTrue(extracted.contains("const msg = `Template literal \${1 + 2} with { braces }`;"))
    }

    @Test
    fun testJsSyntaxValidatorValidCode() {
        val validScript = """
            'use strict';
            const TAG = "VALID_APP";

            function waitForModule(moduleName, callback) {
                const mod = Process.findModuleByName(moduleName);
                if (mod) callback(mod);
            }

            waitForModule("libil2cpp.so", function(il2cpp) {
                Interceptor.attach(il2cpp.base.add(ptr("0x123456")), {
                    onEnter(args) {
                        if (args[0] && !args[0].isNull()) {
                            console.log("[AGENT_STATUS] SUCCESS");
                        }
                    }
                });
            });
        """.trimIndent()

        val result = JsSyntaxValidator.validateAndSanitize(validScript)
        assertTrue(result.isValid)
        assertEquals(validScript, result.sanitizedScript)
    }

    @Test
    fun testJsSyntaxValidatorUnbalancedBrackets() {
        val invalidScript = """
            'use strict';
            const TAG = "INVALID_APP";

            function test() {
                console.log("Missing closing brace");
            // End of script
        """.trimIndent()

        val result = JsSyntaxValidator.validateAndSanitize(invalidScript)
        assertFalse(result.isValid)
        assertTrue(result.errorMessage?.contains("Unclosed opening brace") == true)
    }

    @Test
    fun testJsSyntaxValidatorDeduplicatesTag() {
        val scriptWithDuplicateTag = """
            const TAG = "FIRST_TAG";
            const TAG = "SECOND_TAG";
            console.log("Hello");
        """.trimIndent()

        val result = JsSyntaxValidator.validateAndSanitize(scriptWithDuplicateTag)
        assertTrue(result.sanitizedScript.contains("const TAG = \"FIRST_TAG\";"))
        assertTrue(result.sanitizedScript.contains("// duplicate TAG removed:"))
    }

    @Test
    fun testLineNumberedCorrectionPrompt() {
        val originalScript = """
            const TAG = "GAME";
            function foo() {
                console.log("hello");
            }
        """.trimIndent()

        val prompt = aiAssistant.buildAutonomousCorrectionPrompt(
            originalScript = originalScript,
            errorLog = "SyntaxError: Unexpected token at line 3",
            fullConsoleLogs = "Console output sample",
            logcatLogs = "Logcat sample",
            sqlAstContext = "SQL AST Context",
            targetPackage = "com.test.game",
            iteration = 1,
            maxIterations = 5
        )

        assertTrue(prompt.contains("1 | const TAG = \"GAME\";"))
        assertTrue(prompt.contains("2 | function foo() {"))
        assertTrue(prompt.contains("3 |     console.log(\"hello\");"))
        assertTrue(prompt.contains("ERROR LINE 3"))
    }
}
