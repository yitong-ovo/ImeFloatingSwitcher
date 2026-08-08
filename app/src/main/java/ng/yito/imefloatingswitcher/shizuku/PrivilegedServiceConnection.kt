package ng.yito.imefloatingswitcher.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder

class PrivilegedServiceConnection(
    private val onConnected: (IPrivilegedImeService) -> Unit,
    private val onDisconnected: () -> Unit
) : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val api = service?.let { IPrivilegedImeService.Stub.asInterface(it) }
        if (api == null) onDisconnected() else onConnected(api)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        onDisconnected()
    }
}
