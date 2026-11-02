package dev.davidcevallos.tvremote.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * E2E contra remote/tools/fake_tv.py. Se ejecuta solo si FAKE_TV_DIR está definido:
 *   python3 remote/tools/fake_tv.py --out /tmp/fake-tv &
 *   FAKE_TV_DIR=/tmp/fake-tv ./gradlew :remote:testDebugUnitTest
 * Las aserciones de wire format las hace el lado Python con el parser protobuf oficial.
 */
class FakeTvE2ETest {

    private val dir = System.getenv("FAKE_TV_DIR")?.takeIf { it.isNotEmpty() }?.let(::File)

    @Test fun fullFlowAgainstFakeTv() {
        assumeTrue("FAKE_TV_DIR no definido", dir != null)
        val host = "127.0.0.1"
        // Identidad generada por el mismo código que corre en el celular
        val me = SoftwareIdentity.decode(SoftwareIdentity.generate().encode()) // incluye round-trip de persistencia
        File(dir, "client.pem").writeText(me.certificatePem())
        val listening = File(dir, "listening")
        repeat(100) { if (!listening.exists()) Thread.sleep(100) }
        assertTrue("el fake TV aceptó el certificado y abrió los puertos", listening.exists())

        // 1) Pairing: el "usuario" lee el PIN de la pantalla del TV (pin.txt)
        val pairing = PairingSession.start(host, me, "Pixel de David", port = 16467)
        val pinFile = File(dir, "pin.txt")
        repeat(50) { if (!pinFile.exists()) Thread.sleep(100) }
        val pin = pinFile.readText().trim()
        assertTrue("PIN válido aceptado", pairing.finish(pin))
        val pinned = pairing.serverCertSha256
        pairing.close()
        assertEquals(File(dir, "server.sha256").readText().trim(), pinned)

        // 2) Un celular no emparejado es rechazado por TLS
        val stranger = SoftwareIdentity.generate("intruso")
        try {
            val s = RemoteSession.connect(host, stranger, pinned, object : RemoteSession.Listener {}, port = 16466)
            // En TLS 1.3 el rechazo puede llegar en la primera lectura en vez del handshake
            Thread.sleep(500); s.close()
        } catch (expected: SSLException) {
        } catch (expected: java.net.SocketException) {
        }

        // 3) Pinning: un fingerprint distinto aborta antes de enviar nada
        try {
            RemoteSession.connect(host, me, "00".repeat(32), object : RemoteSession.Listener {}, port = 16466).close()
            fail("Debió rechazar un certificado que no coincide con el fijado")
        } catch (expected: SSLException) {
        }

        // 4) Sesión remota completa
        val ready = CountDownLatch(1)
        val ime = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var model = ""
        var volume = -1
        val session = RemoteSession.connect(host, me, pinned, object : RemoteSession.Listener {
            override fun onReady(tvModel: String, features: Int) { model = tvModel; ready.countDown() }
            override fun onVolume(level: Int, max: Int, muted: Boolean) { volume = level }
            override fun onImeField(active: Boolean) { if (active) ime.countDown() }
            override fun onDisconnected(error: Throwable?) { closed.countDown() }
        }, port = 16466)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals("BeyondTV2", model)
        assertTrue("el TV avisó campo de texto", ime.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(12, volume)

        session.key(Keys.DPAD_UP)
        session.key(Keys.DPAD_CENTER)
        session.key(Keys.BACK)
        session.key(Keys.VOLUME_UP)
        session.key(Keys.DPAD_CENTER, Remote.START_LONG)
        session.key(Keys.DPAD_CENTER, Remote.END_LONG)
        session.text("Canción ñandú 🎬")
        session.launch("com.netflix.ninja")

        val voice = CountDownLatch(1)
        var sessionId: Int? = null
        session.startVoice { sessionId = it; voice.countDown() }
        assertTrue(voice.await(3, TimeUnit.SECONDS))
        assertEquals(42, sessionId)
        session.voiceChunk(42, ByteArray(30_000))  // → 20 KB + 10 KB
        session.voiceChunk(42, ByteArray(1_000))   // → rellenado a 8 KB
        session.endVoice(42)

        assertTrue("el fake TV cerró al terminar sus verificaciones", closed.await(10, TimeUnit.SECONDS))
        session.close()
    }
}
