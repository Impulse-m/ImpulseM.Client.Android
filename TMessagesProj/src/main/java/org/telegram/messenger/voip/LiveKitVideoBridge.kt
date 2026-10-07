package org.telegram.messenger.voip

import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import org.telegram.messenger.AndroidUtilities
import org.webrtc.JavaI420Buffer
import java.util.concurrent.atomic.AtomicBoolean


/** Retains the planar buffer while the existing UI renders a shaded WebRTC frame. */
internal class LiveKitVideoBridge(
    private val sink: org.webrtc.VideoSink,
    private val firstFrame: (() -> Unit)? = null
) : VideoSink {
    private val received: AtomicBoolean = AtomicBoolean(false)


    override fun onFrame(frame: VideoFrame) {
        val planar: VideoFrame.I420Buffer = frame.buffer.toI420() ?: return
        val buffer: JavaI420Buffer = JavaI420Buffer.wrap(
            planar.width, planar.height,
            planar.dataY, planar.strideY,
            planar.dataU, planar.strideU,
            planar.dataV, planar.strideV,
            planar::release
        )
        val output: org.webrtc.VideoFrame = org.webrtc.VideoFrame(buffer, frame.rotation, frame.timestampNs)
        try {
            sink.onFrame(output)
            if (received.compareAndSet(false, true)) {
                firstFrame?.let { callback ->
                    AndroidUtilities.runOnUIThread {
                        callback()
                    }
                }
            }
        } finally {
            output.release()
        }
    }
}
