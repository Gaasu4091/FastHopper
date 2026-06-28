package me.gaasu9041.fasthopper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * change hopper speed, transfer
 */
public class FastHopperPlugin extends JavaPlugin
        implements Listener, CommandExecutor, TabCompleter {
  private static final int defaultAmount = 5;
  private static final int defaultTick = 8;
  private static final int minAmount = 1;
  private static final int minTick = 1;
  private static final int maxAmountLimit = 64;
  private static final String commandName = "fasthopper";
  private static final String adminPermission = "fasthopper.admin";
  private static final String transferAmountPath = "max-transfer-amount";
  private static final String transferTicksPath = "hopper-transfer-ticks";
  private static final String spigotConfigFieldName = "spigotConfig";
  private static final String amountFieldName = "hopperAmount";
  private static final String tickFieldName = "hopperTransfer";

  private final Map<UUID, HopperSettings> originalSettings = new HashMap<>();
  private volatile int maxTransferAmount = defaultAmount;
  private volatile int hopperTransferTicks = defaultTick;
  private NativeComposterAccess nativeComposterAccess;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    reloadSettings();
    registerCommand();
    getServer().getPluginManager().registerEvents(this, this);

    try {
      nativeComposterAccess = NativeComposterAccess.resolve();
      applySettingsToLoadedWorlds();
    } catch (ReflectiveOperationException exception) {
      getLogger()
              .log(
                      Level.SEVERE,
                      "FastHopper cannot access Folia/Paper's native hopper settings. "
                              + "The plugin will be disabled to avoid non-vanilla transfers.",
                      exception);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  /**
   * composter move
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onComposterMove(InventoryMoveItemEvent event) {
    ItemStack movedItem = event.getItem();
    if (event.getInitiator().getType() != InventoryType.HOPPER
            || event.getSource().getType() != InventoryType.HOPPER
            || event.getDestination().getType() != InventoryType.COMPOSTER
            || movedItem.getAmount() <= 1
            || !movedItem.getType().isCompostable()) {
      return;
    }

    Location sourceLocation = event.getSource().getLocation();
    Location composterLocation = event.getDestination().getLocation();
    if (sourceLocation == null || composterLocation == null) {
      return;
    }

    int transferAmount = Math.min(maxTransferAmount, movedItem.getAmount());
    ItemStack sampleItem = movedItem.clone();
    sampleItem.setAmount(1);
    event.setCancelled(true);

    getServer()
            .getRegionScheduler()
            .runDelayed(
                    this,
                    composterLocation,
                    task ->
                            transferToComposter(
                                    sourceLocation, composterLocation, sampleItem, transferAmount),
                    1L);
  }

  @Override
  public synchronized void onDisable() {
    for (World world : getServer().getWorlds()) {
      HopperSettings settings = originalSettings.get(world.getUID());
      if (settings == null) {
        continue;
      }

      try {
        writeNativeSettings(world, settings);
      } catch (ReflectiveOperationException exception) {
        getLogger()
                .log(
                        Level.WARNING,
                        "Could not restore native hopper settings for world " + world.getName(),
                        exception);
      }
    }
    originalSettings.clear();
  }

  /**
   * Applies the configured values to worlds that are loaded after this plugin is enabled.
   */
  @EventHandler
  public synchronized void onWorldLoad(WorldLoadEvent event) {
    try {
      applySettings(event.getWorld());
    } catch (ReflectiveOperationException exception) {
      getLogger()
              .log(
                      Level.SEVERE,
                      "Could not apply native hopper settings to world "
                              + event.getWorld().getName()
                              + ". FastHopper will be disabled.",
                      exception);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  @Override
  public synchronized boolean onCommand(
          CommandSender sender, Command command, String label, String[] args) {
    if (!commandName.equalsIgnoreCase(command.getName())) {
      return false;
    }

    if (!sender.hasPermission(adminPermission)) {
      sender.sendMessage("You do not have permission to use this command.");
      return true;
    }

    if (args.length == 0) {
      sendStatus(sender);
      return true;
    }

    if ("transfar".equalsIgnoreCase(args[0])) {
      return handleTransferCommand(sender, args);
    }

    if ("tick".equalsIgnoreCase(args[0])) {
      return handleTickCommand(sender, args);
    }

    if ("reload".equalsIgnoreCase(args[0])) {
      return handleReloadCommand(sender);
    }

    sendUsage(sender, label);
    return true;
  }

  @Override
  public List<String> onTabComplete(
          CommandSender sender, Command command, String alias, String[] args) {
    if (!commandName.equalsIgnoreCase(command.getName())
            || !sender.hasPermission(adminPermission)) {
      return List.of();
    }

    if (args.length == 1) {
      String prefix = args[0].toLowerCase(Locale.ROOT);
      List<String> suggestions = new ArrayList<>();
      addIfMatching(suggestions, "transfar", prefix);
      addIfMatching(suggestions, "tick", prefix);
      addIfMatching(suggestions, "reload", prefix);
      return suggestions;
    }

    if (args.length == 2) {
      if ("transfar".equalsIgnoreCase(args[0])) {
        return List.of(String.valueOf(maxTransferAmount));
      }
      if ("tick".equalsIgnoreCase(args[0])) {
        return List.of(String.valueOf(hopperTransferTicks));
      }
    }

    return List.of();
  }

  static int clampTransferAmount(int amount) {
    return Math.max(minAmount, Math.min(maxAmountLimit, amount));
  }

  static int clampTransferTicks(int ticks) {
    return Math.max(minTick, ticks);
  }

  private static void addIfMatching(List<String> suggestions, String value, String prefix) {
    if (value.startsWith(prefix)) {
      suggestions.add(value);
    }
  }

  private void registerCommand() {
    PluginCommand command = getCommand(commandName);
    if (command == null) {
      throw new IllegalStateException("Command " + commandName + " is missing from plugin.yml.");
    }

    command.setExecutor(this);
    command.setTabCompleter(this);
  }

  private boolean handleTransferCommand(CommandSender sender, String[] args) {
    if (args.length != 2) {
      sender.sendMessage("Usage: /fasthopper transfar <1-64>");
      return true;
    }

    Integer requestedAmount = parseNumber(sender, args[1]);
    if (requestedAmount == null) {
      return true;
    }
    if (requestedAmount < minAmount || requestedAmount > maxAmountLimit) {
      sender.sendMessage("Transfer amount must be between 1 and 64.");
      return true;
    }

    int previousAmount = maxTransferAmount;
    maxTransferAmount = requestedAmount;
    if (!tryApplyCommandSettings(sender, previousAmount, hopperTransferTicks)) {
      return true;
    }

    saveSettings();
    sendTransferAmount(sender);
    return true;
  }

  private boolean handleTickCommand(CommandSender sender, String[] args) {
    if (args.length != 2) {
      sender.sendMessage("Usage: /fasthopper tick <1 or higher>");
      return true;
    }

    Integer requestedTicks = parseNumber(sender, args[1]);
    if (requestedTicks == null) {
      return true;
    }
    if (requestedTicks < minTick) {
      sender.sendMessage("Transfer interval must be at least 1 tick.");
      return true;
    }

    int previousTicks = hopperTransferTicks;
    hopperTransferTicks = requestedTicks;
    if (!tryApplyCommandSettings(sender, maxTransferAmount, previousTicks)) {
      return true;
    }

    saveSettings();
    sendTransferTicks(sender);
    return true;
  }

  private boolean handleReloadCommand(CommandSender sender) {
    int previousAmount = maxTransferAmount;
    int previousTicks = hopperTransferTicks;
    reloadConfig();
    reloadSettings();

    try {
      applySettingsToLoadedWorlds();
    } catch (ReflectiveOperationException exception) {
      maxTransferAmount = previousAmount;
      hopperTransferTicks = previousTicks;
      restoreActiveSettingsAfterFailure(exception);
      sender.sendMessage("Could not apply the reloaded hopper settings. See the server log.");
      return true;
    }

    sendStatus(sender);
    return true;
  }

  private boolean tryApplyCommandSettings(
          CommandSender sender, int previousAmount, int previousTicks) {
    try {
      applySettingsToLoadedWorlds();
      return true;
    } catch (ReflectiveOperationException exception) {
      maxTransferAmount = previousAmount;
      hopperTransferTicks = previousTicks;
      restoreActiveSettingsAfterFailure(exception);
      sender.sendMessage("Could not apply the hopper settings. See the server log.");
      return false;
    }
  }

  private void restoreActiveSettingsAfterFailure(ReflectiveOperationException originalFailure) {
    getLogger().log(Level.SEVERE, "Could not update native hopper settings.", originalFailure);
    try {
      applySettingsToLoadedWorlds();
    } catch (ReflectiveOperationException rollbackFailure) {
      originalFailure.addSuppressed(rollbackFailure);
      getLogger()
              .log(
                      Level.SEVERE,
                      "Could not restore the previous hopper settings. FastHopper will be disabled.",
                      rollbackFailure);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  private Integer parseNumber(CommandSender sender, String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      sender.sendMessage("Please enter a whole number.");
      return null;
    }
  }

  private void sendStatus(CommandSender sender) {
    sendTransferAmount(sender);
    sendTransferTicks(sender);
  }

  private void sendTransferAmount(CommandSender sender) {
    sender.sendMessage("Hopper transfer amount is " + maxTransferAmount + ".");
  }

  private void sendTransferTicks(CommandSender sender) {
    sender.sendMessage("Hopper transfer interval is " + hopperTransferTicks + " ticks.");
  }

  private void sendUsage(CommandSender sender, String label) {
    sender.sendMessage("Usage: /" + label + " transfar <1-64>");
    sender.sendMessage("Usage: /" + label + " tick <1 or higher>");
    sender.sendMessage("Usage: /" + label + " reload");
  }

  private void reloadSettings() {
    int configuredAmount = getConfig().getInt(transferAmountPath, defaultAmount);
    int configuredTicks = getConfig().getInt(transferTicksPath, defaultTick);
    maxTransferAmount = clampTransferAmount(configuredAmount);
    hopperTransferTicks = clampTransferTicks(configuredTicks);
    boolean configChanged = false;

    if (configuredAmount != maxTransferAmount) {
      getConfig().set(transferAmountPath, maxTransferAmount);
      configChanged = true;
      getLogger()
              .warning(
                      transferAmountPath
                              + " must be between 1 and 64; using "
                              + maxTransferAmount
                              + ".");
    }

    if (!getConfig().contains(transferTicksPath) || configuredTicks != hopperTransferTicks) {
      getConfig().set(transferTicksPath, hopperTransferTicks);
      configChanged = true;
      if (configuredTicks != hopperTransferTicks) {
        getLogger()
                .warning(
                        transferTicksPath
                                + " must be at least 1; using "
                                + hopperTransferTicks
                                + ".");
      }
    }

    if (configChanged) {
      saveConfig();
    }
  }

  private void saveSettings() {
    getConfig().set(transferAmountPath, maxTransferAmount);
    getConfig().set(transferTicksPath, hopperTransferTicks);
    saveConfig();
  }

  private void transferToComposter(
          Location sourceLocation,
          Location composterLocation,
          ItemStack sampleItem,
          int transferAmount) {
    Block sourceBlock = sourceLocation.getBlock();
    Block composterBlock = composterLocation.getBlock();
    if (sourceBlock.getType() != Material.HOPPER
            || composterBlock.getType() != Material.COMPOSTER
            || !(sourceBlock.getState(false) instanceof Hopper hopper)) {
      return;
    }

    Inventory source = hopper.getInventory();
    int sourceSlot = findFirstSimilarSlot(source, sampleItem);
    if (sourceSlot < 0) {
      return;
    }

    for (int transferred = 0; transferred < transferAmount; transferred++) {
      int oldLevel = getComposterLevel(composterBlock);
      if (oldLevel < 0 || oldLevel >= 7) {
        return;
      }

      ItemStack sourceItem = source.getItem(sourceSlot);
      if (sourceItem == null || !sourceItem.isSimilar(sampleItem)) {
        return;
      }

      ItemStack originalSourceItem = sourceItem.clone();
      removeOneItem(source, sourceSlot, sourceItem);

      try {
        boolean raisedLevel = nativeComposterAccess.addItem(composterBlock, sampleItem);
        composterBlock
                .getWorld()
                .playEffect(
                        composterLocation,
                        Effect.COMPOSTER_FILL_ATTEMPT,
                        raisedLevel ? 1 : 0);
      } catch (ReflectiveOperationException exception) {
        if (getComposterLevel(composterBlock) == oldLevel) {
          source.setItem(sourceSlot, originalSourceItem);
        }
        getLogger().log(Level.SEVERE, "Could not run the native composter transfer.", exception);
        return;
      }
    }
  }

  private int getComposterLevel(Block composterBlock) {
    if (composterBlock.getType() == Material.COMPOSTER
            && composterBlock.getBlockData() instanceof org.bukkit.block.data.Levelled levelled) {
      return levelled.getLevel();
    }
    return -1;
  }

  private int findFirstSimilarSlot(Inventory inventory, ItemStack sampleItem) {
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      ItemStack item = inventory.getItem(slot);
      if (item != null && item.isSimilar(sampleItem)) {
        return slot;
      }
    }
    return -1;
  }

  private void removeOneItem(Inventory inventory, int slot, ItemStack item) {
    if (item.getAmount() == 1) {
      inventory.setItem(slot, null);
      return;
    }

    ItemStack reducedItem = item.clone();
    reducedItem.setAmount(item.getAmount() - 1);
    inventory.setItem(slot, reducedItem);
  }

  private void applySettingsToLoadedWorlds() throws ReflectiveOperationException {
    for (World world : getServer().getWorlds()) {
      applySettings(world);
    }
  }

  private void applySettings(World world) throws ReflectiveOperationException {
    NativeHopperConfig nativeConfig = resolveNativeConfig(world);
    originalSettings.putIfAbsent(world.getUID(), nativeConfig.read());
    nativeConfig.write(new HopperSettings(maxTransferAmount, hopperTransferTicks));
  }

  private void writeNativeSettings(World world, HopperSettings settings)
          throws ReflectiveOperationException {
    resolveNativeConfig(world).write(settings);
  }

  private NativeHopperConfig resolveNativeConfig(World world) throws ReflectiveOperationException {
    Method getHandle = world.getClass().getMethod("getHandle");
    Object worldHandle = getHandle.invoke(world);
    Field spigotConfigField = worldHandle.getClass().getField(spigotConfigFieldName);
    Object spigotConfig = spigotConfigField.get(worldHandle);
    Field amountField = spigotConfig.getClass().getField(amountFieldName);
    Field ticksField = spigotConfig.getClass().getField(tickFieldName);
    return new NativeHopperConfig(spigotConfig, amountField, ticksField);
  }

  private record HopperSettings(int amount, int ticks) {
  }

  private record NativeHopperConfig(Object config, Field amountField, Field ticksField) {
    private HopperSettings read() throws IllegalAccessException {
      return new HopperSettings(amountField.getInt(config), ticksField.getInt(config));
    }

    private void write(HopperSettings settings) throws IllegalAccessException {
      amountField.setInt(config, settings.amount());
      ticksField.setInt(config, settings.ticks());
    }
  }

  private record NativeComposterAccess(
          Class<?> blockPosClass,
          java.lang.reflect.Constructor<?> blockPosConstructor,
          Method getBlockStateMethod,
          Method asNmsCopyMethod,
          Method addItemMethod) {
    private static NativeComposterAccess resolve() throws ReflectiveOperationException {
      Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
      Class<?> serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
      Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
      Class<?> composterBlock = Class.forName("net.minecraft.world.level.block.ComposterBlock");
      Class<?> craftItemStack =
              Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");

      java.lang.reflect.Constructor<?> constructor =
              blockPos.getConstructor(int.class, int.class, int.class);
      Method getBlockState = serverLevel.getMethod("getBlockState", blockPos);
      Method asNmsCopy = craftItemStack.getMethod("asNMSCopy", ItemStack.class);
      Method addItem = findNativeAddItemMethod(composterBlock, nmsItemStack);
      addItem.setAccessible(true);

      return new NativeComposterAccess(
              blockPos, constructor, getBlockState, asNmsCopy, addItem);
    }

    private static Method findNativeAddItemMethod(Class<?> composterBlock, Class<?> nmsItemStack)
            throws NoSuchMethodException {
      for (Method method : composterBlock.getDeclaredMethods()) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (method.getName().equals("addItem")
                && Modifier.isStatic(method.getModifiers())
                && parameterTypes.length == 5
                && parameterTypes[4] == nmsItemStack) {
          return method;
        }
      }
      throw new NoSuchMethodException("ComposterBlock.addItem");
    }

    private boolean addItem(Block composter, ItemStack item)
            throws ReflectiveOperationException {
      World world = composter.getWorld();
      Method getHandle = world.getClass().getMethod("getHandle");
      Object worldHandle = getHandle.invoke(world);
      Object blockPos =
              blockPosConstructor.newInstance(composter.getX(), composter.getY(), composter.getZ());
      Object oldBlockState = getBlockStateMethod.invoke(worldHandle, blockPos);
      Object nmsItem = asNmsCopyMethod.invoke(null, item);
      Object newBlockState =
              addItemMethod.invoke(null, null, oldBlockState, worldHandle, blockPos, nmsItem);
      return !oldBlockState.equals(newBlockState);
    }
  }
}