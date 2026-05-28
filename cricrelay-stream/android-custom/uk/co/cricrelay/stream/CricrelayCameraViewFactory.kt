package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Context
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class CricrelayCameraViewFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val act = StreamRtmpPlugin.activity
            ?: (context as? Activity)
            ?: throw IllegalStateException("Activity not available for camera preview")
        return CricrelayCameraPlatformView(context, act)
    }
}
