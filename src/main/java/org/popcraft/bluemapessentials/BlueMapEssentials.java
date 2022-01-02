package org.popcraft.bluemapessentials;

import com.earth2me.essentials.IEssentials;
import com.earth2me.essentials.User;
import com.earth2me.essentials.UserMap;
import com.earth2me.essentials.api.IWarps;
import com.earth2me.essentials.commands.WarpNotFoundException;
import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.marker.Marker;
import de.bluecolored.bluemap.api.marker.MarkerAPI;
import de.bluecolored.bluemap.api.marker.MarkerSet;
import de.bluecolored.bluemap.api.marker.POIMarker;
import net.ess3.api.InvalidWorldException;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public final class BlueMapEssentials extends JavaPlugin {
    private static final String MARKERSET_ID_HOMES = "homes", MARKERSET_ID_WARPS = "warps";
    private static final String MARKERSET_LABEL_HOMES = "Homes", MARKERSET_LABEL_WARPS = "Warps";
    private IEssentials essentials;
    private BlueMapAPI blueMap;
    private Set<Marker> warpMarkers, homeMarkers;
    private String homeImageURL, warpImageURL;
    private boolean warpsEnabled, homesEnabled;
    private String warpLabelFormat, homeLabelFormat;
    private boolean homesOnlinePlayersOnly;

    @Override
    public void onEnable() {
        this.getConfig().options().copyDefaults(true);
        this.saveConfig();
        this.essentials = (IEssentials) getServer().getPluginManager().getPlugin("Essentials");
        this.warpMarkers = new HashSet<>();
        this.homeMarkers = new HashSet<>();
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
                this.homeImageURL = blueMap.createImage(ImageIO.read(homeImage), "essentials/home");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        try (InputStream warpImage = getResource("warp.png")) {
            if (warpImage != null) {
                this.warpImageURL = blueMap.createImage(ImageIO.read(warpImage), "essentials/warp");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void addMarkers() {
        if (essentials == null) {
            return;
        }
        try {
            final MarkerAPI markerAPI = blueMap.getMarkerAPI();
            if (warpsEnabled) {
                addWarpMarkers(markerAPI);
            }
            if (homesEnabled) {
                addHomeMarkers(markerAPI);
            }
            markerAPI.save();
        } catch (IOException ignored) {
        }
    }

    private void addWarpMarkers(MarkerAPI markerAPI) {
        IWarps warps = essentials.getWarps();
        for (final String warp : warps.getList()) {
            final MarkerSet markerSetWarps = markerAPI.createMarkerSet(MARKERSET_ID_WARPS);
            markerSetWarps.setLabel(MARKERSET_LABEL_WARPS);
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
                POIMarker warpMarker = markerSetWarps.createPOIMarker(warpMarkerId, map, warpMarkerPos);
                warpMarker.setLabel(warpLabelFormat.replace("%warp%", warp));
                Vector2i iconAnchor = warpMarker.getIconAnchor();
                if (warpImageURL != null) {
                    warpMarker.setIcon(warpImageURL, iconAnchor);
                }
                warpMarkers.add(warpMarker);
            }));
        }
    }

    private void addHomeMarkers(MarkerAPI markerAPI) {
        final UserMap userMap = essentials.getUserMap();
        final Collection<UUID> users;
        if (homesOnlinePlayersOnly) {
            users = getServer().getOnlinePlayers().stream().map(Player::getUniqueId).collect(Collectors.toSet());
        } else {
            users = userMap.getAllUniqueUsers();
        }
        for (UUID uuid : users) {
            final MarkerSet markerSetHomes = markerAPI.createMarkerSet(MARKERSET_ID_HOMES);
            markerSetHomes.setLabel(MARKERSET_LABEL_HOMES);
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
                    POIMarker homeMarker = markerSetHomes.createPOIMarker(homeMarkerId, map, homeMarkerPos);
                    homeMarker.setLabel(homeLabelFormat.replace("%home%", home).replace("%player%", user.getName()));
                    Vector2i iconAnchor = homeMarker.getIconAnchor();
                    if (homeImageURL != null) {
                        homeMarker.setIcon(homeImageURL, iconAnchor);
                    }
                    homeMarkers.add(homeMarker);
                }));
            }
        }
    }

    private void removeMarkers() {
        if (essentials == null) {
            return;
        }
        try {
            final MarkerAPI markerAPI = blueMap.getMarkerAPI();
            markerAPI.removeMarkerSet(MARKERSET_ID_WARPS);
            if (warpsEnabled) {
                final MarkerSet markerSetWarps = markerAPI.createMarkerSet(MARKERSET_ID_WARPS);
                markerSetWarps.setLabel(MARKERSET_LABEL_WARPS);
                warpMarkers.forEach(markerSetWarps::removeMarker);
                warpMarkers.clear();
            }
            markerAPI.removeMarkerSet(MARKERSET_ID_HOMES);
            if (homesEnabled) {
                final MarkerSet markerSetHomes = markerAPI.createMarkerSet(MARKERSET_ID_HOMES);
                markerSetHomes.setLabel(MARKERSET_LABEL_HOMES);
                homeMarkers.forEach(markerSetHomes::removeMarker);
                homeMarkers.clear();
            }
            markerAPI.save();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void refreshMarkers() {
        removeMarkers();
        addMarkers();
    }
}
