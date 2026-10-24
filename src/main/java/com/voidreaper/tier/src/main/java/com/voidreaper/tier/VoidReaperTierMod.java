package com.voidreaper.tier;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Mod(VoidReaperTierMod.MOD_ID)
public final class VoidReaperTierMod {
    public static final String MOD_ID = "voidreapertier";
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern TIER = Pattern.compile("[A-Z]{1,4}T[1-5]");
    private static final Path CONFIG =
            FMLPaths.CONFIGDIR.get().resolve("void-reaper-tier.properties");

    public VoidReaperTierMod() {
        try {
            ensureConfig();
        } catch (IOException e) {
            System.err.println("[VoidReaperTier] Could not create config: " + e.getMessage());
        }
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static void ensureConfig() throws IOException {
        if (Files.exists(CONFIG)) return;
        Files.createDirectories(CONFIG.getParent());
        Properties p = new Properties();
        p.setProperty("endpoint",
                "https://darling-donut-6dc032.netlify.app/.netlify/functions/tier");
        p.setProperty("apiToken", "CHANGE-ME-TO-THE-SAME-TIER_API_TOKEN-FROM-NETLIFY");
        try (Writer w = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
            p.store(w, "Void Reaper tier command. Keep apiToken private; do not share this file.");
        }
    }

    private static Properties config() throws IOException {
        ensureConfig();
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tier")
            .requires(source -> source.hasPermission(2))
            .then(Commands.argument("tier", StringArgumentType.word())
            .then(Commands.argument("player", StringArgumentType.word())
            .then(Commands.argument("scores", StringArgumentType.greedyString())
            .executes(ctx -> {
                String tier = StringArgumentType.getString(ctx, "tier").toUpperCase();
                String player = StringArgumentType.getString(ctx, "player");
                String[] raw = StringArgumentType.getString(ctx, "scores").trim().split("\\s+");

                if (!TIER.matcher(tier).matches()) {
                    ctx.getSource().sendFailure(Component.literal(
                            "Tier must look like LT5, HT5, LT4, or HT1."));
                    return 0;
                }
                if (!USERNAME.matcher(player).matches()) {
                    ctx.getSource().sendFailure(Component.literal(
                            "Player name must be 3-16 letters, numbers, or underscores."));
                    return 0;
                }
                if (raw.length != 10) {
                    ctx.getSource().sendFailure(Component.literal(
                            "Usage: /tier <tier> <player> <10 scores 0-10>"));
                    ctx.getSource().sendFailure(Component.literal(
                            "Example: /tier LT5 Steve 7 8 6 7 6 8 7 8 9 8"));
                    return 0;
                }

                int[] scores = new int[10];
                try {
                    for (int i = 0; i < raw.length; i++) {
                        scores[i] = Integer.parseInt(raw[i]);
                        if (scores[i] < 0 || scores[i] > 10) throw new NumberFormatException();
                    }
                } catch (NumberFormatException ex) {
                    ctx.getSource().sendFailure(Component.literal(
                            "Each score must be a whole number from 0 to 10."));
                    return 0;
                }

                final var source = ctx.getSource();
                CompletableFuture.runAsync(() -> postTier(source, player, tier, scores));
                source.sendSuccess(() -> Component.literal(
                        "Sending " + player + "'s " + tier + " rating to Void Reaper..."), false);
                return 1;
            })))));
    }

    private static void postTier(net.minecraft.commands.CommandSourceStack source,
                                 String player, String tier, int[] scores) {
        try {
            Properties p = config();
            String endpoint = p.getProperty("endpoint", "").trim();
            String token = p.getProperty("apiToken", "").trim();

            if (!endpoint.startsWith("https://") || endpoint.contains("YOUR-SITE")
                    || token.isBlank() || token.startsWith("CHANGE-ME")) {
                source.sendFailure(Component.literal(
                        "Configure endpoint and apiToken in config/void-reaper-tier.properties first."));
                return;
            }

            JsonObject json = new JsonObject();
            json.addProperty("player", player);
            json.addProperty("tier", tier);
            var arr = new com.google.gson.JsonArray();
            for (int score : scores) arr.add(score);
            json.add("scores", arr);

            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("x-api-token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            GSON.toJson(json), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response =
                    HTTP.send(req, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                source.sendSuccess(() -> Component.literal(
                        "Updated " + player + " on Void Reaper: " + tier + " ("
                                + java.util.Arrays.stream(scores).sum() + "/100)."), false);
            } else {
                source.sendFailure(Component.literal(
                        "Void Reaper update failed (HTTP " + response.statusCode()
                                + "). Check Netlify function logs/config."));
                System.err.println("[VoidReaperTier] Endpoint response: " + response.body());
            }
        } catch (Exception ex) {
            source.sendFailure(Component.literal(
                    "Could not reach the Void Reaper endpoint. Check endpoint/token and server internet access."));
            System.err.println("[VoidReaperTier] Request failed: " + ex.getMessage());
        }
    }
}
