package com.example.videocallapp;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class JanusWebSocketClient extends WebSocketClient {
    private static final String TAG = "JanusWebSocketClient";
    private static final int CONNECTION_TIMEOUT = 40000; // 10 seconds timeout
    private static boolean alreadySent = false;

    public interface JanusListener {
        void onJanusConnected();
        void onJanusDisconnected();
        void onJanusError(String error);
        void onJanusEvent(JSONObject event);
    }

    private JanusListener listener;
    private long sessionId;
    private long handleId;
    private String pendingUsername;
    private boolean isSessionCreated = false;
    private boolean isPluginAttached = false;

    public JanusWebSocketClient(URI serverUri, JanusListener listener, Map<String, String> httpHeaders) {
        super(serverUri, httpHeaders);
        this.listener = listener;
        setConnectionLostTimeout(20); // 60 seconds ping interval
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        Log.d(TAG, "WebSocket connected, handshake: " + handshakedata.getHttpStatus());
        listener.onJanusConnected();
        createSession(); // Start Janus session creation
    }

    @Override
    public void onMessage(String message) {
        Log.d(TAG, "Received: " + message);
        try {
            JSONObject json = new JSONObject(message);
            processJanusMessage(json); // Process all Janus messages
            listener.onJanusEvent(json); // Forward to listener
        } catch (JSONException e) {
            listener.onJanusError("JSON parsing error: " + e.getMessage());
        }
    }

    private void processJanusMessage(JSONObject json) throws JSONException {
        if (!json.has("janus")) return;

        String janus = json.getString("janus");
        switch (janus) {
            case "success":
                Log.e("Janus Debug",json.toString());
                handleSuccessResponse(json);
                break;
            case "error":
                handleErrorResponse(json);
                break;
            case "event":
                // Events are handled by the listener
                break;
        }
    }

    private void handleSuccessResponse(JSONObject json) throws JSONException {
        if (json.has("data") && json.getJSONObject("data").has("id")) {
            if (!json.has("session_id")) {
                // This is a session creation response
                sessionId = json.getJSONObject("data").getLong("id");
                isSessionCreated = true;
                Log.d(TAG, "Session created: " + sessionId);
                attachPlugin();
            } else {
                // This is a plugin attach response
                handleId = json.getJSONObject("data").getLong("id");
                isPluginAttached = true;
                Log.d(TAG, "Plugin attached, handle ID: " + handleId);
                pendingUsername = MainActivity.currentUsername;
                if (pendingUsername != null) {
                    register(pendingUsername);
                    pendingUsername = null;
                }
            }
        }
    }

    private void handleErrorResponse(JSONObject json) throws JSONException {
        String error = json.optString("error", "Unknown error");
        String reason = json.getJSONObject("error").optString("reason", "No reason provided");
        String errorMsg = "Janus error (" + error + "): " + reason;
        Log.e(TAG, errorMsg);
        listener.onJanusError(errorMsg);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        Log.d(TAG, "WebSocket closed. Code: " + code + ", Reason: " + reason);
        resetState();
        listener.onJanusDisconnected();
    }

    @Override
    public void onError(Exception ex) {
        String errorMsg = "WebSocket error: " + ex.getMessage();
        Log.e(TAG, errorMsg, ex);
        listener.onJanusError(errorMsg);
    }

    public void connectWithTimeout() throws Exception {
        super.connectBlocking(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS);
    }

    private void resetState() {
        sessionId = 0;
        handleId = 0;
        isSessionCreated = false;
        isPluginAttached = false;
        pendingUsername = null;
    }

    private String generateTransactionId() {
        return "txn-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void createSession() {
        try {
            JSONObject create = new JSONObject();
            create.put("janus", "create");
            create.put("transaction", generateTransactionId());
            send(create.toString());
            Log.d(TAG, "Sent create session request");
        } catch (JSONException e) {
            Log.e(TAG, "Error creating session request", e);
            listener.onJanusError("Error creating session: " + e.getMessage());
        }
    }

    private void attachPlugin() {
        try {
            JSONObject attach = new JSONObject();
            attach.put("janus", "attach");
            attach.put("plugin", "janus.plugin.videocall"); // Using videoroom plugin
            attach.put("session_id", sessionId);
            attach.put("transaction", generateTransactionId());
            Log.e("Plugin Data", attach.toString());
            send(attach.toString());
            Log.d(TAG, "Sent attach plugin request");
        } catch (JSONException e) {
            Log.e(TAG, "Error creating attach request", e);
            listener.onJanusError("Error attaching plugin: " + e.getMessage());
        }
    }

    public void register(String username) {
        if (!isPluginAttached) {
            pendingUsername = username;
            return;
        }

        try {
            JSONObject register = new JSONObject();
            register.put("janus", "message");
            register.put("session_id", sessionId);
            register.put("handle_id", handleId);
            register.put("transaction", generateTransactionId());

            JSONObject body = new JSONObject();
            body.put("request", "register");
            body.put("username", username);

            register.put("body", body);
            send(register.toString());
            Log.d(TAG, "Sent register request for username: " + username);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating register request", e);
            listener.onJanusError("Error registering: " + e.getMessage());
        }
    }

    public void call(String peerUsername, JSONObject jsep) {
        try {
            JSONObject call = new JSONObject();
            call.put("janus", "message");
            call.put("session_id", sessionId);
            call.put("handle_id", handleId);
            call.put("transaction", generateTransactionId());

            JSONObject body = new JSONObject();
            body.put("request", "call");
            body.put("username", peerUsername);

            call.put("body", body);
            if (jsep != null) {
                call.put("jsep", jsep);
            }
            send(call.toString());
            Log.d(TAG, "Sent call request to: " + peerUsername);
        } catch (JSONException e) {
            Log.e(TAG, "Error creating call request", e);
            listener.onJanusError("Error calling: " + e.getMessage());
        }
    }

    public void hangup() {
        try {
            JSONObject hangup = new JSONObject();
            hangup.put("janus", "message");
            hangup.put("session_id", sessionId);
            hangup.put("handle_id", handleId);
            hangup.put("transaction", generateTransactionId());

            JSONObject body = new JSONObject();
            body.put("request", "hangup");

            hangup.put("body", body);
            send(hangup.toString());
            Log.d(TAG, "Sent hangup request");
        } catch (JSONException e) {
            Log.e(TAG, "Error creating hangup request", e);
            listener.onJanusError("Error hanging up: " + e.getMessage());
        }
    }

    public void trickle(JSONObject candidate) {
        try {
            JSONObject trickle = new JSONObject();
            trickle.put("janus", "trickle");
            trickle.put("session_id", sessionId);
            trickle.put("handle_id", handleId);
            trickle.put("candidate", candidate);
            trickle.put("transaction", generateTransactionId());
            send(trickle.toString());
            Log.d(TAG, "Sent trickle candidate");
        } catch (JSONException e) {
            Log.e(TAG, "Error creating trickle request", e);
            listener.onJanusError("Error sending ICE candidate: " + e.getMessage());
        }
    }
}