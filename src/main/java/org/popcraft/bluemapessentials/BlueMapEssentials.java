package org.popcraft.bluemapessentials;

import com.earth2me.essentials.IEssentials;
import com.earth2me.essentials.User;
import com.earth2me.essentials.UserMap;
import com.earth2me.essentials.api.IWarps;
import com.earth2me.essentials.commands.WarpNotFoundException;
import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import net.ess3.api.InvalidWorldException;
import org.bstats.bukkit.Metrics;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.UUID;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public final class BlueMapEssentials extends JavaPlugin {
    private static final String MARKERSET_ID_HOMES = "homes", MARKERSET_ID_WARPS = "warps";
    private static final String MARKERSET_LABEL_HOMES = "Homes", MARKERSET_LABEL_WARPS = "Warps";
    private IEssentials essentials;
    private BlueMapAPI blueMap;
    private String homeImageURL, warpImageURL;
    private boolean warpsEnabled, homesEnabled;
    private String warpLabelFormat, homeLabelFormat;
    private boolean homesOnlinePlayersOnly;

    @Override
    public void onEnable() {
        this.getConfig().options().copyDefaults(true);
        this.saveConfig();
        this.essentials = (IEssentials) getServer().getPluginManager().getPlugin("Essentials");
        this.warpsEnabled = getConfig().getBoolean("warps.enabled", true);
        this.homesEnabled = getConfig().getBoolean("homes.enabled", true);
        this.warpLabelFormat = getConfig().getString("warps.label", "%warp%");
        this.homeLabelFormat = getConfig().getString("homes.label", "%home% (%player%'s home)");
        this.homesOnlinePlayersOnly = getConfig().getBoolean("homes.online-players-only", true);
        BlueMapAPI.onEnable(blueMapAPI -> {
            this.blueMap = blueMapAPI;
            loadImages();
            addMarkers();
            final long updateInterval = Math.max(1, getConfig().getLong("update-interval", 300));
            getServer().getScheduler().runTaskTimerAsynchronously(this, this::refreshMarkers, 0, 20 * updateInterval);
        });
        new Metrics(this, 9011);
    }

    @Override
    public void onDisable() {
        BlueMapAPI.onDisable(blueMapAPI -> {
            getServer().getScheduler().cancelTasks(this);
            removeMarkers();
            this.blueMap = null;
        });
    }

    private void loadImages() {
        try (InputStream homeImage = getResource("home.png")) {
            if (homeImage != null) {
                this.homeImageURL = blueMap.getWebApp().createImage(ImageIO.read(homeImage), "essentials/home");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (InputStream warpImage = getResource("warp.png")) {
            if (warpImage != null) {
                this.warpImageURL = blueMap.getWebApp().createImage(ImageIO.read(warpImage), "essentials/warp");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void addMarkers() {
        if (essentials == null) {
            return;
        }
        if (warpsEnabled) {
            addWarpMarkers();
        }
        if (homesEnabled) {
            addHomeMarkers();
        }
    }

    private void addWarpMarkers() {
        if (warpImageURL == null) {
            return;
        }
        IWarps warps = essentials.getWarps();
        final MarkerSet markerSetWarps = MarkerSet.builder().label(MARKERSET_LABEL_WARPS).build();
        for (final String warp : warps.getList()) {
            final Location warpLocation;
            try {
                warpLocation = warps.getWarp(warp);
            } catch (WarpNotFoundException | InvalidWorldException e) {
                continue;
            }
            World warpWorld = warpLocation.getWorld();
            if (warpWorld == null) {
                continue;
            }
            blueMap.getWorld(warpWorld.getUID()).ifPresent(blueWorld -> blueWorld.getMaps().forEach(map -> {
                String warpMarkerId = String.format("warp:%s:%s", map.getName(), warp);
                Vector3d warpMarkerPos = Vector3d.from(warpLocation.getX(), warpLocation.getY(), warpLocation.getZ());
                POIMarker warpMarker = POIMarker.toBuilder()
                        .label(warpLabelFormat.replace("%warp%", warp))
                        .icon(warpImageURL, Vector2i.from(19, 19))
                        .position(warpMarkerPos)
                        .build();
                markerSetWarps.getMarkers().put(warpMarkerId, warpMarker);
                map.getMarkerSets().put(MARKERSET_ID_WARPS, markerSetWarps);
            }));
        }
    }

    private void addHomeMarkers() {
        if (homeImageURL == null) {
            return;
        }
        final UserMap userMap = essentials.getUserMap();
        final Collection<UUID> users;
        if (homesOnlinePlayersOnly) {
            users = getServer().getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet());
        } else {
            users = userMap.getAllUniqueUsers();
        }
        for (UUID uuid : users) {
            final MarkerSet markerSetHomes = MarkerSet.builder().label(MARKERSET_LABEL_HOMES).build();
            final User user = userMap.getUser(uuid);
            for (final String home : user.getHomes()) {
                final Location homeLocation;
                try {
                    homeLocation = user.getHome(home);
                } catch (Exception e) {
                    continue;
                }
                if (homeLocation == null) {
                    continue;
                }
                World homeWorld = homeLocation.getWorld();
                if (homeWorld == null) {
                    continue;
                }
                blueMap.getWorld(homeWorld.getUID()).ifPresent(blueWorld -> blueWorld.getMaps().forEach(map -> {
                    String homeMarkerId = String.format("home:%s:%s", user.getName(), home);
                    Vector3d homeMarkerPos = Vector3d.from(homeLocation.getX(), homeLocation.getY(), homeLocation.getZ());
                    POIMarker homeMarker = POIMarker.toBuilder()
                            .label(homeLabelFormat.replace("%home%", home).replace("%player%", user.getName()))
                            .icon(homeImageURL, Vector2i.from(18, 18))
                            .position(homeMarkerPos)
                            .build();
                    markerSetHomes.getMarkers().put(homeMarkerId, homeMarker);
                    map.getMarkerSets().put(MARKERSET_ID_HOMES, markerSetHomes);
                }));
            }
        }
    }

    private void removeMarkers() {
        if (essentials == null) {
            return;
        }
        blueMap.getWorlds().forEach(blueMapWorld -> blueMapWorld.getMaps().forEach(map -> {
            map.getMarkerSets().remove(MARKERSET_ID_WARPS);
            map.getMarkerSets().remove(MARKERSET_ID_HOMES);
        }));
    }

    private void refreshMarkers() {
        removeMarkers();
        addMarkers();
    }
}
