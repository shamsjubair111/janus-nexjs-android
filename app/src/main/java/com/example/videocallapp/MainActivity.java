package com.example.videocallapp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.SurfaceViewRenderer;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements JanusWebSocketClient.JanusListener, PeerConnectionClient.PeerConnectionListener {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 1;

    private JanusWebSocketClient webSocketClient;
    private PeerConnectionClient peerConnectionClient;

    private EditText usernameEditText;
    private EditText peerEditText;
    private Button registerButton;
    private Button callButton;
    private Button hangupButton;
    private TextView statusTextView;
    private SurfaceViewRenderer localVideoView;
    private SurfaceViewRenderer remoteVideoView;

    private String currentUsername;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeViews();
        requestPermissions();
    }

    private void initializeViews() {
        usernameEditText = findViewById(R.id.usernameEditText);
        peerEditText = findViewById(R.id.peerEditText);
        registerButton = findViewById(R.id.registerButton);
        callButton = findViewById(R.id.callButton);
        hangupButton = findViewById(R.id.hangupButton);
        statusTextView = findViewById(R.id.statusTextView);
        localVideoView = findViewById(R.id.localVideoView);
        remoteVideoView = findViewById(R.id.remoteVideoView);

        registerButton.setOnClickListener(v -> registerUser());
        callButton.setOnClickListener(v -> callPeer());
        hangupButton.setOnClickListener(v -> hangupCall());

        // Initialize WebRTC video views
        localVideoView.init(PeerConnectionClient.getEglBase().getEglBaseContext(), null);
        remoteVideoView.init(PeerConnectionClient.getEglBase().getEglBaseContext(), null);
        localVideoView.setMirror(true); // Mirror local video
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE
        };

        if (!hasPermissions(permissions)) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    private boolean hasPermissions(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "All permissions are required for video calls", Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
            }
        }
    }

    private void registerUser() {
        if (!isNetworkAvailable()) {
            statusTextView.setText("No network connection");
            return;
        }

        String username = usernameEditText.getText().toString().trim();
        if (username.isEmpty()) {
            statusTextView.setText("Please enter a username");
            return;
        }

        currentUsername = username;
        new Thread(() -> {
            try {
                URI serverUri = new URI("wss://janus.hobenaki.com/"); // Updated endpoint
                Map<String, String> httpHeaders = new HashMap<>();
                httpHeaders.put("Sec-WebSocket-Protocol", "janus-protocol");

                runOnUiThread(() -> statusTextView.setText("Connecting to Janus server..."));

                webSocketClient = new JanusWebSocketClient(serverUri, MainActivity.this, httpHeaders);
                webSocketClient.connectWithTimeout();
            } catch (Exception e) {
                runOnUiThread(() -> statusTextView.setText("Connection failed: " + e.getMessage()));
                Log.e(TAG, "WebSocket connection error", e);
            }
        }).start();
    }

    private void callPeer() {
        String peerUsername = peerEditText.getText().toString().trim();
        if (peerUsername.isEmpty()) {
            statusTextView.setText("Please enter a peer username");
            return;
        }

        if (peerConnectionClient == null) {
            peerConnectionClient = new PeerConnectionClient(
                    this,
                    webSocketClient,
                    localVideoView,
                    remoteVideoView,
                    this
            );
            peerConnectionClient.createPeerConnection();
            peerConnectionClient.startLocalVideo();
        }

        peerConnectionClient.createOffer(peerUsername);
        statusTextView.setText("Calling " + peerUsername + "...");
    }

    private void hangupCall() {
        if (webSocketClient != null) {
            webSocketClient.hangup();
        }

        if (peerConnectionClient != null) {
            peerConnectionClient.close();
            peerConnectionClient = null;
        }

        statusTextView.setText("Call ended");
    }

    @Override
    public void onJanusConnected() {
        runOnUiThread(() -> statusTextView.setText("Connected to Janus server"));
        // Session creation is handled automatically in WebSocketClient
    }

    @Override
    public void onJanusDisconnected() {
        runOnUiThread(() -> {
            statusTextView.setText("Disconnected from Janus server");
            if (peerConnectionClient != null) {
                peerConnectionClient.close();
                peerConnectionClient = null;
            }
        });
    }

    @Override
    public void onJanusError(String error) {
        runOnUiThread(() -> statusTextView.setText("Error: " + error));
    }

    @Override
    public void onJanusEvent(JSONObject event) {
        try {
            if (event.has("janus")) {
                String janus = event.getString("janus");

                if (janus.equals("event")) {
                    if (event.has("plugindata")) {
                        JSONObject plugindata = event.getJSONObject("plugindata");
                        JSONObject data = plugindata.getJSONObject("data");

                        if (data.has("result")) {
                            JSONObject result = data.getJSONObject("result");

                            if (result.has("event")) {
                                handlePluginEvent(event, result.getString("event"), result);
                            }
                        }
                    }
                } else if (janus.equals("webrtcup")) {
                    runOnUiThread(() -> statusTextView.setText("Call established"));
                } else if (janus.equals("trickle")) {
                    handleTrickleEvent(event);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing Janus event", e);
        }
    }

    private void handlePluginEvent(JSONObject event, String eventType, JSONObject result) throws JSONException {
        switch (eventType) {
            case "incomingcall":
                handleIncomingCall(event, result);
                break;
            case "accepted":
                handleCallAccepted(event);
                break;
            case "hangup":
                handleHangup();
                break;
            default:
                Log.d(TAG, "Unhandled event type: " + eventType);
        }
    }

    private void handleIncomingCall(JSONObject event, JSONObject result) throws JSONException {
        String caller = result.getString("username");
        runOnUiThread(() -> {
            statusTextView.setText("Incoming call from " + caller);
            if (peerConnectionClient == null) {
                peerConnectionClient = new PeerConnectionClient(
                        MainActivity.this,
                        webSocketClient,
                        localVideoView,
                        remoteVideoView,
                        MainActivity.this
                );
                peerConnectionClient.createPeerConnection();
                peerConnectionClient.startLocalVideo();
            }

            if (event.has("jsep")) {
                try {
                    JSONObject jsep = event.getJSONObject("jsep");
                    peerConnectionClient.setRemoteDescription(jsep);
                } catch (JSONException e) {
                    Log.e(TAG, "Error parsing JSEP", e);
                    runOnUiThread(() -> statusTextView.setText("Error parsing call data"));
                }
            }
        });
    }

    private void handleCallAccepted(JSONObject event) throws JSONException {
        runOnUiThread(() -> statusTextView.setText("Call accepted"));
        if (event.has("jsep")) {
            JSONObject jsep = event.getJSONObject("jsep");
            peerConnectionClient.setRemoteDescription(jsep);
        }
    }

    private void handleHangup() {
        runOnUiThread(() -> {
            statusTextView.setText("Call ended by remote peer");
            hangupCall();
        });
    }

    private void handleTrickleEvent(JSONObject event) throws JSONException {
        if (event.has("candidate")) {
            JSONObject candidate = event.getJSONObject("candidate");
            if (peerConnectionClient != null) {
                peerConnectionClient.addIceCandidate(candidate);
            }
        }
    }

    @Override
    public void onLocalStream(MediaStream stream) {
        runOnUiThread(() -> {
            Log.d(TAG, "Local stream added");
            localVideoView.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onRemoteStream(MediaStream stream) {
        runOnUiThread(() -> {
            Log.d(TAG, "Remote stream added");
            remoteVideoView.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onIceCandidate(IceCandidate candidate) {
        // Handled by PeerConnectionClient directly
    }

    @Override
    public void onConnectionChange(PeerConnection.PeerConnectionState state) {
        runOnUiThread(() -> {
            switch (state) {
                case CONNECTED:
                    statusTextView.setText("Connected");
                    break;
                case DISCONNECTED:
                    statusTextView.setText("Disconnected");
                    break;
                case FAILED:
                    statusTextView.setText("Connection failed");
                    hangupCall();
                    break;
                case CLOSED:
                    statusTextView.setText("Connection closed");
                    break;
            }
        });
    }

    @Override
    public void onError(String error) {
        runOnUiThread(() -> statusTextView.setText("Error: " + error));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webSocketClient != null) {
            webSocketClient.close();
        }
        if (peerConnectionClient != null) {
            peerConnectionClient.close();
        }
        localVideoView.release();
        remoteVideoView.release();
    }
}