package com.aurumxau.api;

import com.aurumxau.util.Json;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** WebSocket fan-out. Messages are {"type": ..., "ts": serverMs, "data": ...}. */
public final class WsHub {
    private static final Logger log = LoggerFactory.getLogger(WsHub.class);
    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();

    public void add(WsContext c) {
        clients.add(c);
    }

    public void remove(WsContext c) {
        clients.remove(c);
    }

    public int size() {
        return clients.size();
    }

    public void broadcast(String type, Object data) {
        if (clients.isEmpty()) return;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("ts", System.currentTimeMillis());
        m.put("data", data);
        String json = Json.api(m);
        for (WsContext c : clients) {
            try {
                if (c.session.isOpen()) c.send(json);
                else clients.remove(c);
            } catch (RuntimeException e) {
                log.debug("ws send failed: {}", e.toString());
                clients.remove(c);
            }
        }
    }
}
