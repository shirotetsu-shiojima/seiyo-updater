package jp.seiyo.updater;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class Downloads {
    private static final String DRIVE = "drive.google.com";
    private static final String CONTENT = "drive.usercontent.google.com";
    private static final Pattern DRIVE_FILE = Pattern.compile(
        "^/file/(?:u/[0-9]+/)?d/([A-Za-z0-9_-]+)(?:/(?:view|preview|edit))?/?$");
    private static final Pattern FORM = Pattern.compile("<form\\b([^>]*)>(.*?)</form\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern INPUT = Pattern.compile("<input\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z][a-zA-Z-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);

    record Result(byte[] data, String disposition, String contentType) {}

    static URI https(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IOException("HTTPS の配布 URL を指定してください。");
        }
        return uri;
    }

    static boolean isDrive(URI uri) {
        return DRIVE.equalsIgnoreCase(uri.getHost()) || CONTENT.equalsIgnoreCase(uri.getHost());
    }

    static Result fetch(URI location, int limit) throws IOException {
        boolean drive = isDrive(location);
        URI current = https(location);
        String driveId = null;
        var originalParameters = drive ? parameters(current.getRawQuery()) : Map.<String, String>of();
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        if (drive) {
            var match = DRIVE_FILE.matcher(current.getPath());
            if (DRIVE.equalsIgnoreCase(current.getHost()) && match.matches()) {
                driveId = match.group(1);
            } else if ((DRIVE.equalsIgnoreCase(current.getHost()) && current.getPath().matches("/(?:open|uc)/?"))
                    || (CONTENT.equalsIgnoreCase(current.getHost()) && current.getPath().equals("/download"))) {
                driveId = originalParameters.get("id");
            }
            if (driveId == null || !driveId.matches("[A-Za-z0-9_-]+") || current.getPort() != -1) {
                throw new IOException("Google ドライブの JAR／ZIP の共有 URL を指定してください。");
            }
            var download = new LinkedHashMap<String, String>();
            download.put("id", driveId);
            download.put("export", "download");
            download.put("confirm", originalParameters.getOrDefault("confirm", "t"));
            for (String key : Set.of("uuid", "resourcekey")) {
                if (originalParameters.containsKey(key)) download.put(key, originalParameters.get(key));
            }
            current = driveUrl(download);
        }

        boolean confirmed = false;
        for (int hop = 0; hop < 8; hop++) {
            if (drive && !allowedDriveHost(current)) throw driveUnavailable();
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "SeiyoUpdater/1.6");
            if (drive) {
                cookies.get(current, Map.of()).forEach((name, values) ->
                    connection.setRequestProperty(name, String.join("; ", values)));
            }
            try {
                int status = connection.getResponseCode();
                if (drive) cookies.put(current, connection.getHeaderFields());
                if (status == 200) {
                    String type = connection.getContentType();
                    if (drive && type != null && type.toLowerCase(Locale.ROOT).contains("text/html")) {
                        if (confirmed) throw driveUnavailable();
                        try (var body = connection.getInputStream()) {
                            var submitted = confirmation(new String(Catalog.read(body, Catalog.MAX_JSON),
                                StandardCharsets.UTF_8), current, driveId, originalParameters.get("resourcekey"));
                            if (submitted == null) throw driveUnavailable();
                            current = submitted;
                            confirmed = true;
                            continue;
                        }
                    }
                    if (connection.getContentLengthLong() > limit) {
                        throw new IOException("配布ファイルがサイズ上限を超えています。");
                    }
                    try (var body = connection.getInputStream()) {
                        return new Result(Catalog.read(body, limit), connection.getHeaderField("Content-Disposition"), type);
                    }
                }
                String redirect = connection.getHeaderField("Location");
                if (!REDIRECTS.contains(status) || redirect == null) {
                    if (drive) throw new IOException("Google ドライブ HTTP " + status + "。共有設定、ダウンロード制限、利用上限を確認してください。");
                    throw new IOException("配布先で HTTP " + status + " が返されました。");
                }
                current = https(current.resolve(redirect));
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("配布先の URL が何度も転送されました。");
    }

    private static boolean allowedDriveHost(URI uri) {
        if (uri.getPort() != -1) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return isDrive(uri) || host.endsWith(".googleusercontent.com") && host.startsWith("doc-");
    }

    private static IOException driveUnavailable() {
        return new IOException("Google ドライブがファイルを返しません。ログインせず取得できる共有権限とダウンロード制限を確認してください。");
    }

    static Map<String, String> parameters(String query) {
        var result = new LinkedHashMap<String, String>();
        if (query == null) return result;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) result.putIfAbsent(
                URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static URI driveUrl(Map<String, String> fields) {
        var query = new java.util.StringJoiner("&");
        fields.forEach((key, value) -> query.add(key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return URI.create("https://" + CONTENT + "/download?" + query);
    }

    static URI confirmation(String html, URI page, String id, String resourceKey) {
        var forms = FORM.matcher(html);
        while (forms.find()) {
            String action = attributes(forms.group(1)).get("action");
            if (action == null) continue;
            URI target;
            try {
                target = page.resolve(action);
            } catch (IllegalArgumentException invalid) {
                continue;
            }
            if (!CONTENT.equalsIgnoreCase(target.getHost()) || !"/download".equals(target.getPath())) continue;
            var values = parameters(target.getRawQuery());
            var inputs = INPUT.matcher(forms.group(2));
            while (inputs.find()) {
                var fields = attributes(inputs.group(1));
                String name = fields.get("name");
                if (name != null && fields.containsKey("value")) values.put(name, fields.get("value"));
            }
            if (!id.equals(values.get("id")) || !values.containsKey("confirm")) continue;
            values.keySet().retainAll(Set.of("id", "export", "confirm", "uuid", "resourcekey"));
            values.putIfAbsent("export", "download");
            if (resourceKey != null) values.putIfAbsent("resourcekey", resourceKey);
            return driveUrl(values);
        }
        return null;
    }

    private static Map<String, String> attributes(String html) {
        var result = new LinkedHashMap<String, String>();
        var attributes = ATTR.matcher(html);
        while (attributes.find()) {
            String value = attributes.group(2);
            if (value == null) value = attributes.group(3);
            if (value == null) value = attributes.group(4);
            result.put(attributes.group(1).toLowerCase(Locale.ROOT),
                value.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&"));
        }
        return result;
    }

    private Downloads() {}
}
