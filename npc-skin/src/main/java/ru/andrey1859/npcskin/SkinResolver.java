package ru.andrey1859.npcskin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Loads the official skin of a Minecraft account from Mojang APIs.
 */
public final class SkinResolver {

    /** Signed skin textures, exactly as returned by Mojang (value and signature unchanged). */
    public record SkinData(String value, String signature) {}

    public static final class SkinException extends Exception {
        public SkinException(String message) {
            super(message);
        }
    }

    private static final int MAX_RESPONSE_SIZE = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private SkinResolver() {}

    public static SkinData fetch(String nick) throws SkinException {
        String uuid = requestProfileUuid(nick);
        return requestSkin(uuid);
    }

    private static String requestProfileUuid(String nick) throws SkinException {
        HttpResponse<byte[]> response = send("https://api.mojang.com/users/profiles/minecraft/" + nick);
        if (response.statusCode() == 404) {
            throw new SkinException("Minecraft account not found: " + nick);
        }
        checkStatus(response, "Mojang API");
        Object parsed = parse(response);
        Object id = parsed instanceof Map<?, ?> map ? map.get("id") : null;
        if (!(id instanceof String uuid) || uuid.isEmpty()) {
            throw new SkinException("Unexpected response from Mojang API");
        }
        return uuid;
    }

    private static SkinData requestSkin(String uuid) throws SkinException {
        HttpResponse<byte[]> response = send(
                "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
        checkStatus(response, "Mojang session server");
        Object parsed = parse(response);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new SkinException("Unexpected response from session server");
        }
        Object properties = root.get("properties");
        if (!(properties instanceof List<?> list)) {
            throw new SkinException("Skin profile has no properties");
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> property)) {
                continue;
            }
            if (!"textures".equals(property.get("name"))) {
                continue;
            }
            Object value = property.get("value");
            Object signature = property.get("signature");
            if (!(value instanceof String text) || text.isEmpty()) {
                throw new SkinException("Skin has no value");
            }
            if (!(signature instanceof String signatureText) || signatureText.isEmpty()) {
                throw new SkinException("Skin has no signature");
            }
            SkinUrlValidator.validate(text);
            return new SkinData(text, signatureText);
        }
        throw new SkinException("Account has no skin");
    }

    private static HttpResponse<byte[]> send(String url) throws SkinException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "NpcSkin/1.0")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.body() != null && response.body().length > MAX_RESPONSE_SIZE) {
                throw new SkinException("Response too large");
            }
            return response;
        } catch (HttpTimeoutException e) {
            throw new SkinException("Request timed out");
        } catch (IOException e) {
            throw new SkinException("Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinException("Request interrupted");
        }
    }

    private static void checkStatus(HttpResponse<byte[]> response, String source) throws SkinException {
        int status = response.statusCode();
        if (status == 429) {
            throw new SkinException("Mojang API rate limit exceeded, try again later");
        }
        if (status < 200 || status >= 300) {
            throw new SkinException("HTTP " + status + " from " + source);
        }
    }

    private static Object parse(HttpResponse<byte[]> response) throws SkinException {
        if (response.body() == null) {
            throw new SkinException("Empty response");
        }
        try {
            return Json.parse(new String(response.body(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new SkinException("Invalid response data: " + e.getMessage());
        }
    }
}
