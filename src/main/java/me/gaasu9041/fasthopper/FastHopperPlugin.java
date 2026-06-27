package me.gaasu9041.fasthopper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Crafter;
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

  /**
   * Cancels vanilla hopper transfers into disabled Crafter slots or into a Shulker Box when the
   * transferred item is itself a Shulker Box.
   */
  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryMoveItemHighest(InventoryMoveItemEvent event) {
    if (!isHopperInventory(event.getInitiator())) {
      return;
    }

    Inventory destination = event.getDestination();
    ItemStack movedItem = event.getItem();

    if (isShulkerBoxInventory(destination) && isShulkerBox(movedItem.getType())) {
      event.setCancelled(true);
      return;
    }

    if (!isCrafterInventory(destination)) {
      return;
    }

    if (!hasCrafterDisabledSlot(destination)) {
      return;
    }

    int destinationSlot = findVanillaDestinationSlot(destination, movedItem);
    if (destinationSlot < 0 || isCrafterSlotDisabled(destination, destinationSlot)) {
      event.setCancelled(true);
    }
  }

  /**
   * Transfers additional items beyond the single item vanilla already moved, up to
   * {@code maxTransferAmount} total.
   */
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
    if (extraAmount <= 0) {
      return;
    }

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

  /**
   * Returns {@code true} if the inventory is a Crafter and has at least one disabled slot.
   */
  private boolean hasCrafterDisabledSlot(Inventory inventory) {
    if (!(inventory.getHolder(false) instanceof Crafter crafter)) {
      return false;
    }
    int size = inventory.getSize();
    for (int i = 0; i < size; i++) {
      if (crafter.isSlotDisabled(i)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns {@code true} if the given slot in the Crafter inventory is disabled.
   *
   * <p>Returns {@code false} if the holder is not a {@link Crafter}.
   */
  private boolean isCrafterSlotDisabled(Inventory inventory, int slot) {
    if (inventory.getHolder(false) instanceof Crafter crafter) {
      return crafter.isSlotDisabled(slot);
    }
    return false;
  }

  /**
   * Returns the slot index that vanilla would place {@code item} into, or -1 if no valid slot
   * exists.
   *
   * <p>Mirrors vanilla hopper logic: prefer an existing partial stack first, then the first empty
   * slot.
   */
  private int findVanillaDestinationSlot(Inventory inventory, ItemStack item) {
    int size = inventory.getSize();
    int stackLimit = Math.min(item.getMaxStackSize(), inventory.getMaxStackSize());
    int emptySlot = -1;

    for (int slot = 0; slot < size; slot++) {
      ItemStack current = inventory.getItem(slot);
      if (current == null || current.getType().isAir()) {
        if (emptySlot < 0) {
          emptySlot = slot;
        }
      } else if (current.isSimilar(item) && current.getAmount() < stackLimit) {
        return slot;
      }
    }

    return emptySlot;
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

    int remaining = addItemSkippingDisabledSlots(destination, sampleItem, removedAmount);
    if (remaining <= 0) {
      return;
    }

    ItemStack returnStack = sampleItem.clone();
    returnStack.setAmount(remaining);
    Map<Integer, ItemStack> sourceLeftovers = source.addItem(returnStack);
    if (!sourceLeftovers.isEmpty()) {
      getLogger().warning("Some hopper items could not be restored after a partial transfer.");
    }
  }

  /**
   * Adds up to {@code amount} items of type {@code sampleItem} into the inventory, skipping
   * disabled Crafter slots.
   *
   * <p>Uses {@link Inventory#getItem} and {@link Inventory#setItem} per slot to avoid overwriting
   * Crafter disabled-slot state via {@code setStorageContents}.
   *
   * @return the number of items that could not be placed (0 means all placed successfully)
   */
  private int addItemSkippingDisabledSlots(
          Inventory inventory, ItemStack sampleItem, int amount) {
    int stackLimit = Math.min(sampleItem.getMaxStackSize(), inventory.getMaxStackSize());
    int remaining = amount;
    int size = inventory.getSize();

    for (int slot = 0; slot < size && remaining > 0; slot++) {
      if (isCrafterSlotDisabled(inventory, slot)) {
        continue;
      }
      ItemStack current = inventory.getItem(slot);
      if (current == null || current.getType().isAir()) {
        continue;
      }
      if (!current.isSimilar(sampleItem)) {
        continue;
      }
      int space = stackLimit - current.getAmount();
      if (space <= 0) {
        continue;
      }
      int take = Math.min(space, remaining);
      current.setAmount(current.getAmount() + take);
      inventory.setItem(slot, current);
      remaining -= take;
    }

    for (int slot = 0; slot < size && remaining > 0; slot++) {
      if (isCrafterSlotDisabled(inventory, slot)) {
        continue;
      }
      ItemStack current = inventory.getItem(slot);
      if (current != null && !current.getType().isAir()) {
        continue;
      }
      int take = Math.min(stackLimit, remaining);
      ItemStack placed = sampleItem.clone();
      placed.setAmount(take);
      inventory.setItem(slot, placed);
      remaining -= take;
    }

    return remaining;
  }

  /**
   * Sets the transfer cooldown on the hopper that initiated the transfer.
   *
   * <p>Returns {@code true} if the cooldown was applied successfully.
   */
  private boolean setTransferCooldown(Inventory inventory) {
    if (inventory.getHolder(false) instanceof Hopper hopper) {
      hopper.setTransferCooldown(hopperTransferTicks);
      return true;
    }
    return false;
  }

  /**
   * Calculates the number of additional items the destination inventory can accept, excluding
   * disabled Crafter slots.
   */
  private int countDestinationCapacity(Inventory inventory, ItemStack sampleItem) {
    int stackLimit = Math.min(sampleItem.getMaxStackSize(), inventory.getMaxStackSize());
    int capacity = 0;
    int size = inventory.getSize();

    for (int slot = 0; slot < size; slot++) {
      if (isCrafterSlotDisabled(inventory, slot)) {
        continue;
      }
      ItemStack item = inventory.getItem(slot);
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

  /**
   * Counts how many items similar to {@code sampleItem} are available in the inventory, up to
   * {@code maxTransferAmount}.
   */
  private int countSimilarItems(Inventory inventory, ItemStack sampleItem) {
    int size = inventory.getSize();
    int available = 0;

    for (int slot = 0; slot < size; slot++) {
      ItemStack item = inventory.getItem(slot);
      if (item != null && !item.getType().isAir() && item.isSimilar(sampleItem)) {
        available += item.getAmount();
      }
      if (available >= maxTransferAmount) {
        return maxTransferAmount;
      }
    }

    return available;
  }

  /**
   * Removes up to {@code amount} items similar to {@code sampleItem} from the inventory using
   * per-slot {@link Inventory#setItem} calls.
   *
   * @return the number of items actually removed
   */
  private int takeSimilarItems(Inventory inventory, ItemStack sampleItem, int amount) {
    int size = inventory.getSize();
    int remaining = amount;

    for (int slot = 0; slot < size && remaining > 0; slot++) {
      ItemStack item = inventory.getItem(slot);
      if (item == null || item.getType().isAir() || !item.isSimilar(sampleItem)) {
        continue;
      }

      int take = Math.min(remaining, item.getAmount());
      remaining -= take;

      int newAmount = item.getAmount() - take;
      if (newAmount <= 0) {
        inventory.setItem(slot, null);
      } else {
        item.setAmount(newAmount);
        inventory.setItem(slot, item);
      }
    }

    return amount - remaining;
  }

  private boolean isHopperInventory(Inventory inventory) {
    return inventory.getType() == InventoryType.HOPPER;
  }

  private boolean isShulkerBoxInventory(Inventory inventory) {
    return inventory.getType() == InventoryType.SHULKER_BOX;
  }

  private boolean isCrafterInventory(Inventory inventory) {
    return inventory.getType() == InventoryType.CRAFTER;
  }
}