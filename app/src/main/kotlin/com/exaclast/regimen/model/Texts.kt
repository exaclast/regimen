package com.exaclast.regimen.model

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.Locale

/**
 * User-visible text for code that isn't a composable (domain builders, helpers). It is an interface so
 * that those can be tested on the JVM, where there are no resources: the app passes [Resources.texts],
 * tests pass a fake that reads `strings.xml`.
 *
 * Callers resolve text when they need it and don't keep it: a [Texts] made from a stale [Resources]
 * would ignore a language change.
 */
interface Texts {
  /** For case changes and other locale-sensitive handling of the text it returns. */
  val locale: Locale

  fun get(@StringRes id: Int, vararg args: Any): String

  fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String

  /** An enum value's name. */
  fun label(value: Labeled): String = get(value.labelRes)
}

/**
 * Text decided where there is no [Texts] to hand (a network lookup, say) and shown later: a string
 * resource and its arguments. An argument that is itself a [Message] or a [Labeled] value is resolved
 * too. With a [quantity] it names a plural.
 */
data class Message(
  /** A string resource, or a plurals resource when [quantity] is set. */
  val id: Int,
  val args: List<Any> = emptyList(),
  val quantity: Int? = null,
) {
  constructor(id: Int, vararg args: Any) : this(id, args.toList())

  fun resolve(texts: Texts): String {
    val resolved = args.map {
      when (it) {
        is Message -> it.resolve(texts)
        is Labeled -> texts.label(it)
        else -> it
      }
    }.toTypedArray()
    return if (quantity == null) texts.get(id, *resolved) else texts.plural(id, quantity, *resolved)
  }
}

/** An exception whose message is shown to the user. */
open class MessageException(val text: Message) : Exception()

/** What to tell the user about a failure: its [Message] if it has one, else the platform's own text. */
fun Throwable.userMessage(texts: Texts): String? =
  (this as? MessageException)?.text?.resolve(texts) ?: localizedMessage

private class ResourceTexts(private val resources: Resources) : Texts {
  override val locale: Locale
    get() = resources.configuration.locales[0]

  override fun get(id: Int, vararg args: Any): String = resources.getString(id, *args)

  override fun plural(id: Int, count: Int, vararg args: Any): String =
    resources.getQuantityString(id, count, *args)
}

fun Resources.texts(): Texts = ResourceTexts(this)
