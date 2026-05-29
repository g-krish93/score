package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

class CricrelayCameraViewFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        DebugTrace.log(
            "CricrelayCameraViewFactory.create",
            "creating platform view",
            "H5",
            mapOf("viewId" to viewId, "hasPluginActivity" to (StreamRtmpPlugin.activity != null)),
        )
        val act = resolveActivity(context)
            ?: run {
                DebugTrace.log("CricrelayCameraViewFactory.create", "no activity", "H5")
                throw IllegalStateException("Activity not available for camera preview")
            }
        return CricrelayCameraPlatformView(context, act)
    }

    private fun resolveActivity(context: Context): Activity? {
        StreamRtmpPlugin.activity?.let { return it }
        var ctx: Context? = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return context as? Activity
    }
}
