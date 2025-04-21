package com.example.videocallapp;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.IceCandidate;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.SurfaceViewRenderer;

import java.net.URI;
import java.net.URISyntaxException;

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

        // Initialize UI components
        usernameEditText = findViewById(R.id.usernameEditText);
        peerEditText = findViewById(R.id.peerEditText);
        registerButton = findViewById(R.id.registerButton);
        callButton = findViewById(R.id.callButton);
        hangupButton = findViewById(R.id.hangupButton);
        statusTextView = findViewById(R.id.statusTextView);
        localVideoView = findViewById(R.id.localVideoView);
        remoteVideoView = findViewById(R.id.remoteVideoView);

        // Set click listeners
        registerButton.setOnClickListener(v -> registerUser());
        callButton.setOnClickListener(v -> callPeer());
        hangupButton.setOnClickListener(v -> hangupCall());

        // Request permissions
        requestPermissions();
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET
        };

        boolean allPermissionsGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                break;
            }
        }

        if (!allPermissionsGranted) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (!allGranted) {
                Toast.makeText(this, "All permissions are required for video calls", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void registerUser() {
        String username = usernameEditText.getText().toString().trim();
        if (username.isEmpty()) {
            statusTextView.setText("Please enter a username");
            return;
        }

        currentUsername = username;
        try {
            URI serverUri = new URI("wss://janus.hobenaki.com/");
            webSocketClient = new JanusWebSocketClient(serverUri, this);
            webSocketClient.connect();
            statusTextView.setText("Connecting to Janus server...");
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid WebSocket URI", e);
            statusTextView.setText("Error: Invalid server URL");
        }
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
        runOnUiThread(() -> {
            statusTextView.setText("Connected to Janus server");
            if (currentUsername != null) {
                webSocketClient.register(currentUsername);
            }
        });
    }

    @Override
    public void onJanusDisconnected() {
        runOnUiThread(() -> statusTextView.setText("Disconnected from Janus server"));
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
                                String eventType = result.getString("event");

                                switch (eventType) {
                                    case "incomingcall":
                                        String caller = result.getString("username");
                                        runOnUiThread(() -> {
                                            statusTextView.setText("Incoming call from " + caller);
                                            // Here you should show a dialog to accept/reject the call
                                            // For simplicity, we'll auto-accept
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
                                        break;

                                    case "accepted":
                                        runOnUiThread(() -> statusTextView.setText("Call accepted"));
                                        if (event.has("jsep")) {
                                            JSONObject jsep = event.getJSONObject("jsep");
                                            peerConnectionClient.setRemoteDescription(jsep);
                                        }
                                        break;

                                    case "hangup":
                                        runOnUiThread(() -> {
                                            statusTextView.setText("Call ended by remote peer");
                                            hangupCall();
                                        });
                                        break;
                                }
                            }
                        }
                    }
                } else if (janus.equals("webrtcup")) {
                    runOnUiThread(() -> statusTextView.setText("Call established"));
                } else if (janus.equals("trickle")) {
                    if (event.has("candidate")) {
                        JSONObject candidate = event.getJSONObject("candidate");
                        if (peerConnectionClient != null) {
                            peerConnectionClient.addIceCandidate(candidate);
                        }
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing Janus event", e);
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
    }
}