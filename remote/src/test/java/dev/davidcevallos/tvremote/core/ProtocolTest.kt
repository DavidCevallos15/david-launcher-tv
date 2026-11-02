package dev.davidcevallos.tvremote.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/**
 * Golden tests: los bytes esperados se generaron con los descriptores protobuf oficiales
 * (polo_pb2 / remotemessage_pb2), así que si coinciden, el TV los parsea igual.
 */
class ProtocolTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun pairingRequest() =
        assertArrayEquals(hex("080210c80152120a0961747672656d6f74651205506978656c"), Polo.pairingRequest("Pixel"))

    @Test fun options() = assertArrayEquals(hex("080210c801a201080a04080310061801"), Polo.options())

    @Test fun configuration() = assertArrayEquals(hex("080210c801f201080a04080310061001"), Polo.configuration())

    @Test fun secret() = assertArrayEquals(hex("080210c801c202060a0400010203"), Polo.secret(byteArrayOf(0, 1, 2, 3)))

    @Test fun keyInject() = assertArrayEquals(hex("520408131003"), Remote.key(Keys.DPAD_UP))

    @Test fun configure() = assertArrayEquals(
        hex("0a2208ef04121d0a014d12015618012201312a0961747672656d6f74653205312e302e30"),
        Remote.configure(623, "M", "V"),
    )

    @Test fun textUnicode() = assertArrayEquals(hex("aa010e080510091a08080112041a02c3b1"), Remote.text("ñ", 5, 9))

    @Test fun negativeInt32() = assertArrayEquals(hex("4a0b08ffffffffffffffffff01"), Remote.pingResponse(-1))

    @Test fun appLink() = assertArrayEquals(
        hex("d205160a146d61726b65743a2f2f6c61756e63683f69643d78"),
        Remote.appLink("market://launch?id=x"),
    )

    @Test fun parseRoundTrip() {
        val msg = ProtoMessage.parse(Remote.configure(623, "BeyondTV2", "TCL"))
        val cfg = msg.message(Remote.CONFIGURE)!!
        assertEquals(623, cfg.int(1))
        assertEquals("BeyondTV2", cfg.message(2)!!.string(1))
        assertEquals("TCL", cfg.message(2)!!.string(2))
    }

    @Test fun unsignedStripsSignByte() {
        assertArrayEquals(byteArrayOf(1, 0, 1), PairingMath.unsigned(BigInteger.valueOf(65537)))
        assertArrayEquals(byteArrayOf(0x80.toByte()), PairingMath.unsigned(BigInteger.valueOf(128)))
    }

    @Test fun onlyTheRightPinPrefixIsAccepted() {
        val kpg = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val a = kpg.generateKeyPair().public as java.security.interfaces.RSAPublicKey
        val b = kpg.generateKeyPair().public as java.security.interfaces.RSAPublicKey
        // pin[0..2] debe ser hash[0]: de 256 prefijos posibles, exactamente uno es válido
        val valid = (0..255).map { "%02XABCD".format(it) }.filter { PairingMath.secret(a, b, it) != null }
        assertEquals(1, valid.size)
    }
}
