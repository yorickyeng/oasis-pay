package ru.oasispay.data.protocol

import com.upokecenter.cbor.CBOREncodeOptions
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** The wire profile uses RFC 8949 length-first deterministic ordering. */
internal object Cbor {
    const val LIMIT = 16_384
    private const val MAX_DEPTH = 8
    private val decodeOptions = CBOREncodeOptions("allowduplicatekeys=false;resolvereferences=false;allowempty=false")

    fun encode(value: Any): ByteArray = toObject(value, 0).EncodeToBytes().also {
        require(it.size <= LIMIT) { "Сообщение превышает допустимый размер" }
    }

    fun decode(raw: ByteArray): Any {
        require(raw.size in 1..LIMIT) { "Недопустимый размер сообщения" }
        val value = try {
            fromObject(CBORObject.DecodeFromBytes(raw, decodeOptions), 0)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Некорректное сообщение CBOR", error)
        }
        require(encode(value).contentEquals(raw)) { "Требуется детерминированный CBOR" }
        return value
    }

    private fun toObject(value: Any, depth: Int): CBORObject {
        require(depth <= MAX_DEPTH)
        return when (value) {
            is Boolean -> CBORObject.FromObject(value)
            is Int -> CBORObject.FromObject(value.toLong())
            is Long -> CBORObject.FromObject(value)
            is ByteArray -> {
                require(value.size <= LIMIT)
                CBORObject.FromObject(value)
            }
            is String -> {
                require(value.length <= LIMIT)
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
                CBORObject.FromObject(value)
            }
            is List<*> -> {
                require(value.size <= 64)
                CBORObject.NewArray().also { array ->
                    for (item in value) array.Add(toObject(requireNotNull(item), depth + 1))
                }
            }
            is Map<*, *> -> {
                require(value.size <= 32)
                val entries = value.entries.map { entry ->
                    val key = requireNotNull(entry.key)
                    require(key is String || key is Int || key is Long)
                    val encoded = toObject(key, depth + 1)
                    Triple(encoded.EncodeToBytes(), encoded, toObject(requireNotNull(entry.value), depth + 1))
                }.sortedWith { first, second -> compare(first.first, second.first) }
                require(entries.zipWithNext().none { (a, b) -> a.first.contentEquals(b.first) })
                CBORObject.NewOrderedMap().also { map ->
                    for ((_, key, item) in entries) map.Add(key, item)
                }
            }
            else -> error("Тип CBOR не поддерживается")
        }
    }

    private fun fromObject(value: CBORObject, depth: Int): Any {
        require(depth <= MAX_DEPTH && !value.isTagged)
        return when (value.type) {
            CBORType.Integer -> value.AsInt64Value()
            CBORType.Boolean -> value.AsBoolean()
            CBORType.ByteString -> value.GetByteString()
            CBORType.TextString -> value.AsString()
            CBORType.Array -> {
                require(value.size() <= 64)
                List(value.size()) { fromObject(value[it], depth + 1) }
            }
            CBORType.Map -> {
                require(value.size() <= 32)
                buildMap {
                    for ((keyObject, item) in value.entries) {
                        val key = fromObject(keyObject, depth + 1)
                        require(key is String || key is Long)
                        require(!containsKey(key))
                        put(key, fromObject(item, depth + 1))
                    }
                }
            }
            else -> error("Тип CBOR не поддерживается")
        }
    }

    private fun compare(first: ByteArray, second: ByteArray): Int {
        if (first.size != second.size) return first.size.compareTo(second.size)
        for (index in first.indices) {
            val difference = (first[index].toInt() and 255) - (second[index].toInt() and 255)
            if (difference != 0) return difference
        }
        return 0
    }
}

internal typealias Fields = Map<String, Any>

internal fun Any.fields(): Fields {
    val map = this as? Map<*, *> ?: error("Ожидался объект CBOR")
    require(map.keys.all { it is String })
    return map.entries.associate { (key, value) -> (key as String) to requireNotNull(value) }
}

internal fun Fields.text(name: String) = get(name) as? String ?: error("Ожидалось текстовое поле")
internal fun Fields.bytes(name: String) = get(name) as? ByteArray ?: error("Ожидалось байтовое поле")
internal fun Fields.number(name: String) = get(name) as? Long ?: error("Ожидалось целое число")
