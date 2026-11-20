package me.chocolf.moneyfrommobs.managers;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import me.chocolf.moneyfrommobs.MoneyFromMobs;
import me.chocolf.moneyfrommobs.display.FloatingTexts;
import me.chocolf.moneyfrommobs.utils.VersionUtils;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;

public class MessageManager {
	
	private final MoneyFromMobs plugin;
	private double floatingTextHeight;
	private boolean shouldSendChatMessage;
	private boolean shouldSendActionBarMessage;
	private boolean shouldSendFloatingTextMessage;
	private boolean sendEventMessageAsTitle;
	private boolean moveFloatingTextMessageUpwards;
	private double floatingTextDuration;
	private boolean combineFloatingTexts;
	private FloatingTexts floatingTexts;
	private final HashMap<String, String> messagesMap = new HashMap<>();
	private static final Pattern hexColorPattern = Pattern.compile("#([A-Fa-f0-9]){6}");
	
	public MessageManager(MoneyFromMobs plugin) {
		this.plugin = plugin;
		loadMessage();
	}

	public void loadMessage() {
		FileConfiguration config = plugin.getConfig();
		
		floatingTextHeight = config.getDouble("ShowMessageAsFloatingText.Height")/4;
		
		shouldSendChatMessage = config.getBoolean("ShowMessageInChat.Enabled");
		shouldSendActionBarMessage = config.getBoolean("ShowMessageInActionBar.Enabled");
		shouldSendFloatingTextMessage = config.getBoolean("ShowMessageAsFloatingText.Enabled");
		sendEventMessageAsTitle = config.getBoolean("SendEventMessageAsTitle");
		moveFloatingTextMessageUpwards = config.getBoolean("ShowMessageAsFloatingText.Movement");
		floatingTextDuration = config.getDouble("ShowMessageAsFloatingText.Duration") * 20;
		combineFloatingTexts = config.getBoolean("ShowMessageAsFloatingText.Combine", true);
		if (shouldSendFloatingTextMessage && floatingTexts == null)
			floatingTexts = createFloatingTexts();
		
		messagesMap.clear();
		messagesMap.put("chatMessage", applyColour( config.getString("ShowMessageInChat.Message") ));
		messagesMap.put("actionBarMessage", applyColour( config.getString("ShowMessageInActionBar.Message") ));
		messagesMap.put("floatingTextMessage", applyColour( config.getString("ShowMessageAsFloatingText.Message") ));
		messagesMap.put("playerMessage", applyColour( config.getString("PLAYER.Message") ));
		
		messagesMap.put("muteToggleOnMessage", applyColour( config.getString("MuteToggleOnMessage") ));
		messagesMap.put("muteToggleOffMessage", applyColour( config.getString("MuteToggleOffMessage") ));
		
		messagesMap.put("clearMoneyDropsMessage", applyColour(config.getString("ClearMoneyDropsMessage") ));
		messagesMap.put("reloadMessage", applyColour(config.getString("ReloadMessage") ));
		
		messagesMap.put("maxDropsReachedMessage", applyColour(config.getString("MaxDropsReachedMessage") ));
		
		messagesMap.put("eventAlreadyRunningMessage", applyColour(config.getString("EventAlreadyRunningMessage") ));
		messagesMap.put("noEventRunningMessage", applyColour(config.getString("NoEventRunningMessage") ));
		messagesMap.put("eventStart", applyColour(config.getString("EventStart") ));
		messagesMap.put("eventFinish", applyColour(config.getString("EventFinish") ));
	}
	
	public void sendMessage(double amount, Player p) {
		if ( p.hasMetadata("MfmMuteMessages"))
			return;

		String strAmount = formatAmount(amount);
		if (shouldSendChatMessage) {
			p.sendMessage(fill("chatMessage", p, strAmount));
		}
		
		if (shouldSendActionBarMessage) {
			p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(fill("actionBarMessage", p, strAmount)));
		}
		
		if (shouldSendFloatingTextMessage && floatingTexts != null) {
			floatingTexts.show(p, amount);
		}
	}

	/** Amount as shown in the messages: no decimal when DisableDecimal is on. */
	public String formatAmount(double amount) {
		return plugin.getDropsManager().shouldDisableDecimal() ? String.format("%.0f", amount) : String.format("%.2f", amount);
	}

	/** Client-side floating texts, through packetevents. Without it, no floating text is shown. */
	private FloatingTexts createFloatingTexts() {
		if (!Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
			plugin.getLogger().warning("packetevents is not installed: floating text messages are not shown.");
			return null;
		}
		try {
			return new FloatingTexts(plugin, new FloatingTexts.Settings() {
				public boolean combine() { return combineFloatingTexts; }
				public boolean rise() { return moveFloatingTextMessageUpwards; }
				public int durationTicks() { return (int) floatingTextDuration; }
				public double height() { return floatingTextHeight; }
			}, (player, total) -> fill("floatingTextMessage", player, formatAmount(total)));
		}
		catch (LinkageError e) {
			plugin.getLogger().warning("packetevents could not be used, floating text messages are not shown: " + e);
			return null;
		}
	}

	/** Removes the floating texts still shown (plugin disabled). */
	public void clearFloatingTexts() {
		if (floatingTexts != null) {
			floatingTexts.clear();
			floatingTexts = null;
		}
	}

	public void sendPlayerMessage(double amount, Player p) {
		String strAmount = String.format("%.2f", amount);
		double balance = plugin.getEcon().getBalance(p);
		String messageToSend = getMessage("playerMessage", balance, strAmount);
		p.sendMessage(messageToSend);
	}

	public static String applyColour (String msg) {
		if ( VersionUtils.getVersionNumber() > 15) {
			Matcher match = hexColorPattern.matcher(msg);
			while (match.find()) {
				String color = msg.substring(match.start(), match.end());
				msg = msg.replace(color, ChatColor.valueOf(color) + "");
				match = hexColorPattern.matcher(msg);
			}
		}
		return ChatColor.translateAlternateColorCodes('&', msg);
	}

	public void logToConsole (String msg){
		plugin.getServer().getConsoleSender().sendMessage(MessageManager.applyColour(msg));
	}
	
	public String getMessage(String messageName) {
		return messagesMap.get(messageName);
	}
	
	/** The message with its amount, and the player's balance only when the message shows it (one economy call less). */
	private String fill(String messageName, Player p, String strAmount) {
		String message = messagesMap.get(messageName);
		double balance = message.contains("%balance") ? plugin.getEcon().getBalance(p) : 0;
		return getMessage(messageName, balance, strAmount);
	}

	private String getMessage(String messageName, double balance, String strAmount) {
		return messagesMap.get(messageName).replace("%amount%", strAmount).replace("%balance%", String.format("%.2f", balance)).replace("%balance_0dp%", String.format("%.0f", balance) );
	}

	public boolean shouldSendEventMessageAsTitle() {
		return sendEventMessageAsTitle;
	}
}
