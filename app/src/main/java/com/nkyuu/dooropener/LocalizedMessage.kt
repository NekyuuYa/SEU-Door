package com.nkyuu.dooropener

import android.content.Context
import java.io.IOException

interface LocalizedMessage {
    fun resolveMessage(context: Context): String
}

interface TextValue {
    fun resolve(context: Context): String
}

data class ResText(
    val resId: Int,
    val formatArgs: List<Any> = emptyList()
) : TextValue {
    override fun resolve(context: Context): String {
        val resolvedArgs = formatArgs.map { arg ->
            when (arg) {
                is ResText -> arg.resolve(context)
                is RawText -> arg.resolve(context)
                else -> arg
            }
        }.toTypedArray()
        return context.getString(resId, *resolvedArgs)
    }
}

data class RawText(val value: String, val formatArgs: List<Any> = emptyList()) : TextValue {
    override fun resolve(context: Context): String {
        if (formatArgs.isEmpty()) return value
        val resolvedArgs = formatArgs.map { arg ->
            when (arg) {
                is ResText -> arg.resolve(context)
                is RawText -> arg.resolve(context)
                else -> arg
            }
        }.toTypedArray()
        return String.format(value, *resolvedArgs)
    }
}

fun resText(resId: Int, vararg formatArgs: Any): ResText {
    return ResText(resId, formatArgs.toList())
}

fun rawText(value: String, vararg formatArgs: Any): RawText {
    return RawText(value, formatArgs.toList())
}

open class LocalizedIOException(
    private val text: TextValue,
    cause: Throwable? = null
) : IOException(null, cause), LocalizedMessage {

    override fun resolveMessage(context: Context): String {
        return text.resolve(context)
    }

    override val message: String?
        get() = null
}
