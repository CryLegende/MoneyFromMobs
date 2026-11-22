package me.chocolf.moneyfrommobs.display;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/**
 * The floating "+money" texts, as text displays that only exist on the client of the player who earns the money.
 * <p>
 * The server holds no entity: nothing is ticked, tracked or saved with a chunk, and nothing is left behind when a
 * chunk unloads or the server stops. Each text costs a spawn, a metadata and a teleport packet, sent together (the
 * client moves it up by itself, through the display position interpolation), and a destroy packet, all sent to that
 * one player. While a text is still shown, the next amounts are added to it instead of spawning a new one. A task
 * runs only while texts are shown, to remove them.
 * <p>
 * Money is earned on the thread of the player's world (parallel world ticking), the task runs on the main thread:
 * each player's texts change only inside an atomic {@code compute} on the map, so a text can never be lost before
 * its destroy packet is sent (a lost one would stay frozen in the air on that client).
 */
public final class FloatingTexts implements Listener {

	// Display and text display metadata (26.x protocol, translated by ViaBackwards for older clients)
	private static final int POS_ROT_DURATION = 10;
	private static final int BILLBOARD = 15;
	private static final int BRIGHTNESS = 16;
	private static final int TEXT = 23;
	private static final int TEXT_FLAGS = 27;

	private static final byte BILLBOARD_CENTER = 3;
	/** Full block and sky light: as bright as a name tag, also in the dark. */
	private static final int FULL_BRIGHT = (15 << 4) | (15 << 20);
	/** Drawn through blocks, like a name tag. */
	private static final byte SEE_THROUGH = 0x02;
	/** Rise per tick of the old moving armor stand, kept for the same look. */
	private static final double RISE_PER_TICK = 0.1;
	/** Longest position interpolation a client accepts, in ticks. */
	private static final int MAX_MOVE_TICKS = 59;

	private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
			.character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
	private static final Vector3d NO_VELOCITY = new Vector3d(0, 0, 0);

	/** What one text shows, built again whenever its amount changes. */
	public interface Settings {
		boolean combine();
		boolean rise();
		int durationTicks();
		double height();
	}

	private final Plugin plugin;
	private final Settings settings;
	private final BiFunction<Player, Double, String> format;
	private final Map<UUID, Deque<Shown>> shown = new ConcurrentHashMap<>();
	private BukkitTask task;
	private volatile long tick;

	private static final class Shown {
		final int id = SpigotReflectionUtil.generateEntityId();
		final UUID uuid = UUID.randomUUID();
		double amount;
		long removeAt;
	}

	/**
	 * @param format the text shown for a player and a total amount (legacy colour codes)
	 */
	public FloatingTexts(Plugin plugin, Settings settings, BiFunction<Player, Double, String> format) {
		this.plugin = plugin;
		this.settings = settings;
		this.format = format;
		Bukkit.getPluginManager().registerEvents(this, plugin);
	}

	/** Shows the amount to the player, in front of him, or adds it to the text he still sees. */
	public void show(Player player, double amount) {
		int duration = Math.max(1, settings.durationTicks());
		Location at = frontOf(player);
		shown.compute(player.getUniqueId(), (uuid, texts) -> {
			Deque<Shown> list = texts == null ? new ArrayDeque<>() : texts;
			show(player, list, amount, at, duration);
			return list;
		});
		startTask();
	}

	private void show(Player player, Deque<Shown> texts, double amount, Location at, int duration) {
		long tick = this.tick;
		Shown last = texts.peekLast();
		List<PacketWrapper<?>> packets = new ArrayList<>(6);
		if (settings.combine() && last != null && last.removeAt > tick) {
			last.amount += amount;
			// new total, back in front of the player at once, then up again
			packets.add(new WrapperPlayServerEntityMetadata(last.id, List.of(
					new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text(player, last.amount)),
					new EntityData<>(POS_ROT_DURATION, EntityDataTypes.INT, 0))));
			packets.add(new WrapperPlayServerEntityTeleport(last.id, position(at), 0f, 0f, false));
			rise(packets, last.id, at, duration);
			last.removeAt = tick + duration;
		} else {
			Shown text = new Shown();
			text.amount = amount;
			text.removeAt = tick + duration;
			packets.add(new WrapperPlayServerSpawnEntity(text.id, Optional.of(text.uuid), EntityTypes.TEXT_DISPLAY,
					position(at), 0f, 0f, 0f, 0, Optional.of(NO_VELOCITY)));
			packets.add(new WrapperPlayServerEntityMetadata(text.id, List.of(
					new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER),
					new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, FULL_BRIGHT),
					new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text(player, amount)),
					new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, SEE_THROUGH))));
			rise(packets, text.id, at, duration);
			texts.addLast(text);
		}
		send(player, packets);
	}

	private synchronized void startTask() {
		if (task == null) {
			task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
		}
	}

	/** The client moves the text up by itself, over its whole display time (59 ticks at most). */
	private void rise(List<PacketWrapper<?>> packets, int id, Location at, int duration) {
		if (!settings.rise()) {
			return;
		}
		int ticks = Math.min(duration, MAX_MOVE_TICKS);
		packets.add(new WrapperPlayServerEntityMetadata(id, List.of(new EntityData<>(POS_ROT_DURATION, EntityDataTypes.INT, ticks))));
		packets.add(new WrapperPlayServerEntityTeleport(id, new Vector3d(at.getX(), at.getY() + RISE_PER_TICK * ticks, at.getZ()),
				0f, 0f, false));
	}

	private void tick() {
		long now = ++tick;
		for (UUID uuid : shown.keySet()) {
			shown.computeIfPresent(uuid, (key, texts) -> {
				Player player = Bukkit.getPlayer(key);
				if (player == null) {
					return null;
				}
				for (Iterator<Shown> it = texts.iterator(); it.hasNext(); ) {
					Shown text = it.next();
					if (text.removeAt <= now) {
						it.remove();
						send(player, List.of(new WrapperPlayServerDestroyEntities(text.id)));
					}
				}
				return texts.isEmpty() ? null : texts;
			});
		}
		synchronized (this) {
			if (shown.isEmpty() && task != null) {
				task.cancel();
				task = null;
			}
		}
	}

	/** Removes the player's texts from his client, then forgets them. */
	private void drop(UUID uuid) {
		shown.computeIfPresent(uuid, (key, texts) -> {
			Player player = Bukkit.getPlayer(key);
			if (player != null && !texts.isEmpty()) {
				send(player, List.of(new WrapperPlayServerDestroyEntities(texts.stream().mapToInt(text -> text.id).toArray())));
			}
			return null;
		});
	}

	/** Removes every text from the clients (plugin disabled). */
	public void clear() {
		for (UUID uuid : shown.keySet()) {
			drop(uuid);
		}
		synchronized (this) {
			if (task != null) {
				task.cancel();
				task = null;
			}
		}
		HandlerList.unregisterAll(this);
	}

	/** Gone: nothing to send. */
	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		shown.remove(event.getPlayer().getUniqueId());
	}

	/** The client has dropped its entities with the old world; the destroy costs nothing and leaves nothing to chance. */
	@EventHandler
	public void onWorldChange(PlayerChangedWorldEvent event) {
		drop(event.getPlayer().getUniqueId());
	}

	/** Same place as the old armor stand: four blocks ahead of the player, at the configured height. */
	private Location frontOf(Player player) {
		Location at = player.getLocation();
		Vector direction = at.getDirection();
		direction.setY(settings.height());
		return at.add(direction.multiply(4));
	}

	private Component text(Player player, double amount) {
		return LEGACY.deserialize(format.apply(player, amount));
	}

	private static Vector3d position(Location at) {
		return new Vector3d(at.getX(), at.getY(), at.getZ());
	}

	private static void send(Player player, List<PacketWrapper<?>> packets) {
		for (PacketWrapper<?> packet : packets) {
			PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, packet);
		}
	}
}
