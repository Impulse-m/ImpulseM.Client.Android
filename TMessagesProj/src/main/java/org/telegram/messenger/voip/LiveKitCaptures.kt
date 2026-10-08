package org.telegram.messenger.voip

import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.room.Room
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import livekit.org.webrtc.CameraVideoCapturer
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.tgnet.impulse.ImpulseConnection
import org.webrtc.VideoSink
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


/** UI-thread-owned previews. A call adopts the preview room before joining it. */
object LiveKitCaptures {
    internal class Capture(
        val room: Room,
        val sink: VideoSink,
        var type: Int,
        val track: LocalVideoTrack,
        var ownsRoom: Boolean,
        var active: Boolean = true
    )

    private val nextId: AtomicLong = AtomicLong(1)
    private val captures: MutableMap<Long, Capture> = mutableMapOf()
    private val proxyClients: MutableMap<Room, LiveKitProxyClient> = ConcurrentHashMap()


    @JvmStatic
    fun createVideoCapturer(sink: VideoSink, type: Int): Long {
        val room: Room = createRoom()
        try {
            return create(room, sink, type, true)
        } catch (exception: Exception) {
            closeForwarders(room)
            room.release()
            throw exception
        }
    }


    @JvmStatic
    fun destroyVideoCapturer(handle: Long) {
        val capture: Capture = captures.remove(handle) ?: return
        capture.room.localParticipant.unpublishTrack(capture.track, false)
        if (!capture.track.rtcTrack.isDisposed) {
            capture.track.stop()
            capture.track.dispose()
        }
        if (capture.ownsRoom) {
            closeForwarders(capture.room)
            capture.room.release()
        }
    }


    @JvmStatic
    fun setVideoStateCapturer(handle: Long, state: Int) {
        val capture: Capture = captures[handle] ?: return
        val active: Boolean = state == Instance.VIDEO_STATE_ACTIVE
        if (capture.active == active) {
            return
        }
        capture.active = active
        capture.track.enabled = active
        // A MediaProjection consent token cannot be reused after stopping capture.
        if (capture.type != 2) {
            if (active) {
                capture.track.startCapture()
            } else {
                capture.track.stopCapture()
            }
        }
    }


    @JvmStatic
    fun switchCameraCapturer(handle: Long, front: Boolean) {
        val capture: Capture = captures[handle] ?: return
        val capturer: CameraVideoCapturer? = capture.track.capturer as? CameraVideoCapturer
        if (capturer == null || (capture.type == 1) == front) {
            VoIPService.getSharedInstance()?.setSwitchingCamera(false, capture.type == 1)
            return
        }
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                AndroidUtilities.runOnUIThread {
                    capture.type = if (isFrontCamera) 1 else 0
                    VoIPService.getSharedInstance()?.setSwitchingCamera(false, isFrontCamera)
                }
            }


            override fun onCameraSwitchError(errorDescription: String?) {
                AndroidUtilities.runOnUIThread {
                    VoIPService.getSharedInstance()?.setSwitchingCamera(false, capture.type == 1)
                }
            }
        })
    }


    internal fun createRoom(): Room {
        // The tunnel decision is made once per room; a room keeps it for its whole life.
        val proxyClient: LiveKitProxyClient? = if (LiveKitProxyClient.shouldTunnel()) {
            LiveKitProxyClient(ImpulseConnection.httpClient())
        } else {
            null
        }
        val room: Room = LiveKit.create(
            ApplicationLoader.applicationContext,
            overrides = LiveKitOverrides(
                okHttpClient = proxyClient,
                audioOptions = AudioOptions(audioHandler = NoAudioHandler())
            )
        )
        if (proxyClient != null) {
            proxyClients[room] = proxyClient
        }
        return room
    }


    /** True when this room was created for a call routed through the VLESS tunnel. */
    internal fun isTunnelled(room: Room): Boolean {
        return proxyClients.containsKey(room)
    }


    /** Stops the TURN forwarders of a room that is being released. */
    internal fun closeForwarders(room: Room) {
        proxyClients.remove(room)?.closeForwarders()
    }


    internal fun get(handle: Long): Capture? {
        return captures[handle]
    }


    internal fun adopt(handle: Long): Room? {
        val capture: Capture = captures[handle] ?: return null
        capture.ownsRoom = false
        return capture.room
    }


    internal fun attach(room: Room, handle: Long): Long {
        val capture: Capture = captures[handle] ?: return 0
        if (capture.room === room) {
            capture.ownsRoom = false
            return handle
        }
        check(capture.type != 2) {
            "Screen capture must keep its original room"
        }
        val sink: VideoSink = capture.sink
        val type: Int = capture.type
        val active: Boolean = capture.active
        destroyVideoCapturer(handle)
        // Keep the handle stored in VoIPService valid after preview ownership changes.
        val replacement: Long = create(room, sink, type, false)
        captures[handle] = captures.remove(replacement)!!
        setVideoStateCapturer(handle, if (active) Instance.VIDEO_STATE_ACTIVE else Instance.VIDEO_STATE_INACTIVE)
        return handle
    }


    internal fun create(room: Room, sink: VideoSink, type: Int, ownsRoom: Boolean = false): Long {
        val options: LocalVideoTrackOptions = LocalVideoTrackOptions(
            isScreencast = type == 2,
            position = if (type == 1) CameraPosition.FRONT else CameraPosition.BACK
        )
        val track: LocalVideoTrack = if (type == 2) {
            room.localParticipant.createScreencastTrack(
                mediaProjectionPermissionResultData = checkNotNull(VideoCapturerDevice.mediaProjectionPermissionResultData),
                options = options,
                onStop = { stoppedTrack ->
                    AndroidUtilities.runOnUIThread {
                        val stillOwned: Boolean = captures.values.any { item ->
                            item.track === stoppedTrack
                        }
                        if (stillOwned) {
                            VoIPService.getSharedInstance()?.stopScreenCapture()
                        }
                    }
                }
            )
        } else {
            room.localParticipant.createVideoTrack(options = options)
        }
        val bridge: LiveKitVideoBridge = LiveKitVideoBridge(sink) {
            VoIPService.getSharedInstance()?.onCameraFirstFrameAvailable()
        }
        track.addRenderer(bridge)
        try {
            track.startCapture()
        } catch (exception: Exception) {
            track.dispose()
            throw exception
        }
        val handle: Long = nextId.getAndIncrement()
        captures[handle] = Capture(room, sink, type, track, ownsRoom)
        return handle
    }
}
