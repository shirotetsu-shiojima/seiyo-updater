package jp.seiyo.updater;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class SeiyoTools {
    public static void main(String[] args) {
        try {
            if (args.length == 2 && args[0].equals("editor")) {
                editor(Path.of(args[1]));
                return;
            }
            if (args.length >= 2 && args[0].equals("server")
                    && (args.length == 2 || args.length > 3 && args[2].equals("--"))) {
                List<String> command = args.length == 2 ? List.of() : Arrays.asList(args).subList(3, args.length);
                System.exit(ServerUpdates.run(Path.of(args[1]), command));
            }
            throw new IllegalArgumentException("使い方: editor <HTML> または server <サーバーのフォルダー> [-- <起動コマンド>]");
        } catch (Exception exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }

    private static void editor(Path htmlFile) throws Exception {
        String token = UUID.randomUUID().toString();
        String html = Files.readString(htmlFile).replace("<head>",
            "<head>\n<meta name=\"seiyo-drive-token\" content=\"" + token + "\">");
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        String address = "127.0.0.1:" + http.getAddress().getPort();
        http.createContext("/", exchange -> {
            try (exchange) {
                if (!address.equals(exchange.getRequestHeaders().getFirst("Host"))
                        || !exchange.getRequestMethod().equals("GET")) {
                    send(exchange, 403, "text/plain; charset=utf-8", new byte[0]);
                    return;
                }
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/")) {
                    send(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
                } else if (path.equals("/drive") && token.equals(exchange.getRequestHeaders().getFirst("X-Seiyo-Drive"))) {
                    serveDrive(exchange);
                } else {
                    send(exchange, 404, "text/plain; charset=utf-8", new byte[0]);
                }
            }
        });
        http.start();
        URI url = URI.create("http://" + address + "/");
        System.out.println("配布ジェネレーター: " + url);
        System.out.println("使用中はこのウィンドウを開いたままにしてください。");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(url);
            }
        } catch (IOException ignored) {
            // The printed local URL can also be opened manually.
        }
    }

    private static void serveDrive(HttpExchange exchange) throws IOException {
        Downloads.Result result;
        try {
            String query = exchange.getRequestURI().getRawQuery();
            if (query == null || query.length() > 4096) throw new IOException("共有 URL を確認してください。");
            String url = Downloads.parameters(query).get("url");
            if (url == null || url.length() > 2048) throw new IOException("共有 URL を確認してください。");
            URI source = Downloads.https(URI.create(url));
            if (!Downloads.isDrive(source)) throw new IOException("補助ツールで取得できるのは Google ドライブのファイルです。");
            result = Downloads.fetch(source, Catalog.MAX_FILE);
        } catch (IOException | IllegalArgumentException error) {
            send(exchange, 502, "text/plain; charset=utf-8", error.getMessage().getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (result.disposition() != null) {
            exchange.getResponseHeaders().set("Content-Disposition", result.disposition());
        }
        send(exchange, 200, "application/octet-stream", result.data());
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) exchange.getResponseBody().write(body);
    }
}
