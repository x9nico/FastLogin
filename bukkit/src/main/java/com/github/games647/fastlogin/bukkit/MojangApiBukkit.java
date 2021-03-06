package com.github.games647.fastlogin.bukkit;

import com.github.games647.fastlogin.core.shared.FastLoginCore;
import com.github.games647.fastlogin.core.shared.LoginSession;
import com.github.games647.fastlogin.core.shared.MojangApiConnector;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

public class MojangApiBukkit extends MojangApiConnector {

    //mojang api check to prove a player is logged in minecraft and made a join server request
    private static final String HAS_JOINED_URL = "https://sessionserver.mojang.com/session/minecraft/hasJoined?" +
            "username=%s&serverId=%s";

    public MojangApiBukkit(Logger logger, List<String> localAddresses, int rateLimit, Map<String, Integer> proxies) {
        super(logger, localAddresses, rateLimit, proxies);
    }

    @Override
    public boolean hasJoinedServer(LoginSession session, String serverId, InetSocketAddress ip) {
        BukkitLoginSession playerSession = (BukkitLoginSession) session;
        try {
            String url = String.format(HAS_JOINED_URL, playerSession.getUsername(), serverId);
            if (ip != null) {
                url += "&ip=" + URLEncoder.encode(ip.getAddress().getHostAddress(), "UTF-8");
            }

            HttpURLConnection conn = getConnection(url);

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line = reader.readLine();
            if (line != null && !"null".equals(line)) {
                //validate parsing
                //http://wiki.vg/Protocol_Encryption#Server
                JSONObject userData = (JSONObject) JSONValue.parseWithException(line);
                String uuid = (String) userData.get("id");
                playerSession.setUuid(FastLoginCore.parseId(uuid));

                JSONArray properties = (JSONArray) userData.get("properties");
                JSONObject skinProperty = (JSONObject) properties.get(0);

                String propertyName = (String) skinProperty.get("name");
                if ("textures".equals(propertyName)) {
                    String skinValue = (String) skinProperty.get("value");
                    String signature = (String) skinProperty.get("signature");
                    playerSession.setSkin(skinValue, signature);
                }

                return true;
            }
        } catch (Exception ex) {
            //catch not only io-exceptions also parse and NPE on unexpected json format
            logger.log(Level.WARNING, "Failed to verify session", ex);
        }

        //this connection doesn't need to be closed. So can make use of keep alive in java
        return false;
    }

    @Override
    protected String getUUIDFromJson(String json) {
        boolean isArray = json.startsWith("[");

        JSONObject mojangPlayer;
        if (isArray) {
            JSONArray array = (JSONArray) JSONValue.parse(json);
            mojangPlayer = (JSONObject) array.get(0);
        } else {
            mojangPlayer = (JSONObject) JSONValue.parse(json);
        }

        String uuid = (String) mojangPlayer.get("id");
        if ("null".equals(uuid)) {
            return null;
        }
        
        return uuid;
    }
}
