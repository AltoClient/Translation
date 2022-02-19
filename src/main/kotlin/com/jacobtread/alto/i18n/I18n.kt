package com.jacobtread.alto.i18n

import com.jacobtread.alto.logger.Logger
import com.jacobtread.alto.utils.Identifier
import com.jacobtread.alto.utils.resource.ResourceManager
import com.jacobtread.alto.utils.resource.ResourcePack
import com.jacobtread.alto.utils.resource.ResourceReloadListener
import com.jacobtread.alto.utils.resource.data.Language
import com.jacobtread.alto.utils.resource.meta.LanguageMetadataSection
import com.jacobtread.alto.utils.resource.meta.MetadataSerializer
import java.io.IOException
import java.util.*
import kotlin.math.min

object I18n : ResourceReloadListener {

    const val DEFAULT_LANGUAGE = "en_US"

    private val FIXER = Regex("%(\\d+\\$)?[\\d.]*[df]")
    private val LOGGER = Logger.get()

    private val languageByCode = HashMap<String, Language>()

    var currentLanguageCode = DEFAULT_LANGUAGE
    private val currentLanguage: Language get() = languageByCode[currentLanguageCode] ?: languageByCode[DEFAULT_LANGUAGE]!!

    val isBidirectional: Boolean get() = currentLanguage.isBidirectional

    lateinit var resourceManager: ResourceManager

    fun init(resourceManager: ResourceManager, defaultLanguage: String) {
        this.resourceManager = resourceManager
        currentLanguageCode = defaultLanguage
        resourceManager.registerReloadListener(this)
    }

    override fun onResourceReload(resourceManager: ResourceManager) {
        val languages = mutableListOf(DEFAULT_LANGUAGE)
        if (currentLanguageCode != DEFAULT_LANGUAGE) {
            languages.add(currentLanguageCode)
        }
        loadFiles(resourceManager, languages)
    }

    fun parseLanguageMeta(metadataSerializer: MetadataSerializer, resourcePacks: List<ResourcePack>) {
        languageByCode.clear()
        resourcePacks.forEach {
            try {
                val languageMeta = it.getPackMetadata<LanguageMetadataSection?>(metadataSerializer, "language")
                languageMeta?.languages?.forEach { lang -> languageByCode.putIfAbsent(lang.code, lang) }
            } catch (e: RuntimeException) {
                LOGGER.warn("Unable to parse metadata section of resource pack '${it.packName}'", e)
            } catch (e: IOException) {
                LOGGER.warn("Unable to parse metadata section of resource pack '${it.packName}'", e)
            }
        }
    }

    fun getLanguages(): TreeSet<Language> = TreeSet(languageByCode.values)


    private val translations = HashMap<String, String>()
    private val fallback = HashMap<String, String>()
    var isUnicode = false
        private set

    /**
     * checkUnicode Checks if over 10% of all the translations contain a
     * unicode character, if they do then this language is to be considered
     * a unicode language
     */
    private fun checkUnicode() {
        var unicodeCount = 0
        var totalLength = 0
        translations.values.forEach {
            val length = it.length
            totalLength += length
            for (i in 0 until length) {
                if (it[i].code >= 256) unicodeCount++
            }
        }
        val amount = unicodeCount.toDouble() / totalLength.toDouble()
        isUnicode = amount > 0.1
    }

    /**
     * loadFiles Loads the language files for all the provided [languages].
     * Loads the language files from both internal resource domains and all
     * other present resource domains
     *
     * @param resourceManager
     * @param languages
     */
    @Synchronized
    fun loadFiles(resourceManager: ResourceManager, languages: List<String>) {
        translations.clear()
        languages.forEach { lang ->
            val name = "lang/$lang.lang"
            resourceManager.resourceDomains.forEach { domain ->
                try {
                    val resources = resourceManager.getAllResources(Identifier(domain, name))
                    resources.forEach {
                        it.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                            if (line.isNotEmpty() && !line.startsWith('#')) {
                                val parts = line.split('=', limit = 2)
                                if (parts.size == 2) {
                                    val key = parts[0]
                                    val value = FIXER.replace(parts[1], "%$1s")
                                    translations[key] = value
                                }
                            }
                        }
                    }
                } catch (_: IOException) {
                }
            }
        }
        checkUnicode()
    }

    /**
     * translate Translates the provided key by performing a lookup in
     * [translations] if the lookup fails and there is no value with
     * the provided [key] the [key] will be returned instead
     *
     * @param key The translation key to lookup
     * @return The translated value or the [key] if there is none
     */
    @JvmStatic
    fun translate(key: String): String = translations.getOrDefault(key, key)

    /**
     * translateWithFallback Here for any translation text that the server sends.
     * This needs to be used in case of mismatches between server and client
     * translations
     *
     * @param key
     * @return
     */
    fun translateWithFallback(key: String): String {
        if (translations.containsKey(key)) return translations[key]!!
        if (fallback.containsKey(key)) return fallback[key]!!
        val fallbackIdentifier = Identifier("alto", "lang/fallback.lang")
        val resource = resourceManager.getResource(fallbackIdentifier)
        resource.inputStream.bufferedReader().use {
            var line: String?
            do {
                line = it.readLine()
                if (line != null && line.startsWith(key)) {
                    val parts = line.split('=', limit = 2)
                    if (parts.size == 2) {
                        val value = FIXER.replace(parts[1], "%$1s")
                        fallback[key] = value
                        return value
                    }
                }
            } while (line != null)
        }
        return key
    }

    /**
     * hasKey Checks if the provided [key] is present within the
     * translations' lookup map and returns the result
     *
     * @param key The key to search for
     * @return Whether the key is in [translations]
     */
    @JvmStatic
    fun hasKey(key: String): Boolean = translations.containsKey(key)

    /**
     * format Translates and formats the provided [key] using
     * the provided [args] and [String.format] will return
     * "Format error: [key]" on format failure
     *
     * @param key The translation key
     * @param args The translation arguments
     * @return The resulting translation
     */
    @JvmStatic
    fun format(key: String, vararg args: Any?): String {
        val translated = translate(key)
        return try {
            String.format(translated, *args)
        } catch (e: IllegalFormatException) {
            e.printStackTrace()
            "Format error: $translated"
        }
    }

    fun formatWithFallback(key: String, vararg args: Any?): String {
        val translated = translateWithFallback(key) ?: ""
        return try {
            String.format(translated, *args)
        } catch (e: IllegalFormatException) {
            e.printStackTrace()
            "Format error: $translated"
        }
    }
}

/**
 * i18n Shorter way to access translations through a string
 * extension usage: "key.example".i18n()
 *
 * @see I18n.translate The underlying function
 * @return The translated value
 */
@Suppress("NOTHING_TO_INLINE") // Inline this function call because its direct anyway
inline fun String.i18n(): String = I18n.translate(this)

/**
 * i18n Shorter way to access translations through a string
 * extension usage: "key.example".i18n("Example", "Test")
 * this function runs the String format aswell as translate
 *
 * @see I18n.format The underlying function
 * @return The translated value
 */
@Suppress("NOTHING_TO_INLINE") // Inline this function call because its direct anyway
inline fun String.i18n(vararg parameters: Any): String = I18n.format(this, *parameters)


