package me.mrcici.simplevotekick;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class SimpleVoteKick extends JavaPlugin implements Listener {

    private final Map<UUID, Long> lastActivity = new HashMap<>();

    private final Set<UUID> activeVoteTargets = new HashSet<>();
    private final Map<UUID, Set<UUID>> yesVotes = new HashMap<>();
    private final Map<UUID, Set<UUID>> noVotes = new HashMap<>();
    private final Map<UUID, UUID> voteStarter = new HashMap<>();      // target -> starter
    private final Map<UUID, Integer> voteTaskId = new HashMap<>();    // target -> repeating task id
    private final Map<UUID, Long> voteEndAtMs = new HashMap<>();      // target -> end timestamp
    private final Map<UUID, BossBar> voteBossBar = new HashMap<>();   // target -> bossbar

    private final Map<UUID, Long> voterCooldownUntil = new HashMap<>();

    private long AFK_TIME_MS;
    private long COOLDOWN_MS;
    private int VOTE_DURATION_SECONDS;
    private boolean ADMIN_BYPASS;
    private String BYPASS_PERMISSION;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        Bukkit.getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("votekick")).setExecutor((sender, cmd, label, args) -> {
            if (!(sender instanceof Player)) return true;
            Player voter = (Player) sender;

            if (args.length == 1) {
                Player target = Bukkit.getPlayerExact(args[0]);
                if (!basicTargetChecks(voter, target)) return true;

                startVote(voter, target);
                return true;
            }

            if (args.length == 2) {
                String choice = args[0].toLowerCase(Locale.ROOT);
                Player target = Bukkit.getPlayerExact(args[1]);

                if (!choice.equals("yes") && !choice.equals("no")) {
                    voter.sendMessage("§cUse: /votekick <player> | /votekick yes <player> | /votekick no <player>");
                    return true;
                }

                if (target == null) {
                    voter.sendMessage("§cThat player is not online.");
                    return true;
                }

                UUID t = target.getUniqueId();
                if (!activeVoteTargets.contains(t)) {
                    voter.sendMessage("§cThere is no active vote for that player.");
                    return true;
                }

                if (voter.equals(target)) {
                    voter.sendMessage("§cYou cannot vote on yourself.");
                    return true;
                }

                if (ADMIN_BYPASS && target.hasPermission(BYPASS_PERMISSION)) {
                    voter.sendMessage("§cThis player cannot be vote-kicked.");
                    return true;
                }

                UUID starterId = voteStarter.get(t);
                if (starterId != null && starterId.equals(voter.getUniqueId())) {
                    voter.sendMessage("§eYou started the vote, so you already voted YES.");
                    return true;
                }

                long now = System.currentTimeMillis();
                long until = voterCooldownUntil.getOrDefault(voter.getUniqueId(), 0L);
                if (until > now) {
                    voter.sendMessage("§cYou must wait before voting again.");
                    return true;
                }

                yesVotes.putIfAbsent(t, new HashSet<>());
                noVotes.putIfAbsent(t, new HashSet<>());

                // Switch vote if needed
                if (choice.equals("yes")) {
                    noVotes.get(t).remove(voter.getUniqueId());
                    boolean added = yesVotes.get(t).add(voter.getUniqueId());
                    if (!added) voter.sendMessage("§cYou already voted YES.");
                } else {
                    yesVotes.get(t).remove(voter.getUniqueId());
                    boolean added = noVotes.get(t).add(voter.getUniqueId());
                    if (!added) voter.sendMessage("§cYou already voted NO.");
                }

                voterCooldownUntil.put(voter.getUniqueId(), now + COOLDOWN_MS);

                // Update UI immediately after a vote
                updateVoteUI(t);

                return true;
            }

            voter.sendMessage("§cUse: /votekick <player> | /votekick yes <player> | /votekick no <player>");
            return true;
        });
    }

    private boolean basicTargetChecks(Player voter, Player target) {
        if (target == null) {
            voter.sendMessage("§cThat player is not online.");
            return false;
        }
        if (target.equals(voter)) {
            voter.sendMessage("§cYou cannot vote-kick yourself.");
            return false;
        }
        if (ADMIN_BYPASS && target.hasPermission(BYPASS_PERMISSION)) {
            voter.sendMessage("§cThis player cannot be vote-kicked.");
            return false;
        }
        return true;
    }

    private void startVote(Player starter, Player target) {
        int online = Bukkit.getOnlinePlayers().size();

        if (online <= 3 && !isAfk(target)) {
            starter.sendMessage("§cThe player must be AFK for " + prettyTime(AFK_TIME_MS) + ".");
            return;
        }

        UUID t = target.getUniqueId();

        cancelVoteInternal(t, false, null);

        activeVoteTargets.add(t);
        yesVotes.put(t, new HashSet<>());
        noVotes.put(t, new HashSet<>());
        voteStarter.put(t, starter.getUniqueId());

        yesVotes.get(t).add(starter.getUniqueId());

	BossBar bar = Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID);
        voteBossBar.put(t, bar);

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(target)) {
                bar.addPlayer(p);
            }
        }

        Component msg = Component.text(starter.getName() + " has started a vote to kick " + target.getName() + ". ")
                .append(Component.text("Click "))
                .append(Component.text("[YES]")
                        .color(NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/votekick yes " + target.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text("Vote YES"))))
                .append(Component.text(" or "))
                .append(Component.text("[NO]")
                        .color(NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand("/votekick no " + target.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text("Vote NO"))))
                .append(Component.text(" to vote."));

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.equals(target)) {
                p.sendMessage(msg);
            }
        }

        long endAt = System.currentTimeMillis() + (long) VOTE_DURATION_SECONDS * 1000L;
        voteEndAtMs.put(t, endAt);

        int taskId = Bukkit.getScheduler().runTaskTimer(this, () -> tickVote(t), 0L, 20L).getTaskId();
        voteTaskId.put(t, taskId);

        updateVoteUI(t);
    }

    private void tickVote(UUID targetId) {
        if (!activeVoteTargets.contains(targetId)) return;

        long now = System.currentTimeMillis();
        long endAt = voteEndAtMs.getOrDefault(targetId, now);
        long remainingMs = endAt - now;

        if (remainingMs <= 0) {
            finishVote(targetId);
            return;
        }

        updateVoteUI(targetId);
    }

    private void updateVoteUI(UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        BossBar bar = voteBossBar.get(targetId);
        if (bar == null) return;

        int yes = yesVotes.getOrDefault(targetId, Collections.emptySet()).size();
        int no = noVotes.getOrDefault(targetId, Collections.emptySet()).size();

        long now = System.currentTimeMillis();
        long endAt = voteEndAtMs.getOrDefault(targetId, now);
        long remainingMs = Math.max(0, endAt - now);
        int remainingSec = (int) Math.ceil(remainingMs / 1000.0);

        double progress = Math.min(1.0, Math.max(0.0, remainingMs / (VOTE_DURATION_SECONDS * 1000.0)));

        String targetName = (target != null) ? target.getName() : "player";

        // Boss bar title + progress
        bar.setTitle("VoteKick: " + targetName + " — " + remainingSec + "s left");
        bar.setProgress(progress);

        // Action bar counts (sent to bossbar viewers)
        Component action = Component.text("YES: " + yes + "  |  NO: " + no, NamedTextColor.YELLOW);

        for (Player p : bar.getPlayers()) {
            // Paper supports this method for action bar via Adventure
            p.sendActionBar(action);
        }
    }

    private void finishVote(UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        int yes = yesVotes.getOrDefault(targetId, Collections.emptySet()).size();
        int no = noVotes.getOrDefault(targetId, Collections.emptySet()).size();

        // Clean up UI first
        cancelVoteInternal(targetId, false, null);

        if (target == null) return; // if they left, just end silently

        if (yes > no) {
            Bukkit.broadcastMessage("§eVote ended: §aYES " + yes + " §7> §cNO " + no + "§e. Kicking " + target.getName() + ".");
            target.kickPlayer("You were vote-kicked.");
        } else if (no > yes) {
            Bukkit.broadcastMessage("§eVote ended: §cNO " + no + " §7> §aYES " + yes + "§e. " + target.getName() + " stays.");
        } else {
            Bukkit.broadcastMessage("§eVote ended: §cNO " + no + " §7= §aYES " + yes + "§e. It's a tie. " + target.getName() + " stays.");
        }
    }

    private void cancelVoteInternal(UUID targetId, boolean announce, String announceMsg) {
        // Cancel scheduler
        Integer task = voteTaskId.remove(targetId);
        if (task != null) Bukkit.getScheduler().cancelTask(task);

        voteEndAtMs.remove(targetId);

        BossBar bar = voteBossBar.remove(targetId);
        if (bar != null) {
            for (Player p : new ArrayList<>(bar.getPlayers())) {
                bar.removePlayer(p);
                // Clear action bar after removing (optional)
                p.sendActionBar(Component.empty());
            }
        }

        // Clear vote state
        activeVoteTargets.remove(targetId);
        yesVotes.remove(targetId);
        noVotes.remove(targetId);
        voteStarter.remove(targetId);

        if (announce && announceMsg != null) {
            Bukkit.broadcastMessage(announceMsg);
        }
    }

    private void loadConfigValues() {
        long afkMinutes = getConfig().getLong("afk-time.minutes", 10);
        long afkSeconds = getConfig().getLong("afk-time.seconds", 0);

        long cooldownMinutes = getConfig().getLong("cooldown.minutes", 0);
        long cooldownSeconds = getConfig().getLong("cooldown.seconds", 30);

        VOTE_DURATION_SECONDS = Math.max(5, getConfig().getInt("vote-duration-seconds", 60));

        AFK_TIME_MS = Math.max(0, (afkMinutes * 60 + afkSeconds) * 1000);
        COOLDOWN_MS = Math.max(0, (cooldownMinutes * 60 + cooldownSeconds) * 1000);

        ADMIN_BYPASS = getConfig().getBoolean("admin-bypass", true);
        BYPASS_PERMISSION = getConfig().getString("admin-permission", "votekick.bypass");
    }

    private boolean isAfk(Player p) {
        long last = lastActivity.getOrDefault(p.getUniqueId(), System.currentTimeMillis());
        return System.currentTimeMillis() - last >= AFK_TIME_MS;
    }

    private void active(Player p) {
        lastActivity.put(p.getUniqueId(), System.currentTimeMillis());

        // Your rule: only cancel vote if the TARGET becomes active when there are 2 players online
        if (Bukkit.getOnlinePlayers().size() == 2 && activeVoteTargets.contains(p.getUniqueId())) {
            UUID t = p.getUniqueId();
            cancelVoteInternal(t, true, "§eVoteKick canceled because " + p.getName() + " is no longer AFK.");
        }
    }

    private String prettyTime(long ms) {
        long totalSeconds = Math.max(0, ms / 1000);
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes == 0) return seconds + "s";
        if (seconds == 0) return minutes + "m";
        return minutes + "m " + seconds + "s";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        active(e.getPlayer());

        // If a vote is currently running, show the bossbar to the joiner (unless they're the target)
        Player joiner = e.getPlayer();
        for (UUID targetId : new HashSet<>(activeVoteTargets)) {
            Player target = Bukkit.getPlayer(targetId);
            BossBar bar = voteBossBar.get(targetId);
            if (bar != null && (target == null || !joiner.equals(target))) {
                bar.addPlayer(joiner);

                // Optional: send the clickable message again to the joiner so they can vote
                if (target != null) {
                    Component msg = Component.text("A VoteKick is in progress for " + target.getName() + ". ")
                            .append(Component.text("Click "))
                            .append(Component.text("[YES]")
                                    .color(NamedTextColor.GREEN)
                                    .clickEvent(ClickEvent.runCommand("/votekick yes " + target.getName()))
                                    .hoverEvent(HoverEvent.showText(Component.text("Vote YES"))))
                            .append(Component.text(" or "))
                            .append(Component.text("[NO]")
                                    .color(NamedTextColor.RED)
                                    .clickEvent(ClickEvent.runCommand("/votekick no " + target.getName()))
                                    .hoverEvent(HoverEvent.showText(Component.text("Vote NO"))))
                            .append(Component.text(" to vote."));
                    joiner.sendMessage(msg);
                }
            }
        }
    }

    @EventHandler public void onMove(PlayerMoveEvent e) { active(e.getPlayer()); }
    @EventHandler public void onChat(AsyncPlayerChatEvent e) { active(e.getPlayer()); }
    @EventHandler public void onCmd(PlayerCommandPreprocessEvent e) { active(e.getPlayer()); }
}
