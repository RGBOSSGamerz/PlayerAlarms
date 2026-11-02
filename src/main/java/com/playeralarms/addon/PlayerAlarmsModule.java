package com.playeralarms.addon;

import autismclient.api.module.SimpleModule;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PlayerAlarmsModule extends SimpleModule {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    private static final long DISCORD_RATE_LIMIT_MS = 10_000L;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Set<String> playersInRender = new HashSet<>();
    private final Map<String, Long> lastAlertTimes = new HashMap<>();

    private int ringTicksRemaining = 0;
    private int nextRingDelayTicks = 0;
    private long lastDiscordPostMs = 0L;
    private float lastHealth = -1.0f;

    public PlayerAlarmsModule() {
        super("player-alarms", "PlayerAlarms", "Alerts when players join or enter render distance.");

        // Step 1: Detection
        addBool("alarm-join", "Alarm On Player Join", true);
        addBool("alarm-render", "Alarm On Render Distance", true);
        addInt("cooldown", "Cooldown (seconds)", 5, 0, 60, 1);

        // Step 2: Filters
        addText("watch-list", "Watch List", "");
        addText("ignore-list", "Ignore List", "");
        addText("own-accounts", "Ignore Own Accounts", "");
        addDouble("max-distance", "Max Distance", 1000.0, 1.0, 5000.0, 10.0);

        // Step 3: Alerts
        addChoice("sound", "Sound", "Bell", "Bell", "Anvil", "Chime", "Pling", "Exp", "Resonate");
        addDouble("volume", "Volume", 1.0, 0.0, 1.0, 0.05);
        addDouble("pitch", "Pitch", 1.0, 0.1, 2.0, 0.05);
        addInt("ring-count", "Ring Count", 3, 1, 20, 1);
        addInt("ring-delay", "Ring Delay (ticks)", 10, 1, 100, 1);
        addBool("ring-until-leaves", "Ring Until Player Leaves", false);
        addBool("chat-alert", "Chat Alert", true);

        // Step 4: AFK Safety
        addBool("afk-disconnect", "AFK Disconnect", false);
        addDouble("afk-distance", "AFK Disconnect Distance", 32.0, 1.0, 256.0, 1.0);

        // Step 5: Discord Webhook
        addBool("discord-webhook", "Discord Webhook", false);
        addText("discord-url", "Discord Webhook URL", "");

        // Step 6: Sighting Log File
        addBool("log-sightings", "Log Sightings To File", true);

        // Step 7: Damage / Kick Alarm
        addBool("damage-alarm", "Damage Alarm", true);
        addBool("kick-alarm", "Kick / Game Left Alarm", true);

        onEnabled(self -> onEnableModule());
        onDisabled(self -> onDisableModule());
        onTick(self -> onTickModule());
    }

    private void onEnableModule() {
        resetState();
    }

    private void onDisableModule() {
        resetState();
    }

    public void onGameJoin() {
        resetState();
    }

    public void onGameLeft() {
        if (getBool("kick-alarm")) {
            playSound();
        }
        resetState();
    }

    private void resetState() {
        playersInRender.clear();
        lastAlertTimes.clear();
        ringTicksRemaining = 0;
        nextRingDelayTicks = 0;
        lastDiscordPostMs = 0L;
        lastHealth = -1.0f;
    }

    public void handlePacket(Packet<?> packet) {
        if (!getBool("alarm-join")) return;
        if (packet instanceof ClientboundPlayerInfoUpdatePacket info
                && info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
            Minecraft mc = Minecraft.getInstance();
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : info.entries()) {
                if (entry == null || entry.profile() == null) continue;
                String name = entry.profile().name();
                if (name == null || name.isBlank()) continue;
                if (!isPlayerAllowed(name, 0.0, false)) continue;

                if (isCooldownActive("join:" + name)) continue;
                triggerJoinAlert(name);
            }
        }
    }

    private void onTickModule() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            playersInRender.clear();
            lastHealth = -1.0f;
            return;
        }

        // Damage Alarm check
        float currentHealth = mc.player.getHealth();
        if (getBool("damage-alarm") && lastHealth >= 0.0f && currentHealth < lastHealth) {
            if (getBool("chat-alert")) {
                AutismClientMessaging.sendPrefixed(String.format(
                    Locale.ROOT,
                    "§c[PlayerAlarms] Health dropped from %.1f to %.1f!",
                    lastHealth, currentHealth
                ));
            }
            queueAlarmRings();
        }
        lastHealth = currentHealth;

        // Render Distance Alarm check
        if (getBool("alarm-render")) {
            Set<String> currentPlayers = new HashSet<>();
            for (Player player : mc.level.players()) {
                if (player == null || player == mc.player) continue;
                String name = player.getGameProfile().name();
                if (name == null || name.isBlank()) continue;
                double dist = mc.player.distanceTo(player);
                if (!isPlayerAllowed(name, dist, true)) continue;

                String lower = name.toLowerCase(Locale.ROOT);
                currentPlayers.add(lower);

                if (!playersInRender.contains(lower)) {
                    if (!isCooldownActive("render:" + name)) {
                        triggerRenderAlert(player, name, dist);
                    }
                }
            }

            playersInRender.retainAll(currentPlayers);
            playersInRender.addAll(currentPlayers);
        }

        // AFK Disconnect check
        if (getBool("afk-disconnect")) {
            checkAfkDisconnect(mc);
        }

        tickRinging();
    }

    private void checkAfkDisconnect(Minecraft mc) {
        if (mc.getConnection() == null || mc.getConnection().getConnection() == null) return;
        double threshold = getDouble("afk-distance");
        for (Player player : mc.level.players()) {
            if (player == null || player == mc.player) continue;
            String name = player.getGameProfile().name();
            if (name == null || name.isBlank()) continue;
            double dist = mc.player.distanceTo(player);
            if (dist <= threshold && isPlayerAllowed(name, dist, false)) {
                String reason = String.format(Locale.ROOT, "PlayerAlarms: AFK safety triggered by %s within %.1f blocks (limit: %.1f)", name, dist, threshold);
                mc.getConnection().getConnection().disconnect(Component.literal(reason));
                break;
            }
        }
    }

    private void tickRinging() {
        if (getBool("ring-until-leaves") && !playersInRender.isEmpty()) {
            if (nextRingDelayTicks <= 0) {
                playSound();
                nextRingDelayTicks = getInt("ring-delay");
            } else {
                nextRingDelayTicks--;
            }
        } else if (ringTicksRemaining > 0) {
            if (nextRingDelayTicks <= 0) {
                playSound();
                ringTicksRemaining--;
                nextRingDelayTicks = getInt("ring-delay");
            } else {
                nextRingDelayTicks--;
            }
        } else {
            nextRingDelayTicks = 0;
        }
    }

    public void queueAlarmRings() {
        ringTicksRemaining = Math.max(ringTicksRemaining, getInt("ring-count"));
        if (nextRingDelayTicks <= 0) {
            playSound();
            ringTicksRemaining--;
            nextRingDelayTicks = getInt("ring-delay");
        }
    }

    public void playSound() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() == null) return;

        SoundEvent soundEvent = getSelectedSound();
        if (soundEvent == null) return;

        float pitch = (float) getDouble("pitch");
        float volume = (float) getDouble("volume");

        mc.getSoundManager().play(SimpleSoundInstance.forUI(soundEvent, pitch, volume));
    }

    private SoundEvent getSelectedSound() {
        String choice = getChoice("sound");
        if (choice == null) return SoundEvents.BELL_BLOCK;
        return switch (choice.toLowerCase(Locale.ROOT)) {
            case "anvil" -> SoundEvents.ANVIL_LAND;
            case "chime" -> SoundEvents.AMETHYST_BLOCK_CHIME;
            case "pling" -> SoundEvents.NOTE_BLOCK_PLING.value();
            case "exp" -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case "resonate" -> SoundEvents.BELL_RESONATE;
            default -> SoundEvents.BELL_BLOCK;
        };
    }

    private boolean isPlayerAllowed(String playerName, double distance, boolean checkDistance) {
        if (playerName == null || playerName.isBlank()) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && playerName.equalsIgnoreCase(mc.player.getGameProfile().name())) {
            return false;
        }

        String lower = playerName.toLowerCase(Locale.ROOT).trim();

        Set<String> ownAccounts = parseCommaSeparated(getText("own-accounts"));
        if (ownAccounts.contains(lower)) {
            return false;
        }

        Set<String> ignoreList = parseCommaSeparated(getText("ignore-list"));
        if (ignoreList.contains(lower)) {
            return false;
        }

        Set<String> watchList = parseCommaSeparated(getText("watch-list"));
        if (!watchList.isEmpty() && !watchList.contains(lower)) {
            return false;
        }

        if (checkDistance) {
            double maxDist = getDouble("max-distance");
            if (distance > maxDist) {
                return false;
            }
        }

        return true;
    }

    private Set<String> parseCommaSeparated(String text) {
        Set<String> set = new HashSet<>();
        if (text == null || text.isBlank()) return set;
        String[] parts = text.split(",");
        for (String part : parts) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                set.add(trimmed);
            }
        }
        return set;
    }

    private boolean isCooldownActive(String key) {
        long now = System.currentTimeMillis();
        long cooldownMs = getInt("cooldown") * 1000L;
        String lowerKey = key.toLowerCase(Locale.ROOT);
        Long lastTime = lastAlertTimes.get(lowerKey);
        if (lastTime != null && now - lastTime < cooldownMs) {
            return true;
        }
        lastAlertTimes.put(lowerKey, now);
        return false;
    }

    private void triggerJoinAlert(String name) {
        if (getBool("chat-alert")) {
            AutismClientMessaging.sendPrefixed("§6[PlayerAlarms] §fPlayer §e" + name + "§f joined the server!");
        }
        queueAlarmRings();
        sendDiscordWebhook("Player **" + name + "** joined the server!");
        logSighting(String.format(Locale.ROOT, "[%s] Joined: %s", LocalDateTime.now().format(TIME_FORMATTER), name));
    }

    private void triggerRenderAlert(Player player, String name, double dist) {
        if (getBool("chat-alert")) {
            AutismClientMessaging.sendPrefixed(String.format(
                Locale.ROOT,
                "§6[PlayerAlarms] §fPlayer §e%s§f spotted at distance §a%.1f§f (X: §b%d§f, Y: §b%d§f, Z: §b%d§f)",
                name, dist, (int) player.getX(), (int) player.getY(), (int) player.getZ()
            ));
        }
        queueAlarmRings();
        sendDiscordWebhook(String.format(
            Locale.ROOT,
            "Player **%s** spotted at distance **%.1f** (X: %d, Y: %d, Z: %d)",
            name, dist, (int) player.getX(), (int) player.getY(), (int) player.getZ()
        ));
        logSighting(String.format(
            Locale.ROOT,
            "[%s] Sighted: %s | Distance: %.1f | Coords: X=%d, Y=%d, Z=%d",
            LocalDateTime.now().format(TIME_FORMATTER), name, dist, (int) player.getX(), (int) player.getY(), (int) player.getZ()
        ));
    }

    private void logSighting(String entry) {
        if (!getBool("log-sightings")) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameDirectory == null) return;
        try {
            File logFile = new File(mc.gameDirectory, "player_alarms.log");
            Files.writeString(
                logFile.toPath(),
                entry + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (Throwable ignored) {
        }
    }

    private void sendDiscordWebhook(String content) {
        if (!getBool("discord-webhook")) return;
        String url = getText("discord-url");
        if (url == null || url.isBlank()) return;

        long now = System.currentTimeMillis();
        if (now - lastDiscordPostMs < DISCORD_RATE_LIMIT_MS) {
            return;
        }
        lastDiscordPostMs = now;

        try {
            URI uri = URI.create(url.trim());
            String jsonPayload = "{\"content\":\"" + escapeJsonString(content) + "\"}";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(10))
                .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(throwable -> null);
        } catch (Throwable ignored) {
            // Never log the webhook URL
        }
    }

    private String escapeJsonString(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c <= 0x1F) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
