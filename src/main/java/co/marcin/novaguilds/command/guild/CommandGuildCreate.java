/*
 *     NovaGuilds - Bukkit plugin
 *     Copyright (C) 2015 Marcin (CTRL) Wieczorek
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package co.marcin.novaguilds.command.guild;

import co.marcin.novaguilds.NovaGuilds;
import co.marcin.novaguilds.basic.NovaGroup;
import co.marcin.novaguilds.basic.NovaGuild;
import co.marcin.novaguilds.basic.NovaPlayer;
import co.marcin.novaguilds.basic.NovaRegion;
import co.marcin.novaguilds.enums.Config;
import co.marcin.novaguilds.enums.Message;
import co.marcin.novaguilds.enums.RegionValidity;
import co.marcin.novaguilds.event.GuildCreateEvent;
import co.marcin.novaguilds.manager.GuildManager;
import co.marcin.novaguilds.util.InventoryUtils;
import co.marcin.novaguilds.util.LoggerUtils;
import co.marcin.novaguilds.util.NumberUtils;
import co.marcin.novaguilds.util.StringUtils;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;

public class CommandGuildCreate implements CommandExecutor {
	private final NovaGuilds plugin;
	
	public CommandGuildCreate(NovaGuilds pl) {
		plugin = pl;
	}

	@SuppressWarnings("deprecation")
	public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
		if(!sender.hasPermission("novaguilds.guild.create")) {
			Message.CHAT_NOPERMISSIONS.send(sender);
			return true;
		}

		if(!(sender instanceof Player)) {
			Message.CHAT_CMDFROMCONSOLE.send(sender);
			return true;
		}

		if(args.length != 2) {
			Message.CHAT_USAGE_GUILD_CREATE.send(sender);
			return true;
		}

		Player player = (Player)sender;

		String tag = args[0];
		String guildname = args[1];
		
		//remove colors
		guildname = StringUtils.removeColors(guildname);
		if(!Config.GUILD_SETTINGS_TAG_COLOR.getBoolean()) {
			tag = StringUtils.removeColors(tag);
		}
			
		NovaPlayer nPlayer = plugin.getPlayerManager().getPlayer(sender);
		HashMap<String,String> vars = new HashMap<>();
		
		if(nPlayer.hasGuild()) { //has guild already
			Message.CHAT_CREATEGUILD_HASGUILD.send(sender);
			return true;
		}

		if(plugin.getGuildManager().getGuildByName(guildname) != null) {
			Message.CHAT_CREATEGUILD_NAMEEXISTS.send(sender);
			return true;
		}

		if(plugin.getGuildManager().getGuildByTag(tag) != null) {
			Message.CHAT_CREATEGUILD_TAGEXISTS.send(sender);
			return true;
		}

		if(plugin.getRegionManager().getRegion(player.getLocation()) != null) {
			Message.CHAT_CREATEGUILD_REGIONHERE.send(sender);
			return true;
		}

		//tag length
		if(tag.length() > Config.GUILD_SETTINGS_TAG_MAX.getInt()) { //too long
			Message.CHAT_CREATEGUILD_TAG_TOOLONG.send(sender);
			return true;
		}

		if(StringUtils.removeColors(tag).length() < Config.GUILD_SETTINGS_TAG_MIN.getInt()) { //too short
			Message.CHAT_CREATEGUILD_TAG_TOOSHORT.send(sender);
			return true;
		}

		//name length
		if(guildname.length() > Config.GUILD_SETTINGS_NAME_MAX.getInt()) { //too long
			Message.CHAT_CREATEGUILD_NAME_TOOLONG.send(sender);
			return true;
		}

		if(guildname.length() < Config.GUILD_SETTINGS_NAME_MIN.getInt()) { //too short
			Message.CHAT_CREATEGUILD_NAME_TOOSHORT.send(sender);
			return true;
		}

		//allowed strings (tag, name)
		if(!StringUtils.isStringAllowed(tag) || !StringUtils.isStringAllowed(guildname)) {
			Message.CHAT_CREATEGUILD_NOTALLOWEDSTRING.send(sender);
			return true;
		}

		//distance from spawn
		if(player.getWorld().getSpawnLocation().distance(player.getLocation()) < Config.GUILD_FROMSPAWN.getInt()) {
			vars.put("DISTANCE", String.valueOf(Config.GUILD_FROMSPAWN.getInt()));
			Message.CHAT_CREATEGUILD_TOOCLOSESPAWN.vars(vars).send(sender);
			return true;
		}

		//Disabled worlds
		if(Config.GUILD_DISABLEDWORLDS.getStringList().contains(player.getWorld().getName())) {
			Message.CHAT_CREATEGUILD_DISABLEDWORLD.send(sender);
			return true;
		}

		//items required
		NovaGroup group = plugin.getGroupManager().getGroup(sender);
		List<ItemStack> items = group.getGuildCreateItems();
		double requiredmoney = group.getGuildCreateMoney();

		if(requiredmoney>0 && plugin.econ.getBalance(player.getName()) < requiredmoney) {
			vars.put("REQUIREDMONEY", String.valueOf(requiredmoney));
			Message.CHAT_CREATEGUILD_NOTENOUGHMONEY.vars(vars).send(sender);
			return true;
		}

		if(!InventoryUtils.containsItems(player.getInventory(), items)) {
			String itemlist = "";
			int i = 0;
			for(ItemStack missingItemStack : InventoryUtils.getMissingItems(player.getInventory(), items)) {
				String itemrow = Message.CHAT_CREATEGUILD_ITEMLIST.get();
				itemrow = StringUtils.replace(itemrow, "{ITEMNAME}", missingItemStack.getType().name());
				itemrow = StringUtils.replace(itemrow, "{AMOUNT}", missingItemStack.getAmount() + "");

				itemlist += itemrow;

				if(i<items.size()-1) {
					itemlist += Message.CHAT_CREATEGUILD_ITEMLISTSEP.get();
				}
				i++;
			}

			Message.CHAT_CREATEGUILD_NOITEMS.send(sender);
			sender.sendMessage(StringUtils.fixColors(itemlist));
			return true;
		}

		RegionValidity regionValid = RegionValidity.VALID;
		NovaRegion region = null;

		//Automatic Region
		if(Config.REGION_AUTOREGION.getBoolean()) {
			int size = group.getRegionAutoSize();
			Location playerLocation = player.getLocation();
			Location c1 = new Location(player.getWorld(), playerLocation.getBlockX() - size, 0, playerLocation.getBlockZ() - size);
			Location c2 = new Location(player.getWorld(), playerLocation.getBlockX() + size, 0, playerLocation.getBlockZ() + size);

			region = new NovaRegion();

			region.setCorner(0, c1);
			region.setCorner(1, c2);
			region.setWorld(playerLocation.getWorld());

			regionValid = plugin.getRegionManager().checkRegionSelect(c1, c2);
			LoggerUtils.debug(regionValid.name());
		}

		switch(regionValid) {
			case VALID:
				//Guild object
				NovaGuild newGuild = new NovaGuild();
				newGuild.setName(guildname);
				newGuild.setTag(tag);
				newGuild.setLeader(nPlayer);
				newGuild.setSpawnPoint(player.getLocation());
				newGuild.addPlayer(nPlayer);
				newGuild.updateInactiveTime();
				newGuild.setLives(Config.GUILD_STARTLIVES.getInt());
				newGuild.setPoints(Config.GUILD_STARTPOINTS.getInt());
				newGuild.setMoney(Config.GUILD_STARTMONEY.getInt());
				newGuild.setSlots(Config.GUILD_STARTSLOTS.getInt());
				newGuild.setTimeCreated(NumberUtils.systemSeconds());

				//fire event
				GuildCreateEvent guildCreateEvent = new GuildCreateEvent(newGuild,(Player)sender);
				plugin.getServer().getPluginManager().callEvent(guildCreateEvent);

				if(!guildCreateEvent.isCancelled()) {
					//Add the guild
					plugin.getGuildManager().add(newGuild);

					//nPlayer
					nPlayer.setGuild(newGuild);

					//taking money away
					plugin.econ.withdrawPlayer(sender.getName(), requiredmoney);

					//taking items away
					InventoryUtils.removeItems(player, items);

					//update tag and tabs
					plugin.tagUtils.updatePrefix((Player)sender);

					//Update holograms
					plugin.getHologramManager().refreshTopHolograms();

					//autoregion
					if(region != null) {
						region.setGuild(nPlayer.getGuild());
						plugin.getRegionManager().add(region, nPlayer.getGuild());

						for(Player playerCheck : plugin.getServer().getOnlinePlayers()) {
							if(region.equals(plugin.getRegionManager().getRegion(playerCheck.getLocation()))) {
								plugin.getRegionManager().playerEnteredRegion(playerCheck,playerCheck.getLocation());
							}
						}
					}

					//homefloor
					GuildManager.createHomeFloor(newGuild);

					//vault item
					if(Config.VAULT_ENABLED.getBoolean()) {
						nPlayer.getPlayer().getInventory().addItem(Config.VAULT_ITEM.getItemStack());
					}

					//messages
					Message.CHAT_CREATEGUILD_SUCCESS.send(sender);

					vars.put("GUILDNAME", newGuild.getName());
					vars.put("PLAYER", sender.getName());
					Message.BROADCAST_GUILD_CREATED.vars(vars).broadcast();
				}
				break;
			case OVERLAPS:
				Message.CHAT_REGION_VALIDATION_OVERLAPS.send(sender);
				break;
			case TOOCLOSE:
				Message.CHAT_REGION_VALIDATION_TOOCLOSE.send(sender);
				break;
		}
		return true;
	}
}
