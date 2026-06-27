package me.gaasu9041.fasthopper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class FastHopperPlugin extends JavaPlugin
    implements Listener, CommandExecutor, TabCompleter {
  private static final int defaultTransferAmount = 5;
  private static final int defaultTransferTicks = 8;
  private static final int minTransferAmount = 1;
  private static final int minTransferTicks = 1;
  private static final int maxTransferAmountLimit = 64;
  private static final String commandName = "fasthopper";
  private static final String adminPermission = "fasthopper.admin";
  private static final String transferAmountPath = "max-transfer-amount";
  private static final String transferTicksPath = "hopper-transfer-ticks";

  private volatile int maxTransferAmount = defaultTransferAmount;
  private volatile int hopperTransferTicks = defaultTransferTicks;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    reloadSettings();
    registerCommand();
    getServer().getPluginManager().registerEvents(this, this);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void preventShulkerBoxNesting(InventoryMoveItemEvent event) {
    if (!isHopperInventory(event.getInitiator())) {
      return;
    }

    if (isShulkerBoxInventory(event.getDestination())
        && isShulkerBox(event.getItem().getType())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInventoryMoveItem(InventoryMoveItemEvent event) {
    if (!isHopperInventory(event.getInitiator())) {
      return;
    }

    ItemStack movedItem = event.getItem();
    if (movedItem == null || movedItem.getType().isAir()) {
      return;
    }

    int extraAmount = Math.max(0, maxTransferAmount - movedItem.getAmount());
    Inventory initiator = event.getInitiator();
    Location location = initiator.getLocation();
    if (location == null) {
      return;
    }

    Inventory source = event.getSource();
    Inventory destination = event.getDestination();
    ItemStack sampleItem = movedItem.clone();
    getServer()
        .getRegionScheduler()
        .runDelayed(
            this,
            location,
            task -> {
              if (!setTransferCooldown(initiator)) {
                return;
              }
              transferItems(source, destination, sampleItem, extraAmount);
            },
            1L);
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

    String subcommand = args[0];
    if ("transfar".equalsIgnoreCase(subcommand)) {
      return handleTransferCommand(sender, args);
    }

    if ("tick".equalsIgnoreCase(subcommand)) {
      return handleTickCommand(sender, args);
    }

    if ("reload".equalsIgnoreCase(subcommand)) {
      reloadConfig();
      reloadSettings();
      sendStatus(sender);
      return true;
    }

    sendUsage(sender, label);
    return true;
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String alias, String[] args) {
    if (!commandName.equalsIgnoreCase(command.getName())) {
      return List.of();
    }

    if (args.length == 1) {
      List<String> suggestions = new ArrayList<>();
      String prefix = args[0].toLowerCase();

      if ("transfar".startsWith(prefix)) {
        suggestions.add("transfar");
      }
      if ("tick".startsWith(prefix)) {
        suggestions.add("tick");
      }
      if ("reload".startsWith(prefix)) {
        suggestions.add("reload");
      }
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
    return Math.max(minTransferAmount, Math.min(maxTransferAmountLimit, amount));
  }

  static int clampTransferTicks(int ticks) {
    return Math.max(minTransferTicks, ticks);
  }

  static boolean isShulkerBox(Material material) {
    return material == Material.SHULKER_BOX || material.name().endsWith("_SHULKER_BOX");
  }

  private void registerCommand() {
    PluginCommand command = getCommand(commandName);
    if (command == null) {
      getLogger().severe("Command " + commandName + " is missing from plugin.yml.");
      return;
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

    maxTransferAmount = clampTransferAmount(requestedAmount);
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

    hopperTransferTicks = clampTransferTicks(requestedTicks);
    saveSettings();
    sendTransferTicks(sender);
    return true;
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
    int configuredAmount = getConfig().getInt(transferAmountPath, defaultTransferAmount);
    int configuredTicks = getConfig().getInt(transferTicksPath, defaultTransferTicks);
    maxTransferAmount = clampTransferAmount(configuredAmount);
    hopperTransferTicks = clampTransferTicks(configuredTicks);
    boolean configChanged = false;

    if (configuredAmount != maxTransferAmount) {
      getConfig().set(transferAmountPath, maxTransferAmount);
      configChanged = true;
      getLogger()
          .warning(
              transferAmountPath + " must be between 1 and 64; using " + maxTransferAmount + ".");
    }

    if (!getConfig().contains(transferTicksPath) || configuredTicks != hopperTransferTicks) {
      getConfig().set(transferTicksPath, hopperTransferTicks);
      configChanged = true;
      if (configuredTicks != hopperTransferTicks) {
        getLogger()
            .warning(transferTicksPath + " must be at least 1; using " + hopperTransferTicks + ".");
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

  private void transferItems(
      Inventory source, Inventory destination, ItemStack sampleItem, int amount) {
    if (isShulkerBoxInventory(destination) && isShulkerBox(sampleItem.getType())) {
      return;
    }

    amount = Math.min(amount, sampleItem.getMaxStackSize());
    amount = Math.min(amount, countSimilarItems(source, sampleItem));
    amount = Math.min(amount, countDestinationCapacity(destination, sampleItem));
    if (amount <= 0) {
      return;
    }

    int removedAmount = takeSimilarItems(source, sampleItem, amount);
    if (removedAmount <= 0) {
      return;
    }

    ItemStack transferStack = sampleItem.clone();
    transferStack.setAmount(removedAmount);

    Map<Integer, ItemStack> leftovers = destination.addItem(transferStack);
    if (leftovers.isEmpty()) {
      return;
    }

    ItemStack[] leftoverItems = leftovers.values().toArray(ItemStack[]::new);
    Map<Integer, ItemStack> sourceLeftovers = source.addItem(leftoverItems);
    if (!sourceLeftovers.isEmpty()) {
      getLogger().warning("Some hopper items could not be restored after a partial transfer.");
    }
  }

  private boolean setTransferCooldown(Inventory inventory) {
    if (inventory.getHolder(false) instanceof Hopper hopper) {
      hopper.setTransferCooldown(hopperTransferTicks);
      return true;
    }
    return false;
  }

  private int countDestinationCapacity(Inventory inventory, ItemStack sampleItem) {
    int stackLimit = Math.min(sampleItem.getMaxStackSize(), inventory.getMaxStackSize());
    int capacity = 0;
    ItemStack[] contents = inventory.getStorageContents();

    for (ItemStack item : contents) {
      if (item == null || item.getType().isAir()) {
        capacity += stackLimit;
      } else if (item.isSimilar(sampleItem)) {
        capacity += Math.max(0, stackLimit - item.getAmount());
      }

      if (capacity >= maxTransferAmount) {
        return maxTransferAmount;
      }
    }

    return capacity;
  }

  private int countSimilarItems(Inventory inventory, ItemStack sampleItem) {
    ItemStack[] contents = inventory.getStorageContents();
    int availableAmount = 0;
    for (ItemStack item : contents) {
      if (item != null && !item.getType().isAir() && item.isSimilar(sampleItem)) {
        availableAmount += item.getAmount();
      }

      if (availableAmount >= maxTransferAmount) {
        return maxTransferAmount;
      }
    }

    return availableAmount;
  }

  private boolean isHopperInventory(Inventory inventory) {
    return inventory.getType() == InventoryType.HOPPER;
  }

  private boolean isShulkerBoxInventory(Inventory inventory) {
    return inventory.getType() == InventoryType.SHULKER_BOX;
  }

  private int takeSimilarItems(Inventory inventory, ItemStack sampleItem, int amount) {
    ItemStack[] contents = inventory.getStorageContents();
    int remainingAmount = amount;

    for (int slot = 0; slot < contents.length && remainingAmount > 0; slot++) {
      ItemStack item = contents[slot];
      if (item == null || item.getType().isAir() || !item.isSimilar(sampleItem)) {
        continue;
      }

      int amountTakenFromSlot = Math.min(remainingAmount, item.getAmount());
      remainingAmount -= amountTakenFromSlot;

      int newAmount = item.getAmount() - amountTakenFromSlot;
      if (newAmount <= 0) {
        contents[slot] = null;
      } else {
        item.setAmount(newAmount);
      }
    }

    int takenAmount = amount - remainingAmount;
    if (takenAmount > 0) {
      inventory.setStorageContents(contents);
    }

    return takenAmount;
  }
}
