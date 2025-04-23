package com.example.videocallapp;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpSender;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PeerConnectionClient {
    private static final String TAG = "PeerConnectionClient";
    private static EglBase eglBase;

    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private MediaStream localStream;
    private final Context context;
    private final JanusWebSocketClient webSocketClient;
    private final SurfaceViewRenderer localVideoView;
    private final SurfaceViewRenderer remoteVideoView;
    private final PeerConnectionListener listener;

    public interface PeerConnectionListener {
        void onLocalStream(MediaStream stream);
        void onRemoteStream(MediaStream stream);
        void onIceCandidate(IceCandidate candidate);
        void onConnectionChange(PeerConnection.PeerConnectionState state);
        void onError(String error);
    }

    static {
        eglBase = EglBase.create();
    }

    public static EglBase getEglBase() {
        return eglBase;
    }

    public PeerConnectionClient(Context context, JanusWebSocketClient webSocketClient,
                                SurfaceViewRenderer localVideoView, SurfaceViewRenderer remoteVideoView,
                                PeerConnectionListener listener) {
        this.context = context;
        this.webSocketClient = webSocketClient;
        this.localVideoView = localVideoView;
        this.remoteVideoView = remoteVideoView;
        this.listener = listener;

        initializePeerConnectionFactory();
    }

    private void initializePeerConnectionFactory() {
        PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(true)
                        .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                        .createInitializationOptions()
        );

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        DefaultVideoEncoderFactory encoderFactory = new DefaultVideoEncoderFactory(
                eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory = new DefaultVideoDecoderFactory(
                eglBase.getEglBaseContext());

        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
    }

    public void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "onSignalingChange: " + signalingState);
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: " + iceConnectionState);
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                Log.d(TAG, "onIceConnectionReceivingChange: " + b);
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: " + iceGatheringState);
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: " + iceCandidate);
                try {
                    JSONObject candidateJson = new JSONObject();
                    candidateJson.put("sdpMid", iceCandidate.sdpMid);
                    candidateJson.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                    candidateJson.put("candidate", iceCandidate.sdp);
                    webSocketClient.trickle(candidateJson);
                } catch (JSONException e) {
                    Log.e(TAG, "Error creating candidate JSON", e);
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                Log.d(TAG, "onIceCandidatesRemoved");
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                // Deprecated in Unified Plan - kept for backward compatibility
                Log.d(TAG, "onAddStream (deprecated): " + mediaStream.getId());
            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(TAG, "onRemoveStream: " + mediaStream.getId());
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "onDataChannel: " + dataChannel.label());
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded");
            }

            @Override
            public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                Log.d(TAG, "onAddTrack");
                if (rtpReceiver.track() instanceof VideoTrack) {
                    VideoTrack remoteVideoTrack = (VideoTrack) rtpReceiver.track();
                    remoteVideoTrack.addSink(remoteVideoView);
                    if (mediaStreams != null && mediaStreams.length > 0) {
                        listener.onRemoteStream(mediaStreams[0]);
                    }
                }
            }

            @Override
            public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                Log.d(TAG, "onConnectionChange: " + newState);
                listener.onConnectionChange(newState);
            }
        });
    }

    public void startLocalVideo() {
        videoCapturer = createCameraCapturer();
        if (videoCapturer == null) {
            listener.onError("Failed to create camera capturer");
            return;
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());

        VideoSource videoSource = factory.createVideoSource(videoCapturer.isScreencast());
        videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
        videoCapturer.startCapture(640, 480, 30);

        VideoTrack videoTrack = factory.createVideoTrack("ARDAMSv0", videoSource);
        AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
        AudioTrack audioTrack = factory.createAudioTrack("ARDAMSa0", audioSource);

        // Create local stream for UI rendering
        localStream = factory.createLocalMediaStream("ARDAMS");
        localStream.addTrack(videoTrack);
        localStream.addTrack(audioTrack);

        // Add tracks to peer connection (Unified Plan compatible)
        List<String> streamIds = Collections.singletonList("ARDAMS");
        peerConnection.addTrack(videoTrack, streamIds);
        peerConnection.addTrack(audioTrack, streamIds);

        // Setup local video view
        videoTrack.addSink(localVideoView);
        listener.onLocalStream(localStream);
    }

    private VideoCapturer createCameraCapturer() {
        Camera2Enumerator enumerator = new Camera2Enumerator(context);
        String[] deviceNames = enumerator.getDeviceNames();

        // Try to find front-facing camera first
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Fall back to any available camera
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                CameraVideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);
                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    public void createOffer(String peerUsername) {
        MediaConstraints sdpConstraints = new MediaConstraints();
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {}

                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject jsep = new JSONObject();
                            jsep.put("type", sessionDescription.type.canonicalForm());
                            jsep.put("sdp", sessionDescription.description);
                            webSocketClient.call(peerUsername, jsep);
                        } catch (JSONException e) {
                            Log.e(TAG, "Error creating JSEP JSON", e);
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        Log.e(TAG, "onCreateFailure: " + s);
                    }

                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "onSetFailure: " + s);
                    }
                }, sessionDescription);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "onCreateFailure: " + s);
            }

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "onSetFailure: " + s);
            }
        }, sdpConstraints);
    }

    public void setRemoteDescription(JSONObject jsep) {
        try {
            SessionDescription sessionDescription = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(jsep.getString("type")),
                    jsep.getString("sdp")
            );

            peerConnection.setRemoteDescription(new SdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {}

                @Override
                public void onSetSuccess() {
                    if (sessionDescription.type == SessionDescription.Type.OFFER) {
                        createAnswer();
                    }
                }

                @Override
                public void onCreateFailure(String s) {
                    Log.e(TAG, "onCreateFailure: " + s);
                }

                @Override
                public void onSetFailure(String s) {
                    Log.e(TAG, "onSetFailure: " + s);
                }
            }, sessionDescription);
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing JSEP", e);
        }
    }

    private void createAnswer() {
        MediaConstraints sdpConstraints = new MediaConstraints();
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        sdpConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));

        peerConnection.createAnswer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {}

                    @Override
                    public void onSetSuccess() {
                        try {
                            JSONObject jsep = new JSONObject();
                            jsep.put("type", sessionDescription.type.canonicalForm());
                            jsep.put("sdp", sessionDescription.description);

                            JSONObject body = new JSONObject();
                            body.put("request", "accept");

                            JSONObject accept = new JSONObject();
                            accept.put("janus", "message");
                            accept.put("body", body);
                            accept.put("jsep", jsep);

                            webSocketClient.send(accept.toString());
                        } catch (JSONException e) {
                            Log.e(TAG, "Error creating answer JSEP", e);
                        }
                    }

                    @Override
                    public void onCreateFailure(String s) {
                        Log.e(TAG, "onCreateFailure: " + s);
                    }

                    @Override
                    public void onSetFailure(String s) {
                        Log.e(TAG, "onSetFailure: " + s);
                    }
                }, sessionDescription);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onCreateFailure(String s) {
                Log.e(TAG, "onCreateFailure: " + s);
            }

            @Override
            public void onSetFailure(String s) {
                Log.e(TAG, "onSetFailure: " + s);
            }
        }, sdpConstraints);
    }

    public void addIceCandidate(JSONObject candidate) {
        try {
            IceCandidate iceCandidate = new IceCandidate(
                    candidate.getString("sdpMid"),
                    candidate.getInt("sdpMLineIndex"),
                    candidate.getString("candidate")
            );
            peerConnection.addIceCandidate(iceCandidate);
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing ICE candidate", e);
        }
    }

    public void close() {
        if (peerConnection != null) {
            // Remove all tracks first
            for (RtpSender sender : peerConnection.getSenders()) {
                peerConnection.removeTrack(sender);
            }
            peerConnection.close();
            peerConnection = null;
        }

        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
                videoCapturer.dispose();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping video capture", e);
            }
            videoCapturer = null;
        }

        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }

        if (localStream != null) {
            localStream.dispose();
            localStream = null;
        }
    }
}