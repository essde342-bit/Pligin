package ru.essde.pligin;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PliginPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final DateTimeFormatter REPORT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private String prefix;
    private ExecutorService worker;
    private volatile TestSession activeTest;
    private long nextTestAllowedAt;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        prefix = color(getConfig().getString("messages.prefix", "&8[&bPligin&8] &7"));
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Pligin-Test-Worker");
            thread.setDaemon(true);
            return thread;
        });

        if (getCommand("hosttest") == null) {
            getLogger().severe("Command 'hosttest' is missing from plugin.yml. Plugin will be disabled.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        getCommand("hosttest").setExecutor(this);
        getCommand("hosttest").setTabCompleter(this);

        getLogger().info("Pligin enabled. Paper server diagnostics are ready.");
    }

    @Override
    public void onDisable() {
        finishActiveTest(null, true);
        if (worker != null) {
            worker.shutdownNow();
        }
        getLogger().info("Pligin disabled. Active test tasks were stopped and temporary entities were removed.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pligin.admin")) {
            send(sender, "У тебя нет прав на эту команду.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            showHelp(sender, label);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> showStatus(sender);
            case "players" -> showPlayers(sender);
            case "worlds" -> showWorlds(sender);
            case "ping" -> showPing(sender, args);
            case "report" -> createReport(sender);
            case "benchmark", "bench" -> startBenchmark(sender, args);
            case "stress" -> handleStress(sender, args);
            case "stop" -> stopTest(sender);
            default -> showHelp(sender, label);
        }
        return true;
    }

    private void showHelp(CommandSender sender, String label) {
        send(sender, "Команды тестирования сервера:");
        send(sender, "/" + label + " status - общая информация о сервере и TPS/MSPT");
        send(sender, "/" + label + " players - игроки онлайн и их пинг");
        send(sender, "/" + label + " worlds - информация по мирам и чанкам");
        send(sender, "/" + label + " ping [ник] - пинг игрока или свой");
        send(sender, "/" + label + " benchmark [секунды] - замер времени тиков (до 120 сек.)");
        send(sender, "/" + label + " report - сохранить TXT-отчёт в plugins/Pligin/reports");
        send(sender, "/" + label + " stress entities <количество> - временные ArmorStand-сущности");
        send(sender, "/" + label + " stress particles <количество> - тест частиц (за один импульс)");
        send(sender, "/" + label + " stress memory <МБ> - временная загрузка памяти");
        send(sender, "/" + label + " stress cpu <секунды> - тест одного CPU-потока");
        send(sender, "/" + label + " stop - остановить текущий тест и очистить его объекты");
        send(sender, "Все нагрузочные тесты ограничены настройками config.yml и запускаются по одному.");
    }

    private void showStatus(CommandSender sender) {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        double[] tps = Bukkit.getServer().getTPS();

        send(sender, "Диагностика сервера:");
        send(sender, "Ядро: " + Bukkit.getName() + " | " + Bukkit.getBukkitVersion());
        send(sender, "Версия: " + Bukkit.getVersion());
        send(sender, "Java: " + System.getProperty("java.version") + " | ОС: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        send(sender, "CPU-потоков: " + runtime.availableProcessors() + " | Онлайн: " + Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
        send(sender, "RAM JVM: " + formatMb(used) + " / " + formatMb(max) + " МБ занято / максимум");
        send(sender, String.format(Locale.US, "TPS: %.2f / %.2f / %.2f (1m / 5m / 15m)", safeTps(tps, 0), safeTps(tps, 1), safeTps(tps, 2)));
        send(sender, String.format(Locale.US, "Средний MSPT Paper: %.2f мс", Bukkit.getServer().getAverageTickTime()));
        send(sender, "Загружено миров: " + Bukkit.getWorlds().size() + " | Текущий тест: " + (activeTest == null ? "нет" : activeTest.name));
    }

    private void showPlayers(CommandSender sender) {
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            send(sender, "Сейчас на сервере нет игроков.");
            return;
        }
        send(sender, "Игроки онлайн (" + Bukkit.getOnlinePlayers().size() + "):");
        Bukkit.getOnlinePlayers().stream()
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(player -> send(sender, player.getName() + " | ping: " + player.getPing() + " мс | мир: " + player.getWorld().getName()));
    }

    private void showWorlds(CommandSender sender) {
        send(sender, "Миры сервера:");
        for (World world : Bukkit.getWorlds()) {
            int entities = world.getEntities().size();
            int chunks = world.getLoadedChunks().length;
            send(sender, world.getName() + " | игроков: " + world.getPlayers().size()
                    + " | загружено чанков: " + chunks + " | сущностей: " + entities
                    + " | время: " + world.getTime());
        }
    }

    private void showPing(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                send(sender, "Игрок не найден или не в сети: " + args[1]);
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            send(sender, "Из консоли укажи игрока: /hosttest ping <ник>");
            return;
        }
        send(sender, "Пинг " + target.getName() + ": " + target.getPing() + " мс.");
    }

    private void startBenchmark(CommandSender sender, String[] args) {
        int seconds = 30;
        if (args.length >= 2) {
            Integer parsed = parseInt(args[1]);
            if (parsed == null) {
                send(sender, "Укажи длительность числом секунд.");
                return;
            }
            seconds = parsed;
        }
        int maxSeconds = getConfig().getInt("safety.max-benchmark-seconds", 120);
        if (seconds < 5 || seconds > maxSeconds) {
            send(sender, "Длительность должна быть от 5 до " + maxSeconds + " секунд.");
            return;
        }

        TestSession session = beginTest(sender, "бенчмарк");
        if (session == null) return;

        send(sender, "Бенчмарк запущен на " + seconds + " сек. Замеряются интервалы между серверными тиками.");
        List<Double> tickTimesMs = new ArrayList<>();
        long[] lastTick = {System.nanoTime()};

        BukkitTask sampler = Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.nanoTime();
            double elapsedMs = (now - lastTick[0]) / 1_000_000.0;
            lastTick[0] = now;
            if (elapsedMs > 0.0 && elapsedMs < 60_000.0) {
                tickTimesMs.add(elapsedMs);
            }
        }, 1L, 1L);
        session.tasks.add(sampler);
        BukkitTask finish = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (activeTest != session) return;
            Collections.sort(tickTimesMs);
            if (tickTimesMs.isEmpty()) {
                finishActiveTest(null, false);
                send(sender, "Бенчмарк завершён, но не удалось собрать замеры.");
                return;
            }
            double average = tickTimesMs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double p50 = percentile(tickTimesMs, 0.50);
            double p95 = percentile(tickTimesMs, 0.95);
            double max = tickTimesMs.get(tickTimesMs.size() - 1);
            send(sender, String.format(Locale.US,
                    "Бенчмарк готов: замеров %d | среднее %.2f мс | P50 %.2f мс | P95 %.2f мс | максимум %.2f мс",
                    tickTimesMs.size(), average, p50, p95, max));
            send(sender, "Ориентир для 20 TPS — 50 мс на тик. P95 показывает, насколько тяжёлыми были медленные тики.");
            finishActiveTest(null, false);
        }, seconds * 20L);
        session.tasks.add(finish);
    }

    private void handleStress(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "Использование: /hosttest stress <entities|particles|memory|cpu> <значение>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "entities" -> startEntityTest(sender, args[2]);
            case "particles" -> startParticleTest(sender, args[2]);
            case "memory" -> startMemoryTest(sender, args[2]);
            case "cpu" -> startCpuTest(sender, args[2]);
            default -> send(sender, "Неизвестный тест. Доступно: entities, particles, memory, cpu.");
        }
    }

    private void startEntityTest(CommandSender sender, String rawAmount) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        Integer amount = parseInt(rawAmount);
        int max = getConfig().getInt("safety.max-entities", 300);
        if (amount == null || amount < 1 || amount > max) {
            send(sender, "Количество должно быть от 1 до " + max + ".");
            return;
        }
        TestSession session = beginTest(sender, "тест сущностей");
        if (session == null) return;

        World world = player.getWorld();
        Location origin = player.getLocation().clone().add(0, 2.0, 0);
        int duration = Math.max(5, getConfig().getInt("safety.entity-test-duration-seconds", 20));
        int[] spawned = {0};
        send(sender, "Тест сущностей запущен: " + amount + ". Объекты будут автоматически удалены через " + duration + " сек.");

        BukkitTask[] spawner = new BukkitTask[1];
        spawner[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (activeTest != session) return;
            int batch = Math.min(10, amount - spawned[0]);
            for (int i = 0; i < batch; i++) {
                double angle = (spawned[0] * 2.399963229728653);
                double radius = 1.5 + (spawned[0] % 8) * 0.18;
                Location location = origin.clone().add(Math.cos(angle) * radius, (spawned[0] % 3) * 0.15, Math.sin(angle) * radius);
                try {
                    Entity entity = world.spawnEntity(location, EntityType.ARMOR_STAND);
                    if (entity instanceof ArmorStand stand) {
                        stand.setVisible(false);
                        stand.setMarker(true);
                        stand.setGravity(false);
                        stand.setInvulnerable(true);
                        stand.setSilent(true);
                        stand.setBasePlate(false);
                        stand.setArms(false);
                        stand.setPersistent(false);
                        session.entities.add(stand);
                    } else {
                        entity.remove();
                    }
                } catch (RuntimeException exception) {
                    getLogger().warning("Could not spawn test entity: " + exception.getMessage());
                }
                spawned[0]++;
            }
            if (spawned[0] >= amount) {
                spawner[0].cancel();
                send(sender, "Создано тестовых сущностей: " + session.entities.size() + ". Ожидай завершения теста.");
            }
        }, 1L, 1L);
        session.tasks.add(spawner[0]);
        scheduleFinish(session, sender, duration, "Тест сущностей завершён. Временные сущности удалены.");
    }

    private void startParticleTest(CommandSender sender, String rawAmount) {
        Player player = requirePlayer(sender);
        if (player == null) return;
        Integer amount = parseInt(rawAmount);
        int max = getConfig().getInt("safety.max-particles-per-pulse", 1000);
        if (amount == null || amount < 1 || amount > max) {
            send(sender, "Количество частиц за импульс должно быть от 1 до " + max + ".");
            return;
        }
        TestSession session = beginTest(sender, "тест частиц");
        if (session == null) return;

        int duration = Math.max(5, getConfig().getInt("safety.particle-test-duration-seconds", 10));
        Location center = player.getLocation().clone().add(0, 1.0, 0);
        World world = player.getWorld();
        send(sender, "Тест частиц запущен: " + amount + " частиц раз в секунду, длительность " + duration + " сек.");
        BukkitTask particles = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (activeTest != session) return;
            world.spawnParticle(Particle.CLOUD, center, amount, 2.0, 1.0, 2.0, 0.01);
        }, 0L, 20L);
        session.tasks.add(particles);
        scheduleFinish(session, sender, duration, "Тест частиц завершён.");
    }

    private void startMemoryTest(CommandSender sender, String rawMb) {
        Integer mb = parseInt(rawMb);
        int max = getConfig().getInt("safety.max-memory-mb", 32);
        if (mb == null || mb < 1 || mb > max) {
            send(sender, "Размер теста должен быть от 1 до " + max + " МБ.");
            return;
        }
        TestSession session = beginTest(sender, "тест памяти");
        if (session == null) return;

        int duration = Math.max(5, getConfig().getInt("safety.memory-test-duration-seconds", 15));
        AtomicReference<byte[]> allocation = new AtomicReference<>();
        send(sender, "Тест памяти запущен: выделение " + mb + " МБ на " + duration + " сек. После теста память будет освобождена.");
        session.future = worker.submit(() -> {
            try {
                byte[] bytes = new byte[mb * 1024 * 1024];
                for (int i = 0; i < bytes.length; i += 4096) bytes[i] = 1;
                if (!session.cancelled.get() && !Thread.currentThread().isInterrupted()) {
                    allocation.set(bytes);
                }
            } catch (OutOfMemoryError error) {
                if (isEnabled()) {
                    Bukkit.getScheduler().runTask(this, () -> {
                        if (activeTest == session) {
                            send(sender, "Не удалось выделить память. Тест остановлен; проверь свободную RAM и Xmx.");
                            finishActiveTest(null, false);
                        }
                    });
                }
            }
        });
        session.cleanup = () -> {
            session.cancelled.set(true);
            allocation.set(null);
        };
        scheduleFinish(session, sender, duration, "Тест памяти завершён. Временный массив освобождён.");
    }

    private void startCpuTest(CommandSender sender, String rawSeconds) {
        Integer seconds = parseInt(rawSeconds);
        int max = getConfig().getInt("safety.max-cpu-seconds", 5);
        if (seconds == null || seconds < 1 || seconds > max) {
            send(sender, "Длительность CPU-теста должна быть от 1 до " + max + " секунд.");
            return;
        }
        TestSession session = beginTest(sender, "тест CPU");
        if (session == null) return;

        AtomicBoolean cancelled = session.cancelled;
        session.cleanup = () -> cancelled.set(true);
        send(sender, "CPU-тест запущен в одном фоновом потоке на " + seconds + " сек. Основной поток сервера не блокируется специально.");
        session.future = worker.submit(() -> {
            long endAt = System.nanoTime() + seconds * 1_000_000_000L;
            double value = 0.25;
            while (!cancelled.get() && !Thread.currentThread().isInterrupted() && System.nanoTime() < endAt) {
                value = Math.sin(value + 0.0001) + Math.sqrt(Math.abs(value) + 1.0);
            }
            double result = value;
        });
        scheduleFinish(session, sender, seconds, "CPU-тест завершён.");
    }

    private TestSession beginTest(CommandSender sender, String name) {
        if (activeTest != null) {
            send(sender, "Уже выполняется " + activeTest.name + ". Сначала дождись завершения или введи /hosttest stop.");
            return null;
        }
        long now = System.currentTimeMillis();
        if (now < nextTestAllowedAt) {
            long remaining = Math.max(1, (nextTestAllowedAt - now + 999) / 1000);
            send(sender, "Перед следующим нагрузочным тестом подожди " + remaining + " сек.");
            return null;
        }
        activeTest = new TestSession(name);
        return activeTest;
    }

    private void scheduleFinish(TestSession session, CommandSender sender, int seconds, String message) {
        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (activeTest != session) return;
            finishActiveTest(null, false);
            send(sender, message);
        }, seconds * 20L);
        session.tasks.add(task);
    }

    private void stopTest(CommandSender sender) {
        if (activeTest == null) {
            send(sender, "Сейчас нет активного теста.");
            return;
        }
        String name = activeTest.name;
        finishActiveTest(null, false);
        send(sender, "Тест " + name + " остановлен. Временные объекты очищены.");
    }

    private void finishActiveTest(CommandSender sender, boolean silent) {
        TestSession session = activeTest;
        if (session == null) return;
        activeTest = null;
        session.cancelled.set(true);
        for (BukkitTask task : session.tasks) {
            if (task != null) task.cancel();
        }
        if (session.future != null) session.future.cancel(true);
        for (Entity entity : session.entities) {
            if (entity != null && entity.isValid()) entity.remove();
        }
        if (session.cleanup != null) {
            try {
                session.cleanup.run();
            } catch (RuntimeException exception) {
                getLogger().warning("Test cleanup failed: " + exception.getMessage());
            }
        }
        if (!silent) {
            int cooldown = Math.max(0, getConfig().getInt("safety.cooldown-seconds", 15));
            nextTestAllowedAt = System.currentTimeMillis() + cooldown * 1000L;
        }
    }

    private void createReport(CommandSender sender) {
        String report = buildReport();
        send(sender, "Формирую отчёт...");
        Path reportDirectory = getDataFolder().toPath().resolve("reports");
        String filename = "report-" + LocalDateTime.now().format(REPORT_DATE) + ".txt";
        Path target = reportDirectory.resolve(filename);
        worker.submit(() -> {
            try {
                Files.createDirectories(reportDirectory);
                Files.writeString(target, report, StandardCharsets.UTF_8);
                if (isEnabled()) {
                    Bukkit.getScheduler().runTask(this, () -> send(sender, "Отчёт сохранён: plugins/Pligin/reports/" + filename));
                }
            } catch (IOException exception) {
                getLogger().severe("Could not save report: " + exception.getMessage());
                if (isEnabled()) {
                    Bukkit.getScheduler().runTask(this, () -> send(sender, "Не удалось сохранить отчёт: " + exception.getMessage()));
                }
            }
        });
    }

    private String buildReport() {
        StringBuilder out = new StringBuilder(2048);
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        double[] tps = Bukkit.getServer().getTPS();
        out.append("PLIGIN SERVER REPORT\n");
        out.append("Created: ").append(LocalDateTime.now()).append('\n');
        out.append("Server: ").append(Bukkit.getName()).append('\n');
        out.append("Bukkit version: ").append(Bukkit.getBukkitVersion()).append('\n');
        out.append("Full version: ").append(Bukkit.getVersion()).append('\n');
        out.append("Java: ").append(System.getProperty("java.version")).append('\n');
        out.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append(" / ").append(System.getProperty("os.arch")).append('\n');
        out.append("CPU threads visible to JVM: ").append(runtime.availableProcessors()).append('\n');
        out.append("Online: ").append(Bukkit.getOnlinePlayers().size()).append('/').append(Bukkit.getMaxPlayers()).append('\n');
        out.append(String.format(Locale.US, "TPS: %.2f, %.2f, %.2f%n", safeTps(tps, 0), safeTps(tps, 1), safeTps(tps, 2)));
        out.append(String.format(Locale.US, "Average MSPT: %.2f ms%n", Bukkit.getServer().getAverageTickTime()));
        out.append("JVM memory used/max: ").append(formatMb(used)).append(" / ").append(formatMb(runtime.maxMemory())).append(" MB\n");
        out.append("\nPLAYERS\n");
        for (Player player : Bukkit.getOnlinePlayers()) {
            out.append(player.getName()).append(" | ping=").append(player.getPing()).append("ms | world=")
                    .append(player.getWorld().getName()).append(" | xyz=")
                    .append(player.getLocation().getBlockX()).append(',')
                    .append(player.getLocation().getBlockY()).append(',')
                    .append(player.getLocation().getBlockZ()).append('\n');
        }
        out.append("\nWORLDS\n");
        for (World world : Bukkit.getWorlds()) {
            out.append(world.getName()).append(" | players=").append(world.getPlayers().size())
                    .append(" | loadedChunks=").append(world.getLoadedChunks().length)
                    .append(" | entities=").append(world.getEntities().size())
                    .append(" | time=").append(world.getTime()).append('\n');
        }
        out.append("\nPLUGINS\n");
        for (var plugin : Bukkit.getPluginManager().getPlugins()) {
            out.append(plugin.getName()).append(" ").append(plugin.getDescription().getVersion())
                    .append(" | enabled=").append(plugin.isEnabled()).append('\n');
        }
        out.append("\nSYSTEM\n");
        out.append("JVM arguments: ").append(ManagementFactory.getRuntimeMXBean().getInputArguments()).append('\n');
        out.append("File system free space near plugin folder: ");
        try {
            out.append(formatMb(Files.getFileStore(getDataFolder().toPath()).getUsableSpace())).append(" MB\n");
        } catch (IOException | RuntimeException exception) {
            out.append("unavailable (plugin data folder may not exist yet)\n");
        }
        out.append("\nNote: CPU usage percentage is not sampled; CPU test is a controlled single-worker load test.\n");
        return out.toString();
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) return player;
        send(sender, "Эту проверку нужно запускать в игре, потому что ей нужна твоя позиция.");
        return null;
    }

    private void send(CommandSender sender, String message) {
        sender.sendMessage(prefix + color(message));
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private static Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static double safeTps(double[] values, int index) {
        if (values == null || index >= values.length || !Double.isFinite(values[index])) return 0.0;
        return values[index];
    }

    private static String formatMb(long bytes) {
        return String.format(Locale.US, "%.1f", bytes / 1024.0 / 1024.0);
    }

    private static double percentile(List<Double> sorted, double percentile) {
        if (sorted.isEmpty()) return 0.0;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("pligin.admin")) return Collections.emptyList();
        if (args.length == 1) {
            return filter(List.of("help", "status", "players", "worlds", "ping", "benchmark", "report", "stress", "stop"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("stress")) {
            return filter(List.of("entities", "particles", "memory", "cpu"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ping")) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("stress")) {
            return switch (args[1].toLowerCase(Locale.ROOT)) {
                case "entities" -> filter(List.of("50", "100", "200", "300"), args[2]);
                case "particles" -> filter(List.of("100", "250", "500", "1000"), args[2]);
                case "memory" -> filter(List.of("8", "16", "32"), args[2]);
                case "cpu" -> filter(List.of("1", "3", "5"), args[2]);
                default -> Collections.emptyList();
            };
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("benchmark") || args[0].equalsIgnoreCase("bench"))) {
            return filter(List.of("10", "30", "60", "120"), args[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> options, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private static final class TestSession {
        private final String name;
        private final List<BukkitTask> tasks = new ArrayList<>();
        private final Set<Entity> entities = new HashSet<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private Future<?> future;
        private Runnable cleanup;

        private TestSession(String name) {
            this.name = name;
        }
    }
}
