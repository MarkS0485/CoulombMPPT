package app.coulombmppt

import android.app.Application
import app.coulombmppt.data.log.CrashHandler
import app.coulombmppt.di.ServiceLocator

class CoulombMpptApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // AppLogger is up — wire the uncaught-exception handler so fatals
        // land in the per-launch log file before the process dies.
        CrashHandler.install()
        // We deliberately do NOT start the foreground polling service here.
        // The launch chooser starts it once the user picks a connection mode,
        // so we never open a Bluetooth link before that choice is made (in
        // remote mode there is no local BLE link at all). If the OS later kills
        // a running service, START_STICKY restarts it and the service re-derives
        // the source kind from the persisted mode in onStartCommand.
    }
}
