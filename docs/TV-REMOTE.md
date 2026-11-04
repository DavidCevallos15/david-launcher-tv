# TV Remote

[← Volver al README](../README.md)

Requiere un celular con **Android 8.0+**. [Descargar el APK](https://github.com/DavidCevallos15/david-launcher-tv/releases/download/v0.5.0-preview.1/tv-remote.apk) e instalarlo en el celular, no en el televisor.


Control remoto para el celular (Android) en `remote/`. Habla el protocolo oficial **Android TV Remote v2** (el de la app Google TV), y requiere un Android TV / Google TV con Android TV Remote Service compatible. Su funcionamiento depende del firmware; no necesita ADB ni root para el control.

| Función | Cómo |
|---|---|
| Navegación | Clickpad estilo Siri Remote: desliza para moverte, toca para OK, toca un borde para una flecha, mantén para OK largo |
| Teclado | Escribe en el celular y se inserta en el TV. Si el TV anuncia su campo por el protocolo IME, entra con tildes, ñ y emojis; si no (algunos TCL), se teclea carácter a carácter sin tildes |
| Escribir por voz | Mantén el **mic azul**, habla (ves la transcripción en vivo) y suelta: se escribe en el campo enfocado del TV. En el panel de teclado, el mic dicta al campo del celular para revisar antes de enviar |
| Asistente del TV | Mantén el chip **Asistente TV**: el audio va directo al Asistente/búsqueda del TV, como el mic del control físico (si el TV lo soporta) |
| Volumen | Botones en pantalla (mantener repite) y **las teclas de volumen del celular** |
| Accesos | Netflix, YouTube, Disney+, Spotify, Entrada, Ajustes |

**Primera vez:** conecta el celular y el TV a la misma red, abre la app → elige tu TV (se descubre por mDNS) o escribe su IP → ingresa el código de 6 caracteres que aparece en el TV.

**Si no escribe en el TV:** el texto con tildes (IME) depende del teclado y del servicio del TV. El proyecto recomienda **Gboard para Android TV**; varios TCL traen su propio teclado. Cámbialo en el TV (Ajustes → Preferencias del dispositivo → Teclado → Teclado actual → Gboard) o por ADB:

```bash
adb shell ime list -s                      # ver los teclados instalados
adb shell ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
```

Sin cambiar de teclado, en el panel de teclado toca **Modo** para usar *Teclas* (letra por letra, sin tildes).

**Diagnóstico:** mantén presionado el nombre del TV en la app para ver qué funciones anuncia el TV y cada mensaje enviado/recibido; el botón *Copiar* lo deja listo para compartir.

**Si falla el emparejamiento:** toca el estado en la app para ver el error completo. Si persiste, en el TV: Ajustes → Apps → Apps del sistema → *Android TV Remote Service* → Borrar datos, y empareja de nuevo.

**Emparejamiento y almacenamiento:** TLS mutuo con una identidad RSA-2048 propia del celular, guardada cifrada (AES-256-GCM con llave del AndroidKeyStore) en almacenamiento excluido de backups. El PIN amarra criptográficamente ambos certificados (anti-MITM) y después se fija el certificado del TV (TOFU): si cambia, la app pide emparejar de nuevo.


## Si el TV no aparece

Comprueba que ambos dispositivos están en la misma red, sin aislamiento de clientes o red de invitados. Prueba la IP manual y verifica que Android TV Remote Service esté habilitado en el TV. El descubrimiento usa mDNS. No se garantiza compatibilidad con Fire TV ni con televisores sin este servicio.

El dictado utiliza el servicio de reconocimiento de voz del celular y puede necesitar Internet. Concede el micrófono cuando la app lo solicite. Los accesos a aplicaciones dependen de que estén instaladas y de las funciones anunciadas por el TV.

[Pruebas del protocolo y desarrollo](DEVELOPMENT.md#pruebas).
