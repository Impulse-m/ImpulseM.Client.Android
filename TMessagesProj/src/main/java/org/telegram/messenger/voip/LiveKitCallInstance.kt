package org.telegram.messenger.voip

import io.livekit.android.ConnectOptions
import io.livekit.android.e2ee.BaseKeyProvider
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.VideoTrackPublishOptions
import io.livekit.android.room.track.LocalTrackPublication
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoQuality
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import livekit.org.webrtc.FrameCryptorKeyDerivationAlgorithm
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RTCStatsReport
import net.impulsem.transport.calls.LiveKitContract
import org.telegram.messenger.AndroidUtilities
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean


/** Implements the service's media interface without entering the legacy tgcalls JNI. */
class LiveKitCallInstance private constructor(
    private val callId: Long,
    private val group: Boolean,
    private val presentation: Boolean,
    private val peerIdentity: String?,
    initialCapture: Long,
    private val remoteSink: VideoSink?,
    private val payloadCallback: PayloadCallback?,
    private val audioCallback: AudioLevelsCallback
) : NativeInstance() {
    companion object {
        @JvmStatic
        fun createPrivate(
            callId: Long,
            peerId: Long,
            versions: List<String>,
            parameters: String,
            authKey: ByteArray,
            remoteSink: VideoSink,
            capture: Long,
            audioCallback: AudioLevelsCallback
        ): NativeInstance {
            require(versions.contains(LiveKitContract.Protocol)) {
                "Unsupported call protocol"
            }
            val join: LiveKitContract.JoinParameters = LiveKitContract.parseJoin(parameters, false, callId)
            val material: ByteArray = LiveKitContract.mediaKey(authKey)
            val instance: LiveKitCallInstance = LiveKitCallInstance(
                callId, false, false, "u$peerId", capture, remoteSink, null, audioCallback
            )
            try {
                val provider: BaseKeyProvider = BaseKeyProvider(
                    ratchetWindowSize = 0,
                    discardFrameWhenCryptorNotReady = true,
                    keyDerivationAlgorithm = FrameCryptorKeyDerivationAlgorithm.HKDF
                )
                instance.keyProvider = provider
                check(provider.rtcKeyProvider.setSharedKey(0, material)) {
                    "Unable to set call media key"
                }
                instance.room.e2eeOptions = E2EEOptions(keyProvider = provider)
                // Run after the service installs listeners and the initial mute state.
                AndroidUtilities.runOnUIThread({
                    instance.connect(join)
                }, 1)
                return instance
            } catch (exception: Exception) {
                instance.stop()
                throw exception
            } finally {
                material.fill(0)
            }
        }


        @JvmStatic
        fun createGroup(
            callId: Long,
            capture: Long,
            presentation: Boolean,
            payloadCallback: PayloadCallback,
            audioCallback: AudioLevelsCallback
        ): NativeInstance {
            return LiveKitCallInstance(callId, true, presentation, null, capture, null, payloadCallback, audioCallback)
        }
    }

    private class Output(val endpoint: String, val bridge: LiveKitVideoBridge, var track: VideoTrack? = null)

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mediaLock: Mutex = Mutex()
    private val room: Room = LiveKitCaptures.adopt(initialCapture) ?: LiveKitCaptures.createRoom()
    private val outputIds: AtomicLong = AtomicLong(1)
    private val outputs: MutableMap<Long, Output> = mutableMapOf()
    private val volumes: MutableMap<Int, Double> = mutableMapOf()
    private val qualities: MutableMap<String, VideoQuality> = mutableMapOf()
    private var capture: Long = initialCapture
    private var stateListener: Instance.OnStateUpdatedListener? = null
    private var barsListener: Instance.OnSignalBarsUpdatedListener? = null
    private var mediaListener: Instance.OnRemoteMediaStateUpdatedListener? = null
    private var connectJob: Job? = null
    private var mediaJob: Job? = null
    private var keyProvider: BaseKeyProvider? = null
    private val stopped: AtomicBoolean = AtomicBoolean(false)
    private val previousBytes: MutableMap<String, Long> = mutableMapOf()
    private var networkType: Int = Instance.NET_TYPE_UNKNOWN
    @Volatile private var stats: Instance.TrafficStats = Instance.TrafficStats(0, 0, 0, 0)
    private var muted: Boolean = true
    private var noiseSuppression: Boolean = true
    private var connected: Boolean = false
    private var lastError: String = ""
    private var privateVideo: VideoTrack? = null
    private val privateBridge: LiveKitVideoBridge? = remoteSink?.let { sink ->
        LiveKitVideoBridge(sink)
    }
    @Volatile private var closed: Boolean = false

    init {
        scope.launch {
            room.events.collect { event ->
                if (!closed) {
                    handleEvent(event)
                }
            }
        }
        scope.launch {
            while (!closed) {
                if (connected) {
                    reportLevels()
                }
                delay(100)
            }
        }
        scope.launch {
            while (!closed) {
                if (connected) {
                    room.getPublisherRTCStats { report ->
                        collectStats("publisher", report)
                    }
                    room.getSubscriberRTCStats { report ->
                        collectStats("subscriber", report)
                    }
                }
                delay(5000)
            }
        }
    }


    override fun isGroup(): Boolean {
        return group
    }


    override fun setOnStateUpdatedListener(listener: Instance.OnStateUpdatedListener?) {
        stateListener = listener
    }


    override fun setOnSignalBarsUpdatedListener(listener: Instance.OnSignalBarsUpdatedListener?) {
        barsListener = listener
    }


    override fun setOnRemoteMediaStateUpdatedListener(listener: Instance.OnRemoteMediaStateUpdatedListener?) {
        mediaListener = listener
    }


    override fun setJoinResponsePayload(payload: String) {
        try {
            val join: LiveKitContract.JoinParameters = LiveKitContract.parseJoin(payload, true, callId)
            onMain {
                connect(join)
            }
        } catch (exception: IllegalArgumentException) {
            onMain {
                fail("Invalid LiveKit group parameters")
            }
        }
    }


    override fun resetGroupInstance(set: Boolean, disconnect: Boolean) {
        onMain {
            connectJob?.cancel()
            mediaJob?.cancel()
            if (room.state != Room.State.DISCONNECTED) {
                // Keep an externally owned capture alive across an RPC-triggered rejoin.
                LiveKitCaptures.get(capture)?.let { local ->
                    room.localParticipant.unpublishTrack(local.track, false)
                }
                room.disconnect()
            }
            connected = false
            val payload: LiveKitContract.JoinPayload = LiveKitContract.createJoinPayload(presentation, SecureRandom())
            payloadCallback?.run(payload.source, payload.json)
        }
    }


    override fun setMuteMicrophone(muteMicrophone: Boolean) {
        onMain {
            if (muted != muteMicrophone) {
                muted = muteMicrophone
                updateMedia()
            }
        }
    }


    override fun setupOutgoingVideo(localSink: VideoSink, type: Int) {
        onMain {
            clearCapture()
            capture = LiveKitCaptures.create(room, localSink, type)
            updateMedia()
        }
    }


    override fun setupOutgoingVideoCreated(videoCapturer: Long) {
        onMain {
            if (capture != videoCapturer) {
                clearCapture()
                capture = LiveKitCaptures.attach(room, videoCapturer)
            }
            LiveKitCaptures.setVideoStateCapturer(capture, Instance.VIDEO_STATE_ACTIVE)
            updateMedia()
        }
    }


    override fun activateVideoCapturer(videoCapturer: Long) {
        setupOutgoingVideoCreated(videoCapturer)
    }


    override fun clearVideoCapturer() {
        onMain {
            clearCapture()
        }
    }


    override fun hasVideoCapturer(): Boolean {
        return capture != 0L && !closed
    }


    override fun switchCamera(front: Boolean) {
        onMain {
            LiveKitCaptures.switchCameraCapturer(capture, front)
        }
    }


    override fun setVideoState(videoState: Int) {
        onMain {
            LiveKitCaptures.setVideoStateCapturer(capture, videoState)
            updateMedia()
        }
    }


    override fun addIncomingVideoOutput(
        quality: Int,
        endpointId: String,
        ssrcGroups: Array<SsrcGroup>?,
        remoteSink: VideoSink,
        userId: Long
    ): Long {
        val id: Long = outputIds.getAndIncrement()
        onMain {
            outputs[id] = Output(endpointId, LiveKitVideoBridge(remoteSink))
            refreshRemoteMedia()
            setVideoEndpointQuality(endpointId, quality)
        }
        return id
    }


    override fun removeIncomingVideoOutput(nativeRemoteSink: Long) {
        onMain {
            val output: Output? = outputs.remove(nativeRemoteSink)
            output?.track?.removeRenderer(output.bridge)
        }
    }


    override fun setVideoEndpointQuality(endpointId: String, quality: Int) {
        onMain {
            qualities[endpointId] = when (quality) {
                0 -> VideoQuality.LOW
                1 -> VideoQuality.MEDIUM
                else -> VideoQuality.HIGH
            }
            refreshRemoteMedia()
        }
    }


    override fun setVolume(ssrc: Int, volume: Double) {
        onMain {
            volumes[ssrc] = volume.coerceIn(0.0, 10.0)
            refreshRemoteMedia()
        }
    }


    override fun getLastError(): String {
        return lastError
    }


    override fun getDebugInfo(): String {
        return "ImpulseM LiveKit; group=$group; connected=$connected; error=$lastError"
    }


    override fun getPreferredRelayId(): Long {
        return 0
    }


    override fun getTrafficStats(): Instance.TrafficStats {
        return stats
    }


    override fun getPersistentState(): ByteArray {
        return byteArrayOf()
    }


    override fun stop(): Instance.FinalState {
        val finalState: Instance.FinalState = Instance.FinalState(byteArrayOf(), debugInfo, trafficStats, false)
        if (!stopped.compareAndSet(false, true)) {
            return finalState
        }
        closed = true
        AndroidUtilities.runOnUIThread {
            scope.cancel()
            for (output in outputs.values) {
                output.track?.removeRenderer(output.bridge)
            }
            outputs.clear()
            privateBridge?.let { bridge ->
                privateVideo?.removeRenderer(bridge)
            }
            privateVideo = null
            clearCapture()
            room.disconnect()
            room.release()
            LiveKitCaptures.closeForwarders(room)
            keyProvider?.rtcKeyProvider?.dispose()
            keyProvider = null
        }
        return finalState
    }


    override fun stopGroup() {
        stop()
    }


    // Compatibility hooks for the service. LiveKit owns ICE, codecs and congestion control.
    override fun setOnSignalDataListener(listener: Instance.OnSignalingDataListener?) {}

    override fun onSignalingDataReceive(data: ByteArray?) {}

    override fun onMediaDescriptionAvailable(taskPtr: Long, ssrcs: Array<VoIPService.RequestedParticipant>?) {}

    override fun setNoiseSuppressionEnabled(value: Boolean) {
        onMain {
            noiseSuppression = value
            room.audioTrackCaptureDefaults = room.audioTrackCaptureDefaults.copy(noiseSuppression = value)
            updateMedia()
        }
    }

    override fun setGlobalServerConfig(serverConfigJson: String?) {}

    override fun setBufferSize(size: Int) {}

    override fun setNetworkType(networkType: Int) {
        onMain {
            this.networkType = networkType
        }
    }

    override fun setAudioOutputGainControlEnabled(enabled: Boolean) {}

    override fun setEchoCancellationStrength(strength: Int) {}

    override fun onStreamPartAvailable(ts: Long, buffer: ByteBuffer?, size: Int, timestamp: Long, channel: Int, quality: Int) {}

    override fun onRequestTimeComplete(taskPtr: Long, time: Long) {}

    override fun setConferenceCallId(callId: Long) {
        onMain {
            fail("Conferences are not supported by this call protocol")
        }
    }


    override fun prepareForStream(isRtpStream: Boolean) {
        onMain {
            fail("Broadcast streams are not supported by this call protocol")
        }
    }


    private fun connect(join: LiveKitContract.JoinParameters) {
        if (closed || connected || connectJob?.isActive == true) {
            return
        }
        connectJob = scope.launch {
            try {
                if (LiveKitCaptures.isTunnelled(room)) {
                    // Relay-only: every media path goes through the loopback TURN forwarders and the tunnel.
                    val tunnelConfig: PeerConnection.RTCConfiguration = PeerConnection.RTCConfiguration(emptyList()).apply {
                        iceTransportsType = PeerConnection.IceTransportsType.RELAY
                    }
                    room.connect(join.url, join.token, ConnectOptions(rtcConfig = tunnelConfig))
                } else {
                    room.connect(join.url, join.token)
                }
                connected = true
                updateMedia()
                reportConnected()
                refreshRemoteMedia()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // SDK exception messages may include the authenticated WebSocket URL.
                fail("LiveKit connection failed")
            }
        }
    }


    private fun updateMedia() {
        if (!connected || closed) {
            return
        }
        mediaJob?.cancel()
        mediaJob = scope.launch {
            mediaLock.withLock {
                try {
                    if (!presentation) {
                        check(room.localParticipant.setMicrophoneEnabled(!muted)) {
                            "Microphone update failed"
                        }
                        val audio: LocalAudioTrack? = room.localParticipant
                            .getTrackPublication(Track.Source.MICROPHONE)?.track as? LocalAudioTrack
                        if (audio != null && audio.options.noiseSuppression != noiseSuppression) {
                            audio.applyOptions(audio.options.copy(noiseSuppression = noiseSuppression)).getOrThrow()
                        }
                    }
                    val local: LiveKitCaptures.Capture? = LiveKitCaptures.get(capture)
                    if (local != null) {
                        val publication: LocalTrackPublication? = room.localParticipant.trackPublications.values
                            .filterIsInstance<LocalTrackPublication>().firstOrNull { item ->
                                item.track === local.track
                            }
                        if (publication == null && local.active) {
                            check(room.localParticipant.publishVideoTrack(
                                local.track,
                                VideoTrackPublishOptions(
                                    source = if (local.type == 2) Track.Source.SCREEN_SHARE else Track.Source.CAMERA,
                                    simulcast = group && local.type != 2
                                )
                            )) {
                                "Video publication failed"
                            }
                        } else if (publication != null) {
                            publication.muted = !local.active
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    fail("LiveKit media update failed")
                }
            }
        }
    }


    private fun handleEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.Reconnecting -> {
                stateListener?.onStateUpdated(if (group) 0 else Instance.STATE_RECONNECTING, true)
            }
            is RoomEvent.Reconnected -> {
                reportConnected()
                refreshRemoteMedia()
            }
            is RoomEvent.Disconnected -> {
                if (event.reason != DisconnectReason.CLIENT_INITIATED) {
                    connected = false
                    fail("LiveKit disconnected")
                }
            }
            is RoomEvent.FailedToConnect -> fail("LiveKit connection failed")
            is RoomEvent.TrackSubscribed, is RoomEvent.TrackUnsubscribed,
            is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted,
            is RoomEvent.ParticipantDisconnected -> refreshRemoteMedia()
            is RoomEvent.ConnectionQualityChanged -> {
                val bars: Int = when (event.quality) {
                    ConnectionQuality.EXCELLENT -> 4
                    ConnectionQuality.GOOD -> 3
                    ConnectionQuality.POOR -> 1
                    else -> 0
                }
                barsListener?.onSignalBarsUpdated(bars)
            }
            is RoomEvent.TrackE2EEStateEvent -> {
                if (event.state == E2EEState.ENCRYPTION_FAILED || event.state == E2EEState.DECRYPTION_FAILED
                    || event.state == E2EEState.MISSING_KEY || event.state == E2EEState.INTERNAL_ERROR) {
                    fail("LiveKit media encryption failed")
                }
            }
            else -> Unit
        }
    }


    private fun refreshRemoteMedia() {
        val videos: MutableMap<String, VideoTrack> = mutableMapOf()
        var audioActive: Boolean = false
        for (participant in room.remoteParticipants.values) {
            val identity: String = participant.identity?.value ?: continue
            val accepted: Boolean = group || identity == peerIdentity
            for (publication in participant.trackPublications.values) {
                if (publication is RemoteTrackPublication && publication.kind == Track.Kind.VIDEO) {
                    qualities[identity]?.let { quality ->
                        publication.setVideoQuality(quality)
                    }
                }
                val track: Track = publication.track ?: continue
                if (track is RemoteAudioTrack) {
                    val volume: Double = if (!accepted || presentation) {
                        0.0
                    } else if (group) {
                        try {
                            volumes[LiveKitContract.groupSource(identity)] ?: 1.0
                        } catch (exception: IllegalArgumentException) {
                            0.0
                        }
                    } else {
                        1.0
                    }
                    track.setVolume(volume)
                    audioActive = audioActive || (accepted && !publication.muted)
                }
                if (accepted && track is VideoTrack && !publication.muted) {
                    val isScreen: Boolean = publication.source == Track.Source.SCREEN_SHARE
                    if (!group || isScreen == identity.endsWith(".p")) {
                        videos[identity] = track
                    }
                }
            }
        }
        for (output in outputs.values) {
            val next: VideoTrack? = videos[output.endpoint]
            if (output.track !== next) {
                output.track?.removeRenderer(output.bridge)
                output.track = next
                next?.addRenderer(output.bridge)
            }
        }
        if (!group) {
            val next: VideoTrack? = videos[peerIdentity]
            if (privateVideo !== next) {
                privateBridge?.let { bridge ->
                    privateVideo?.removeRenderer(bridge)
                    next?.addRenderer(bridge)
                }
                privateVideo = next
            }
            mediaListener?.onMediaStateUpdated(
                if (audioActive) Instance.AUDIO_STATE_ACTIVE else Instance.AUDIO_STATE_MUTED,
                if (next != null) Instance.VIDEO_STATE_ACTIVE else Instance.VIDEO_STATE_INACTIVE
            )
        }
    }


    private fun reportLevels() {
        val localLevel: Float = if (muted || presentation) 0f else room.localParticipant.audioLevel
        if (!group) {
            val remote: Float = room.remoteParticipants.values.firstOrNull { participant ->
                participant.identity?.value == peerIdentity
            }?.audioLevel ?: 0f
            audioCallback.run(intArrayOf(0, 1), floatArrayOf(localLevel, remote), booleanArrayOf(localLevel > 0f, remote > 0f))
            return
        }
        val sources: MutableList<Int> = mutableListOf(0)
        val levels: MutableList<Float> = mutableListOf(localLevel)
        for (participant in room.remoteParticipants.values) {
            try {
                sources.add(LiveKitContract.groupSource(participant.identity?.value ?: continue))
                levels.add(participant.audioLevel)
            } catch (exception: IllegalArgumentException) {
                // Ignore identities outside the application's group-call contract.
            }
        }
        audioCallback.run(sources.toIntArray(), levels.toFloatArray(), BooleanArray(levels.size) { index ->
            levels[index] > 0f
        })
    }


    private fun reportConnected() {
        stateListener?.onStateUpdated(if (group) 1 else Instance.STATE_ESTABLISHED, false)
        barsListener?.onSignalBarsUpdated(4)
    }


    private fun clearCapture() {
        mediaJob?.cancel()
        LiveKitCaptures.destroyVideoCapturer(capture)
        capture = 0
    }


    private fun collectStats(connection: String, report: RTCStatsReport) {
        onMain {
            var sent: Long = 0
            var received: Long = 0
            for ((id, entry) in report.statsMap) {
                val field: String = when (entry.type) {
                    "outbound-rtp" -> "bytesSent"
                    "inbound-rtp" -> "bytesReceived"
                    else -> continue
                }
                val current: Long = (entry.members[field] as? Number)?.toLong() ?: continue
                val key: String = "$connection:$id:$field"
                val previous: Long = previousBytes.put(key, current) ?: 0
                val delta: Long = if (current >= previous) current - previous else current
                if (field == "bytesSent") {
                    sent += delta
                } else {
                    received += delta
                }
            }
            val wifi: Boolean = networkType == Instance.NET_TYPE_WIFI || networkType == Instance.NET_TYPE_ETHERNET
            stats = Instance.TrafficStats(
                stats.bytesSentWifi + if (wifi) sent else 0,
                stats.bytesReceivedWifi + if (wifi) received else 0,
                stats.bytesSentMobile + if (wifi) 0 else sent,
                stats.bytesReceivedMobile + if (wifi) 0 else received
            )
        }
    }


    private fun fail(message: String) {
        lastError = message
        if (group) {
            VoIPService.getSharedInstance()?.hangUp()
        } else {
            stateListener?.onStateUpdated(Instance.STATE_FAILED, false)
        }
    }


    private fun onMain(action: () -> Unit) {
        AndroidUtilities.runOnUIThread {
            if (!closed) {
                action()
            }
        }
    }
}
