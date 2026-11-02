package com.playeralarms.addon;

import autismclient.api.ApiVersion;
import autismclient.api.SimpleAddon;
import autismclient.api.event.AddonEvents;

public final class PlayerAlarmsAddon extends SimpleAddon {
    public static final String ID = "playeralarms";
    private PlayerAlarmsModule module;

    public PlayerAlarmsAddon() {
        super(ApiVersion.CURRENT, "com.playeralarms.addon");
    }

    @Override
    protected void initialize() {
        this.module = new PlayerAlarmsModule();
        registerModule(module);

        AddonEvents.onPacketReceive(packet -> {
            if (module != null && module.isEnabled()) {
                module.handlePacket(packet);
            }
        });

        AddonEvents.onGameJoin(() -> {
            if (module != null && module.isEnabled()) {
                module.onGameJoin();
            }
        });

        AddonEvents.onGameLeft(() -> {
            if (module != null && module.isEnabled()) {
                module.onGameLeft();
            }
        });
    }

    public PlayerAlarmsModule getModule() {
        return module;
    }
}
