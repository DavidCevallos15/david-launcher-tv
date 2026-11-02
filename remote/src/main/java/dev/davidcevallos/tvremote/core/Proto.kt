package dev.davidcevallos.tvremote.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/**
 * Protobuf mínimo (proto3 wire format) para los ~20 mensajes que usa Android TV Remote v2.
 * Evita la dependencia de protobuf-lite y su generador: unas decenas de líneas en vez de ~1 MB.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    // proto3: los escalares en su valor por defecto (0, "", vacío) no se serializan.
    fun varint(field: Int, value: Long) = apply { if (value != 0L) { tag(field, 0); raw(value) } }
    fun int(field: Int, value: Int) = varint(field, value.toLong()) // int32 negativo → 10 bytes, como protobuf
    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1 else 0)
    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun bytes(field: Int, value: ByteArray) = apply { if (value.isNotEmpty()) lengthDelimited(field, value) }
    /** Los submensajes sí se escriben aunque estén vacíos: su presencia es la señal (p. ej. set_active). */
    fun message(field: Int, build: ProtoWriter.() -> Unit) = apply {
        lengthDelimited(field, ProtoWriter().apply(build).toByteArray())
    }

    private fun lengthDelimited(field: Int, value: ByteArray) {
        tag(field, 2); raw(value.size.toLong()); out.write(value)
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = raw(((field shl 3) or wire).toLong())
    private fun raw(value: Long) = out.write(encodeVarint(value))

    companion object {
        fun encodeVarint(value: Long): ByteArray {
            val buf = ByteArrayOutputStream(10)
            var v = value
            while (v and 0x7FL.inv() != 0L) {
                buf.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            buf.write(v.toInt())
            return buf.toByteArray()
        }

        /** Lee un varint de un stream; null si el stream terminó limpio antes del primer byte. */
        fun readVarint(input: InputStream): Long? {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                val b = input.read()
                if (b == -1) {
                    if (shift == 0) return null
                    throw EOFException("varint truncado")
                }
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            throw IllegalStateException("varint demasiado largo")
        }
    }
}

/** Mensaje decodificado: número de campo → valores (Long para varint/fixed, ByteArray para length-delimited). */
class ProtoMessage private constructor(private val fields: Map<Int, List<Any>>) {

    fun has(field: Int) = fields.containsKey(field)
    fun long(field: Int): Long = (fields[field]?.last() as? Long) ?: 0L
    fun int(field: Int): Int = long(field).toInt()
    fun bool(field: Int): Boolean = long(field) != 0L
    fun bytes(field: Int): ByteArray = (fields[field]?.last() as? ByteArray) ?: ByteArray(0)
    fun string(field: Int): String = bytes(field).toString(Charsets.UTF_8)
    fun message(field: Int): ProtoMessage? = (fields[field]?.last() as? ByteArray)?.let { parse(it) }

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            var pos = 0

            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    require(pos < data.size) { "varint truncado" }
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    require(shift < 64) { "varint demasiado largo" }
                }
            }

            fun fixed(n: Int): Long {
                require(pos + n <= data.size) { "fixed truncado" }
                var r = 0L
                for (i in 0 until n) r = r or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                pos += n
                return r
            }

            while (pos < data.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                val value: Any = when ((key and 7).toInt()) {
                    0 -> varint()
                    1 -> fixed(8)
                    2 -> {
                        val len = varint().toInt()
                        require(len >= 0 && pos + len <= data.size) { "longitud inválida" }
                        data.copyOfRange(pos, pos + len).also { pos += len }
                    }
                    5 -> fixed(4)
                    else -> throw IllegalArgumentException("wire type no soportado")
                }
                fields.getOrPut(field) { ArrayList(1) }.add(value)
            }
            return ProtoMessage(fields)
        }
    }
}
