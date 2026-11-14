package com.universalremote.app.connection

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal bounded protobuf wire codec for the Android TV Remote v2 messages
 * used by this app. Only varint and length-delimited fields are required.
 */
object RemoteWire {
    const val MAX_FRAME_SIZE = 1_048_576

    data class Field(
        val number: Int,
        val value: Long = 0,
        val bytes: ByteArray? = null
    )

    fun varint(value: Long): ByteArray {
        require(value >= 0) { "Protobuf varint must be non-negative" }
        var number = value
        val output = ByteArrayOutputStream()
        while (number > 127) {
            output.write(((number and 127) or 128).toInt())
            number = number ushr 7
        }
        output.write(number.toInt())
        return output.toByteArray()
    }

    fun int(field: Int, value: Int): ByteArray {
        require(field > 0)
        require(value >= 0)
        return varint((field * 8).toLong()) + varint(value.toLong())
    }

    fun bytes(field: Int, value: ByteArray): ByteArray {
        require(field > 0)
        return varint((field * 8 + 2).toLong()) + varint(value.size.toLong()) + value
    }

    fun string(field: Int, value: String): ByteArray =
        bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(vararg fields: ByteArray): ByteArray =
        fields.fold(ByteArray(0)) { result, field -> result + field }

    fun readVarint(input: InputStream): Long {
        var value = 0L
        for (index in 0..9) {
            val byte = input.read()
            require(byte >= 0) { "Соединение закрыто" }
            require(index < 9 || byte <= 1) { "Неверный protobuf varint" }
            value = value or ((byte and 127).toLong() shl (7 * index))
            if (byte and 128 == 0) return value
        }
        error("Неверный protobuf varint")
    }

    fun parse(bytes: ByteArray): List<Field> {
        val input = ByteArrayInputStream(bytes)
        val fields = mutableListOf<Field>()
        while (input.available() > 0) {
            val tag = readVarint(input).toInt()
            val number = tag ushr 3
            require(number > 0) { "Некорректный protobuf-тег" }
            when (tag and 7) {
                0 -> fields += Field(number, readVarint(input))
                2 -> {
                    val length = readVarint(input)
                    require(length in 0..MAX_FRAME_SIZE && length <= input.available()) {
                        "Некорректная длина protobuf-поля"
                    }
                    val data = ByteArray(length.toInt())
                    require(input.read(data) == data.size) { "Соединение закрыто" }
                    fields += Field(number, bytes = data)
                }
                1 -> require(input.skip(8) == 8L) { "Соединение закрыто" }
                5 -> require(input.skip(4) == 4L) { "Соединение закрыто" }
                else -> error("Неподдерживаемый protobuf wire type")
            }
        }
        return fields
    }

    fun frame(input: InputStream): ByteArray {
        val length = readVarint(input)
        require(length in 1..MAX_FRAME_SIZE) { "Некорректный размер сообщения" }
        val data = ByteArray(length.toInt())
        var offset = 0
        while (offset < data.size) {
            val read = input.read(data, offset, data.size - offset)
            require(read > 0) { "Соединение закрыто" }
            offset += read
        }
        return data
    }

    fun write(output: OutputStream, bytes: ByteArray) {
        output.write(varint(bytes.size.toLong()))
        output.write(bytes)
        output.flush()
    }

    /** RemoteMessage.field 10: a remote key event. Direction 3 is a short press. */
    fun key(keyCode: Int, direction: Int = KEY_SHORT): ByteArray {
        require(keyCode in 0..300)
        require(direction in 1..3)
        return bytes(10, message(int(1, keyCode), int(2, direction)))
    }

    /** RemoteMessage.field 90: launch an app through its market URI. */
    fun appLink(packageName: String): ByteArray {
        require(packageName.length <= 255)
        require(packageName.matches(PACKAGE_PATTERN))
        return bytes(90, string(1, "market://launch?id=$packageName"))
    }

    const val KEY_SHORT = 3
    const val KEY_DOWN = 1
    const val KEY_UP = 2

    private val PACKAGE_PATTERN =
        Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
}

fun List<RemoteWire.Field>.num(field: Int): Int =
    firstOrNull { it.number == field }?.value?.toInt() ?: 0

fun List<RemoteWire.Field>.nested(field: Int): List<RemoteWire.Field> =
    firstOrNull { it.number == field }?.bytes?.let(RemoteWire::parse) ?: emptyList()
