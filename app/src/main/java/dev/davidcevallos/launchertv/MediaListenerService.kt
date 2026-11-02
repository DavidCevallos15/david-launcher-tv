package dev.davidcevallos.launchertv

import android.service.notification.NotificationListenerService

/**
 * No lee notificaciones: existe solo porque Android exige un "acceso a notificaciones" concedido
 * para dejar que una app vea las sesiones de medios activas (Spotify, YouTube Music...) y las controle.
 * Con el acceso concedido, el sistema lo enlaza y esto arranca el [MediaHub].
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        MediaHub.start(applicationContext)
    }
}
