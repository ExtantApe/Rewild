package com.extantape.rewild.track;

import com.extantape.rewild.config.RegenConfig;
import com.extantape.rewild.scan.IntentRules;
import com.extantape.rewild.store.ChunkRecord;
import com.extantape.rewild.store.WorldState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Records player changes by chunk. Natural changes (growth, decay, fire, mobs picking up blocks) aren't recorded.
 * Explosions count as damage, and never erase a record of what a player built, so a creeper can't strip a cabin of
 * its protection.
 */
public final class TrackerListener implements Listener {
    private final Plugin plugin;
    private final Tracking tracking;
    private IntentRules intent;
    private final Map<UUID, Map<Material, Integer>> openContainers = new HashMap<>();

    public TrackerListener(Plugin plugin, Tracking tracking, IntentRules intent) {
        this.plugin = plugin;
        this.tracking = tracking;
        this.intent = intent;
    }

    public void setIntent(IntentRules intent) {
        this.intent = intent;
    }

    private void change(Block b, Consumer<ChunkRecord> extra) {
        WorldState s = tracking.state(b.getWorld());
        if (s == null) return;
        long now = System.currentTimeMillis();
        int section = b.getY() >> 4;
        s.store.edit(b.getX() >> 4, b.getZ() >> 4, r -> {
            r.lastChange = now;
            r.changeBySection.put(section, now);
            if (extra != null) extra.accept(r);
        });
    }

    private static long pos(Block b) {
        return ChunkRecord.pos(b.getX() & 15, b.getY(), b.getZ() & 15);
    }

    private boolean countsAsDamage(Block b, Material type) {
        if (!type.isSolid() || type.isAir()) return false;
        WorldState s = tracking.state(b.getWorld());
        RegenConfig.WorldSettings ws = tracking.config().world(s == null ? "" : s.name);
        return !RegenConfig.matchesAny(ws.damageIgnore(), type.name());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        tracking.open(e.getWorld(), null);
    }

    /** A chunk generated while tracking runs is "known": every later player change to it is seen. */
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!e.isNewChunk()) return;
        WorldState s = tracking.state(e.getWorld());
        if (s == null) return;
        long now = System.currentTimeMillis();
        int epoch = s.epoch;
        s.store.edit(e.getChunk().getX(), e.getChunk().getZ(), r -> {
            r.clearForReset(r.lastReset);
            r.known = true;
            r.epoch = epoch;
            r.bornAt = now;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material type = b.getType();
        long p = pos(b);
        boolean damage = countsAsDamage(b, type);
        int section = b.getY() >> 4;
        change(b, r -> {
            String was = r.placed.remove(p);
            r.filledContainers.remove(p);
            if (was == null && damage) r.removedBySection.merge(section, 1, Integer::sum);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        String m = b.getType().name();
        long p = pos(b);
        boolean keep = intent.worthRecording(m);
        change(b, r -> {
            if (keep) r.placed.put(p, m);
            else r.placed.remove(p);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        change(e.getBlock(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        change(e.getBlock(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent e) {
        change(e.getBlock(), null);
    }

    /** Shovels make paths, hoes make farmland and axes strip logs. The result shows on the next tick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getItem() == null) return;
        if (e.useItemInHand() == org.bukkit.event.Event.Result.DENY) return;
        String tool = e.getItem().getType().name();
        if (!tool.endsWith("_SHOVEL") && !tool.endsWith("_HOE") && !tool.endsWith("_AXE")) return;
        Block b = e.getClickedBlock();
        if (tracking.state(b.getWorld()) == null) return;
        Material before = b.getType();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Material after = b.getType();
            if (after == before) return;
            String m = after.name();
            long p = pos(b);
            boolean keep = after == Material.DIRT_PATH || after == Material.FARMLAND || intent.worthRecording(m);
            change(b, r -> { if (keep) r.placed.put(p, m); });
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        explode(e.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        explode(e.blockList());
    }

    private void explode(List<Block> blocks) {
        Map<Long, int[]> perChunk = new HashMap<>();
        Map<Long, Block> any = new HashMap<>();
        for (Block b : blocks) {
            if (!countsAsDamage(b, b.getType())) continue;
            long k = ChunkRecord.key(b.getX() >> 4, b.getZ() >> 4);
            perChunk.computeIfAbsent(k, kk -> new int[1])[0]++;
            any.putIfAbsent(k, b);
        }
        perChunk.forEach((k, n) -> change(any.get(k), r -> r.explosionBlocks += n[0]));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHang(HangingPlaceEvent e) {
        placeEntity(e.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent e) {
        placeEntity(e.getEntity());
    }

    private void placeEntity(Entity entity) {
        Block b = entity.getLocation().getBlock();
        String m = entity.getType().name();
        long p = pos(b);
        boolean keep = intent.worthRecording(m);
        change(b, r -> { if (keep) r.placed.put(p, m); });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PortalCreateEvent e) {
        for (BlockState st : e.getBlocks()) {
            Block b = st.getBlock();
            String m = st.getType().name();
            if (!intent.worthRecording(m)) continue;
            long p = pos(b);
            change(b, r -> r.placed.put(p, m));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent e) {
        change(e.getEntity().getLocation().getBlock(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent e) {
        change(e.getEntity().getLocation().getBlock(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        ItemStack hand = e.getPlayer().getInventory().getItem(e.getHand());
        if (hand != null && hand.getType() == Material.NAME_TAG) change(e.getRightClicked().getLocation().getBlock(), null);
    }

    // ---- containers: a player putting something in, even a natural chest, is a sign they care ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p) || containerBlocks(e.getInventory()) == null) return;
        openContainers.put(p.getUniqueId(), counts(e.getInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        Map<Material, Integer> before = openContainers.remove(e.getPlayer().getUniqueId());
        if (before == null) return;
        Block[] blocks = containerBlocks(e.getInventory());
        if (blocks == null) return;
        Map<Material, Integer> after = counts(e.getInventory());
        if (after.equals(before)) return;
        boolean added = false;
        for (var en : after.entrySet()) if (en.getValue() > before.getOrDefault(en.getKey(), 0)) added = true;
        boolean filled = added;
        for (Block b : blocks) {
            long p = pos(b);
            change(b, r -> { if (filled) r.filledContainers.add(p); });
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        openContainers.remove(e.getPlayer().getUniqueId());
    }

    private static Block[] containerBlocks(Inventory inv) {
        InventoryHolder h;
        try {
            h = inv.getHolder(false);
        } catch (NoSuchMethodError e) {
            h = inv.getHolder();
        }
        if (h instanceof DoubleChest dc) {
            Location l = dc.getLocation();
            if (dc.getLeftSide() instanceof BlockState left && dc.getRightSide() instanceof BlockState right) {
                return new Block[]{left.getBlock(), right.getBlock()};
            }
            return l == null ? null : new Block[]{l.getBlock()};
        }
        if (h instanceof BlockState st) return new Block[]{st.getBlock()};
        return null;
    }

    private static Map<Material, Integer> counts(Inventory inv) {
        Map<Material, Integer> m = new EnumMap<>(Material.class);
        for (ItemStack it : inv.getContents()) if (it != null && !it.getType().isAir()) m.merge(it.getType(), it.getAmount(), Integer::sum);
        return m;
    }
}
