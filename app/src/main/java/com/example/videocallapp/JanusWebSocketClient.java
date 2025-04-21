package com.example.videocallapp;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Random;

public class JanusWebSocketClient extends WebSocketClient {
    private JanusListener listener;
    private String sessionId;
    private String handleId;

    public interface JanusListener {
        void onJanusConnected();
        void onJanusDisconnected();
        void onJanusError(String error);
        void onJanusEvent(JSONObject event);
    }

    public JanusWebSocketClient(URI serverUri, JanusListener listener) {
        super(serverUri);
        this.listener = listener;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        listener.onJanusConnected();
        createSession();
    }

    @Override
    public void onMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            listener.onJanusEvent(json);

            if (json.has("janus")) {
                String janus = json.getString("janus");

                if (janus.equals("success")) {
                    if (json.has("data") && json.getJSONObject("data").has("id")) {
                        sessionId = json.getJSONObject("data").getString("id");
                        attachPlugin();
                    } else if (json.has("plugindata")) {
                        JSONObject plugindata = json.getJSONObject("plugindata");
                        if (plugindata.has("data") && plugindata.getJSONObject("data").has("id")) {
                            handleId = plugindata.getJSONObject("data").getString("id");
                        }
                    }
                }
            }
        } catch (JSONException e) {
            listener.onJanusError("JSON parsing error: " + e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        listener.onJanusDisconnected();
    }

    @Override
    public void onError(Exception ex) {
        listener.onJanusError(ex.getMessage());
    }

    private String generateTransactionId() {
        return "txn-" + new Random().nextInt(1000000);
    }

    private void createSession() {
        try {
            JSONObject create = new JSONObject();
            create.put("janus", "create");
            create.put("transaction", generateTransactionId());
            send(create.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void attachPlugin() {
        try {
            JSONObject attach = new JSONObject();
            attach.put("janus", "attach");
            attach.put("plugin", "janus.plugin.videocall");
            attach.put("session_id", sessionId);
            attach.put("transaction", generateTransactionId());
            send(attach.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public void register(String username) {
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
        } catch (JSONException e) {
            e.printStackTrace();
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
        } catch (JSONException e) {
            e.printStackTrace();
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
        } catch (JSONException e) {
            e.printStackTrace();
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
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
}