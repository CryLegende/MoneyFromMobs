package me.chocolf.moneyfrommobs.commands;


import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import me.chocolf.moneyfrommobs.MoneyFromMobs;

public class ClearDropsCommand implements CommandExecutor{
	
	private final MoneyFromMobs plugin;
	
	public ClearDropsCommand(MoneyFromMobs plugin) {
		this.plugin = plugin;
		plugin.getCommand("mfmclear").setExecutor(this);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		int count = 0;
		// floating texts only exist on the clients now: only money items can be left in a world
		for (World world : Bukkit.getServer().getWorlds()) {
			for (Item item : world.getEntitiesByClass(Item.class)) {
				if (plugin.getPickUpManager().isMoneyPickedUp(item.getItemStack())) {
					item.remove();
					count++;
				}
			}
		}
		
		sender.sendMessage(plugin.getMessageManager().getMessage("clearMoneyDropsMessage").replace("%amount%", String.valueOf(count)));
		return true;
	}

}
