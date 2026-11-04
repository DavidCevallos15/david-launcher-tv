# Instalar David Launcher TV en otro televisor

[← Volver al README](../README.md)

No necesitas compilar el proyecto ni tener root. Puedes descargar el APK, copiarlo al TV e instalarlo como una aplicación. **Usarlo como pantalla de inicio predeterminada es un paso separado** y depende de las restricciones del fabricante.

## 1. Comprueba tu dispositivo

- Televisor o TV box con **Android TV / Google TV**, Android 5.0 o posterior.
- Un gestor de archivos en el TV que pueda abrir archivos APK, o un computador con ADB.
- Para transferir por red o usar ADB, conecta el computador o celular y el TV a la misma red local.

La prueba física documentada por el proyecto corresponde a un TCL BeyondTV2 con Android TV 11. No se garantiza el comportamiento en todos los modelos. Samsung con Tizen y LG con webOS no son compatibles con APK de Android. Fire TV necesita validación específica, especialmente para cambiar el inicio.

## 2. Descarga el archivo correcto

**[Página de descarga y notas de la versión](https://github.com/DavidCevallos15/david-launcher-tv/releases/tag/v0.5.0-preview.1)**

| Archivo | Dónde instalarlo |
| --- | --- |
| [david-launcher-tv.apk](https://github.com/DavidCevallos15/david-launcher-tv/releases/download/v0.5.0-preview.1/david-launcher-tv.apk) | En el televisor: es la pantalla de inicio |
| [tv-remote.apk](https://github.com/DavidCevallos15/david-launcher-tv/releases/download/v0.5.0-preview.1/tv-remote.apk) | Opcional, en un celular con Android 8.0+: es el control remoto |

Estos enlaces públicos no requieren una cuenta de GitHub. Si entras a la página de la versión, abre **Assets** y elige el `.apk`; los archivos **Source code** contienen el código, no la aplicación instalable.

La descarga inicial es una **versión de prueba con firma de depuración**. Las notas incluyen el origen de la compilación y un archivo `SHA256SUMS.txt` para comprobar la integridad. Conserva el APK si quieres reinstalar exactamente esa compilación.

## 3. Instala sin computador conectado al TV

### Mediante una memoria USB

1. Descarga `david-launcher-tv.apk` en un computador y cópialo a una memoria USB que el TV pueda leer.
2. Conecta la memoria al televisor.
3. Abre el gestor de archivos del TV y selecciona el APK.
4. Si Android lo solicita, permite **Instalar aplicaciones desconocidas** para ese gestor. El nombre y la ubicación del ajuste varían por modelo; en versiones antiguas puede aparecer como **Fuentes desconocidas**.
5. Confirma **Instalar** y después **Abrir**.

Si tu dispositivo no tiene USB o no permite abrir APK desde el gestor, usa la transferencia por red o la alternativa ADB.

### Mediante tu red local

1. Descarga el APK en tu celular o computador.
2. Transfiérelo al TV con una herramienta de envío de archivos que ya utilices y sea compatible con ambos dispositivos.
3. Abre el archivo recibido en el gestor de archivos del TV y sigue los pasos de instalación anteriores.

También puedes abrir el enlace directo en un navegador del TV que permita descargar archivos. En todos los casos se instala **el mismo APK**. No necesitas ejecutar los scripts de limpieza del repositorio.

## 4. Pruébalo antes de cambiar Home

Abre **David Launcher TV** desde la lista de aplicaciones del televisor. En algunos modelos puedes abrirlo desde **Ajustes → Apps → David Launcher TV → Abrir**.

Comprueba que puedes navegar con las flechas, abrir tus apps y entrar en **Personalizar**. Los degradados integrados funcionan sin subir fondos. El saludo de esta versión usa el nombre **David**; cambiarlo requiere [editar el código y compilar](DEVELOPMENT.md#personalizar-el-código).

Para la música, concede el acceso desde **Personalizar → Permiso para controlar la música** solo si quieres esos controles. Para el clima, elige una ciudad o utiliza la ubicación aproximada por IP.

## 5. Hacer que el botón Home abra el launcher

Primero prueba el mecanismo del propio TV:

1. Pulsa **Home**.
2. Si aparece un selector de aplicación de inicio, elige **David Launcher TV** y **Siempre**.
3. Si el TV tiene un ajuste de **Aplicaciones predeterminadas → Aplicación de inicio**, selecciónalo allí.

Algunos equipos no muestran estas opciones o fuerzan su launcher de fábrica. Puedes intentar la configuración por ADB que se explica abajo. Si el fabricante la bloquea, puedes seguir abriendo David Launcher TV como una app; no hay un cambio universal de Home para todos los modelos.

## Alternativa: instalar desde un computador con ADB

Descarga las [Android SDK Platform-Tools oficiales](https://developer.android.com/tools/releases/platform-tools) y extrae el archivo. No hace falta instalar Android Studio completo. Las opciones de conexión se describen en la [documentación oficial de ADB](https://developer.android.com/tools/adb).

### Preparar la conexión

1. En el TV, busca **Ajustes → Sistema / Preferencias del dispositivo → Información** y pulsa repetidamente **Compilación** hasta habilitar las opciones de desarrollador, si tu firmware lo permite.
2. Activa la opción de depuración disponible en tu TV. Puede llamarse **Depuración USB**, **Depuración de red** o **Depuración inalámbrica**.
3. Consulta la IP del TV en los ajustes de red. Los ejemplos usan `192.168.1.50`: sustitúyela por la real.

**Equipos con ADB de red en el puerto 5555:**

```sh
adb connect 192.168.1.50:5555
adb devices
```

Acepta en el TV la autorización del computador. El estado debe ser `device`. Activar depuración USB no garantiza que todos los televisores abran el puerto 5555.

**Si el TV ofrece depuración inalámbrica con código de emparejamiento:** usa la dirección y el puerto que indique cada pantalla. El puerto de emparejamiento y el de conexión pueden ser diferentes.

```sh
adb pair IP_DEL_TV:PUERTO_DE_EMPAREJAMIENTO
adb connect IP_DEL_TV:PUERTO_DE_CONEXION
adb devices
```

No escribas los nombres de ejemplo literalmente. Introduce el código cuando ADB lo solicite. Si tu TV no ofrece ninguno de estos métodos, utiliza la instalación por archivos o consulta las instrucciones del fabricante.

### Instalar y abrir

Con ADB disponible en la terminal y el APK en el directorio actual:

```sh
adb install -r david-launcher-tv.apk
adb shell am start -n dev.davidcevallos.launchertv/.MainActivity
```

En PowerShell, si abriste la terminal dentro de `platform-tools` y ADB no está en el PATH, usa `./adb.exe` en lugar de `adb`. Puedes pasar la ruta completa del APK entre comillas.

Si tienes varios dispositivos conectados, añade `-s IP:PUERTO` después de `adb` a los comandos dirigidos al TV. Usa la dirección que aparece en `adb devices`.

### Configurar Home por ADB, si el equipo lo permite

Antes de cambiarlo, guarda la actividad de inicio actual que devuelva:

```sh
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Si devuelve un selector del sistema en lugar del launcher de fábrica, identifica la aplicación de inicio en los ajustes del TV antes de continuar. Estos comandos pueden no estar disponibles en versiones antiguas de Android.

Luego intenta:

```sh
adb shell cmd package set-home-activity dev.davidcevallos.launchertv/.MainActivity
```

Pulsa Home y comprueba el resultado. Si falla, conserva el inicio original y abre la app manualmente. **La instalación normal no necesita deshabilitar el launcher de fábrica.**

<details>
<summary>Opción avanzada: equipos con el launcher com.google.android.tvlauncher</summary>

El script del repositorio tiene una opción `--disable-stock` que deshabilita específicamente `com.google.android.tvlauncher`. No sirve como receta genérica para Google TV, Fire TV u otros fabricantes. Úsala únicamente si verificaste que ese paquete corresponde al inicio de tu TV, David Launcher funciona y conservas una conexión ADB operativa para restaurarlo.

Desde el repositorio, en Bash y con ADB de red en el puerto 5555:

```bash
export TV_HOST=192.168.1.50
./scripts/install.sh /ruta/david-launcher-tv.apk --disable-stock
```

Para volver a habilitar ese paquete:

```sh
adb shell pm enable --user 0 com.google.android.tvlauncher
```

Después restaura la actividad Home original que guardaste. No sustituyas el paquete por otro sin identificarlo primero.

</details>

## Actualizar una instalación existente

Descarga la nueva versión e instálala sobre la anterior. Por ADB se utiliza `adb install -r`. Para conservar datos, Android requiere una firma compatible y una versión admitida por el sistema.

Si aparece `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, la firma es distinta. Los APK de depuración pueden usar claves diferentes entre compilaciones. **Desinstalar elimina las preferencias y los fondos del almacenamiento de la app**; guarda lo que necesites antes de decidir hacerlo. La firma persistente para futuras versiones estables está pendiente.

## Volver al inicio original

Si no cambiaste Home, simplemente desinstala David Launcher TV desde **Ajustes → Apps**.

Si lo configuraste como inicio predeterminado:

1. Vuelve a elegir el launcher original en los ajustes de aplicaciones predeterminadas. Si lo deshabilitaste manualmente, vuelve a habilitarlo primero.
2. Si utilizaste ADB, restaura la actividad original que guardaste:

```sh
adb shell cmd package set-home-activity PAQUETE_ORIGINAL/ACTIVIDAD_ORIGINAL
```

3. Pulsa Home y confirma que funciona el inicio de fábrica.
4. Desinstala la app desde Ajustes o con:

```sh
adb uninstall dev.davidcevallos.launchertv
```

El script `scripts/restore.sh` es específico de la configuración del autor: habilita la lista de debloat e intenta restaurar el launcher de Google. No detecta automáticamente el launcher de cualquier TV.

## Problemas frecuentes

| Problema | Qué revisar |
| --- | --- |
| El archivo no se instala | Comprueba que descargaste el APK del launcher, que terminó la descarga y que el TV ejecuta Android compatible. Revisa el mensaje del instalador. |
| ADB muestra `unauthorized` | Acepta la autorización en la pantalla del TV. |
| ADB no conecta | Revisa IP, puerto, opciones de depuración y aislamiento de la red de invitados. El puerto 5555 no está disponible en todos los modelos. |
| Home sigue mostrando el inicio de fábrica | El firmware puede restringir el cambio. Puedes abrir David Launcher TV desde Apps. |
| No aparece música | Concede acceso a notificaciones y reproduce contenido en una app con sesión multimedia compatible. |
| TV Remote no encuentra el TV | Comprueba la misma red y Android TV Remote Service; prueba la IP manual. [Más ayuda](TV-REMOTE.md). |
| El saludo dice David | Es el nombre definido en el código de esta versión; no es un error de instalación. |

¿Sigue fallando? [Abre un issue](https://github.com/DavidCevallos15/david-launcher-tv/issues/new) con modelo, versión de Android, APK usado y mensaje exacto del error.
