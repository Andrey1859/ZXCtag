package ru.andrey1859.npcskin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates the texture URL inside a signed skin value.
 * The signed value itself is never modified.
 */
public final class SkinUrlValidator {

    private static final Pattern TEXTURE_PATH = Pattern.compile("^/texture/[0-9a-fA-F]{32}$");
    private static final String HOST = "textures.minecraft.net";

    private SkinUrlValidator() {}

    public static void validate(String value) throws SkinResolver.SkinException {
        String url = readUrl(value);
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SkinResolver.SkinException("Skin URL is not a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new SkinResolver.SkinException("Skin URL must use http or https");
        }
        if (!HOST.equals(uri.getHost())) {
            throw new SkinResolver.SkinException("Skin URL must use " + HOST);
        }
        if (uri.getRawUserInfo() != null) {
            throw new SkinResolver.SkinException("Skin URL must not contain user info");
        }
        if (uri.getPort() != -1) {
            throw new SkinResolver.SkinException("Skin URL must not contain a port");
        }
        if (uri.getRawQuery() != null) {
            throw new SkinResolver.SkinException("Skin URL must not contain a query");
        }
        if (uri.getRawFragment() != null) {
            throw new SkinResolver.SkinException("Skin URL must not contain a fragment");
        }
        if (!TEXTURE_PATH.matcher(uri.getPath()).matches()) {
            throw new SkinResolver.SkinException("Skin URL must have the form /texture/{hex}");
        }
    }

    private static String readUrl(String value) throws SkinResolver.SkinException {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new SkinResolver.SkinException("Skin value is not valid Base64");
        }
        Object parsed;
        try {
            parsed = Json.parse(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new SkinResolver.SkinException("Skin value is not valid JSON: " + e.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new SkinResolver.SkinException("Skin value is not a JSON object");
        }
        Object textures = root.get("textures");
        if (!(textures instanceof Map<?, ?> texturesMap)) {
            throw new SkinResolver.SkinException("Skin value has no textures");
        }
        Object skin = texturesMap.get("SKIN");
        if (!(skin instanceof Map<?, ?> skinMap)) {
            throw new SkinResolver.SkinException("Skin value has no SKIN texture");
        }
        Object url = skinMap.get("url");
        if (!(url instanceof String text) || text.isEmpty()) {
            throw new SkinResolver.SkinException("Skin texture has no url");
        }
        return text;
    }
}
