package ox.fzer0x.snakeloader

import org.junit.Test
import org.junit.Assert.*
import java.lang.reflect.Method

class ScriptMetadataTest {

    @Test
    fun testMetadataExtraction() {
        val scriptContent = """
            
            console.log("Hello from snake hack");
        """.trimIndent()

        val scriptManager = ScriptManager(MockContext())
        val method: Method = ScriptManager::class.java.getDeclaredMethod("extractMetadata", String::class.java)
        method.isAccessible = true
        
        val metadata = method.invoke(scriptManager, scriptContent) as ModuleMetadata
        
        assertEquals("1.2.3", metadata.version)
        assertEquals("fzer0x", metadata.author)
        assertEquals(listOf("com.snake.game", "com.snake.game.pro"), metadata.targetPackages)
    }

    @Test
    fun testMetadataExtractionWithDifferentCommentStyles() {
        val scriptContent = """
            /*
             * @version 2.0
             * @author snake_dev
             * @target com.another.game
             */
            
            'use strict';
        """.trimIndent()

        val scriptManager = ScriptManager(MockContext())
        val method: Method = ScriptManager::class.java.getDeclaredMethod("extractMetadata", String::class.java)
        method.isAccessible = true
        
        val metadata = method.invoke(scriptManager, scriptContent) as ModuleMetadata
        
        assertEquals("2.0", metadata.version)
        assertEquals("snake_dev", metadata.author)
        assertEquals(listOf("com.another.game"), metadata.targetPackages)
    }

    class MockContext : android.content.ContextWrapper(null) {
        override fun getFilesDir(): java.io.File {
            return java.io.File("/tmp/reshift_test")
        }
    }
}
