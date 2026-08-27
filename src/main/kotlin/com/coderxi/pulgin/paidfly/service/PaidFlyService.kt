package com.coderxi.pulgin.paidfly.service

import com.coderxi.pulgin.paidfly.PaidFlyPlugin.Companion.plugin
import com.coderxi.pulgin.paidfly.paytype.PayRule
import com.coderxi.pulgin.paidfly.paytype.PayType
import com.coderxi.pulgin.paidfly.utils.BaseService
import com.coderxi.pulgin.paidfly.utils.SoundUtils.parseSoundExpress
import com.coderxi.pulgin.paidfly.utils.isOnGroundByServerCheck
import com.coderxi.pulgin.paidfly.utils.sendLocalAction
import com.coderxi.pulgin.paidfly.utils.sendLocalMessage
import com.coderxi.pulgin.paidfly.utils.sendLocalTitle
import net.kyori.adventure.sound.Sound
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PaidFlyService : BaseService() {

    private const val BALANCE_EPSILON = 1.0E-9

    private val tasksByRule: ConcurrentHashMap<PayRule, PaidFlyTask> = ConcurrentHashMap()
    private val tasksByPlayer: ConcurrentHashMap<UUID, PaidFlyTask> = ConcurrentHashMap()
    private val billingStates: ConcurrentHashMap<UUID, BillingState> = ConcurrentHashMap()

    private class BillingState(
        val rule: PayRule,
        val payType: PayType
    ) {
        var pendingAmount: Double = 0.0
            private set

        fun accrue() {
            pendingAmount += rule.payCost
        }

        fun hasEnough(balance: Double): Boolean {
            return balance + BALANCE_EPSILON >= pendingAmount + rule.autoOffThreshold
        }
    }

    private class PaidFlyTask(val rule: PayRule, val players: MutableSet<UUID>) : BukkitRunnable() {
        override fun run() {
            val iterator = players.iterator()
            while (iterator.hasNext()) {
                val uuid = iterator.next()
                val player = Bukkit.getPlayer(uuid)
                if (player == null || !player.isOnline) {
                    iterator.remove()
                    tasksByPlayer.remove(uuid, this)
                    billingStates.remove(uuid)
                    clearCountdownState(uuid)
                    continue
                }
                if (!player.isFlying) continue
                if (player.isGliding) continue
                if (player.isOnGround) continue
                if (player.isOnGroundByServerCheck) continue

                val state = billingStates[uuid]
                if (state == null) {
                    iterator.remove()
                    tasksByPlayer.remove(uuid, this)
                    clearCountdownState(uuid)
                    continue
                }

                // Keep the existing interval semantics, but defer the economy operation
                // until the player stops flying or the plugin is disabled.
                state.accrue()
                if (!state.hasEnough(state.payType.getValue(player))) {
                    stopFlyByAuto(player)
                    iterator.remove()
                }
            }
            if (players.isEmpty()) {
                cancel()
                tasksByRule.remove(rule, this)
            }
        }
    }

    private var countdownTask: BukkitTask? = null
    private val lastRemainingSecondsByPlayer = HashMap<UUID, Long>()
    private val lastRemainingBalanceByPlayer = HashMap<UUID, Double>()

    private fun startCountdownTask() {
        if (countdownTask != null) return
        countdownTask = object : BukkitRunnable() {
            override fun run() {
                for (task in tasksByRule.values) {
                    val rule = task.rule
                    val players = synchronized(task.players) { task.players.toList() }
                    for (uuid in players) {
                        val player = Bukkit.getPlayer(uuid) ?: continue
                        if (!player.isFlying) continue

                        val state = billingStates[uuid] ?: continue
                        val balance = state.payType.getValue(player)
                        val availableBalance = (
                            balance - state.pendingAmount - rule.autoOffThreshold
                        ).coerceAtLeast(0.0)
                        val remainingSeconds = if (rule.payCost > 0.0) {
                            (availableBalance * rule.payInterval / rule.payCost / 20).toLong()
                        } else {
                            0L
                        }
                        val lastBalance = lastRemainingBalanceByPlayer[uuid] ?: 0.0
                        val lastRemainingSeconds = lastRemainingSecondsByPlayer[uuid]
                        val secondsToOff = when {
                            lastRemainingSeconds == null ||
                                lastRemainingSeconds <= 0 ||
                                balance > lastBalance ||
                                !isFlying(player) -> remainingSeconds
                            remainingSeconds < lastRemainingSeconds -> lastRemainingSeconds - 1
                            else -> continue
                        }

                        Notify.playAutoOffWarnCountdown(player, secondsToOff.toInt())
                        lastRemainingSecondsByPlayer[uuid] = secondsToOff
                        lastRemainingBalanceByPlayer[uuid] = balance
                    }
                }
                if (tasksByRule.isEmpty()) {
                    cancel()
                    countdownTask = null
                }
            }
        }.runTaskTimer(plugin, 20, 20)
    }

    private fun startFly(player: Player, notify: Boolean = true): Boolean {
        val rule = PayService.getPayRuleByPlayer(player)
        val payType = PayService.getPayTypeByName(rule.payType) ?: return false
        val task = tasksByRule.computeIfAbsent(rule) {
            PaidFlyTask(rule, Collections.synchronizedSet(mutableSetOf())).apply {
                runTaskTimer(plugin, rule.payInterval, rule.payInterval)
            }
        }

        player.allowFlight = true
        player.isFlying = true
        task.players.add(player.uniqueId)
        tasksByPlayer[player.uniqueId] = task
        billingStates[player.uniqueId] = BillingState(rule, payType)
        clearCountdownState(player.uniqueId)

        if (notify) {
            Notify.playSound(player, Notify.flyOnSound)
            Notify.send(player, "On", "pay_type" to rule.payTypeLocalName)
        }
        startCountdownTask()
        return true
    }

    private fun stopFly(
        player: Player,
        notify: Boolean = true,
        removeFromTask: Boolean = true,
        playSound: Boolean = true
    ): Boolean {
        val uuid = player.uniqueId
        val task = tasksByPlayer.remove(uuid) ?: return false
        val state = billingStates.remove(uuid)

        player.isFlying = false
        player.allowFlight = false

        if (removeFromTask) {
            task.players.remove(uuid)
            removeTaskIfEmpty(task)
        }

        settlePayment(player, state)
        clearCountdownState(uuid)

        if (playSound && Notify.soundEnable) {
            Notify.playSound(player, Notify.flyOffSound)
        }
        if (notify) {
            Notify.send(player, "Off")
        }
        return true
    }

    private fun removeTaskIfEmpty(task: PaidFlyTask) {
        if (task.players.isEmpty()) {
            task.cancel()
            tasksByRule.remove(task.rule, task)
        }
    }

    private fun settlePayment(player: Player, state: BillingState?) {
        if (state == null || state.pendingAmount <= BALANCE_EPSILON) return

        val amount = state.pendingAmount
        if (!state.payType.pay(player, amount)) {
            plugin.logger.warning(
                "Failed to settle deferred ${state.rule.payType} payment of $amount " +
                    "for player ${player.name}."
            )
        }
    }

    private fun clearCountdownState(uuid: UUID) {
        lastRemainingSecondsByPlayer.remove(uuid)
        lastRemainingBalanceByPlayer.remove(uuid)
    }

    private fun stopAllFly(notify: Boolean = false) {
        val playerUuids = tasksByPlayer.keys.toList()
        playerUuids.mapNotNull { Bukkit.getPlayer(it) }.forEach { player ->
            stopFly(player, notify = notify, playSound = notify)
        }

        tasksByRule.values.forEach { it.cancel() }
        tasksByRule.clear()
        tasksByPlayer.clear()

        billingStates.keys.toList().forEach { uuid ->
            val state = billingStates.remove(uuid)
            if (state != null && state.pendingAmount > BALANCE_EPSILON) {
                plugin.logger.warning(
                    "Could not settle deferred ${state.rule.payType} payment of " +
                        "${state.pendingAmount} for offline player $uuid."
                )
            }
            clearCountdownState(uuid)
        }
        billingStates.clear()

        countdownTask?.cancel()
        countdownTask = null
        lastRemainingSecondsByPlayer.clear()
        lastRemainingBalanceByPlayer.clear()
    }

    fun shutdown() {
        stopAllFly()
    }

    fun isFlying(player: Player): Boolean {
        return tasksByPlayer.containsKey(player.uniqueId)
    }

    fun getRemainingFlySeconds(player: Player): Long {
        val state = billingStates[player.uniqueId]
        val rule = state?.rule ?: PayService.getPayRuleByPlayer(player)
        val payType = state?.payType ?: PayService.getPayTypeByName(rule.payType) ?: return 0
        if (rule.payCost <= 0.0) return 0

        val balance = payType.getValue(player)
        val availableBalance = (balance - (state?.pendingAmount ?: 0.0)).coerceAtLeast(0.0)
        return ((availableBalance / rule.payCost) * (rule.payInterval / 20.0)).toLong()
    }

    private class Listener : org.bukkit.event.Listener {
        @EventHandler
        fun onPlayerQuit(event: PlayerQuitEvent) {
            stopFly(event.player)
        }

        @EventHandler
        fun onPluginDisable(event: PluginDisableEvent) {
            if (event.plugin === plugin) {
                shutdown()
            }
        }
    }

    override fun init() {
        Notify.reload()
        plugin.server.pluginManager.registerEvents(Listener(), plugin)
    }

    override fun reload() {
        stopAllFly()
        Notify.reload()
    }

    fun startFlySelf(player: Player) {
        if (isFlying(player)) return player.sendLocalMessage("System.SelfAlreadyFlying")
        if (!PayService.testEnough(player)) {
            return Notify.send(
                player,
                "NotEnough",
                "pay_type" to PayService.getPayRuleByPlayer(player).payTypeLocalName
            )
        }
        startFly(player)
    }

    fun stopFlySelf(player: Player) {
        if (!isFlying(player)) return player.sendLocalMessage("System.SelfAlreadyOffFlying")
        stopFly(player)
    }

    fun startFlyOther(sender: CommandSender, player: Player) {
        if (isFlying(player)) return sender.sendLocalMessage("System.TargetAlreadyFlying")
        if (!PayService.testEnough(player)) {
            return sender.sendLocalMessage(
                "System.TargetNotEnough",
                "pay_type" to PayService.getPayRuleByPlayer(player).payTypeLocalName
            )
        }
        if (startFly(player)) sender.sendLocalMessage("System.TargetOn", "player" to player.name)
    }

    fun stopFlyOther(sender: CommandSender, player: Player) {
        if (!isFlying(player)) return player.sendLocalMessage("System.TargetAlreadyOffFlying")
        if (stopFly(player)) sender.sendLocalMessage("System.TargetOff", "player" to player.name)
    }

    fun stopFlyByAuto(player: Player) {
        if (stopFly(player, notify = false, removeFromTask = false)) {
            Notify.send(
                player,
                "AutoOff",
                "pay_type" to PayService.getPayRuleByPlayer(player).payTypeLocalName
            )
        }
    }

    private object Notify {
        var chatEnable = false
        var titleEnable = false
        var actionEnable = false
        var soundEnable = false
        lateinit var chatCountdownSeconds: List<Int>
        lateinit var titleCountdownSeconds: List<Int>
        lateinit var actionCountdownSeconds: List<Int>
        lateinit var flyOnSound: Sound
        lateinit var flyOffSound: Sound
        lateinit var soundCountdownSounds: Map<Int, Sound>
        var maxCountdownSeconds: Int = 0

        fun reload() {
            val config = plugin.config
            chatEnable = config.getBoolean("Notify.Chat.Enable")
            titleEnable = config.getBoolean("Notify.Title.Enable")
            actionEnable = config.getBoolean("Notify.Action.Enable")
            soundEnable = config.getBoolean("Notify.Sound.Enable")
            chatCountdownSeconds = config.getIntegerList("Notify.Chat.AutoOffWarnCountdown")
            titleCountdownSeconds = config.getIntegerList("Notify.Title.AutoOffWarnCountdown")
            actionCountdownSeconds = config.getIntegerList("Notify.Action.AutoOffWarnCountdown")
            config.getString("Notify.Sound.On")?.let { flyOnSound = parseSoundExpress(it) }
            config.getString("Notify.Sound.Off")?.let { flyOffSound = parseSoundExpress(it) }
            soundCountdownSounds = config.getStringList("Notify.Sound.AutoOffWarnCountdown")
                .mapNotNull { line ->
                    val split = line.split(";")
                    if (split.size != 2) return@mapNotNull null
                    val seconds = split[0].toIntOrNull() ?: return@mapNotNull null
                    val sound = parseSoundExpress(split[1])
                    seconds to sound
                }
                .toMap()
            maxCountdownSeconds = (
                chatCountdownSeconds +
                    titleCountdownSeconds +
                    actionCountdownSeconds +
                    soundCountdownSounds.keys.toList()
            ).maxOrNull() ?: 0
        }

        fun send(player: Player, subPath: String, vararg args: Pair<String, String>) {
            if (chatEnable) player.sendLocalMessage("Notify.Chat.$subPath", *args)
            if (titleEnable) player.sendLocalTitle("Notify.Title.$subPath", *args)
            if (actionEnable) player.sendLocalAction("Notify.Action.$subPath", *args)
        }

        fun playSound(player: Player, sound: Sound) {
            if (soundEnable) player.playSound(sound)
        }

        fun playAutoOffWarnCountdown(player: Player, seconds: Int) {
            val payType = PayService.getPayRuleByPlayer(player).payTypeLocalName
            if (chatEnable && chatCountdownSeconds.contains(seconds)) {
                player.sendLocalMessage(
                    "Notify.Chat.AutoOffWarn",
                    "pay_type" to payType,
                    "seconds" to seconds.toString()
                )
            }
            if (titleEnable && titleCountdownSeconds.contains(seconds)) {
                player.sendLocalTitle(
                    "Notify.Title.AutoOffWarn",
                    "pay_type" to payType,
                    "seconds" to seconds.toString()
                )
            }
            if (actionEnable && actionCountdownSeconds.contains(seconds)) {
                player.sendLocalAction(
                    "Notify.Action.AutoOffWarn",
                    "pay_type" to payType,
                    "seconds" to seconds.toString()
                )
            }
            if (soundEnable && soundCountdownSounds.containsKey(seconds)) {
                player.playSound(soundCountdownSounds[seconds]!!)
            }
        }
    }
}
