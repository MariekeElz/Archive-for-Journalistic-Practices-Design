import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ImageTrackerServer {
    private static final int PORT = 8080;
    private static final Path STATIC_DIR = Paths.get("static");
    private static final Path IMAGES_DIR = Paths.get("Images");
    private static final Path IMAGE_DATA_FILE = Paths.get("data", "images.json");
    private static final Path USER_DATA_FILE = Paths.get("data", "users.json");
    private static final String SESSION_COOKIE = "image_tracker_session";

    public static void main(String[] args) throws IOException {
        ImageCatalog imageCatalog = new ImageCatalog(IMAGE_DATA_FILE);
        UserStore userStore = new UserStore(USER_DATA_FILE);
        SessionStore sessionStore = new SessionStore();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/images", new ImagesHandler(imageCatalog, userStore, sessionStore));
        server.createContext("/api/auth", new AuthHandler(userStore, sessionStore));
        server.createContext("/api/me", new UserDataHandler(imageCatalog, userStore, sessionStore));
        server.createContext("/images", new StaticFileHandler(IMAGES_DIR, "/images"));
        server.createContext("/", new StaticFileHandler(STATIC_DIR));
        server.setExecutor(Executors.newFixedThreadPool(12));
        server.start();

        System.out.println("Image tracker running on http://localhost:" + PORT);
    }

    private static final class ImagesHandler implements HttpHandler {
        private final ImageCatalog imageCatalog;
        private final UserStore userStore;
        private final SessionStore sessionStore;

        private ImagesHandler(ImageCatalog imageCatalog, UserStore userStore, SessionStore sessionStore) {
            this.imageCatalog = imageCatalog;
            this.userStore = userStore;
            this.sessionStore = sessionStore;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            try {
                if ("/api/images".equals(path)) {
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleList(exchange);
                        return;
                    }
                    if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleCreate(exchange);
                        return;
                    }
                }

                if (path.startsWith("/api/images/")) {
                    String[] parts = path.split("/");
                    if (parts.length == 4 && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleGetOne(exchange, parts[3]);
                        return;
                    }
                    if (parts.length == 4 && "DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleDelete(exchange, parts[3]);
                        return;
                    }
                    if (parts.length == 5 && "favorite".equals(parts[4]) && "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleToggleFavorite(exchange, parts[3]);
                        return;
                    }
                }

                sendJson(exchange, 404, Map.of("error", "Route not found"));
            } catch (UnauthorizedException ex) {
                sendJson(exchange, 401, Map.of("error", ex.getMessage()));
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, Map.of("error", ex.getMessage()));
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, Map.of("error", "Internal server error"));
            } finally {
                exchange.close();
            }
        }

        private void handleList(HttpExchange exchange) throws IOException {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String searchText = query.getOrDefault("query", "").trim();
            String tag = query.getOrDefault("tag", "").trim();

            List<ImageRecord> images = imageCatalog.search(searchText, tag);
            UserRecord currentUser = resolveUser(exchange).orElse(null);
            List<Map<String, Object>> payload = images.stream()
                .map(image -> image.toClientMap(currentUser != null && currentUser.favoriteMap().containsKey(image.id())))
                .collect(Collectors.toList());

            sendJson(exchange, 200, Map.of("images", payload, "count", payload.size()));
        }

        private void handleCreate(HttpExchange exchange) throws IOException {
            requireUser(exchange);
            Map<String, Object> request = parseRequestObject(exchange);

            String title = readRequiredString(request, "title");
            String imageUrl = readRequiredString(request, "imageUrl");

            ImageRecord created = imageCatalog.addImage(new ImageDraft(
                title,
                imageUrl,
                readOptionalString(request, "imageCaption"),
                readOptionalString(request, "imageDescription"),
                readOptionalString(request, "locationTaken"),
                readOptionalString(request, "dateTaken"),
                readOptionalLong(request, "fileSize"),
                readOptionalString(request, "fileType"),
                readOptionalString(request, "resolution"),
                readObjectMap(request.get("cameraMetadata")),
                readOptionalString(request, "colorProfile"),
                readOptionalString(request, "paper"),
                readOptionalString(request, "sourceUrl"),
                readOptionalString(request, "creator"),
                readOptionalString(request, "copyrightStatus"),
                readOptionalString(request, "licenseType"),
                readOptionalString(request, "dateCreated"),
                readOptionalString(request, "dateModified"),
                readOptionalBoolean(request, "editedStatus"),
                readOptionalString(request, "editor"),
                readOptionalString(request, "version"),
                readStringList(request.get("tags")),
                readOptionalString(request, "notes")
            ));

            sendJson(exchange, 201, created.toClientMap(false));
        }

        private void handleGetOne(HttpExchange exchange, String id) throws IOException {
            ImageRecord image = imageCatalog.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Image not found."));
            UserRecord currentUser = resolveUser(exchange).orElse(null);
            boolean favorite = currentUser != null && currentUser.favoriteMap().containsKey(id);
            List<Map<String, Object>> related = imageCatalog.recommendSimilar(id, 4).stream()
                .map(candidate -> candidate.toClientMap(currentUser != null && currentUser.favoriteMap().containsKey(candidate.id())))
                .collect(Collectors.toList());

            sendJson(exchange, 200, Map.of(
                "image", image.toClientMap(favorite),
                "relatedImages", related
            ));
        }

        private void handleDelete(HttpExchange exchange, String id) throws IOException {
            requireUser(exchange);
            boolean removed = imageCatalog.deleteImage(id);
            if (!removed) {
                sendJson(exchange, 404, Map.of("error", "Image not found"));
                return;
            }
            userStore.removeImageReferences(id);
            sendJson(exchange, 200, Map.of("status", "deleted", "id", id));
        }

        private void handleToggleFavorite(HttpExchange exchange, String imageId) throws IOException {
            UserRecord user = requireUser(exchange);
            if (!imageCatalog.exists(imageId)) {
                sendJson(exchange, 404, Map.of("error", "Image not found"));
                return;
            }
            UserRecord updated = userStore.toggleFavorite(user.username(), imageId);
            boolean favorite = updated.favoriteMap().containsKey(imageId);
            sendJson(exchange, 200, Map.of("favorite", favorite, "imageId", imageId));
        }

        private Optional<UserRecord> resolveUser(HttpExchange exchange) {
            String sessionId = readCookie(exchange, SESSION_COOKIE);
            if (sessionId == null) {
                return Optional.empty();
            }
            String username = sessionStore.usernameForSession(sessionId);
            if (username == null) {
                return Optional.empty();
            }
            return userStore.findUser(username);
        }

        private UserRecord requireUser(HttpExchange exchange) {
            return resolveUser(exchange).orElseThrow(() -> new UnauthorizedException("Please log in first."));
        }
    }

    private static final class AuthHandler implements HttpHandler {
        private final UserStore userStore;
        private final SessionStore sessionStore;

        private AuthHandler(UserStore userStore, SessionStore sessionStore) {
            this.userStore = userStore;
            this.sessionStore = sessionStore;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String path = exchange.getRequestURI().getPath();
            try {
                if ("/api/auth/register".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleRegister(exchange);
                    return;
                }
                if ("/api/auth/login".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleLogin(exchange);
                    return;
                }
                if ("/api/auth/logout".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleLogout(exchange);
                    return;
                }
                if ("/api/auth/me".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleMe(exchange);
                    return;
                }
                sendJson(exchange, 404, Map.of("error", "Route not found"));
            } catch (UnauthorizedException ex) {
                sendJson(exchange, 401, Map.of("error", ex.getMessage()));
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, Map.of("error", ex.getMessage()));
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, Map.of("error", "Internal server error"));
            } finally {
                exchange.close();
            }
        }

        private void handleRegister(HttpExchange exchange) throws IOException {
            Map<String, Object> request = parseRequestObject(exchange);
            String username = normalizeUsername(readRequiredString(request, "username"));
            String password = readRequiredString(request, "password");

            UserRecord created = userStore.register(username, password);
            createSession(exchange, created.username());
            sendJson(exchange, 201, Map.of("user", created.toPublicMap()));
        }

        private void handleLogin(HttpExchange exchange) throws IOException {
            Map<String, Object> request = parseRequestObject(exchange);
            String username = normalizeUsername(readRequiredString(request, "username"));
            String password = readRequiredString(request, "password");

            UserRecord user = userStore.authenticate(username, password)
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password."));
            createSession(exchange, user.username());
            sendJson(exchange, 200, Map.of("user", user.toPublicMap()));
        }

        private void handleLogout(HttpExchange exchange) throws IOException {
            String sessionId = readCookie(exchange, SESSION_COOKIE);
            if (sessionId != null) {
                sessionStore.removeSession(sessionId);
            }
            clearSessionCookie(exchange);
            sendJson(exchange, 200, Map.of("status", "logged_out"));
        }

        private void handleMe(HttpExchange exchange) throws IOException {
            String sessionId = readCookie(exchange, SESSION_COOKIE);
            if (sessionId == null) {
                sendJson(exchange, 200, Map.of("user", null));
                return;
            }
            String username = sessionStore.usernameForSession(sessionId);
            if (username == null) {
                clearSessionCookie(exchange);
                sendJson(exchange, 200, Map.of("user", null));
                return;
            }
            UserRecord user = userStore.findUser(username).orElse(null);
            sendJson(exchange, 200, Map.of("user", user == null ? null : user.toPublicMap()));
        }

        private void createSession(HttpExchange exchange, String username) {
            String sessionId = sessionStore.createSession(username);
            exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Lax");
        }

        private void clearSessionCookie(HttpExchange exchange) {
            exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        }
    }

    private static final class UserDataHandler implements HttpHandler {
        private final ImageCatalog imageCatalog;
        private final UserStore userStore;
        private final SessionStore sessionStore;

        private UserDataHandler(ImageCatalog imageCatalog, UserStore userStore, SessionStore sessionStore) {
            this.imageCatalog = imageCatalog;
            this.userStore = userStore;
            this.sessionStore = sessionStore;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange.getResponseHeaders());
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            try {
                UserRecord user = requireUser(exchange);
                String path = exchange.getRequestURI().getPath();

                if ("/api/me/favorites".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleFavorites(exchange, user);
                    return;
                }
                if ("/api/me/folders".equals(path) && "GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleFolders(exchange, user);
                    return;
                }
                if ("/api/me/folders".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleCreateFolder(exchange, user);
                    return;
                }
                if (path.startsWith("/api/me/folders/")) {
                    String folderId = path.substring("/api/me/folders/".length());
                    if ("PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleUpdateFolder(exchange, user, folderId);
                        return;
                    }
                    if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleDeleteFolder(exchange, user, folderId);
                        return;
                    }
                }
                if (path.startsWith("/api/me/favorites/")) {
                    String suffix = path.substring("/api/me/favorites/".length());
                    String[] parts = suffix.split("/");
                    if (parts.length == 2 && "note".equals(parts[1]) && "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleFavoriteNote(exchange, user, parts[0]);
                        return;
                    }
                    if (parts.length == 2 && "folders".equals(parts[1]) && "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleFavoriteFolders(exchange, user, parts[0]);
                        return;
                    }
                }

                sendJson(exchange, 404, Map.of("error", "Route not found"));
            } catch (UnauthorizedException ex) {
                sendJson(exchange, 401, Map.of("error", ex.getMessage()));
            } catch (IllegalArgumentException ex) {
                sendJson(exchange, 400, Map.of("error", ex.getMessage()));
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJson(exchange, 500, Map.of("error", "Internal server error"));
            } finally {
                exchange.close();
            }
        }

        private void handleFavorites(HttpExchange exchange, UserRecord user) throws IOException {
            sendJson(exchange, 200, buildUserDataPayload(user));
        }

        private void handleFolders(HttpExchange exchange, UserRecord user) throws IOException {
            sendJson(exchange, 200, Map.of("folders", user.folderMaps()));
        }

        private void handleCreateFolder(HttpExchange exchange, UserRecord user) throws IOException {
            Map<String, Object> request = parseRequestObject(exchange);
            String name = readRequiredString(request, "name");
            String note = readOptionalString(request, "note");
            UserRecord updated = userStore.createFolder(user.username(), name, note);
            sendJson(exchange, 201, buildUserDataPayload(updated));
        }

        private void handleUpdateFolder(HttpExchange exchange, UserRecord user, String folderId) throws IOException {
            Map<String, Object> request = parseRequestObject(exchange);
            String name = readRequiredString(request, "name");
            String note = readOptionalString(request, "note");
            UserRecord updated = userStore.updateFolder(user.username(), folderId, name, note);
            sendJson(exchange, 200, buildUserDataPayload(updated));
        }

        private void handleDeleteFolder(HttpExchange exchange, UserRecord user, String folderId) throws IOException {
            UserRecord updated = userStore.deleteFolder(user.username(), folderId);
            sendJson(exchange, 200, buildUserDataPayload(updated));
        }

        private void handleFavoriteNote(HttpExchange exchange, UserRecord user, String imageId) throws IOException {
            Map<String, Object> request = parseRequestObject(exchange);
            String note = readOptionalString(request, "note");
            UserRecord updated = userStore.updateFavoriteNote(user.username(), imageId, note);
            sendJson(exchange, 200, buildUserDataPayload(updated));
        }

        private void handleFavoriteFolders(HttpExchange exchange, UserRecord user, String imageId) throws IOException {
            Map<String, Object> request = parseRequestObject(exchange);
            List<String> folderIds = readStringList(request.get("folderIds"));
            UserRecord updated = userStore.assignFavoriteFolders(user.username(), imageId, folderIds);
            sendJson(exchange, 200, buildUserDataPayload(updated));
        }

        private Map<String, Object> buildUserDataPayload(UserRecord user) {
            List<Map<String, Object>> favoriteItems = new ArrayList<>();
            for (FavoriteItem favorite : user.favoriteMap().values()) {
                imageCatalog.findById(favorite.imageId()).ifPresent(image -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("image", image.toClientMap(true));
                    item.put("note", favorite.note());
                    item.put("folderIds", favorite.folderIds());
                    favoriteItems.add(item);
                });
            }
            favoriteItems.sort(Comparator.comparing(item -> String.valueOf(((Map<?, ?>) item.get("image")).get("createdAt")), Comparator.reverseOrder()));
            return Map.of(
                "user", user.toPublicMap(),
                "folders", user.folderMaps(),
                "favorites", favoriteItems
            );
        }

        private UserRecord requireUser(HttpExchange exchange) {
            String sessionId = readCookie(exchange, SESSION_COOKIE);
            if (sessionId == null) {
                throw new UnauthorizedException("Please log in first.");
            }
            String username = sessionStore.usernameForSession(sessionId);
            if (username == null) {
                throw new UnauthorizedException("Please log in first.");
            }
            return userStore.findUser(username).orElseThrow(() -> new UnauthorizedException("Please log in first."));
        }
    }

    private static final class StaticFileHandler implements HttpHandler {
        private final Path root;
        private final String routePrefix;

        private StaticFileHandler(Path root) {
            this(root, "/");
        }

        private StaticFileHandler(Path root, String routePrefix) {
            this.root = root.toAbsolutePath().normalize();
            this.routePrefix = routePrefix;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
            if (routePrefix.equals("/") && requestPath.equals("/")) {
                requestPath = "/index.html";
            }

            String relativePath = requestPath;
            if (!"/".equals(routePrefix)) {
                if (!relativePath.startsWith(routePrefix)) {
                    sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
                    exchange.close();
                    return;
                }
                relativePath = relativePath.substring(routePrefix.length());
                if (relativePath.isBlank() || "/".equals(relativePath)) {
                    sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
                    exchange.close();
                    return;
                }
            }

            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }

            Path resolved = root.resolve(relativePath).normalize();
            if (!resolved.startsWith(root) || Files.isDirectory(resolved) || !Files.exists(resolved)) {
                sendText(exchange, 404, "Not found", "text/plain; charset=utf-8");
                exchange.close();
                return;
            }

            String contentType = detectContentType(resolved);
            byte[] bytes = Files.readAllBytes(resolved);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            } finally {
                exchange.close();
            }
        }

        private String detectContentType(Path file) throws IOException {
            String guessed = Files.probeContentType(file);
            if (guessed != null) {
                return guessed;
            }

            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".css")) {
                return "text/css; charset=utf-8";
            }
            if (name.endsWith(".js")) {
                return "application/javascript; charset=utf-8";
            }
            if (name.endsWith(".html")) {
                return "text/html; charset=utf-8";
            }
            return "application/octet-stream";
        }
    }

    private static final class ImageCatalog {
        private final Path dataFile;
        private final List<ImageRecord> images = new ArrayList<>();

        private ImageCatalog(Path dataFile) throws IOException {
            this.dataFile = dataFile;
            initialize();
        }

        private synchronized void initialize() throws IOException {
            Files.createDirectories(dataFile.getParent());
            if (!Files.exists(dataFile)) {
                seedData();
                save();
                return;
            }

            String json = Files.readString(dataFile, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                seedData();
                save();
                return;
            }

            Object parsed = SimpleJson.parse(json);
            if (!(parsed instanceof List<?> values)) {
                throw new IllegalArgumentException("Image database file must contain a JSON array.");
            }

            images.clear();
            for (Object value : values) {
                if (value instanceof Map<?, ?> record) {
                    images.add(ImageRecord.fromMap(record));
                }
            }
            sortImages();
        }

        private synchronized List<ImageRecord> search(String query, String tag) {
            String normalizedQuery = query.toLowerCase(Locale.ROOT);
            String normalizedTag = tag.toLowerCase(Locale.ROOT);

            return images.stream()
                .filter(image -> normalizedTag.isBlank() || image.tags().stream()
                    .anyMatch(imageTag -> imageTag.toLowerCase(Locale.ROOT).contains(normalizedTag)))
                .filter(image -> normalizedQuery.isBlank() || image.matches(normalizedQuery))
                .sorted(Comparator.comparing(ImageRecord::createdAt).reversed())
                .collect(Collectors.toList());
        }

        private synchronized ImageRecord addImage(ImageDraft draft) throws IOException {
            ImageRecord image = new ImageRecord(
                UUID.randomUUID().toString(),
                draft.title().trim(),
                draft.imageUrl().trim(),
                draft.imageCaption().trim(),
                draft.imageDescription().trim(),
                draft.locationTaken().trim(),
                draft.dateTaken().trim(),
                draft.fileSize(),
                draft.fileType().trim(),
                draft.resolution().trim(),
                new LinkedHashMap<>(draft.cameraMetadata()),
                draft.colorProfile().trim(),
                draft.paper().trim(),
                draft.sourceUrl().trim(),
                draft.creator().trim(),
                draft.copyrightStatus().trim(),
                draft.licenseType().trim(),
                draft.dateCreated().trim(),
                draft.dateModified().trim(),
                draft.editedStatus(),
                draft.editor().trim(),
                draft.version().trim(),
                sanitizeTags(draft.tags()),
                draft.notes().trim(),
                Instant.now().toString()
            );
            images.add(image);
            sortImages();
            save();
            return image;
        }

        private synchronized boolean deleteImage(String id) throws IOException {
            boolean removed = images.removeIf(image -> image.id().equals(id));
            if (removed) {
                save();
            }
            return removed;
        }

        private synchronized boolean exists(String id) {
            return images.stream().anyMatch(image -> image.id().equals(id));
        }

        private synchronized Optional<ImageRecord> findById(String id) {
            return images.stream().filter(image -> image.id().equals(id)).findFirst();
        }

        private synchronized List<ImageRecord> recommendSimilar(String imageId, int limit) {
            ImageRecord target = findById(imageId).orElse(null);
            if (target == null) {
                return List.of();
            }

            return images.stream()
                .filter(image -> !image.id().equals(imageId))
                .map(image -> Map.entry(image, similarityScore(target, image)))
                .filter(entry -> entry.getValue() > 0)
                .sorted((left, right) -> Double.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        }

        private double similarityScore(ImageRecord target, ImageRecord candidate) {
            double score = 0.0;

            if (!target.paper().isBlank() && target.paper().equalsIgnoreCase(candidate.paper())) {
                score += 4.0;
            }

            String targetYear = yearPart(target.dateTaken());
            String candidateYear = yearPart(candidate.dateTaken());
            if (!targetYear.isBlank() && targetYear.equals(candidateYear)) {
                score += 3.0;
            }

            score += titleTokenOverlap(target.title(), candidate.title()) * 2.0;
            if (!target.tags().isEmpty()) {
                long sharedTags = target.tags().stream()
                    .map(tag -> tag.toLowerCase(Locale.ROOT))
                    .filter(tag -> candidate.tags().stream().map(other -> other.toLowerCase(Locale.ROOT)).anyMatch(tag::equals))
                    .count();
                score += sharedTags * 0.5;
            }

            return score;
        }

        private String yearPart(String dateValue) {
            if (dateValue == null || dateValue.length() < 4) {
                return "";
            }
            return dateValue.substring(0, 4);
        }

        private double titleTokenOverlap(String leftTitle, String rightTitle) {
            List<String> leftTokens = titleTokens(leftTitle);
            List<String> rightTokens = titleTokens(rightTitle);
            if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
                return 0.0;
            }
            long overlap = leftTokens.stream().filter(rightTokens::contains).distinct().count();
            return (double) overlap / Math.max(leftTokens.size(), rightTokens.size());
        }

        private List<String> titleTokens(String title) {
            return List.of(title.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).stream()
                .map(String::trim)
                .filter(token -> token.length() > 2)
                .collect(Collectors.toList());
        }

        private void sortImages() {
            images.sort(Comparator.comparing(ImageRecord::createdAt).reversed());
        }

        private List<String> sanitizeTags(List<String> tags) {
            List<String> cleaned = new ArrayList<>();
            for (String tag : tags) {
                String normalized = tag.trim();
                if (!normalized.isEmpty() && !cleaned.contains(normalized)) {
                    cleaned.add(normalized);
                }
            }
            return cleaned;
        }

        private void seedData() {
            images.clear();
            images.add(new ImageRecord(
                UUID.randomUUID().toString(),
                "Misty Mountain Range",
                "https://images.unsplash.com/photo-1500530855697-b586d89ba3ee?auto=format&fit=crop&w=1200&q=80",
                "A dramatic mountain range in morning mist.",
                "A dramatic landscape photo with layered ridges and morning fog.",
                "Innsbruck, Austria",
                "2024-04-10",
                2483201L,
                "JPEG",
                "1920x1080",
                Map.of("camera", "Sony A7 III", "lens", "24-70mm", "iso", "200"),
                "sRGB IEC61966-2.1",
                "Nature Visual Atlas",
                "https://example.org/nature/misty-mountain-range",
                "Elena Fischer",
                "cc licensed",
                "CC-BY 4.0",
                "2024-04-11",
                "2024-04-15",
                false,
                "",
                "1.0",
                List.of("landscape", "mountains", "fog"),
                "Great inspiration for homepage hero imagery.",
                Instant.now().minusSeconds(7200).toString()
            ));
            images.add(new ImageRecord(
                UUID.randomUUID().toString(),
                "Bold Editorial Portrait",
                "https://images.unsplash.com/photo-1494790108377-be9c29b29330?auto=format&fit=crop&w=1200&q=80",
                "Studio portrait with dramatic magazine styling.",
                "High-contrast portrait with a magazine-like art direction.",
                "Rotterdam, Netherlands",
                "2022-09-02",
                1875400L,
                "JPEG",
                "1600x2000",
                Map.of("camera", "Canon EOS R5", "aperture", "f/2.8", "shutter", "1/200"),
                "Adobe RGB",
                "Portrait Studies Quarterly",
                "https://example.org/portraits/bold-editorial",
                "Mila van Dijk",
                "copyrighted",
                "All rights reserved",
                "2022-09-03",
                "2023-01-10",
                true,
                "Research assistant",
                "2.1",
                List.of("portrait", "editorial", "lighting"),
                "Love the color contrast and framing.",
                Instant.now().minusSeconds(3600).toString()
            ));
        }

        private synchronized void save() throws IOException {
            List<Object> serialized = images.stream()
                .map(ImageRecord::toStorageMap)
                .collect(Collectors.toList());
            Files.writeString(dataFile, SimpleJson.stringify(serialized), StandardCharsets.UTF_8);
        }
    }

    private static final class UserStore {
        private final Path dataFile;
        private final Map<String, UserRecord> users = new LinkedHashMap<>();

        private UserStore(Path dataFile) throws IOException {
            this.dataFile = dataFile;
            initialize();
        }

        private synchronized void initialize() throws IOException {
            Files.createDirectories(dataFile.getParent());
            if (!Files.exists(dataFile)) {
                save();
                return;
            }
            String json = Files.readString(dataFile, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                save();
                return;
            }
            Object parsed = SimpleJson.parse(json);
            if (!(parsed instanceof List<?> values)) {
                throw new IllegalArgumentException("User database file must contain a JSON array.");
            }
            users.clear();
            for (Object value : values) {
                if (value instanceof Map<?, ?> record) {
                    UserRecord user = UserRecord.fromMap(record);
                    users.put(user.username(), user);
                }
            }
        }

        private synchronized Optional<UserRecord> findUser(String username) {
            return Optional.ofNullable(users.get(username));
        }

        private synchronized UserRecord register(String username, String password) throws IOException {
            if (username.length() < 3) {
                throw new IllegalArgumentException("Username must be at least 3 characters.");
            }
            if (password.length() < 4) {
                throw new IllegalArgumentException("Password must be at least 4 characters.");
            }
            if (users.containsKey(username)) {
                throw new IllegalArgumentException("That username is already taken.");
            }
            String salt = UUID.randomUUID().toString();
            UserRecord user = new UserRecord(
                username,
                hashPassword(password, salt),
                salt,
                new LinkedHashMap<>(),
                new LinkedHashMap<>()
            );
            users.put(username, user);
            save();
            return user;
        }

        private synchronized Optional<UserRecord> authenticate(String username, String password) {
            UserRecord user = users.get(username);
            if (user == null) {
                return Optional.empty();
            }
            String hash = hashPassword(password, user.passwordSalt());
            return hash.equals(user.passwordHash()) ? Optional.of(user) : Optional.empty();
        }

        private synchronized UserRecord toggleFavorite(String username, String imageId) throws IOException {
            UserRecord user = requireUser(username);
            Map<String, FavoriteItem> favorites = new LinkedHashMap<>(user.favoriteMap());
            if (favorites.containsKey(imageId)) {
                favorites.remove(imageId);
            } else {
                favorites.put(imageId, new FavoriteItem(imageId, "", List.of()));
            }
            return persistUser(user.withFavorites(favorites));
        }

        private synchronized UserRecord updateFavoriteNote(String username, String imageId, String note) throws IOException {
            UserRecord user = requireUser(username);
            FavoriteItem item = user.favoriteMap().get(imageId);
            if (item == null) {
                throw new IllegalArgumentException("Favorite image not found.");
            }
            Map<String, FavoriteItem> favorites = new LinkedHashMap<>(user.favoriteMap());
            favorites.put(imageId, item.withNote(note));
            return persistUser(user.withFavorites(favorites));
        }

        private synchronized UserRecord assignFavoriteFolders(String username, String imageId, List<String> folderIds) throws IOException {
            UserRecord user = requireUser(username);
            FavoriteItem item = user.favoriteMap().get(imageId);
            if (item == null) {
                throw new IllegalArgumentException("Favorite image not found.");
            }
            List<String> validFolderIds = folderIds.stream()
                .filter(id -> user.folderMap().containsKey(id))
                .distinct()
                .collect(Collectors.toList());
            Map<String, FavoriteItem> favorites = new LinkedHashMap<>(user.favoriteMap());
            favorites.put(imageId, item.withFolderIds(validFolderIds));
            return persistUser(user.withFavorites(favorites));
        }

        private synchronized UserRecord createFolder(String username, String name, String note) throws IOException {
            UserRecord user = requireUser(username);
            Map<String, FolderRecord> folders = new LinkedHashMap<>(user.folderMap());
            FolderRecord folder = new FolderRecord(UUID.randomUUID().toString(), name.trim(), note.trim(), Instant.now().toString());
            folders.put(folder.id(), folder);
            return persistUser(user.withFolders(folders));
        }

        private synchronized UserRecord updateFolder(String username, String folderId, String name, String note) throws IOException {
            UserRecord user = requireUser(username);
            FolderRecord folder = user.folderMap().get(folderId);
            if (folder == null) {
                throw new IllegalArgumentException("Folder not found.");
            }
            Map<String, FolderRecord> folders = new LinkedHashMap<>(user.folderMap());
            folders.put(folderId, folder.withNameAndNote(name.trim(), note.trim()));
            return persistUser(user.withFolders(folders));
        }

        private synchronized UserRecord deleteFolder(String username, String folderId) throws IOException {
            UserRecord user = requireUser(username);
            if (!user.folderMap().containsKey(folderId)) {
                throw new IllegalArgumentException("Folder not found.");
            }
            Map<String, FolderRecord> folders = new LinkedHashMap<>(user.folderMap());
            folders.remove(folderId);
            Map<String, FavoriteItem> favorites = new LinkedHashMap<>();
            for (FavoriteItem item : user.favoriteMap().values()) {
                favorites.put(item.imageId(), item.withFolderIds(item.folderIds().stream()
                    .filter(id -> !id.equals(folderId))
                    .collect(Collectors.toList())));
            }
            return persistUser(user.withFoldersAndFavorites(folders, favorites));
        }

        private synchronized void removeImageReferences(String imageId) throws IOException {
            boolean changed = false;
            Map<String, UserRecord> nextUsers = new LinkedHashMap<>();
            for (UserRecord user : users.values()) {
                if (user.favoriteMap().containsKey(imageId)) {
                    Map<String, FavoriteItem> favorites = new LinkedHashMap<>(user.favoriteMap());
                    favorites.remove(imageId);
                    nextUsers.put(user.username(), user.withFavorites(favorites));
                    changed = true;
                } else {
                    nextUsers.put(user.username(), user);
                }
            }
            if (changed) {
                users.clear();
                users.putAll(nextUsers);
                save();
            }
        }

        private UserRecord requireUser(String username) {
            UserRecord user = users.get(username);
            if (user == null) {
                throw new IllegalArgumentException("User not found.");
            }
            return user;
        }

        private UserRecord persistUser(UserRecord user) throws IOException {
            users.put(user.username(), user);
            save();
            return user;
        }

        private synchronized void save() throws IOException {
            List<Object> payload = users.values().stream()
                .map(UserRecord::toStorageMap)
                .collect(Collectors.toList());
            Files.writeString(dataFile, SimpleJson.stringify(payload), StandardCharsets.UTF_8);
        }
    }

    private static final class SessionStore {
        private final Map<String, String> sessions = new ConcurrentHashMap<>();

        private String createSession(String username) {
            String sessionId = UUID.randomUUID().toString();
            sessions.put(sessionId, username);
            return sessionId;
        }

        private String usernameForSession(String sessionId) {
            return sessions.get(sessionId);
        }

        private void removeSession(String sessionId) {
            sessions.remove(sessionId);
        }
    }

    private record ImageDraft(
        String title,
        String imageUrl,
        String imageCaption,
        String imageDescription,
        String locationTaken,
        String dateTaken,
        long fileSize,
        String fileType,
        String resolution,
        Map<String, Object> cameraMetadata,
        String colorProfile,
        String paper,
        String sourceUrl,
        String creator,
        String copyrightStatus,
        String licenseType,
        String dateCreated,
        String dateModified,
        boolean editedStatus,
        String editor,
        String version,
        List<String> tags,
        String notes
    ) {
    }

    private record ImageRecord(
        String id,
        String title,
        String imageUrl,
        String imageCaption,
        String imageDescription,
        String locationTaken,
        String dateTaken,
        long fileSize,
        String fileType,
        String resolution,
        Map<String, Object> cameraMetadata,
        String colorProfile,
        String paper,
        String sourceUrl,
        String creator,
        String copyrightStatus,
        String licenseType,
        String dateCreated,
        String dateModified,
        boolean editedStatus,
        String editor,
        String version,
        List<String> tags,
        String notes,
        String createdAt
    ) {
        private boolean matches(String query) {
            return title.toLowerCase(Locale.ROOT).contains(query)
                || imageUrl.toLowerCase(Locale.ROOT).contains(query)
                || imageCaption.toLowerCase(Locale.ROOT).contains(query)
                || imageDescription.toLowerCase(Locale.ROOT).contains(query)
                || locationTaken.toLowerCase(Locale.ROOT).contains(query)
                || dateTaken.toLowerCase(Locale.ROOT).contains(query)
                || fileType.toLowerCase(Locale.ROOT).contains(query)
                || resolution.toLowerCase(Locale.ROOT).contains(query)
                || colorProfile.toLowerCase(Locale.ROOT).contains(query)
                || paper.toLowerCase(Locale.ROOT).contains(query)
                || sourceUrl.toLowerCase(Locale.ROOT).contains(query)
                || creator.toLowerCase(Locale.ROOT).contains(query)
                || copyrightStatus.toLowerCase(Locale.ROOT).contains(query)
                || licenseType.toLowerCase(Locale.ROOT).contains(query)
                || dateCreated.toLowerCase(Locale.ROOT).contains(query)
                || dateModified.toLowerCase(Locale.ROOT).contains(query)
                || editor.toLowerCase(Locale.ROOT).contains(query)
                || version.toLowerCase(Locale.ROOT).contains(query)
                || SimpleJson.stringify(cameraMetadata).toLowerCase(Locale.ROOT).contains(query)
                || notes.toLowerCase(Locale.ROOT).contains(query)
                || tags.stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(query));
        }

        private Map<String, Object> toClientMap(boolean favorite) {
            Map<String, Object> map = toStorageMap();
            map.put("favorite", favorite);
            return map;
        }

        private Map<String, Object> toStorageMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("title", title);
            map.put("imageUrl", imageUrl);
            map.put("imageCaption", imageCaption);
            map.put("imageDescription", imageDescription);
            map.put("locationTaken", locationTaken);
            map.put("dateTaken", dateTaken);
            map.put("fileSize", fileSize);
            map.put("fileType", fileType);
            map.put("resolution", resolution);
            map.put("cameraMetadata", cameraMetadata);
            map.put("colorProfile", colorProfile);
            map.put("paper", paper);
            map.put("sourceUrl", sourceUrl);
            map.put("creator", creator);
            map.put("copyrightStatus", copyrightStatus);
            map.put("licenseType", licenseType);
            map.put("dateCreated", dateCreated);
            map.put("dateModified", dateModified);
            map.put("editedStatus", editedStatus);
            map.put("editor", editor);
            map.put("version", version);
            map.put("tags", tags);
            map.put("notes", notes);
            map.put("createdAt", createdAt);
            return map;
        }

        private static ImageRecord fromMap(Map<?, ?> map) {
            return new ImageRecord(
                stringValue(map.get("id")),
                stringValue(map.get("title")),
                stringValue(map.get("imageUrl")),
                stringValue(map.get("imageCaption")),
                stringValue(map.get("imageDescription")),
                stringValue(map.get("locationTaken")),
                stringValue(map.get("dateTaken")),
                longValue(map.get("fileSize")),
                stringValue(map.get("fileType")),
                stringValue(map.get("resolution")),
                objectValue(map.get("cameraMetadata")),
                stringValue(map.get("colorProfile")),
                stringValue(map.get("paper")),
                stringValue(map.get("sourceUrl")),
                stringValue(map.get("creator")),
                stringValue(map.get("copyrightStatus")),
                stringValue(map.get("licenseType")),
                stringValue(map.get("dateCreated")),
                stringValue(map.get("dateModified")),
                booleanValue(map.get("editedStatus")),
                stringValue(map.get("editor")),
                stringValue(map.get("version")),
                stringListValue(map.get("tags")),
                stringValue(map.get("notes")),
                stringValue(map.get("createdAt"))
            );
        }
    }

    private record FavoriteItem(String imageId, String note, List<String> folderIds) {
        private FavoriteItem withNote(String nextNote) {
            return new FavoriteItem(imageId, nextNote, folderIds);
        }

        private FavoriteItem withFolderIds(List<String> nextFolderIds) {
            return new FavoriteItem(imageId, note, nextFolderIds);
        }

        private Map<String, Object> toMap() {
            return Map.of(
                "imageId", imageId,
                "note", note,
                "folderIds", folderIds
            );
        }

        private static FavoriteItem fromMap(Map<?, ?> map) {
            return new FavoriteItem(
                stringValue(map.get("imageId")),
                stringValue(map.get("note")),
                stringListValue(map.get("folderIds"))
            );
        }
    }

    private record FolderRecord(String id, String name, String note, String createdAt) {
        private FolderRecord withNameAndNote(String nextName, String nextNote) {
            return new FolderRecord(id, nextName, nextNote, createdAt);
        }

        private Map<String, Object> toMap() {
            return Map.of(
                "id", id,
                "name", name,
                "note", note,
                "createdAt", createdAt
            );
        }

        private static FolderRecord fromMap(Map<?, ?> map) {
            return new FolderRecord(
                stringValue(map.get("id")),
                stringValue(map.get("name")),
                stringValue(map.get("note")),
                stringValue(map.get("createdAt"))
            );
        }
    }

    private record UserRecord(
        String username,
        String passwordHash,
        String passwordSalt,
        Map<String, FolderRecord> folderMap,
        Map<String, FavoriteItem> favoriteMap
    ) {
        private Map<String, Object> toPublicMap() {
            return Map.of("username", username);
        }

        private List<Map<String, Object>> folderMaps() {
            return folderMap.values().stream()
                .sorted(Comparator.comparing(FolderRecord::createdAt))
                .map(FolderRecord::toMap)
                .collect(Collectors.toList());
        }

        private UserRecord withFavorites(Map<String, FavoriteItem> nextFavorites) {
            return new UserRecord(username, passwordHash, passwordSalt, folderMap, nextFavorites);
        }

        private UserRecord withFolders(Map<String, FolderRecord> nextFolders) {
            return new UserRecord(username, passwordHash, passwordSalt, nextFolders, favoriteMap);
        }

        private UserRecord withFoldersAndFavorites(Map<String, FolderRecord> nextFolders, Map<String, FavoriteItem> nextFavorites) {
            return new UserRecord(username, passwordHash, passwordSalt, nextFolders, nextFavorites);
        }

        private Map<String, Object> toStorageMap() {
            List<Object> folders = folderMap.values().stream().map(FolderRecord::toMap).collect(Collectors.toList());
            List<Object> favorites = favoriteMap.values().stream().map(FavoriteItem::toMap).collect(Collectors.toList());
            return Map.of(
                "username", username,
                "passwordHash", passwordHash,
                "passwordSalt", passwordSalt,
                "folders", folders,
                "favorites", favorites
            );
        }

        private static UserRecord fromMap(Map<?, ?> map) {
            Map<String, FolderRecord> folders = new LinkedHashMap<>();
            for (Object value : listValue(map.get("folders"))) {
                if (value instanceof Map<?, ?> rawFolder) {
                    FolderRecord folder = FolderRecord.fromMap(rawFolder);
                    folders.put(folder.id(), folder);
                }
            }
            Map<String, FavoriteItem> favorites = new LinkedHashMap<>();
            for (Object value : listValue(map.get("favorites"))) {
                if (value instanceof Map<?, ?> rawFavorite) {
                    FavoriteItem favorite = FavoriteItem.fromMap(rawFavorite);
                    favorites.put(favorite.imageId(), favorite);
                }
            }
            return new UserRecord(
                stringValue(map.get("username")),
                stringValue(map.get("passwordHash")),
                stringValue(map.get("passwordSalt")),
                folders,
                favorites
            );
        }
    }

    private static final class UnauthorizedException extends RuntimeException {
        private UnauthorizedException(String message) {
            super(message);
        }
    }

    private static final class SimpleJson {
        private SimpleJson() {
        }

        private static Object parse(String json) {
            Parser parser = new Parser(json);
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (!parser.isAtEnd()) {
                throw new IllegalArgumentException("Unexpected trailing JSON content.");
            }
            return value;
        }

        private static String stringify(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof String stringValue) {
                return "\"" + escape(stringValue) + "\"";
            }
            if (value instanceof Number || value instanceof Boolean) {
                return String.valueOf(value);
            }
            if (value instanceof Map<?, ?> mapValue) {
                List<String> pairs = new ArrayList<>();
                for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                    pairs.add(stringify(String.valueOf(entry.getKey())) + ":" + stringify(entry.getValue()));
                }
                return "{" + String.join(",", pairs) + "}";
            }
            if (value instanceof List<?> listValue) {
                return "[" + listValue.stream().map(SimpleJson::stringify).collect(Collectors.joining(",")) + "]";
            }
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
        }

        private static String escape(String value) {
            StringBuilder escaped = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                switch (current) {
                    case '\\' -> escaped.append("\\\\");
                    case '"' -> escaped.append("\\\"");
                    case '\b' -> escaped.append("\\b");
                    case '\f' -> escaped.append("\\f");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (current < 0x20) {
                            escaped.append(String.format("\\u%04x", (int) current));
                        } else {
                            escaped.append(current);
                        }
                    }
                }
            }
            return escaped.toString();
        }

        private static final class Parser {
            private final String json;
            private int position;

            private Parser(String json) {
                this.json = json;
            }

            private Object parseValue() {
                skipWhitespace();
                if (isAtEnd()) {
                    throw new IllegalArgumentException("Unexpected end of JSON input.");
                }
                char current = json.charAt(position);
                return switch (current) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> parseString();
                    case 't' -> parseLiteral("true", Boolean.TRUE);
                    case 'f' -> parseLiteral("false", Boolean.FALSE);
                    case 'n' -> parseLiteral("null", null);
                    default -> parseNumber();
                };
            }

            private Map<String, Object> parseObject() {
                position++;
                Map<String, Object> map = new LinkedHashMap<>();
                skipWhitespace();
                if (peek('}')) {
                    position++;
                    return map;
                }
                while (true) {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    expect(':');
                    Object value = parseValue();
                    map.put(key, value);
                    skipWhitespace();
                    if (peek('}')) {
                        position++;
                        return map;
                    }
                    expect(',');
                }
            }

            private List<Object> parseArray() {
                position++;
                List<Object> list = new ArrayList<>();
                skipWhitespace();
                if (peek(']')) {
                    position++;
                    return list;
                }
                while (true) {
                    list.add(parseValue());
                    skipWhitespace();
                    if (peek(']')) {
                        position++;
                        return list;
                    }
                    expect(',');
                }
            }

            private String parseString() {
                expect('"');
                StringBuilder builder = new StringBuilder();
                while (!isAtEnd()) {
                    char current = json.charAt(position++);
                    if (current == '"') {
                        return builder.toString();
                    }
                    if (current == '\\') {
                        if (isAtEnd()) {
                            throw new IllegalArgumentException("Invalid JSON escape.");
                        }
                        char escaped = json.charAt(position++);
                        switch (escaped) {
                            case '"', '\\', '/' -> builder.append(escaped);
                            case 'b' -> builder.append('\b');
                            case 'f' -> builder.append('\f');
                            case 'n' -> builder.append('\n');
                            case 'r' -> builder.append('\r');
                            case 't' -> builder.append('\t');
                            case 'u' -> {
                                if (position + 4 > json.length()) {
                                    throw new IllegalArgumentException("Invalid unicode escape.");
                                }
                                String hex = json.substring(position, position + 4);
                                builder.append((char) Integer.parseInt(hex, 16));
                                position += 4;
                            }
                            default -> throw new IllegalArgumentException("Unsupported JSON escape sequence.");
                        }
                    } else {
                        builder.append(current);
                    }
                }
                throw new IllegalArgumentException("Unterminated JSON string.");
            }

            private Object parseLiteral(String literal, Object value) {
                if (json.startsWith(literal, position)) {
                    position += literal.length();
                    return value;
                }
                throw new IllegalArgumentException("Invalid JSON literal.");
            }

            private Number parseNumber() {
                int start = position;
                while (!isAtEnd()) {
                    char current = json.charAt(position);
                    if ((current >= '0' && current <= '9') || current == '-' || current == '+' || current == '.' || current == 'e' || current == 'E') {
                        position++;
                    } else {
                        break;
                    }
                }
                String value = json.substring(start, position);
                if (value.contains(".") || value.contains("e") || value.contains("E")) {
                    return Double.parseDouble(value);
                }
                return Long.parseLong(value);
            }

            private void expect(char expected) {
                skipWhitespace();
                if (isAtEnd() || json.charAt(position) != expected) {
                    throw new IllegalArgumentException("Expected '" + expected + "' in JSON input.");
                }
                position++;
            }

            private boolean peek(char expected) {
                return !isAtEnd() && json.charAt(position) == expected;
            }

            private void skipWhitespace() {
                while (!isAtEnd()) {
                    char current = json.charAt(position);
                    if (current == ' ' || current == '\n' || current == '\r' || current == '\t') {
                        position++;
                    } else {
                        break;
                    }
                }
            }

            private boolean isAtEnd() {
                return position >= json.length();
            }
        }
    }

    private static Map<String, Object> parseRequestObject(HttpExchange exchange) throws IOException {
        Object parsed = SimpleJson.parse(readBody(exchange));
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Request body must be a JSON object.");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private static String readRequiredString(Map<String, Object> map, String key) {
        String value = readOptionalString(map, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value;
    }

    private static String readOptionalString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long readOptionalLong(Map<String, Object> map, String key) {
        String value = readOptionalString(map, key);
        if (value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value);
    }

    private static boolean readOptionalBoolean(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean rawBoolean) {
            return rawBoolean;
        }
        return Boolean.parseBoolean(readOptionalString(map, key));
    }

    private static Map<String, Object> readObjectMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return map;
        }
        return Map.of();
    }

    private static List<String> readStringList(Object rawValue) {
        if (rawValue == null) {
            return List.of();
        }
        if (rawValue instanceof List<?> values) {
            List<String> result = new ArrayList<>();
            for (Object value : values) {
                String cleaned = String.valueOf(value).trim();
                if (!cleaned.isEmpty()) {
                    result.add(cleaned);
                }
            }
            return result;
        }
        String singleValue = String.valueOf(rawValue).trim();
        if (singleValue.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : singleValue.split(",")) {
            String cleaned = value.trim();
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private static List<String> stringListValue(Object rawValue) {
        return readStringList(rawValue);
    }

    private static List<Object> listValue(Object rawValue) {
        if (rawValue instanceof List<?> rawList) {
            return new ArrayList<>(rawList);
        }
        return List.of();
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean rawBoolean) {
            return rawBoolean;
        }
        return Boolean.parseBoolean(stringValue(value));
    }

    private static long longValue(Object value) {
        if (value instanceof Number rawNumber) {
            return rawNumber.longValue();
        }
        String asString = stringValue(value);
        if (asString.isBlank()) {
            return 0L;
        }
        return Long.parseLong(asString);
    }

    private static Map<String, Object> objectValue(Object value) {
        Map<String, Object> object = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> rawMap) {
            for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                object.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return object;
    }

    private static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((salt + ":" + password).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static void addCorsHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream input = exchange.getRequestBody()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> values = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] parts = pair.split("=", 2);
            String key = urlDecode(parts[0]);
            String value = parts.length > 1 ? urlDecode(parts[1]) : "";
            values.put(key, value);
        }
        return values;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String readCookie(HttpExchange exchange, String name) {
        List<String> cookies = exchange.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return null;
        }
        for (String header : cookies) {
            for (String cookie : header.split(";")) {
                String[] parts = cookie.trim().split("=", 2);
                if (parts.length == 2 && name.equals(parts[0])) {
                    return parts[1];
                }
            }
        }
        return null;
    }

    private static void sendJson(HttpExchange exchange, int statusCode, Object payload) throws IOException {
        sendText(exchange, statusCode, SimpleJson.stringify(payload), "application/json; charset=utf-8");
    }

    private static void sendText(HttpExchange exchange, int statusCode, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
