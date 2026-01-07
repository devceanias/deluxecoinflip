/*
 * DeluxeCoinflip Plugin
 * Copyright (c) 2021 - 2025 Zithium Studios. All rights reserved.
 */

package net.zithium.deluxecoinflip.menu.inventories;

import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import me.nahu.scheduler.wrapper.WrappedScheduler;
import net.kyori.adventure.text.Component;
import net.zithium.deluxecoinflip.DeluxeCoinflipPlugin;
import net.zithium.deluxecoinflip.api.events.CoinflipCompletedEvent;
import net.zithium.deluxecoinflip.config.ConfigType;
import net.zithium.deluxecoinflip.config.Messages;
import net.zithium.deluxecoinflip.economy.EconomyManager;
import net.zithium.deluxecoinflip.game.CoinflipGame;
import net.zithium.deluxecoinflip.storage.PlayerData;
import net.zithium.deluxecoinflip.storage.StorageManager;
import net.zithium.deluxecoinflip.utility.ItemStackBuilder;
import net.zithium.deluxecoinflip.utility.TextUtil;
import net.zithium.library.utils.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.util.*;

@SuppressWarnings({ "deprecation", "CallToPrintStackTrace" })
public class CoinflipGUI implements Listener {

    private final DeluxeCoinflipPlugin plugin;
    private final EconomyManager economyManager;
    private final FileConfiguration config;
    private final String coinflipGuiTitle;
    private final boolean taxEnabled;
    private final double taxRate;
    private final long minimumBroadcastWinnings;
    private static final int ANIMATION_COUNT_THRESHOLD = 12;

    public CoinflipGUI(@NotNull final DeluxeCoinflipPlugin plugin) {
        this.plugin = plugin;
        this.economyManager = plugin.getEconomyManager();
        this.config = plugin.getConfigHandler(ConfigType.CONFIG).getConfig();

        // Load config values into variables this helps improve performance.
        this.coinflipGuiTitle = ColorUtil.color(Objects.requireNonNull(config.getString("coinflip-gui.title")));
        this.taxEnabled = config.getBoolean("settings.tax.enabled");
        this.taxRate = config.getDouble("settings.tax.rate");
        this.minimumBroadcastWinnings = config.getLong("settings.minimum-broadcast-winnings");
    }

    public void startGame(@NotNull Player creator, @NotNull OfflinePlayer opponent, final CoinflipGame game) {
        // Send the challenge message BEFORE any swapping
        Messages.PLAYER_CHALLENGE.send(opponent.getPlayer(), "{OPPONENT}", creator.getName());

        // SecureRandom for better randomness
        final SecureRandom random = new SecureRandom();
        random.setSeed(System.nanoTime() + creator.getUniqueId().hashCode() + opponent.getUniqueId().hashCode());

        // Randomly shuffle player order
        final List<OfflinePlayer> players = new ArrayList<>(Arrays.asList(creator, opponent));
        Collections.shuffle(players, random);

        creator = (Player) players.get(0);
        opponent = players.get(1);

        final OfflinePlayer winner = players.get(random.nextInt(players.size()));
        final OfflinePlayer loser = (winner == creator) ? opponent : creator;

        runAnimation(winner, loser, game);
    }

    private void runAnimation(final OfflinePlayer winner, final OfflinePlayer loser, final CoinflipGame game) {
        final WrappedScheduler scheduler = plugin.getScheduler();
        final Gui gui = Gui.gui().rows(3).title(Component.text(coinflipGuiTitle)).create();
        gui.disableAllInteractions();

        final GuiItem winnerHead = new GuiItem(new ItemStackBuilder(
                winner.equals(game.getOfflinePlayer()) ? game.getCachedHead() : new ItemStack(Material.PLAYER_HEAD)
        ).withName(ChatColor.YELLOW + winner.getName()).setSkullOwner(winner).build());

        final GuiItem loserHead = new GuiItem(new ItemStackBuilder(
                winner.equals(game.getOfflinePlayer()) ? new ItemStack(Material.PLAYER_HEAD) : game.getCachedHead()
        ).withName(ChatColor.YELLOW + loser.getName()).setSkullOwner(loser).build());

        final Player winnerPlayer = Bukkit.getPlayer(winner.getUniqueId());
        final Player loserPlayer = Bukkit.getPlayer(loser.getUniqueId());

        if (winnerPlayer != null) {
            scheduler.runTaskAtEntity(winnerPlayer, () -> {
                gui.open(winnerPlayer);
                startAnimation(scheduler, gui, winnerHead, loserHead, winner, loser, game, winnerPlayer, winnerPlayer.getLocation(), true);
            });
        }

        if (loserPlayer != null) {
            scheduler.runTaskAtEntity(loserPlayer, () -> {
                gui.open(loserPlayer);
                startAnimation(scheduler, gui, winnerHead, loserHead, winner, loser, game, loserPlayer, loserPlayer.getLocation(), false);
            });
        }
    }

    private void startAnimation(
        final WrappedScheduler scheduler, final Gui gui, final GuiItem winnerHead, final GuiItem loserHead,
        final OfflinePlayer winner, final OfflinePlayer loser, final CoinflipGame game,
        final Player targetPlayer, final Location regionLoc, final boolean isWinnerThread) {

        final ConfigurationSection animationConfig1 = plugin.getConfig().getConfigurationSection("coinflip-gui.animation.1");
        final ConfigurationSection animationConfig2 = plugin.getConfig().getConfigurationSection("coinflip-gui.animation.2");

        final ItemStack firstAnimationItem = (animationConfig1 != null)
                ? ItemStackBuilder.getItemStack(animationConfig1).build()
                : new ItemStack(Material.YELLOW_STAINED_GLASS_PANE);

        final ItemStack secondAnimationItem = (animationConfig2 != null)
                ? ItemStackBuilder.getItemStack(animationConfig2).build()
                : new ItemStack(Material.GRAY_STAINED_GLASS_PANE);

        class AnimationState {
            boolean alternate = false;
            int count = 0;
        }

        final AnimationState state = new AnimationState();
        final long betAmount = game.getAmount();
        final long totalPot = betAmount * 2;

        final Runnable[] task = new Runnable[1];
        task[0] = () -> {
            if (state.count++ >= ANIMATION_COUNT_THRESHOLD) {
                // Final state
                gui.setItem(13, winnerHead);
                gui.getFiller().fill(new GuiItem(Material.LIGHT_BLUE_STAINED_GLASS_PANE));
                gui.disableAllInteractions();
                gui.update();

                if (targetPlayer.isOnline()) {
                    targetPlayer.playSound(targetPlayer.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    scheduler.runTaskLaterAtEntity(targetPlayer, () -> {
                        if (targetPlayer.isOnline()) {
                            targetPlayer.closeInventory();
                        }
                    }, 20L);
                }

                long taxed = 0;

                long afterTax = betAmount;
                long payoutAmount = totalPot;

                if (taxEnabled) {
                    taxed = (long) ((taxRate * betAmount) / 100.0);
                    afterTax -= taxed;
                    payoutAmount -= taxed;
                }

                if (isWinnerThread) {
                    final long finalPayoutAmount = payoutAmount;
                    final long finalProfitAfterTax = afterTax;

                    scheduler.runTask(() -> {
                        economyManager.getEconomyProvider(game.getProvider()).deposit(winner, finalPayoutAmount);
                        Bukkit.getPluginManager().callEvent(new CoinflipCompletedEvent(winner, loser,
                            finalProfitAfterTax
                        ));
                    });

                    if (config.getBoolean("discord.webhook.enabled", false) || config.getBoolean("discord.bot.enabled", false))
                        plugin.getDiscordHook().executeWebhook(winner, loser, economyManager.getEconomyProvider(game.getProvider()).getDisplayName(),
                            afterTax
                        ).exceptionally(throwable -> {
                            plugin.getLogger().severe("An error occurred when triggering the webhook.");
                            throwable.printStackTrace();
                            return null;
                        });


                    // Update player stats
                    final StorageManager storageManager = plugin.getStorageManager();
                    updatePlayerStats(storageManager, winner, afterTax, betAmount, true);
                    updatePlayerStats(storageManager, loser, 0, betAmount, false);

                    // Send messages
                    final String winAmountFormatted = TextUtil.numberFormat(afterTax);
                    final String taxedFormatted = TextUtil.numberFormat(taxed);

                    if (winner.isOnline()) {
                        Messages.GAME_SUMMARY_WIN.send(winner.getPlayer(), replacePlaceholders(
                                String.valueOf(taxRate), taxedFormatted, winner.getName(), loser.getName(),
                                economyManager.getEconomyProvider(game.getProvider()).getDisplayName(), winAmountFormatted
                        ));
                    }

                    if (loser.isOnline()) {
                        Messages.GAME_SUMMARY_LOSS.send(loser.getPlayer(), replacePlaceholders(
                                String.valueOf(taxRate), taxedFormatted, winner.getName(), loser.getName(),
                                economyManager.getEconomyProvider(game.getProvider()).getDisplayName(), winAmountFormatted
                        ));
                    }

                    // Broadcast results
                    broadcastWinningMessage(
                        afterTax, taxed, winner.getName(), loser.getName(),
                            economyManager.getEconomyProvider(game.getProvider()).getDisplayName());
                }

                return;
            }

            // Animation swapping
            gui.setItem(13, state.alternate ? winnerHead : loserHead);

            final GuiItem filler = new GuiItem(state.alternate ? firstAnimationItem.clone() : secondAnimationItem.clone());

            for (int i = 0; i < gui.getInventory().getSize(); i++) {
                if (i != 13) gui.setItem(i, filler);
            }

            state.alternate = !state.alternate;

            if (targetPlayer.isOnline()) {
                targetPlayer.playSound(targetPlayer.getLocation(), Sound.BLOCK_WOODEN_BUTTON_CLICK_ON, 1f, 1f);
                if (targetPlayer.getOpenInventory().getTopInventory().equals(gui.getInventory())) {
                    gui.update();
                }
            }

            scheduler.runTaskLaterAtEntity(targetPlayer, task[0], 10L);
        };

        scheduler.runTaskAtLocation(regionLoc, task[0]);
    }

    private void updatePlayerStats(final StorageManager storageManager, final OfflinePlayer player, final long winAmount, final long beforeTax, final boolean isWinner) {
        final Optional<PlayerData> playerDataOptional = storageManager.getPlayer(player.getUniqueId());
        if (playerDataOptional.isPresent()) {
            final PlayerData playerData = playerDataOptional.get();
            if (isWinner) {
                playerData.updateWins();
                playerData.updateProfit(winAmount);
                playerData.updateGambled(beforeTax);
            } else {
                playerData.updateLosses();
                playerData.updateLosses(beforeTax);
                playerData.updateGambled(beforeTax);
            }
        } else {
            if (isWinner) {
                storageManager.updateOfflinePlayerWin(player.getUniqueId(), winAmount, beforeTax);
            } else {
                storageManager.updateOfflinePlayerLoss(player.getUniqueId(), beforeTax);
            }
        }
    }

    private void broadcastWinningMessage(final long winAmount, final long tax, final String winner, final String loser, final String currency) {
        if (winAmount >= minimumBroadcastWinnings) {
            for (final Player player : Bukkit.getServer().getOnlinePlayers()) {
                plugin.getStorageManager().getPlayer(player.getUniqueId()).ifPresent(playerData -> {
                    if (playerData.isDisplayBroadcastMessages()) {
                        Messages.COINFLIP_BROADCAST.send(player, replacePlaceholders(
                                String.valueOf(taxRate),
                                TextUtil.numberFormat(tax),
                                winner,
                                loser,
                                currency,
                                TextUtil.numberFormat(winAmount)
                        ));
                    }
                });
            }
        }
    }

    private Object[] replacePlaceholders(final String taxRate, final String taxDeduction, final String winner, final String loser, final String currency, final String winnings) {
        return new Object[]{"{TAX_RATE}", taxRate,
                "{TAX_DEDUCTION}", taxDeduction,
                "{WINNER}", winner,
                "{LOSER}", loser,
                "{CURRENCY}", currency,
                "{WINNINGS}", winnings};
    }
}