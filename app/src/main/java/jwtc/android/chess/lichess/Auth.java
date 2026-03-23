package jwtc.android.chess.lichess;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Auth {
    private static final String TAG = "lichess.Auth";
    private static final String LICHESS_HOST = "https://lichess.org";
    private static final String CLIENT_ID = "lichess-api-demo"; // "lichess-android-client";
    // Request both board play and study read/write so we can import/export studies.
    private static final String[] SCOPES = new String[]{"board:play", "study:read", "study:write"};
    private static final String PREFS_NAME = "AuthPrefs";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";

    private final Context context;
    private final OAuth2AuthCodePKCE oauth;
    private final OkHttpClient httpClient = new OkHttpClient();
    private final OkHttpClient httpStreamClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // infinite timeout
            .build();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    NdJsonStream.Stream eventStream, gameStream, challengeStream, seekStream, studyStream;
    private String accessToken, refreshToken;
    Long expiresAt;


    public interface AuthResponseHandler {
        void onResponse(JsonObject jsonObject);
        void onClose(boolean success);
    }

    public Auth(Context context) {
        this.context = context;
        String redirectUri = "jwtc.android.chess:/oauth2redirect"; // match Android manifest intent filter
        this.oauth = new OAuth2AuthCodePKCE(
                context,
                LICHESS_HOST + "/oauth",
                LICHESS_HOST + "/api/token",
                CLIENT_ID,
                redirectUri,
                SCOPES
        );
    }

    public void login(Activity activity) {
        Log.d(TAG, "login");
        oauth.startAuth(activity);
    }

    public void logout() {
        // /api/token`, { method: 'DELETE' });
        clearTokens();
    }

    public void authenticateWithToken(OAuth2AuthCodePKCE.Callback<String, Exception> callback) {
        // Already logged in, authenticate silently
        authenticate(new OAuth2AuthCodePKCE.Callback<String, Exception>() {
            @Override
            public void onSuccess(String result) {
                Log.d("Auth", "Restored session for " + result);
                mainHandler.post(() -> {
                    callback.onSuccess(result);
                });
            }

            @Override
            public void onError(Exception e) {
                Log.w("Auth", "Failed to restore session", e);
                mainHandler.post(() -> {
                    callback.onError(e);
                });
            }
        });
    }

    public void handleLoginResponse(Intent data, OAuth2AuthCodePKCE.Callback<String, Exception> callback) {
        Log.d(TAG, "handleLoginResponse");

        oauth.handleAuthResponse(data, new OAuth2AuthCodePKCE.Callback<net.openid.appauth.TokenResponse, Exception>() {
            @Override
            public void onSuccess(net.openid.appauth.TokenResponse result) {
                Log.d(TAG, "handleLoginResponse.onSuccess");

                accessToken = result.accessToken;
                refreshToken = result.refreshToken;
                expiresAt = result.accessTokenExpirationTime;

                saveTokens();

                authenticate(callback);
            }

            @Override
            public void onError(Exception e) {
                mainHandler.post(() -> {
                    callback.onError(e);
                });
            }
        });
    }

    public void playing(OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        get("/api/account/playing?nd=5", callback);
    }

    /**
     * List studies of the currently authenticated user.
     * Lichess returns NDJSON; each line is one study JSON object.
     */
    public void listStudies(OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        // The /api/study/by/me endpoint returns studies for the current user.
        // We rely on SCOPES including study:read so private studies are also visible.
        get("/api/study/by/me", callback);
    }

    public void listStudiesByUser(String username, OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        // Use streaming NDJSON for studies, similar to event/game streams.
        if (studyStream != null) {
            studyStream.close();
        }
        studyStream = openStream("/api/study/by/" + username, null, new NdJsonStream.Handler() {
            @Override
            public void onResponse(JsonObject jsonObject) {
                mainHandler.post(() -> callback.onSuccess(jsonObject));
            }

            @Override
            public void onClose(boolean success) {
                mainHandler.post(() -> {
                    studyStream = null;
                    if (!success) {
                        JsonObject error = new JsonObject();
                        error.addProperty("error", "study_stream_closed");
                        callback.onError(error);
                    }
                });
            }
        });
    }

    /**
     * Export all chapters of a study as a single PGN blob.
     * We use a raw string callback instead of JSON because this endpoint returns PGN text.
     */
    public void exportStudyPgn(String studyId, OAuth2AuthCodePKCE.Callback<String, JsonObject> callback) {
        Log.d(TAG, "exportStudyPgn " + studyId);
        Request.Builder reqBuilder = new Request.Builder()
                // Use the API endpoint (Bearer token + study:read). The non-API /study/{id}.pgn endpoint
                // is primarily cookie-based and returns HTML when not authenticated.
                .url(LICHESS_HOST + "/api/study/" + studyId + ".pgn?clocks=true&comments=true&variations=true")
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "application/x-chess-pgn")
                .get();

        httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "exportStudyPgn onFailure " + e);
                mainHandler.post(() -> {
                    // Represent network error as a JSON error payload for the callback
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage() != null ? e.getMessage() : "network_error");
                    callback.onError(error);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    Log.d(TAG, "exportStudyPgn HTTP " + response.code() + " => " + responseBody);
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "http_" + response.code());
                    error.addProperty("body", responseBody);
                    JsonObject finalError = error;
                    mainHandler.post(() -> callback.onError(finalError));
                    return;
                }

                String pgn = response.body() != null ? response.body().string() : "";
                mainHandler.post(() -> callback.onSuccess(pgn));
            }
        });
    }

    /**
     * Import a PGN blob into a study (and implicitly create the study if it does not exist).
     * Endpoint:
     * POST /api/study/{studyId}/import-pgn
     *
     * Requires OAuth2 with `study:write` scope.
     */
    public void importStudyPgn(
            String studyId,
            String pgn,
            String chapterName,
            boolean initial,
            OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback
    ) {
        if (accessToken == null || accessToken.isEmpty()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "no_access_token");
            mainHandler.post(() -> callback.onError(error));
            return;
        }

        // Lichess expects application/x-www-form-urlencoded for most study endpoints.
        okhttp3.FormBody.Builder form = new okhttp3.FormBody.Builder()
                .add("pgn", pgn != null ? pgn : "")
                .add("orientation", "auto")
                .add("mode", "normal")
                .add("initial", initial ? "true" : "false");
        if (chapterName != null && !chapterName.trim().isEmpty()) {
            form.add("name", chapterName.trim());
        }

        Request.Builder reqBuilder = new Request.Builder()
                .url(LICHESS_HOST + "/api/study/" + studyId + "/import-pgn")
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "application/json")
                .post(form.build());

        httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "importStudyPgn onFailure " + e);
                mainHandler.post(() -> {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage() != null ? e.getMessage() : "network_error");
                    callback.onError(error);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    Log.d(TAG, "importStudyPgn HTTP " + response.code() + " => " + responseBody);
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "http_" + response.code());
                    error.addProperty("body", responseBody);
                    JsonObject finalError = error;
                    mainHandler.post(() -> callback.onError(finalError));
                    return;
                }

                try {
                    // Response is typically JSON; but keep it flexible in case it's an array.
                    JsonObject result;
                    com.google.gson.JsonElement parsed = new JsonParser().parse(responseBody);
                    if (parsed != null && parsed.isJsonObject()) {
                        result = parsed.getAsJsonObject();
                        // Some Lichess endpoints may return an error object with HTTP 200.
                        if (result.has("error")
                                && !result.get("error").isJsonNull()
                                && !result.get("error").getAsString().trim().isEmpty()) {
                            JsonObject error = new JsonObject();
                            error.addProperty("error", result.get("error").getAsString());
                            error.addProperty("body", responseBody);
                            mainHandler.post(() -> callback.onError(error));
                            return;
                        }
                    } else {
                        result = new JsonObject();
                        result.add("data", parsed);
                    }
                    mainHandler.post(() -> callback.onSuccess(result));
                } catch (Exception parseEx) {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "json_parse_error");
                    error.addProperty("body", responseBody);
                    error.addProperty("details", parseEx.getMessage() != null ? parseEx.getMessage() : "");
                    mainHandler.post(() -> callback.onError(error));
                }
            }
        });
    }

    public void challenge(Map<String, Object> payload, AuthResponseHandler responseHandler) {
        String username = (String) payload.get("username");
        payload.remove("username");
        payload.put("keepAliveStream", true);

        if (challengeStream != null) {
            challengeStream.close();
        }
        challengeStream = openStream("/api/challenge/" + username, payload, new NdJsonStream.Handler() {
            @Override
            public void onResponse(JsonObject jsonObject) {
                mainHandler.post(() -> {
                    responseHandler.onResponse(jsonObject);
                });
            }

            @Override
            public void onClose(boolean success) {
                mainHandler.post(() -> {
                    responseHandler.onClose(success);
                    challengeStream = null;
                });
            }
        });
    }

    public void seek(Map<String, Object> payload, AuthResponseHandler responseHandler) {
        if (seekStream != null) {
            seekStream.close();
        }
        seekStream = openStream("/api/board/seek", payload, new NdJsonStream.Handler() {
            @Override
            public void onResponse(JsonObject jsonObject) {
                mainHandler.post(() -> {
                    responseHandler.onResponse(jsonObject);
                });
            }

            @Override
            public void onClose(boolean success) {
                mainHandler.post(() -> {
                    responseHandler.onClose(success);
                    seekStream = null;
                });
            }
        });
    }

    public void cancelChallenge() {
        if (challengeStream != null) {
            challengeStream.close();
            challengeStream = null;
        }
    }

    public void cancelSeek() {
        if (seekStream != null) {
            seekStream.close();
            seekStream = null;
        }
    }

    public void acceptChallenge(String challengeId, OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        post("/api/challenge/" + challengeId + "/accept", null, callback);
    }

    public void declineChallenge(String challengeId, OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        post("/api/challenge/" + challengeId + "/decline", null, callback);
    }

    public void event(AuthResponseHandler responseHandler) {
        if (eventStream != null) {
            eventStream.close();
        }
        eventStream = openStream("/api/stream/event", null, new NdJsonStream.Handler() {
            @Override
            public void onResponse(JsonObject jsonObject) {
                mainHandler.post(() -> {
                    responseHandler.onResponse(jsonObject);
                });
            }

            @Override
            public void onClose(boolean success) {
                mainHandler.post(() -> {
                    responseHandler.onClose(success);
                    eventStream = null;
                });
            }
        });
    }

    public void game(String gameId, AuthResponseHandler responseHandler) {
        if (gameStream != null) {
            gameStream.close();
        }
        gameStream = openStream("/api/board/game/stream/" + gameId, null, new NdJsonStream.Handler() {
            @Override
            public void onResponse(JsonObject jsonObject) {
                String type = jsonObject.get("type").getAsString();
                if (type.equals("gameFull") || type.equals("gameState")) {
                    mainHandler.post(() -> {
                        responseHandler.onResponse(jsonObject);
                    });
                }
            }

            @Override
            public void onClose(boolean sucess) {
                mainHandler.post(() -> {
                    responseHandler.onClose(sucess);
                    gameStream = null;
                });
            }
        });
    }

    public void move(String gameId, String move, OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        post("/api/board/game/" + gameId + "/move/" + move, null, callback);
    }

    public void resign(String gameId, OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        post("/api/board/game/" + gameId + "/resign", null, callback);
    }

    public void abort(String gameId, OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        post("/api/board/game/" + gameId + "/abort", null, callback);
    }

    public void draw(String gameId, String accept /* yes|no*/, OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        post("/api/board/game/" + gameId + "/draw/" + accept, null, callback);
    }

    public boolean hasAccessToken() {
        return accessToken != null;
    }

    public void authenticate(OAuth2AuthCodePKCE.Callback<String, Exception> callback) {
        Request req = new Request.Builder()
                .url(LICHESS_HOST + "/api/account")
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> {
                    callback.onError(e);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    mainHandler.post(() -> {
                        callback.onError(new IOException("HTTP " + response.code()));
                    });
                    return;
                }

                String json = response.body().string();
                JsonObject jsonObject = JsonParser.parseString(json).getAsJsonObject();
                Log.d(TAG, json);
                mainHandler.post(() -> {
                    callback.onSuccess(jsonObject.get("id").getAsString());
                });

            }
        });
    }

    public void saveTokens() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACCESS_TOKEN, accessToken)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putLong(KEY_EXPIRES_AT, expiresAt != null ? expiresAt : 0L)
                .apply();
    }

    public void clearTokens() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    public void restoreTokens() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        accessToken = prefs.getString(KEY_ACCESS_TOKEN, null);
        expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L);
        if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) {
            accessToken = null; // expired, force refresh or re-login
        }
    }

    public void closeStreams() {
        Log.d(TAG, "closeStreams");
        if (gameStream != null) {
            gameStream.close();
        }
        if (eventStream != null) {
            eventStream.close();
        }
        if (studyStream != null) {
            studyStream.close();
        }
    }

    public void post(String path, Map<String, Object> jsonBody, OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        Log.d(TAG, "post " + path);
        Request.Builder reqBuilder =  new Request.Builder()
                .url(LICHESS_HOST + path)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "*/*");

        if (jsonBody != null) {
            String json = new Gson().toJson(jsonBody);
            RequestBody body = RequestBody.create(
                    json,
                    MediaType.get("application/json; charset=utf-8")
            );
            reqBuilder.post(body);
        } else {
            reqBuilder.post(RequestBody.create(new byte[0]));
        }

        httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "onFailure " + e);
                mainHandler.post(() -> {
                    //callback.onError(e);
                    // @TODO general error
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String responseBody = response.body().string();
                    try {
                        JsonObject jsonObject = JsonParser.parseString(responseBody).getAsJsonObject();
                        mainHandler.post(() -> {
                            callback.onError(jsonObject);
                        });
                    } catch (Exception ex) {
                        Log.d(TAG, "could not parse " + response.code() + " => " + responseBody);
                    }
                    return;
                }
                Log.d(TAG, "wating for response...");
                String responseBody = response.body().string();
                Log.d(TAG, "responseBody " + responseBody);
                try {
                    String[] lines = responseBody.split("\r?\n");
                    for (String line : lines) {
                        if (line == null || line.trim().isEmpty()) {
                            continue;
                        }

                        JsonObject jsonObject = JsonParser.parseString(line).getAsJsonObject();
                        mainHandler.post(() -> {
                            callback.onSuccess(jsonObject);
                        });
                    }
                } catch (Exception ex) {
                    Log.d(TAG, "Caught " + ex);
                    mainHandler.post(() -> {
                        // callback.onError(ex);
                        // @TODO another general error
                    });
                }
            }
        });
    }

    public void get(String path, OAuth2AuthCodePKCE.Callback<JsonObject, JsonObject> callback) {
        Log.d(TAG, "get " + path);
        Request.Builder reqBuilder =  new Request.Builder()
                .url(LICHESS_HOST + path)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "*/*")
                .get();

        httpClient.newCall(reqBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d(TAG, "onFailure " + e);
                mainHandler.post(() -> {
                    JsonObject error = new JsonObject();
                    error.addProperty("error", e.getMessage() != null ? e.getMessage() : "network_error");
                    callback.onError(error);
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "http_" + response.code());
                    error.addProperty("body", responseBody);
                    mainHandler.post(() -> callback.onError(error));
                    return;
                }
                Log.d(TAG, "wating for response...");
                String responseBody = response.body() != null ? response.body().string() : "";
                Log.d(TAG, "responseBody " + responseBody);
                try {
                    String[] lines = responseBody.split("\r?\n");
                    for (String line : lines) {
                        if (line == null || line.trim().isEmpty()) {
                            continue;
                        }

                        JsonObject jsonObject = JsonParser.parseString(line).getAsJsonObject();
                        mainHandler.post(() -> {
                            callback.onSuccess(jsonObject);
                        });
                    }
                } catch (Exception ex) {
                    Log.d(TAG, "Caught " + ex);
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "parse_error");
                    error.addProperty("message", ex.getMessage() != null ? ex.getMessage() : "parse_error");
                    error.addProperty("body", responseBody);
                    mainHandler.post(() -> callback.onError(error));
                }
            }
        });
    }

    public NdJsonStream.Stream openStream(String path, Map<String, Object> jsonBody, NdJsonStream.Handler handler) {
        Log.d(TAG, "openStream " + path);
        Request.Builder reqBuilder =  new Request.Builder()
                .url(LICHESS_HOST + path)
                .addHeader("Authorization", "Bearer " + accessToken)
                .addHeader("Accept", "*/*");

        if (jsonBody != null) {
            String json = new Gson().toJson(jsonBody);
            Log.d(TAG, "post body " + json);
            RequestBody body = RequestBody.create(
                    json,
                    MediaType.get("application/json; charset=utf-8")
            );
            reqBuilder.post(body);
        }

        Request request = reqBuilder.build();

        return NdJsonStream.readStream("STREAM " + path, httpStreamClient, request, handler);
    }
}