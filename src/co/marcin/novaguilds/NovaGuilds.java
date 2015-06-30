package co.marcin.novaguilds;

import co.marcin.novaguilds.basic.NovaGroup;
import co.marcin.novaguilds.basic.NovaGuild;
import co.marcin.novaguilds.basic.NovaPlayer;
import co.marcin.novaguilds.listener.*;
import co.marcin.novaguilds.manager.*;
import co.marcin.novaguilds.runnable.RunnableAutoSave;
import co.marcin.novaguilds.runnable.RunnableLiveRegeneration;
import co.marcin.novaguilds.runnable.RunnableTeleportRequest;
import co.marcin.novaguilds.utils.RegionUtils;
import co.marcin.novaguilds.utils.StringUtils;
import co.marcin.novaguilds.utils.TagUtils;

import code.husky.mysql.MySQL;
import code.husky.sqlite.SQLite;

import com.gmail.filoghost.holographicdisplays.api.Hologram;
import com.gmail.filoghost.holographicdisplays.api.HologramsAPI;

import me.confuser.barapi.BarAPI;

import net.milkbowl.vault.Metrics;
import net.milkbowl.vault.economy.Economy;

import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class NovaGuilds extends JavaPlugin {
	/*
	* Bukkicie nasz, kt�ry� jest w javie, �wi�� si� metody Twoje, przyjd� repo Twoje,
	* b�d� kod Tw�j jako w gicie tak i w mavenie, stacktrace naszego powszedniego
	* daj nam dzisiaj, i odpu�� nam bugi Twoje, jako i my odpuszczamy naszym
	* kolegom z pracy, i nie w�d� nas na wycieki pami�ci, ale nas zbaw od Skript.
	* Enter. ~Bukkit.PL
	* */
	private static NovaGuilds inst;
	private static final Logger log = Logger.getLogger("Minecraft");
	private static final String logprefix = "[NovaGuilds] ";
	public final PluginDescriptionFile pdf = this.getDescription();
	private final PluginManager pm = getServer().getPluginManager();
	public String sqlp;
	public final boolean DEBUG = getConfig().getBoolean("debug");
	
	private long MySQLReconnectStamp = System.currentTimeMillis();

	//TODO kickowanie z admina dubluje user�w gildii
	//TODO @up nie wiem czy aktualne

	//TODO: podwojny event w MoveListenerze
	//public long moveListenerFix;

	//Vault
	public Economy econ = null;

	public boolean useHolographicDisplays;
	private boolean useBarAPI;
	private boolean useMySQL;

	private final HashMap<String,NovaGroup> groups = new HashMap<>();
	
	private final GuildManager guildManager = new GuildManager(this);
	private final RegionManager regionManager = new RegionManager(this);
	private final PlayerManager playerManager = new PlayerManager(this);
	private CustomCommandManager commandManager;
	private final MessageManager messageManager = new MessageManager(this);
	private ConfigManager configManager;

	public long timeRest;
	public int savePeriod; //minutes
	public long timeInactive;
	public long liveRegenerationTime; //minutes

	public TagUtils tagUtils;
	public boolean updateAvailable = false;

	//TODO: test scheduler
	public final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
	public final List<NovaGuild> guildRaids = new ArrayList<>();

	//Database
	private MySQL MySQL;
	public Connection c = null;

	public double distanceFromSpawn;

	public void onEnable() {
		inst = this;
		saveDefaultConfig();
		sqlp = getConfig().getString("mysql.prefix");
		savePeriod = getConfig().getInt("saveperiod");

		timeRest = getConfig().getLong("raid.timerest");
		distanceFromSpawn = getConfig().getLong("guild.fromspawn");
		timeInactive = getConfig().getLong("raid.timeinactive");

		String liveRegenerationString = getConfig().getString("liveregenerationtime");
		liveRegenerationTime = StringUtils.StringToSeconds(liveRegenerationString);

		useHolographicDisplays = getConfig().getBoolean("holographicdisplays.enabled");
		useBarAPI = getConfig().getBoolean("barapi.enabled");

		useMySQL = getConfig().getBoolean("usemysql");

		//load groups
		loadGroups();
		info("Groups loaded");

		//managers
		commandManager = new CustomCommandManager(this);
		configManager = new ConfigManager(this);

		tagUtils = new TagUtils(this);

		//HolographicDisplays
		if(useHolographicDisplays) {
			if(!checkHolographicDisplays()) {
				log.severe(logprefix + "Couldn't find HolographicDisplays plugin, disabling this feature.");
				useHolographicDisplays = false;
			}
			else {
				info("HolographicDisplays hooked");
			}
		}
		
		
		//Vault Economy
		if(!checkVault()) {
			log.severe(logprefix+"Disabled due to no Vault dependency found!");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		info("Vault hooked");

		if(!setupEconomy()) {
			log.severe(logprefix+"Could not setup Vault's economy, disabling");
			getServer().getPluginManager().disablePlugin(this);
			return;
		}
		info("Vault's Economy hooked");

		//BarAPI
		if(useBarAPI) {
			if(!checkBarAPI()) {
				log.severe(logprefix + "Couldn't find BarAPI plugin, disabling this feature.");
				useBarAPI = false;
			} else {
				info("BarAPI hooked");
			}
		}
		
		//messages.yml
		File langsDir = new File(getDataFolder(),"lang/");
		if(!langsDir.exists()) {
			if(langsDir.mkdir()) {
				info("Language dir created");
			}
		}
		
		if(!getMessageManager().loadMessages()) {
            return;
		}

		info("Messages loaded");
        
		//Version check
        String latest = StringUtils.getContent("http://novaguilds.marcin.co/latest.info");
        info("You're using build: #"+pdf.getVersion());
        info("Latest build of the plugin is: #"+latest);

		int latestint = 0;
		if(StringUtils.isNumeric(latest)) {
			latestint = Integer.parseInt(latest);
		}

		int version = 0;
		if(StringUtils.isNumeric(pdf.getVersion())) {
			version = Integer.parseInt(pdf.getVersion());
		}

        if(version == latestint) {
        	info("Your plugin build is the latest one");
        }
        else if(version > latestint) {
	        info("You are using unreleased build #"+version);
        }
        else {
        	info("You should update your plugin to #"+latest+"!");
			updateAvailable = true;
        }

		try {
			if(useMySQL) {
				MySQL = new MySQL(this, getConfig().getString("mysql.host") , getConfig().getString("mysql.port"), getConfig().getString("mysql.database"), getConfig().getString("mysql.username"), getConfig().getString("mysql.password"));
				c = MySQL.openConnection();
				info("Connected to MySQL database");
			}
			else {
				SQLite sqlite = new SQLite(this, "sqlite.db");
				c = sqlite.openConnection();
				info("Connected to SQLite database");
			}
			
			//Tables setup
			DatabaseMetaData md = c.getMetaData();
			ResultSet rs = md.getTables(null, null, sqlp+"%", null);
			if(!rs.next()) {
				info("Couldn't find tables in the base. Creating...");
				String[] SQLCreateCode = getSQLCreateCode(useMySQL);
				if(SQLCreateCode.length != 0) {
					try {
						for(String tableCode : SQLCreateCode) {
							createTable(tableCode);
							info("Tables added to the database!");
						}
					}
					catch (SQLException e) {
						info("Could not create tables. Disabling");
						info("SQLException: "+e.getMessage());
						getServer().getPluginManager().disablePlugin(this);
						return;
					}
				}
				else {
					info("Couldn't find SQL create code for tables!");
					getServer().getPluginManager().disablePlugin(this);
					return;
				}
			}
			else {
				info("No MySQL config required.");
			}
			
			//Data loading
			getRegionManager().loadRegions();
			info("Regions data loaded");
			getGuildManager().loadGuilds();
			info("Guilds data loaded");
			getPlayerManager().loadPlayers();
			info("Players data loaded");

			info("Post checks running");
			getGuildManager().postCheckGuilds();
			getRegionManager().postCheckRegions();
			
			//Listeners
			new LoginListener(this);
			new ToolListener(this);
			new RegionInteractListener(this);
			new MoveListener(this);
			new ChatListener(this);
			new PvpListener(this);
			new DeathListener(this);
			new InventoryListener(this);

			//Tablist/tag update
			tagUtils.refreshAll();
			
			//save scheduler
			runSaveScheduler();
			info("Save scheduler is running");

			//live regeneration task
			runLiveRegenerationTask();
			info("Live regeneration task is running");

			//metrics
			setupMetrics();

			info("#"+pdf.getVersion()+" Enabled");
		}
		catch (SQLException e) {
			info("MySQL connection failed.");
			info(e.getMessage());
			pm.disablePlugin(this);
		}
		catch (ClassNotFoundException e) {
			info("MySQL class not found.");
		}
	}
	
	public void onDisable() {
		getGuildManager().saveAll();
		getRegionManager().saveAll();
		getPlayerManager().saveAll();
		info("Saved all data");

		//Stop schedulers
		worker.shutdown();

		//reset barapi
		if(useBarAPI) {
			for(Player player : getServer().getOnlinePlayers()) {
				BarAPI.removeBar(player);
			}
		}

		//removing holographic displays
		if(useHolographicDisplays) {
			for(Hologram h : HologramsAPI.getHolograms(this)) {
				h.delete();
			}
		}
		
		for(Player p : getServer().getOnlinePlayers()) {
			NovaPlayer nPlayer = getPlayerManager().getPlayerByName(p.getName());
			Location l1 = nPlayer.getSelectedLocation(0);
			Location l2 = nPlayer.getSelectedLocation(1);
			
			if(l1 != null && l2 != null) {
				getRegionManager().sendSquare(p,l1,l2,null,(byte)0);
				RegionUtils.resetCorner(p, l1);
				RegionUtils.resetCorner(p, l2);

				if(nPlayer.getSelectedRegion() != null) {
					RegionUtils.resetHighlightRegion(p,nPlayer.getSelectedRegion());
				}
			}
		}
		
		info("#"+pdf.getVersion()+" Disabled");
	}

	public static NovaGuilds getInst() {
		return inst;
	}
	
	public void mysqlReload() {
		if(!useMySQL) return;
		long stamp = System.currentTimeMillis();
		
		if(stamp-MySQLReconnectStamp > 3000) {
	    	try {
				MySQL.closeConnection();
				try {
					c = MySQL.openConnection();
					info("MySQL reconnected");
					MySQLReconnectStamp = System.currentTimeMillis();
				}
				catch (ClassNotFoundException e) {
					info(e.getMessage());
				}
			}
	    	catch (SQLException e1) {
	    		info(e1.getMessage());
			}
		}
    }
	
	public void info(String msg) {
		log.info(logprefix+msg);
	}

	public void debug(String msg) {
		if(DEBUG) {
			log.info(logprefix + "[DEBUG] " + msg);
		}
	}
	
	//Managers
	public GuildManager getGuildManager() {
		return guildManager;
	}

	public RegionManager getRegionManager() {
		return regionManager;
	}

	public PlayerManager getPlayerManager() {
		return playerManager;
	}

	public CustomCommandManager getCommandManager() {
		return commandManager;
	}

	public MessageManager getMessageManager() {
		return messageManager;
	}

	public ConfigManager getConfigManager() {
		return configManager;
	}

	//Vault economy
	private boolean setupEconomy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        
        if(rsp == null) {
            return false;
        }
        
        econ = rsp.getProvider();
        return econ != null;
    }

	//Vault
	private boolean checkVault() {
		return getServer().getPluginManager().getPlugin("Vault") != null;
	}

	//BarAPI
	private boolean checkBarAPI() {
		return getServer().getPluginManager().getPlugin("BarAPI") != null;
	}

	//HolographicDisplays
	public boolean checkHolographicDisplays() {
		return getServer().getPluginManager().getPlugin("HolographicDisplays") != null;
	}
	
	//convert sender to player
	public Player senderToPlayer(CommandSender sender) {
		return getServer().getPlayer(sender.getName());
	}
	
	//true=mysql, false=sqlite
	private String[] getSQLCreateCode(boolean mysql) {
		String url = "http://novaguilds.marcin.co/sqltables.txt";
		String sql = StringUtils.getContent(url);
		
		int index;
		if(mysql)
			index=0;
		else
			index=1;
		
		String[] types = sql.split("--TYPE--");
		return types[index].split("--");
	}
	
	private void createTable(String sql) throws SQLException {
		mysqlReload();
		Statement statement;
		sql = StringUtils.replace(sql, "{SQLPREFIX}", sqlp);
		statement = c.createStatement();
		statement.executeUpdate(sql);
	}
	
	public void runSaveScheduler() {
		Runnable task = new RunnableAutoSave(this);
		worker.schedule(task, savePeriod, TimeUnit.MINUTES);
	}

	public void runLiveRegenerationTask() {
		Runnable task = new RunnableLiveRegeneration(this);
		worker.schedule(task,30,TimeUnit.MINUTES);
	}

	private void setupMetrics() {
		if(checkVault()) {
			try {
				Metrics metrics = new Metrics(this);
				Metrics.Graph weaponsUsedGraph = metrics.createGraph("Guilds and users");

				weaponsUsedGraph.addPlotter(new Metrics.Plotter("Guilds") {
					@Override
					public int getValue() {
						return getGuildManager().getGuilds().size();
					}

				});

				weaponsUsedGraph.addPlotter(new Metrics.Plotter("Users") {
					@Override
					public int getValue() {
						return getPlayerManager().getPlayers().size();
					}

				});

				metrics.start();
			}
			catch(IOException e) {
				info("Failed to update stats!");
				info(e.getMessage());
			}
		}
		else {
			info("Vault is not enabled, failed to setup Metrics");
		}
	}

	public void setWarBar(NovaGuild guild, float percent, NovaGuild defender) {
		String msg = getMessageManager().getMessagesString("barapi.warprogress");
		msg = StringUtils.replace(msg, "{DEFENDER}", defender.getName());

		for(Player player : guild.getOnlinePlayers()) {
			if(useBarAPI) {
				BarAPI.setMessage(player, StringUtils.fixColors(msg), percent);
			}
			else {
				getMessageManager().sendPrefixMessage(player,msg);
			}
		}
	}

	public void resetWarBar(NovaGuild guild) {
		if(useBarAPI) {
			for(NovaPlayer nPlayer : guild.getPlayers()) {
				if(nPlayer.isOnline()) {
					BarAPI.removeBar(nPlayer.getPlayer());
				}
			}
		}
	}

	public void loadGroups() {
		groups.clear();
		Set<String> groupsNames = getConfig().getConfigurationSection("guild.create.groups").getKeys(false);
		groupsNames.add("admin");

		for(String groupName : groupsNames) {
			groups.put(groupName, new NovaGroup(this, groupName));
		}

		debug(groups.toString());
	}

	public NovaGroup getGroup(Player player) {
		String groupName = "default";

		if(player == null) {
			debug("Player is null, return is default group");
			return getGroup(groupName);
		}

		if(player.hasPermission("novaguilds.group.admin")) {
			return getGroup("admin");
		}

		for(String name : groups.keySet()) {
			if(player.hasPermission("novaguilds.group."+name) && !name.equalsIgnoreCase("default")) {
				groupName = name;
				break;
			}
		}

		return getGroup(groupName);
	}

	public NovaGroup getGroup(CommandSender sender) {
		if(sender instanceof Player) {
			return getGroup(senderToPlayer(sender));
		}
		else {
			return getGroup("admin");
		}
	}

	public NovaGroup getGroup(String groupName) {
		if(groups.containsKey(groupName)) {
			return groups.get(groupName);
		}

		debug("Requested invalid group ("+groupName+")");

		debug("Trying to get the group again...");
		debug("Found "+groups.size()+" groups");
		debug(groups.toString());
		for(Map.Entry<String, NovaGroup> group : groups.entrySet()) {
			debug(group.getKey()+" and "+groupName);
			if(group.getKey().equalsIgnoreCase(groupName)) {
				debug("Found matching group");
				return group.getValue();
			}
		}
		debug("Failed to get the group, return is null");
		return null;
	}

	public void delayedTeleport(Player player, Location location, String path) {
		Runnable task = new RunnableTeleportRequest(this,player,location,path);
		worker.schedule(task,getGroup(player).getTeleportDelay(),TimeUnit.SECONDS);

		if(getGroup(player).getTeleportDelay()>0) {
			getMessageManager().sendDelayedTeleportMessage(player);
		}
	}

	public boolean isStringAllowed(String string) {
		String allowed = getConfig().getString("guild.allowedchars");
		for(int i=0;i<string.length();i++) {
			if(allowed.indexOf(string.charAt(i)) == -1) {
				return false;
			}
		}

		return true;
	}

	//Utils
	public static long systemSeconds() {
		return System.currentTimeMillis() / 1000;
	}
}