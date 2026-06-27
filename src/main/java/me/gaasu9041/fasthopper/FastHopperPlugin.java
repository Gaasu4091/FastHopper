package me.gaasu9041.fasthopper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
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
  private static final int minTransferAmount = 1;
  private static final int maxTransferAmountLimit = 64;
  private static final String commandName = "fasthopper";
  private static final String adminPermission = "fasthopper.admin";
  private static final String transferAmountPath = "max-transfer-amount";

  private int maxTransferAmount = defaultTransferAmount;

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

    int extraAmount = maxTransferAmount - movedItem.getAmount();
    if (extraAmount <= 0) {
      return;
    }

    Inventory source = event.getSource();
    Inventory destination = event.getDestination();
    ItemStack sampleItem = movedItem.clone();
    getServer()
        .getScheduler()
        .runTask(this, () -> transferItems(source, destination, sampleItem, extraAmount));
  }

  @Override
  public boolean onCommand(
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
    if ("set".equalsIgnoreCase(subcommand)) {
      return handleSetCommand(sender, args);
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

      if ("set".startsWith(prefix)) {
        suggestions.add("set");
      }
      if ("reload".startsWith(prefix)) {
        suggestions.add("reload");
      }
      return suggestions;
    }

    if (args.length == 2 && "set".equalsIgnoreCase(args[0])) {
      return List.of(String.valueOf(maxTransferAmount));
    }

    return List.of();
  }

  static int clampTransferAmount(int amount) {
    return Math.max(minTransferAmount, Math.min(maxTransferAmountLimit, amount));
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

  private boolean handleSetCommand(CommandSender sender, String[] args) {
    if (args.length != 2) {
      sender.sendMessage("Usage: /fasthopper set <1-64>");
      return true;
    }

    int requestedAmount;
    try {
      requestedAmount = Integer.parseInt(args[1]);
    } catch (NumberFormatException exception) {
      sender.sendMessage("Please enter a whole number.");
      return true;
    }

    maxTransferAmount = clampTransferAmount(requestedAmount);
    saveTransferAmount();
    reloadSettings();
    sendStatus(sender);
    return true;
  }

  private void sendStatus(CommandSender sender) {
    sender.sendMessage("Hopper transfer amount is " + maxTransferAmount + ".");
  }

  private void sendUsage(CommandSender sender, String label) {
    sender.sendMessage("Usage: /" + label + " set <1-64>");
    sender.sendMessage("Usage: /" + label + " reload");
  }

  private void reloadSettings() {
    int configuredAmount = getConfig().getInt(transferAmountPath, defaultTransferAmount);
    maxTransferAmount = clampTransferAmount(configuredAmount);

    if (configuredAmount != maxTransferAmount) {
      getConfig().set(transferAmountPath, maxTransferAmount);
      saveConfig();
      getLogger()
          .warning(
              transferAmountPath + " must be between 1 and 64; using " + maxTransferAmount + ".");
    }
  }

  private void saveTransferAmount() {
    getConfig().set(transferAmountPath, maxTransferAmount);
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

  private int countDestinationCapacity(Inventory inventory, ItemStack sampleItem) {
    int stackLimit = Math.min(sampleItem.getMaxStackSize(), inventory.getMaxStackSize());
    int capacity = 0;
    ItemStack[] contents = inventory.getStorageContents();
    if (contents == null) {
      return 0;
    }

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
    if (contents == null) {
      return 0;
    }

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
    if (contents == null) {
      return 0;
    }

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
