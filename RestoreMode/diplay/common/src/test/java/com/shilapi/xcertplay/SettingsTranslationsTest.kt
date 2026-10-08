package com.shilapi.xcertplay

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

// Lint does not check this library module's translations.
class SettingsTranslationsTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("%[0-9]+\\$[-#+ 0,(]*[0-9]*(\\.[0-9]+)?[a-zA-Z]")

    @Test
    fun everySettingsStringIsTranslatedWithTheSamePlaceholders() {
        val base = strings(File(res, "values")).filterKeys { it.startsWith("settings_") }
        val locales = res.listFiles { file ->
            file.name.matches(Regex("values-[a-z]{2}(-r[A-Z]{2})?"))
        }!!
        for (locale in locales) {
            assertTranslations(base, locale)
        }
    }

    @Test
    fun everyStringHasARussianTranslationWithTheSamePlaceholders() {
        assertTranslations(strings(File(res, "values")), File(res, "values-ru"))
        val sharedResources = File("../shared/src/main/res")
        assertTranslations(strings(File(sharedResources, "values")), File(sharedResources, "values-ru"))
    }

    private fun assertTranslations(base: Map<String, String>, locale: File) {
        val translated = strings(locale)
        assertEquals(locale.path, emptySet<String>(), base.keys - translated.keys)
        for ((name, text) in base) {
            assertEquals(
                "${locale.path}/$name",
                placeholders(text),
                placeholders(translated.getValue(name)),
            )
        }
    }

    private fun placeholders(text: String) =
        placeholder.findAll(text).map { it.value }.sorted().toList()

    private fun strings(folder: File): Map<String, String> =
        folder.listFiles { file -> file.extension == "xml" }!!.flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }.toMap()
}
