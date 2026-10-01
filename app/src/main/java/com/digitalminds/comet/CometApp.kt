package com.digitalminds.comet

import android.app.Application
import com.digitalminds.comet.data.Playlists
import com.digitalminds.comet.util.LastSession
import com.digitalminds.comet.util.PositionStore
import com.digitalminds.comet.util.Thumbs

class CometApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PositionStore.init(this)
        Thumbs.init(this)
        Playlists.init(this)
        LastSession.init(this)
    }
}
