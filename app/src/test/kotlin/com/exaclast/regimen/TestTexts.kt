package com.exaclast.regimen

import com.exaclast.regimen.model.Texts
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * English [Texts] for JVM tests: it reads `res/values/strings.xml` and finds each string by the name of
 * its generated `R` field, so tests assert on the real English text without needing Android resources.
 */
object TestTexts : Texts {
  override val locale: Locale = Locale.ENGLISH

  private val stringNames: Map<Int, String> = namesOf(R.string::class.java)
  private val pluralNames: Map<Int, String> = namesOf(R.plurals::class.java)

  private val strings = HashMap<String, String>()
  private val plurals = HashMap<String, Map<String, String>>()

  init {
    val file = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
      .map(::File).first { it.exists() }
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    val nodes = root.childNodes
    for (i in 0 until nodes.length) {
      val node = nodes.item(i) as? Element ?: continue
      when (node.tagName) {
        "string" -> strings[node.getAttribute("name")] = unescape(node.textContent)
        "plurals" -> {
          val items = node.getElementsByTagName("item")
          plurals[node.getAttribute("name")] = (0 until items.length).associate {
            val item = items.item(it) as Element
            item.getAttribute("quantity") to unescape(item.textContent)
          }
        }
      }
    }
  }

  override fun get(id: Int, vararg args: Any): String {
    val name = stringNames[id] ?: error("No string with id $id")
    return String.format(locale, strings[name] ?: error("strings.xml has no string '$name'"), *args)
  }

  override fun plural(id: Int, count: Int, vararg args: Any): String {
    val name = pluralNames[id] ?: error("No plural with id $id")
    val items = plurals[name] ?: error("strings.xml has no plural '$name'")
    val text = items[if (count == 1) "one" else "other"] ?: error("plural '$name' has no matching quantity")
    return String.format(locale, text, *args)
  }

  private fun namesOf(type: Class<*>): Map<Int, String> =
    type.fields.filter { it.type == Int::class.javaPrimitiveType }.associate { it.getInt(null) to it.name }

  private fun unescape(raw: String): String =
    raw.trim().replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
}
